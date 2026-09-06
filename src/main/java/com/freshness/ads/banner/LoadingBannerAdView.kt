package com.freshness.ads.banner

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.FrameLayout
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.isVisible
import com.freshness.ads.databinding.LayoutBannerAdTemplateBinding

class LoadingBannerAdView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : ConstraintLayout(context, attrs) {

   private val viewBinding =
        LayoutBannerAdTemplateBinding.inflate(LayoutInflater.from(context), this, true)
    init {
        viewBinding.shimmerLayout.startShimmer()
    }

    fun getBannerView():FrameLayout{
        return viewBinding.bannerView
    }

    fun setShowAd() {
        viewBinding.shimmerLayout.isVisible = false
        viewBinding.shimmerLayout.hideShimmer()
        viewBinding.bannerView.isVisible = true
    }

}