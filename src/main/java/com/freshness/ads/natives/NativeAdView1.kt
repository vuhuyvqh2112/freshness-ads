package com.freshness.ads.natives

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.widget.AppCompatRatingBar
import com.google.android.libraries.ads.mobile.sdk.nativead.MediaView
import com.google.android.libraries.ads.mobile.sdk.nativead.NativeAdView
import com.freshness.ads.databinding.LayoutNativeAdView1Binding

class NativeAdView1(context: Context, attrs: AttributeSet?) :
    BaseNativeAdView(context, attrs) {

    private val viewBinding = LayoutNativeAdView1Binding.inflate(LayoutInflater.from(context), this, true)

    override fun getTitleView(): TextView = viewBinding.primary

    override fun getSubTitleView(): TextView = viewBinding.body

    override fun getAdvertiser(): TextView? = null

    override fun getRatingView(): AppCompatRatingBar? = null

    override fun getIconView(): ImageView {
        return viewBinding.icon
    }

    override fun getMediaView(): MediaView = viewBinding.mediaView

    override fun getPriceView(): TextView? {
        return null
    }

    override fun getCallActionButtonView(): TextView {
        return viewBinding.cta
    }

    override fun getAdView(): NativeAdView {
        return viewBinding.nativeAdView
    }
}