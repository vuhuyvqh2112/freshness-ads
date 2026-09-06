package com.freshness.ads.natives

import android.app.Application
import android.content.Context
import android.os.Handler
import android.os.Looper
import com.freshness.ads.config.AdBudgets
import com.freshness.ads.config.AdUnitCatalog
import com.freshness.ads.datastore.AdsDataStore
import com.freshness.ads.remoteconfig.RemoteConfigService
import com.google.android.libraries.ads.mobile.sdk.nativead.NativeAd
import com.google.android.libraries.ads.mobile.sdk.nativead.NativeAdEventCallback
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import kotlin.text.set

class NativeAdProvider constructor(
    private val dataStore: AdsDataStore,
    private val remoteConfig: RemoteConfigService,
    private val catalog: AdUnitCatalog
) : NativeAdService {

    override val isEnable: Boolean get() = dataStore.isAdEnabled

    private val _nativeCaches: MutableStateFlow<Map<String, NativeAdResult>> =
        MutableStateFlow(mapOf())
    override val nativeCaches = _nativeCaches.asStateFlow()

    // Keys (in [_nativeCaches]) whose cached ad has recorded a real impression.
    // Lets releaseCachedAdIfImpressed keep a filled-but-never-shown ad alive for
    // reuse on the next visit instead of destroying it and re-requesting: a
    // filled-but-never-shown ad still has revenue left in it.
    private val impressedKeys = mutableSetOf<String>()

    /**
     * Ad về muộn (LATE_FILL): fill tới sau khi waterfall đã bỏ qua tier đó hoặc đã kết thúc.
     *
     * KHÔNG emit thẳng vào [_nativeCaches]: lúc đó slot trên màn hình đã thu gọn vì nhận Failure, đẩy
     * Success vào sẽ khiến ad bật ra giữa lúc user đang đọc — hoặc tệ hơn, với native full splash là
     * hiện sau khi đã chuyển màn. Thay vào đó giữ ở đây và [takeLateAd] tiêu thụ ở lần load kế tiếp,
     * biến một matched request suýt bị vứt thành fill tức thì không tốn request mới.
     */
    private val lateAds = mutableMapOf<String, NativeAd>()

    // SDK load + impression callbacks may arrive off the main thread; hop back so
    // ad state và cờ impression chỉ bị sửa trên một luồng.
    private val mainHandler = Handler(Looper.getMainLooper())

    private inline fun runOnMain(crossinline block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block()
        else mainHandler.post { block() }
    }

    // Các view đang hiển thị native ad (WeakReference để không giữ Activity/Fragment).
    private val containers = mutableListOf<java.lang.ref.WeakReference<BaseLoadingNativeAdView>>()

    override fun registerAdContainer(container: BaseLoadingNativeAdView) {
        containers.removeAll { it.get() == null || it.get() === container }
        containers.add(java.lang.ref.WeakReference(container))
    }

    override fun unregisterAdContainer(container: BaseLoadingNativeAdView) {
        containers.removeAll { it.get() == null || it.get() === container }
    }

    /**
     * Destroy ad SAU KHI đã gỡ nó khỏi mọi view đang hiển thị.
     *
     * [NativeAd.destroy] recycle bitmap của các asset (icon...). Nếu ImageView còn giữ drawable đó
     * thì frame kế tiếp sẽ crash "Canvas: trying to use a recycled bitmap". Mọi lệnh destroy trong
     * class này PHẢI đi qua đây.
     */
    private fun destroySafely(ad: NativeAd) {
        runOnMain {
            containers.removeAll { it.get() == null }
            containers.forEach { ref ->
                ref.get()?.takeIf { it.boundAd === ad }?.unbindAd()
            }
            runCatching { ad.destroy() }
        }
    }

    override fun init(app: Application) {

    }

    /** Nhận ad về muộn của [key], giữ lại cho lần load sau. Ad muộn cũ (nếu có) bị huỷ. */
    private fun keepLateAd(key: String, ad: NativeAd) {
        runOnMain {
            lateAds.put(key, ad)?.let { destroySafely(it) }
            Timber.d("$TAG giữ LATE_FILL key=$key cho lần load sau")
        }
    }

    /** Lấy ad về muộn đang giữ cho [key], nếu có. */
    private fun takeLateAd(key: String): NativeAd? = lateAds.remove(key)

    private fun budgetFor(placement: String, tierCount: Int) =
        catalog.budgetSpecFor(placement, AdBudgets.NATIVE_SINGLE).budgetFor(tierCount)

    /**
     * Load một native đơn theo waterfall và đẩy kết quả vào [emitResult].
     * [cacheKey] chỉ dùng để giữ ad về muộn, tách khỏi [placement] (key catalog).
     */
    private fun loadSingle(
        placement: String,
        cacheKey: String,
        emitResult: (NativeAdResult) -> Unit,
        onAdLoaded: ((NativeAd) -> Unit)? = null,
    ) {
        val ids = catalog.idsFor(placement)
        val budget = budgetFor(placement, ids.size)
        emitResult(NativeAdResult.Loading)
        NativeAdmobManager.loadWaterfall(
            isEnableAd = isEnable,
            placement = placement,
            ids = ids,
            budget = budget,
            onLoadSuccess = { ad ->
                runOnMain {
                    onAdLoaded?.invoke(ad)
                    emitResult(NativeAdResult.Success(ad))
                }
            },
            onLoadFail = { runOnMain { emitResult(NativeAdResult.Failure) } },
            onLateAd = { ad -> keepLateAd(cacheKey, ad) },
        )
    }

    override fun loadNativeAd(context: Context, key: String, placement: String) {
        val ad = _nativeCaches.value[key]
        if (ad is NativeAdResult.Success || ad is NativeAdResult.Loading) {
            Timber.d("$TAG PRELOAD skip key=$key (already ${if (ad is NativeAdResult.Success) "loaded" else "loading"})")
            return
        }
        if (consumeLateAdInto(key)) return
        Timber.d("$TAG PRELOAD start key=$key placement=$placement")
        loadIntoCache(key, placement)
    }

    override fun forceLoadNativeAd(context: Context, key: String, placement: String) {
        // Skip if currently loading to avoid duplicate requests
        if (_nativeCaches.value[key] is NativeAdResult.Loading) {
            Timber.d("$TAG FORCE-PRELOAD skip key=$key (already loading)")
            return
        }
        if (consumeLateAdInto(key)) return
        Timber.d("$TAG FORCE-PRELOAD start key=$key placement=$placement")
        loadIntoCache(key, placement)
    }

    /** Dùng ad về muộn đang giữ cho [key] thay vì phát request mới. true nếu đã dùng được. */
    private fun consumeLateAdInto(key: String): Boolean {
        val late = takeLateAd(key) ?: return false
        Timber.d("$TAG reuse LATE_FILL key=$key (không tốn request mới)")
        trackImpression(key, late)
        emit(key, NativeAdResult.Success(late))
        return true
    }

    private fun loadIntoCache(key: String, placement: String) {
        loadSingle(
            placement = placement,
            cacheKey = key,
            emitResult = { emit(key, it) },
            onAdLoaded = { trackImpression(key, it) },
        )
    }

    /**
     * Track real impressions so releaseCachedAdIfImpressed can tell a shown ad (safe to destroy +
     * replace) from an unshown fill (keep for reuse). A fresh load starts un-impressed.
     */
    private fun trackImpression(key: String, ad: NativeAd) {
        impressedKeys.remove(key)
        ad.adEventCallback = object : NativeAdEventCallback {
            override fun onAdImpression() {
                Timber.d("$TAG impression recorded key=$key")
                runOnMain { impressedKeys.add(key) }
            }
        }
    }

    private fun emit(key: String, value: NativeAdResult) {
        _nativeCaches.value = _nativeCaches.value.toMutableMap().apply {
            set(key, value)
        }
    }

    override fun isReady(key: String): Boolean {
        return _nativeCaches.value[key] is NativeAdResult.Success
    }

    override fun releaseCachedAd(key: String) {
        val current = _nativeCaches.value[key]
        if (current is NativeAdResult.Success) {
            destroySafely(current.nativeAd)
        }
        impressedKeys.remove(key)
        _nativeCaches.value = _nativeCaches.value.toMutableMap().apply {
            remove(key)
        }
    }

    override fun releaseCachedAdIfImpressed(key: String) {
        // Only spend the fill once it has earned an impression. If the cached ad
        // was never shown (user left before it became viewable), keep it cached
        // so the next visit reuses it — loadNativeAd already skips re-requesting
        // while a Success is cached, so the same fill is shown next time instead
        // of being destroyed and re-requested (which wasted the fill and dragged
        // match rate down by inflating the request count).
        if (!impressedKeys.contains(key)) {
            Timber.d("$TAG keep unshown cached ad key=$key (reuse next visit)")
            return
        }
        releaseCachedAd(key)
    }

    override fun reset() {
        // Huỷ hẳn ad đang cache rồi xoá key: reset mà chỉ xoá map sẽ rò NativeAd, còn giữ lại ad cũ
        // thì lần bind sau hiện đúng cái ad của phiên trước.
        _nativeCaches.value.values.forEach {
            if (it is NativeAdResult.Success) destroySafely(it.nativeAd)
        }
        impressedKeys.clear()
        _nativeCaches.value = emptyMap()
    }

    override fun disableNativeAds() {
        // Đẩy mọi slot đang chờ về Failure để container thu gọn, thay vì shimmer vĩnh viễn khi ads
        // bị tắt giữa chừng. Slot chưa từng load thì không có gì để đẩy — call site phải tự kiểm
        // isAdEnabled trước khi bind.
        _nativeCaches.value = _nativeCaches.value.mapValues { NativeAdResult.Failure }
    }

    companion object {
        private const val TAG = "NativeAds"

    }

}

sealed class NativeAdResult {
    data class Success(val nativeAd: NativeAd) : NativeAdResult()
    object Idle : NativeAdResult()
    object Loading : NativeAdResult()
    object Failure : NativeAdResult()
}
