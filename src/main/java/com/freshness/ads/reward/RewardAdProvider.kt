package com.freshness.ads.reward

import android.app.Activity
import android.app.Application
import android.os.SystemClock
import com.freshness.ads.config.AdBudgets
import com.freshness.ads.config.AdFormat
import com.freshness.ads.config.AdUnitCatalog
import com.freshness.ads.events.AdsEvents
import com.freshness.ads.manager.AdShowOutcome
import com.google.android.libraries.ads.mobile.sdk.common.AdValue
import com.freshness.ads.consent.AdInitGate
import com.freshness.ads.datastore.AdsDataStore
import com.freshness.ads.extensions.safeResume
import com.freshness.ads.loading.AdLoading
import com.freshness.ads.loading.awaitMinLoadingWindow
import com.google.android.libraries.ads.mobile.sdk.common.AdLoadCallback
import com.google.android.libraries.ads.mobile.sdk.common.AdRequest
import com.google.android.libraries.ads.mobile.sdk.common.FullScreenContentError
import com.google.android.libraries.ads.mobile.sdk.common.LoadAdError
import com.google.android.libraries.ads.mobile.sdk.common.AdEventCallback
import com.google.android.libraries.ads.mobile.sdk.rewarded.RewardItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import java.lang.ref.WeakReference

/**
 * Provider cho cả rewarded video lẫn rewarded interstitial: phần khác nhau giữa hai format nằm ở
 * [kind], toàn bộ waterfall / cache / hạn dùng / màn chờ / callback dùng chung.
 */
internal class RewardAdProvider<A : Any>(
    private val dataStore: AdsDataStore,
    private val adLoading: AdLoading,
    private val catalog: AdUnitCatalog,
    private val kind: RewardedKind<A>,
) : RewardAdService {

    private val TAG = kind.tag
    private val FORMAT = kind.format

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    /**
     * Mọi thao tác trên map này CHỈ ở main thread. Callback của GMA next-gen tới trên luồng nền của
     * nó nên phải hop về main (scope.launch) trước khi chạm vào đây.
     */
    private val rewards = mutableMapOf<RewardAdConfig, RewardAdRequest<A>>()
    private val _isShowing = MutableStateFlow(false)
    override val isShowing = _isShowing.asStateFlow()

    // True from the moment a showAd call is accepted until it fully resolves
    // (ad dismissed / failed / no fill). Guards against a second tap starting a
    // duplicate load/show during the (now longer) load wait. Only ever touched
    // on the Main dispatcher, so a plain flag is enough.
    private var showInProgress = false

    override fun init(app: Application) {
        Timber.d("$TAG RewardProvider initialized")
    }

    /**
     * Deadline thật của một lần reward. Đọc lại từ Remote Config mỗi lần dùng để chỉnh trên Firebase
     * là có hiệu lực ngay lần show kế tiếp, không cần release — giống cách splash đọc
     * `splashTimeoutMs`. Giá trị `<= 0` hoặc thiếu thì rơi về [RewardAdConfig.timeOut] của màn gọi.
     *
     * Dùng CHUNG cho cả ngân sách waterfall lẫn thời gian `showAd` chờ, hai chỗ lệch nhau nghĩa là
     * waterfall bỏ cuộc trong khi user vẫn đang nhìn spinner, hoặc ngược lại.
     */
    private val RewardAdConfig.effectiveTimeOut: Long
        get() = catalog.settingsSnapshot().rewardTimeoutMs?.takeIf { it > 0L } ?: timeOut

    private suspend fun loadAdInternal(id: String, retry: Int): A? =
        suspendCancellableCoroutine { continuation ->
            if (!dataStore.isAdEnabled) {
                continuation.safeResume(null)
                return@suspendCancellableCoroutine
            }

            val adRequest = AdRequest.Builder(id).build()
            kind.load(adRequest, object : AdLoadCallback<A> {
                override fun onAdLoaded(ad: A) {
                    Timber.d("$TAG  Ad loaded: $id")
                    continuation.safeResume(ad)
                }

                override fun onAdFailedToLoad(adError: LoadAdError) {
                    Timber.e("$TAG Failed to load ad: $id, error: $adError")
                    if (retry > 1) {
                        scope.launch {
                            delay(1_000L)
                            continuation.safeResume(loadAdInternal(id, retry - 1))
                        }
                    } else {
                        continuation.safeResume(null)
                    }
                }
            })
        }

    /**
     * Waterfall: thử từng id, id nào fill thì dừng.
     *
     * Giống inter, reward KHÔNG cắt tier ở `tierCapMs`: mỗi tier được chờ trọn phần ngân sách còn
     * lại nên chỉ nhảy sang id sau khi AdMob thật sự trả no-fill. Cắt ở cap sẽ bỏ luôn những request
     * chỉ chậm hơn cap vài trăm ms, mà reward là format user đứng sau spinner không huỷ được — hụt
     * một fill ở đây đắt hơn hẳn việc chờ thêm. Tổng thời gian vẫn bị `budget` chặn.
     *
     * Hệ quả: hết tổng ngân sách thì các id còn lại không được thử. Request quá hạn vẫn chạy nền
     * (AdMob không cho huỷ) và fill về muộn được [keepLateAd] nhét vào cache cho lần showAd sau —
     * reward là format user chủ động bấm nên fill để dành gần như chắc chắn dùng được.
     */
    private suspend fun loadWaterfall(config: RewardAdConfig): A? {
        val ids = catalog.idsFor(config.placement)
        if (ids.isEmpty()) {
            Timber.w("$TAG SKIP ${config.placement} (không có id nào bật)")
            AdsEvents.failedToLoad(config.placement, FORMAT, "no ids")
            return null
        }
        val budget = catalog.budgetSpecFor(config.placement, AdBudgets.forReward(config.effectiveTimeOut)).budgetFor(ids.size)

        ids.forEachIndexed { index, id ->
            val tierBudget = budget.nextTierBudget()
            if (tierBudget <= 0L) {
                Timber.w("$TAG WATERFALL ${config.placement} hết ngân sách ở tier ${index + 1}/${ids.size}")
                AdsEvents.failedToLoad(config.placement, FORMAT, "budget exhausted")
                return null
            }
            val retry = if (index == ids.lastIndex) config.retryCount else 0
            Timber.d("$TAG WATERFALL ${config.placement} tier=${index + 1}/${ids.size} id=$id budget=${tierBudget}ms")

            val tierJob = scope.async { loadAdInternal(id, retry) }
            val ad = withTimeoutOrNull(tierBudget) { tierJob.await() }
            if (ad != null) {
                Timber.d("$TAG WATERFALL ${config.placement} tier=${index + 1} FILL")
                AdsEvents.loaded(config.placement, FORMAT)
                return ad
            }
            if (tierJob.isActive) {
                // Timeout ở đây = hết TỔNG ngân sách của placement (tier không còn cap riêng).
                Timber.w("$TAG WATERFALL ${config.placement} tier=${index + 1} TIMEOUT (hết ngân sách) — vẫn hứng ad về muộn")
                keepLateAd(config, tierJob)
            } else {
                Timber.e("$TAG WATERFALL ${config.placement} tier=${index + 1} NO_FILL")
            }
        }
        AdsEvents.failedToLoad(config.placement, FORMAT, "no fill")
        return null
    }

    /** Ad đã nạp còn trong hạn dùng, hoặc null. Xem [AdBudgets.FULL_SCREEN_AD_TTL_MS]. */
    private fun RewardAdRequest<A>.liveAd(): A? {
        val ad = rewardedAd ?: return null
        if (SystemClock.elapsedRealtime() - loadedAt <= AdBudgets.FULL_SCREEN_AD_TTL_MS) return ad
        Timber.w("$TAG $placement ad đã hết hạn (${AdBudgets.FULL_SCREEN_AD_TTL_MS / 60_000} phút) — bỏ, nạp lại")
        return null
    }

    // getCompleted chỉ hợp lệ bên trong invokeOnCompletion khi error == null, tức job đã xong và
    // không hủy — đúng điều kiện API này đòi.
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun keepLateAd(config: RewardAdConfig, tierJob: Deferred<A?>) {
        tierJob.invokeOnCompletion { error ->
            if (error != null) return@invokeOnCompletion
            val late = tierJob.getCompleted() ?: return@invokeOnCompletion
            scope.launch {
                if (getRequest(config).liveAd() == null) {
                    Timber.w("$TAG LATE_FILL ${config.placement} → cache cho lần show sau")
                    rewards[config] = getRequest(config).copy(rewardedAd = late, loadedAt = SystemClock.elapsedRealtime())
                }
            }
        }
    }

    override fun loadAd(config: RewardAdConfig) {
        if (rewards[config]?.liveAd() != null || !dataStore.isAdEnabled || rewards[config]?.job?.isActive == true) {
            return
        }
        val job = scope.launch {
            // Wait for the SDK to be initialized before requesting; if it never
            // becomes ready, abort this load (showAd will then resolve to false).
            if (!AdInitGate.awaitReady()) {
                Timber.w("$TAG SKIP reward ${config.placement} (SDK not ready)")
                return@launch
            }
            val loadedAd = loadWaterfall(config)

            val result = getRequest(config)
            if (loadedAd != null) rewards[config] = result.copy(
                rewardedAd = loadedAd,
                loadedAt = SystemClock.elapsedRealtime(),
            )
        }
        val request = getRequest(config)
        rewards[config] = request.copy(job = job)
    }

    private fun getRequest(config: RewardAdConfig): RewardAdRequest<A> {
        return rewards[config] ?: RewardAdRequest(placement = config.placement)
    }

    override suspend fun showAdOutcome(
        activity: WeakReference<Activity>,
        config: RewardAdConfig,
        onShow: () -> Unit,
        onReward: (RewardItem) -> Unit
    ): AdShowOutcome =
        suspendCancellableCoroutine { continuation ->
            if (!dataStore.isAdEnabled) {
                continuation.safeResume(AdShowOutcome.DISABLED)
                return@suspendCancellableCoroutine
            }
            if (isShowing.value || showInProgress) {
                Timber.w("$TAG showAd ${config.placement} bỏ qua (showing=${isShowing.value} inProgress=$showInProgress)")
                continuation.safeResume(AdShowOutcome.ALREADY_SHOWING)
                return@suspendCancellableCoroutine
            }
            showInProgress = true

            /** Kết thúc một lần show: gỡ cờ, tắt màn chờ, trả kết quả. Luôn chạy trên main. */
            fun settle(result: AdShowOutcome) {
                showInProgress = false
                adLoading.setLoading(false)
                continuation.safeResume(result)
            }

            scope.launch {
                val loadingStartedAt = SystemClock.elapsedRealtime()
                if (config.isShowLoading) {
                    adLoading.setLoading(true)
                }
                // No ad cached yet: kick off (or wait for) the in-flight load and
                // keep the loading dialog up until it resolves. We re-read the
                // cached ad AFTER the timeout window on purpose — even if join()
                // is cancelled at the cap, the still-running load job may have
                // just cached a fill, and a loaded ad must always be shown.
                val ad = getRequest(config).liveAd() ?: run {
                    withTimeoutOrNull(config.effectiveTimeOut) {
                        if (getRequest(config).job?.isActive != true) {
                            loadAd(config)
                        }
                        getRequest(config).job?.join()
                    }
                    getRequest(config).liveAd()
                }

                if (ad == null) {
                    // Do NOT clear here: a still-running load job may cache this
                    // fill momentarily; leaving it lets the next showAd reuse it
                    // instead of wasting the matched request.
                    Timber.e("$TAG showAd ${config.placement}: không có ad sau ${config.effectiveTimeOut}ms")
                    settle(AdShowOutcome.NO_FILL)
                    return@launch
                }

                // GMA next-gen bắn callback trên luồng nền của nó (thấy rõ trong crash: "FATAL
                // EXCEPTION: GMA(BG) 5"). State của provider và callback của app đều thuộc main, nên
                // mọi callback hop về scope (Main) trước khi làm gì.
                kind.attach(ad, object : AdEventCallback {
                    override fun onAdDismissedFullScreenContent() {
                        AdsEvents.closed(config.placement, FORMAT)
                        scope.launch {
                            _isShowing.value = false
                            clearConsumedAd(config)
                            settle(AdShowOutcome.SHOWN)
                        }
                    }

                    override fun onAdFailedToShowFullScreenContent(
                        fullScreenContentError: FullScreenContentError
                    ) {
                        Timber.e("$TAG onAdFailedToShowFullScreenContent ${config.placement}: ${fullScreenContentError.message}")
                        AdsEvents.failedToShow(config.placement, FORMAT, fullScreenContentError.message)
                        scope.launch {
                            _isShowing.value = false
                            clearConsumedAd(config)
                            settle(AdShowOutcome.SHOW_FAILED)
                        }
                    }

                    override fun onAdImpression() = AdsEvents.impression(config.placement, FORMAT)
                    override fun onAdClicked() = AdsEvents.clicked(config.placement, FORMAT)
                    override fun onAdPaid(value: AdValue) = AdsEvents.paid(config.placement, FORMAT, value)

                    override fun onAdShowedFullScreenContent() {
                        _isShowing.value = true
                        AdsEvents.showed(config.placement, FORMAT)
                        // Quảng cáo đã chiếm màn hình: việc của màn chờ kết thúc TẠI ĐÂY, không
                        // phải lúc ad đóng. Tắt muộn hơn thì cờ loading còn true suốt lúc xem
                        // quảng cáo, và spinner hiện lại ngay khi activity app resume lúc đóng ad.
                        adLoading.setLoading(false)
                        scope.launch { onShow.invoke() }
                    }
                })

                // Ad đã sẵn sàng. Giữ màn chờ cho đủ khoảng tối thiểu trước khi bung: ad preload sẵn
                // thì tới đây mới trôi vài mili giây kể từ cú chạm của người dùng.
                if (config.isShowLoading) {
                    awaitMinLoadingWindow(loadingStartedAt, config.minLoadingMs)
                }

                val host = activity.get()
                if (host == null) {
                    Timber.e("$TAG showAd ${config.placement}: activity = null")
                    settle(AdShowOutcome.NO_ACTIVITY)
                    return@launch
                }
                // show() ném = không có callback nào tới, phải tự gỡ cờ.
                runCatching {
                    kind.show(ad, host) { rewardItem ->
                        Timber.d("$TAG User earned the reward: ${rewardItem.amount} ${rewardItem.type}")
                        AdsEvents.rewarded(config.placement, rewardItem.amount, rewardItem.type)
                        // onReward chính là chỗ app cộng thưởng — gần như luôn chạm UI hoặc storage.
                        // Đưa về main trước khi giao cho app, đừng bắt mọi call site tự nhớ.
                        scope.launch { onReward.invoke(rewardItem) }
                    }
                }.onFailure { error ->
                    Timber.e("$TAG show() failed: ${error.message}")
                    AdsEvents.failedToShow(config.placement, FORMAT, error.message)
                    clearConsumedAd(config)
                    settle(AdShowOutcome.SHOW_FAILED)
                }
            }
        }

    override fun reset() {
        rewards.clear()
        showInProgress = false
        _isShowing.value = false
    }

    /**
     * Drops only the just-shown / failed ad instance. We intentionally do NOT
     * eagerly preload a replacement here: [showAd] already lazily loads on the
     * next request, so a speculative reload after every dismiss would just
     * produce a matched request with no impression for watch-once users — the
     * main cause of the sub-50% reward show rate. The load wait in [showAd]
     * (see [RewardAdConfig.timeOut]) absorbs the first-show latency instead.
     */
    private fun clearConsumedAd(config: RewardAdConfig) {
        rewards[config] = getRequest(config).copy(rewardedAd = null)
        Timber.d("$TAG reward cleared ${config.placement}")
    }

}

internal data class RewardAdRequest<A : Any>(
    val placement: String,
    val rewardedAd: A? = null,
    /** `elapsedRealtime` lúc [rewardedAd] được nạp, cho hạn dùng. */
    val loadedAt: Long = 0L,
    val job: Job? = null
)
