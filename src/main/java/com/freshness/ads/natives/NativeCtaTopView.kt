package com.freshness.ads.natives

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.widget.AppCompatRatingBar
import com.google.android.libraries.ads.mobile.sdk.nativead.MediaView
import com.google.android.libraries.ads.mobile.sdk.nativead.NativeAdView
import com.freshness.ads.databinding.NativeCtaTopViewBinding

class NativeCtaTopView(context: Context, attrs: AttributeSet?) :
    BaseNativeAdView(context, attrs) {
    private val viewBinding =
        NativeCtaTopViewBinding.inflate(LayoutInflater.from(context), this, true)

    override fun getTitleView(): TextView {
        return viewBinding.primary
    }

    override fun getSubTitleView(): TextView {
        return viewBinding.body
    }

    override fun getMediaView(): MediaView {
        return viewBinding.mediaView
    }

    override fun getRatingView(): AppCompatRatingBar? {
        return null
    }

    override fun getIconView(): ImageView {
        return viewBinding.icon
    }

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