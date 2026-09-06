package com.freshness.ads.natives

import android.app.Application
import android.content.Context
import com.google.android.libraries.ads.mobile.sdk.nativead.NativeAd
import kotlinx.coroutines.flow.StateFlow

interface NativeAdService {

    val isEnable: Boolean

    val nativeCaches: StateFlow<Map<String, NativeAdResult>>

    fun init(app: Application)

    fun reset()

    fun disableNativeAds()

    /**
     * @param key cache key trong [nativeCaches] (xem `AdsCacheKeys`) — định danh CHỖ HIỂN THỊ.
     * @param placement key trong `ads_id_config` (app tự khai hằng placement của mình) — quyết
     *   định load id nào. Hai thứ khác nhau: nhiều màn có thể cùng dùng một placement.
     */
    fun loadNativeAd(context: Context, key: String, placement: String)
    fun forceLoadNativeAd(context: Context, key: String, placement: String)

    /**
     * Idempotent preload — skips if a Success/Loading entry already exists for [key].
     * Alias of [loadNativeAd] for parity with the recipe (see Huong_Dan_Su_Dung_Module_Ads).
     * Use this from earlier screens to roll a 2-screen lookahead.
     */
    fun preloadIfEmpty(context: Context, key: String, placement: String) =
        loadNativeAd(context, key, placement)

    /** Returns true if a Success entry is currently cached for [key]. */
    fun isReady(key: String): Boolean

    /**
     * Đăng ký view đang hiển thị native ad.
     *
     * Trước khi destroy bất kỳ ad nào, provider sẽ gỡ ad đó khỏi các view đã đăng ký
     * ([BaseLoadingNativeAdView.unbindAd]). Không có bước này thì `destroy()` (khi reload ad mới
     * hoặc release lúc màn hình chết) recycle bitmap của icon trong khi ImageView vẫn giữ drawable
     * -> lần vẽ kế tiếp crash "Canvas: trying to use a recycled bitmap".
     *
     * View được giữ bằng WeakReference nên không cần bắt buộc gọi [unregisterAdContainer].
     */
    fun registerAdContainer(container: BaseLoadingNativeAdView)

    fun unregisterAdContainer(container: BaseLoadingNativeAdView)

    /** Destroy the cached NativeAd for [key] (or no-op) and drop from cache. */
    fun releaseCachedAd(key: String)

    /**
     * Impression-aware release for one-off screens.
     *
     * Destroys + drops the cached ad ONLY if it already recorded a real
     * impression; otherwise it KEEPS the cached Success so the next visit
     * reuses the same (still-unshown) fill instead of throwing it away and
     * requesting a fresh one. This recovers the fills that were previously
     * destroyed when a user backed out of a screen before the ad became
     * viewable — the dominant cause of a low "show rate" on one-off screens.
     */
    fun releaseCachedAdIfImpressed(key: String)
}