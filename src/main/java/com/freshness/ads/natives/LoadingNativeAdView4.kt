package com.freshness.ads.natives

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.isVisible
import com.google.android.libraries.ads.mobile.sdk.nativead.NativeAd
import com.freshness.ads.databinding.LoadingLayoutNativeAdView4Binding

class LoadingNativeAdView4 @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : BaseLoadingNativeAdView(context, attrs) {

    private val viewBinding =
        LoadingLayoutNativeAdView4Binding.inflate(LayoutInflater.from(context), this, true)

    init {
        viewBinding.shimmerLayout.startShimmer()
    }

    override fun setNativeAd(nativeAd: NativeAd) {
        viewBinding.shimmerLayout.isVisible = false
        viewBinding.shimmerLayout.hideShimmer()
        viewBinding.nativeTemplate.isVisible = true
        bindTemplateWhenLaidOut(viewBinding.nativeTemplate, nativeAd)
    }


}