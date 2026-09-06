package com.freshness.ads.natives

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.ImageView
import android.widget.TextView
import com.google.android.libraries.ads.mobile.sdk.nativead.NativeAdView
import com.freshness.ads.databinding.NativeSmallViewBinding

class NativeAdSmallView(context: Context, attrs: AttributeSet?) :
    BaseNativeAdView(context, attrs) {
    private val viewBinding =
        NativeSmallViewBinding.inflate(LayoutInflater.from(context), this, true)

    override fun getTitleView(): TextView {
        return viewBinding.primary
    }

    override fun getSubTitleView(): TextView {
        return viewBinding.body
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

    override fun getAdvertiser(): TextView? {
        return null
    }
}