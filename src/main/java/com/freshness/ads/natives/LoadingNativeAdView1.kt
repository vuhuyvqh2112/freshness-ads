package com.freshness.ads.natives

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import androidx.core.view.isVisible
import com.freshness.ads.databinding.LoadingLayoutNativeAdView1Binding
import com.google.android.libraries.ads.mobile.sdk.nativead.NativeAd

class LoadingNativeAdView1 @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : BaseLoadingNativeAdView(context, attrs) {

    private val viewBinding =
        LoadingLayoutNativeAdView1Binding.inflate(LayoutInflater.from(context), this, true)

    init {
        viewBinding.shimmerLayout.startShimmer()
    }

    fun resetToShimmer() {
        // Gỡ ad khỏi view trước khi quay lại shimmer - xem LoadingNativeAdHomeView
        unbindAd()
        viewBinding.nativeTemplate.isVisible = false
        viewBinding.shimmerLayout.isVisible = true
        viewBinding.shimmerLayout.showShimmer(true)
    }

    override fun setNativeAd(nativeAd: NativeAd) {
        viewBinding.shimmerLayout.isVisible = false
        viewBinding.shimmerLayout.hideShimmer()
        viewBinding.nativeTemplate.isVisible = true
        // Retry chờ layout nằm ở BaseLoadingNativeAdView (huỷ được khi view bị recycle)
        bindTemplateWhenLaidOut(viewBinding.nativeTemplate, nativeAd)
    }
}
