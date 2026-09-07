package com.freshness.ads.banner

import android.app.Application
import android.content.Context
import android.widget.FrameLayout

interface AdsBannerService {

    val isEnableAd: Boolean

    fun init(application: Application)

    /**
     * Nạp anchored adaptive banner (bản *large*) vào [bannerView] theo waterfall của [placement].
     *
     * @param placement key trong `ads_id_config` (app tự khai hằng placement của mình).
     * @param bannerView container phải `wrap_content` chiều cao — banner adaptive tự chọn chiều cao.
     * @param size xem [BannerSize]; mặc định anchored như trước.
     */
    fun loadBannerAds(
        context: Context,
        bannerView: FrameLayout,
        placement: String,
        size: BannerSize = BannerSize.ANCHORED,
        onLoadSuccess: (() -> Unit)? = null,
        onLoadFail: (() -> Unit)? = null
    )

    /**
     * @param isCollapsible KHÔNG có tác dụng: GMA next-gen 1.2.1 chưa có API collapsible banner, tham
     *   số này luôn nạp anchored banner thường. Giữ lại để call site cũ còn compile.
     */
    @Deprecated(
        message = "isCollapsible không có tác dụng trên GMA next-gen; dùng bản không có tham số này",
        replaceWith = ReplaceWith("loadBannerAds(context, bannerView, placement, onLoadSuccess, onLoadFail)"),
    )
    fun loadBannerAds(
        context: Context,
        bannerView: FrameLayout,
        placement: String,
        @Suppress("UNUSED_PARAMETER") isCollapsible: Boolean,
        onLoadSuccess: (() -> Unit)? = null,
        onLoadFail: (() -> Unit)? = null
    ) = loadBannerAds(context, bannerView, placement, BannerSize.ANCHORED, onLoadSuccess, onLoadFail)

    fun reset()

}