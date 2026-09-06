package com.freshness.ads.natives

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.ImageView
import android.widget.TextView
import com.freshness.ads.databinding.LayoutNativeAdDisplayBinding
import com.google.android.libraries.ads.mobile.sdk.nativead.MediaView
import com.google.android.libraries.ads.mobile.sdk.nativead.NativeAdView

class NativeAdDisplayView(context: Context, attrs: AttributeSet?) :
    BaseNativeAdView(context, attrs) {

    private val viewBinding =
        LayoutNativeAdDisplayBinding.inflate(LayoutInflater.from(context), this, true)

    override fun getTitleView(): TextView = viewBinding.primary

    override fun getSubTitleView(): TextView = viewBinding.body

    override fun getIconView(): ImageView = viewBinding.icon

    override fun getPriceView(): TextView? = null

    override fun getCallActionButtonView(): TextView = viewBinding.cta

    override fun getAdView(): NativeAdView = viewBinding.nativeAdView

    override fun getMediaView(): MediaView = viewBinding.mediaView

    override fun getAdvertiser(): TextView? = null
}
