package com.freshness.ads.natives

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.freshness.ads.config.AdBudgets
import com.freshness.ads.config.AdUnitCatalog
import com.freshness.ads.config.AdsConfig
import com.freshness.ads.consent.AdInitGate
import com.freshness.ads.datastore.AdsDataStore
import com.google.android.libraries.ads.mobile.sdk.common.LoadAdError
import com.google.android.libraries.ads.mobile.sdk.nativead.NativeAd
import com.google.android.libraries.ads.mobile.sdk.nativead.NativeAdEventCallback
import com.google.android.libraries.ads.mobile.sdk.nativead.NativeAdLoader
import com.google.android.libraries.ads.mobile.sdk.nativead.NativeAdLoaderCallback
import com.google.android.libraries.ads.mobile.sdk.nativead.NativeAdRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Centralized pool manager for Native Ads used in RecyclerView adapters.
 *
 * Instead of loading ads on-demand in onBindViewHolder (which causes over-requesting
 * and low match rates), this manager preloads a pool of ads per placement and serves
 * them instantly when adapters need them.
 *
 * Key benefits:
 * - Reduced ad requests (preload only what's needed)
 * - Higher match rate (controlled request timing)
 * - Smooth scrolling (no network calls during scroll)
 * - Automatic refill with exponential backoff on failure
 */
class AdPoolManager constructor(
    private val dataStore: AdsDataStore,
    private val catalog: AdUnitCatalog,
    private val context: Context,
    /** Xem [AdsConfig.nativePools]. Placement thiếu ở đây dùng [AdsConfig.DEFAULT_POOL_SIZE]. */
    private val poolSizes: Map<String, Int> = emptyMap(),
) {

    /**
     * Placement ở mọi API dưới đây là KEY trong `ads_id_config`, KHÔNG phải ad unit id — id được
     * resolve lúc load nên Remote Config đổi được id mà không phải release.
     *
     * Số ad giữ sẵn phải bằng SỐ SLOT màn hình bind CÙNG LÚC. Màn chỉ có một slot tĩnh mà khai 2 thì
     * ad thứ hai không có đường ra (slot đã có ad thì lần bind sau bỏ qua): mỗi phiên phát dư một
     * request không bao giờ đổi được impression, kéo show rate xuống. Chỉ tăng khi có feed nhiều vị
     * trí ad thật sự hiển thị song song.
     */
    private fun poolSizeFor(placement: String): Int =
        poolSizes[placement] ?: AdsConfig.DEFAULT_POOL_SIZE

    private class PlacementPool {
        val available = ArrayDeque<NativeAd>()
        val assigned = mutableMapOf<String, NativeAd>()

        // Các holder đang giữ từng key (thường là ViewHolder đang hiển thị ad).
        //
        // Key được sinh từ vị trí (native_home_0, ...) nên trong MỘT layout pass của RecyclerView,
        // holder MỚI có thể bind lại đúng key đó (và getAd trả về CHÍNH ad cũ) TRƯỚC khi holder cũ
        // được recycle. ItemAnimator còn tạo hẳn holder thứ hai cho cùng vị trí để crossfade. Nếu
        // releaseAd của holder cũ cứ thế destroy thì ad đang hiển thị trên holder kia bị giết ->
        // vẽ frame kế tiếp là crash "recycled bitmap". Vì vậy chỉ destroy khi KHÔNG còn holder nào.
        //
        // WeakReference để pool (singleton, sống theo process) không giữ ViewHolder/Activity.
        val owners = mutableMapOf<String, MutableList<java.lang.ref.WeakReference<Any>>>()
        var loadingCount = 0
        var lastFailTime = 0L
        var consecutiveFailures = 0
    }

    private val pools = mutableMapOf<String, PlacementPool>()

    private val _adReadyEvents = MutableSharedFlow<String>(extraBufferCapacity = 32)
    val adReadyEvents: SharedFlow<String> = _adReadyEvents.asSharedFlow()

    private val isEnabled: Boolean get() = dataStore.isAdEnabled

    // All pool state (available/assigned/loadingCount/failures) is mutated only on
    // the main thread. SDK load + impression callbacks may arrive on a background
    // thread, so we hop back to main before touching the pool — keeps the simple
    // non-synchronized collections and counters race-free and the size accounting exact.
    private val mainHandler = Handler(Looper.getMainLooper())

    // Process-lived scope for waiting on SDK init. Main.immediate so pool state
    // is touched only on the main thread.
    private val scope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())

    // Placements whose preload was deferred because the SDK wasn't ready yet, and
    // which are now waiting for init to complete (avoids spawning duplicate waiters).
    private val awaitingSdk = mutableSetOf<String>()

    private inline fun runOnMain(crossinline block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block()
        else mainHandler.post { block() }
    }

    private fun getPool(placement: String): PlacementPool {
        return pools.getOrPut(placement) { PlacementPool() }
    }

    /**
     * Idempotent preload alias — same as [preload]; skips when pool is full or
     * a load is already in flight. Provided for parity with the recipe naming.
     */
    fun preloadIfEmpty(placement: String) = preload(placement)

    /** Returns true if at least one ad is currently available in the pool. */
    fun isReady(placement: String): Boolean {
        val pool = pools[placement] ?: return false
        return pool.available.isNotEmpty()
    }

    /**
     * Preload ads for a placement up to its configured pool size.
     * Call this when a screen opens, before the user scrolls to ad positions.
     * Respects exponential backoff on consecutive failures.
     */
    fun preload(placement: String) {
        if (!isEnabled) return

        val pool = getPool(placement)
        val totalAvailable = pool.available.size + pool.loadingCount
        val needed = (poolSizeFor(placement) - totalAvailable).coerceAtLeast(0)

        if (needed <= 0) return

        // Gentle backoff so the feed recovers quickly when fill is intermittent
        // (NO_FILL) while still avoiding hammering AdMob: 5s, 10s, 20s, max 30s.
        if (pool.consecutiveFailures > 0) {
            val cooldownMs = minOf(
                5_000L * (1L shl (pool.consecutiveFailures - 1).coerceAtMost(3)),
                30_000L
            )
            if (System.currentTimeMillis() - pool.lastFailTime < cooldownMs) {
                Timber.d("AdPool[$placement]: Skipping preload, cooldown active (${pool.consecutiveFailures} failures)")
                return
            }
        }

        Timber.d("AdPool[$placement]: Preloading $needed ads (available=${pool.available.size}, loading=${pool.loadingCount})")

        loadAdsBatch(placement, pool, needed)
    }

    /**
     * Load [count] native ads as [count] independent single-ad requests.
     *
     * The next-gen multi-ad load (NativeAdLoader.load(request, numberOfAds, cb))
     * was failing to fill for the Home unit, so we issue single-ad loads instead
     * — the same proven path the language/intro/display natives use successfully.
     */
    private fun loadAdsBatch(placement: String, pool: PlacementPool, count: Int) {
        // Reserve the slots up front so a concurrent preload() sees them and does
        // not over-request while we wait for the SDK to be ready.
        pool.loadingCount += count

        // Never issue requests before MobileAds.initialize has completed. SDK-not-
        // ready is a transient state (init still in flight / consent pending), NOT
        // an AdMob NO_FILL — so just release the reserved slots and do NOT arm
        // backoff. Arming backoff here would penalize the pool on cold start and
        // delay the first genuine requests once init lands. Only a real
        // onAdFailedToLoad (in issueBatch) counts as a fill failure.
        AdInitGate.whenReady(
            onUnavailable = {
                pool.loadingCount -= count
                Timber.w("AdPool[$placement]: SDK not ready, batch deferred (will retry when ready)")
                // Recover instead of giving up: wait for init to finish, then
                // refill. Without this, a slow init / process restart left native
                // slots stuck on the shimmer forever (nothing re-triggers a
                // preload while the user sits on the screen).
                retryWhenSdkReady(placement)
            }
        ) {
            issueBatch(placement, pool, count)
        }
    }

    /** Wait for the SDK to finish initializing (bounded), then re-preload. If it
     *  never initializes this session (e.g. consent not gathered), mark the
     *  placement exhausted so adapters collapse the slot instead of showing the
     *  shimmer forever. */
    private fun retryWhenSdkReady(placement: String) {
        if (!awaitingSdk.add(placement)) return // already waiting for this placement
        scope.launch {
            val ready = AdInitGate.awaitReady(timeoutMs = 60_000L)
            awaitingSdk.remove(placement)
            if (ready) {
                Timber.d("AdPool[$placement]: SDK became ready, retrying preload")
                preload(placement)
            } else {
                Timber.w("AdPool[$placement]: SDK never became ready — collapsing slot")
                val pool = getPool(placement)
                if (pool.available.isEmpty() && pool.loadingCount == 0) {
                    pool.consecutiveFailures = maxOf(pool.consecutiveFailures, 1)
                    pool.lastFailTime = System.currentTimeMillis()
                    _adReadyEvents.tryEmit(placement)
                }
            }
        }
    }

    private fun issueBatch(placement: String, pool: PlacementPool, count: Int) {
        var loaded = 0
        // Requests not yet accounted for, by either a callback or the watchdog.
        // Both paths decrement this exactly once so loadingCount can never go
        // negative even if a late SDK callback arrives after the watchdog fired.
        var remaining = count

        // Settles one request. [ad] non-null on success. Returns true if this was
        // a fresh settlement (callback arrived before the watchdog reclaimed it).
        fun account(ad: NativeAd?): Boolean {
            if (remaining <= 0) {
                // Watchdog already reclaimed this slot's loadingCount. A late
                // success still carries a usable ad — keep it without touching
                // loadingCount (it was already accounted for).
                if (ad != null) {
                    pool.consecutiveFailures = 0
                    pool.available.addLast(ad)
                    _adReadyEvents.tryEmit(placement)
                }
                return false
            }
            remaining--
            pool.loadingCount--
            if (ad != null) {
                loaded++
                pool.consecutiveFailures = 0
                pool.available.addLast(ad)
                _adReadyEvents.tryEmit(placement)
            }
            if (remaining == 0) {
                if (loaded == 0) {
                    pool.consecutiveFailures++
                    pool.lastFailTime = System.currentTimeMillis()
                    Timber.w("AdPool[$placement]: Batch produced no ads, failures=${pool.consecutiveFailures}")
                }
                if (pool.loadingCount == 0 && pool.available.isEmpty()) {
                    _adReadyEvents.tryEmit(placement)
                }
            }
            return true
        }

        val ids = catalog.idsFor(placement)
        if (ids.isEmpty()) {
            Timber.w("AdPool[$placement]: không có id nào bật, huỷ batch")
            repeat(count) { account(null) }
            return
        }
        val budgetSpec = catalog.budgetSpecFor(placement, AdBudgets.NATIVE_POOL)

        // Waterfall áp cho TỪNG SLOT, không cho cả batch: slot nào fill sớm thì dừng sớm, slot còn
        // thiếu tự xuống tier dưới thay vì bỏ trống tới lần preload sau (lần đó còn dính backoff).
        //
        // account() vẫn được gọi ĐÚNG MỘT LẦN cho mỗi slot — ở callback cuối waterfall. Gọi nó ở tier
        // trung gian sẽ làm lệch loadingCount và pool kẹt vĩnh viễn (chính thứ watchdog dưới đây sinh
        // ra để chống).
        repeat(count) {
            NativeAdmobManager.loadWaterfall(
                isEnableAd = true, // đã kiểm ở preload()
                placement = placement,
                ids = ids,
                budget = budgetSpec.budgetFor(ids.size),
                onLoadSuccess = { nativeAd ->
                    runOnMain {
                        if (account(nativeAd)) {
                            Timber.d("AdPool[$placement]: Ad loaded ($loaded/$count), pool size=${pool.available.size}")
                        }
                    }
                },
                onLoadFail = { runOnMain { account(null) } },
                // Ad về muộn vẫn là fill đã trả tiền: đẩy thẳng vào pool thay vì vứt. account() đã
                // chốt slot rồi nên chỉ thêm vào hàng available, không đụng loadingCount.
                onLateAd = { lateAd ->
                    runOnMain {
                        Timber.w("AdPool[$placement]: LATE_FILL → thêm vào pool")
                        pool.consecutiveFailures = 0
                        pool.available.addLast(lateAd)
                        _adReadyEvents.tryEmit(placement)
                    }
                },
            )
        }

        // Watchdog: if the SDK never calls back for some requests (a dropped
        // callback would otherwise leave loadingCount stuck > 0 and permanently
        // block future refills, since needed = poolSize - (available + loading)),
        // reclaim the leaked slots. A timeout is treated as SDK-slowness, not a
        // NO_FILL, so it does not arm backoff.
        //
        // Nhân theo số tier: một slot giờ có thể phải đi qua nhiều tier, watchdog tính cho một lần
        // load sẽ thu hồi slot trong khi waterfall còn đang chạy → loadingCount giải phóng sớm và
        // pool phát dư request.
        val watchdogMs = (LOAD_WATCHDOG_MS * ids.size).coerceAtMost(MAX_WATCHDOG_MS)
        mainHandler.postDelayed({
            if (remaining > 0) {
                pool.loadingCount -= remaining
                Timber.w("AdPool[$placement]: watchdog reclaimed $remaining stuck load(s)")
                remaining = 0
                if (pool.loadingCount == 0 && pool.available.isEmpty()) {
                    _adReadyEvents.tryEmit(placement)
                }
            }
        }, watchdogMs)
    }

    /**
     * Get an ad for a specific adapter position key.
     *
     * If the key already has an assigned ad (rebind scenario), returns it immediately.
     * Otherwise takes one from the preloaded pool. Returns null if pool is empty.
     * Automatically triggers refill when pool runs low.
     *
     * @param placement The ad placement type
     * @param key Unique key for the adapter position (e.g., "native_home_0")
     * @param owner View/ViewHolder đang bind ad này. Truyền vào để [releaseAd] biết ai là chủ hiện
     *   tại của key, tránh việc holder cũ bị recycle lại destroy ad của holder mới.
     * @return NativeAd if available, null if pool is empty
     */
    @JvmOverloads
    fun getAd(placement: String, key: String, owner: Any? = null): NativeAd? {
        val pool = getPool(placement)

        // Return already-assigned ad for this key (consistent rebind)
        pool.assigned[key]?.let {
            addOwner(pool, key, owner)
            return it
        }

        // Take from preloaded pool
        val ad = pool.available.removeFirstOrNull() ?: run {
            // Pool empty - trigger refill
            preload(placement)
            return null
        }

        pool.assigned[key] = ad
        addOwner(pool, key, owner)

        // Refill is driven by a REAL impression, not by assignment.
        //
        // An assigned ad is not guaranteed to be shown: the user may scroll past
        // before it becomes viewable, or the holder may be recycled first. Those
        // ads earn no revenue, so loading a replacement for them just burns
        // requests and drags the match rate down. By topping the pool back up only
        // when the SDK reports an actual impression, every preloaded request stays
        // backed by an earned impression — i.e. the pool only "spends" a slot once
        // it has been monetized.
        //
        // Safety net: when the pool is fully drained, getAd's empty-path above
        // still issues a load so the UI is never starved while impressions catch up.
        ad.adEventCallback = object : NativeAdEventCallback {
            override fun onAdImpression() {
                Timber.d("AdPool[$placement]: Impression for key=$key → refilling pool")
                runOnMain { preload(placement) }
            }
        }

        Timber.d("AdPool[$placement]: Assigned ad to key=$key, remaining=${pool.available.size}")
        return ad
    }

    /**
     * Check if the placement is in a failed state (no ads available and not loading).
     */
    fun isPlacementExhausted(placement: String): Boolean {
        val pool = pools[placement] ?: return false
        return pool.available.isEmpty()
                && pool.loadingCount == 0
                && pool.consecutiveFailures > 0
    }

    /**
     * Release an assigned ad when its view is recycled.
     * Destroy it so the next bind gets a fresh ad with a new paid impression.
     * Reusing the same NativeAd object does NOT generate additional revenue.
     *
     * No preload here — getAd() already auto-refills when pool runs low.
     * Preloading on every release wastes requests when user stops scrolling.
     *
     * @param owner holder đang trả ad. Nếu còn holder khác đang giữ key này (rebind trong cùng
     *   layout pass, hoặc holder crossfade của ItemAnimator) thì bỏ qua — destroy lúc này sẽ giết
     *   ad đang hiển thị trên màn hình. Truyền null = destroy ngay (hành vi cũ).
     */
    @JvmOverloads
    fun releaseAd(placement: String, key: String, owner: Any? = null) {
        val pool = pools[placement] ?: return

        if (owner != null) {
            val remaining = pool.owners[key]
            if (remaining != null) {
                // Bỏ owner này + dọn các ref đã chết
                remaining.removeAll { it.get() == null || it.get() === owner }
                // Còn holder khác đang giữ -> ad vẫn đang hiển thị, KHÔNG destroy
                if (remaining.isNotEmpty()) return
            }
        }

        pool.owners.remove(key)
        pool.assigned.remove(key)?.destroy()
    }

    private fun addOwner(pool: PlacementPool, key: String, owner: Any?) {
        if (owner == null) return
        val owners = pool.owners.getOrPut(key) { mutableListOf() }
        owners.removeAll { it.get() == null || it.get() === owner }
        owners.add(java.lang.ref.WeakReference(owner))
    }

    /**
     * Destroy all ads for a placement.
     * Call when the screen using this placement is destroyed.
     */
    fun destroyPlacement(placement: String) {
        val pool = pools.remove(placement) ?: return
        pool.available.forEach { it.destroy() }
        pool.assigned.values.forEach { it.destroy() }
        pool.available.clear()
        pool.assigned.clear()
        pool.owners.clear()
        Timber.d("AdPool[$placement]: Destroyed all ads")
    }

    /**
     * Destroy all ads across all placements.
     */
    fun destroyAll() {
        pools.keys.toList().forEach { destroyPlacement(it) }
    }

    companion object {
        // Upper bound for a single native load to call back. A real load resolves
        // in a few seconds; anything past this is a dropped/lost callback whose
        // reserved loadingCount the watchdog reclaims so refills can resume.
        private const val LOAD_WATCHDOG_MS = 30_000L

        /** Trần cho watchdog đã nhân theo số tier, để một payload nhiều tier không treo slot quá lâu. */
        private const val MAX_WATCHDOG_MS = 120_000L
    }
}
