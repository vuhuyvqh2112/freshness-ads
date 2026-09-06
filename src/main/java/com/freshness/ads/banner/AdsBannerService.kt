package com.freshness.ads.banner

import android.app.Application
import android.content.Context
import android.widget.FrameLayout

interface AdsBannerService {

    val isEnableAd: Boolean

    fun init(application: Application)

    /** @param placement key trong `ads_id_config` (app tự khai hằng placement của mình). */
    fun loadBannerAds(
        context: Context,
        bannerView: FrameLayout,
        placement: String,
        isCollapsible: Boolean,
        onLoadSuccess: (() -> Unit)? = null,
        onLoadFail: (() -> Unit)? = null
    )

    fun reset()

}