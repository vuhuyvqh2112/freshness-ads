package com.freshness.ads.natives

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.RelativeLayout
import androidx.core.view.isVisible
import com.google.android.libraries.ads.mobile.sdk.nativead.NativeAd
import com.freshness.ads.databinding.LoadingNativeCtaTopViewBinding

class LoadingNativeCtaTopView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : BaseLoadingNativeAdView(context, attrs) {

    private val viewBinding = LoadingNativeCtaTopViewBinding.inflate(LayoutInflater.from(context) , this , true)
    init {
        viewBinding.shimmerLayout.startShimmer()
    }

    override fun setNativeAd(nativeAd: NativeAd) {
        viewBinding.shimmerLayout.stopShimmer()
        viewBinding.shimmerLayout.isVisible = false
        viewBinding.nativeTemplate.isVisible = true
        bindTemplateWhenLaidOut(viewBinding.nativeTemplate, nativeAd)
    }


}