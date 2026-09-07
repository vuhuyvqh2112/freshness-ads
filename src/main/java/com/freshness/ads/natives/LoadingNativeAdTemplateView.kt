package com.freshness.ads.natives

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.annotation.LayoutRes
import androidx.core.view.isVisible
import com.facebook.shimmer.ShimmerFrameLayout
import com.freshness.ads.R
import com.google.android.libraries.ads.mobile.sdk.nativead.NativeAd

/**
 * Container "shimmer + template" cho layout native của app — bản generic của
 * `LoadingNativeAdHomeView` và các view cùng họ. Dùng với `bindNativeAdFromCache` như mọi
 * [BaseLoadingNativeAdView] khác.
 *
 * XML:
 * ```
 * <com.freshness.ads.natives.LoadingNativeAdTemplateView
 *     app:adLayout="@layout/my_native"
 *     app:adShimmerLayout="@layout/my_native_shimmer" />   <!-- tuỳ chọn -->
 * ```
 * Code: `LoadingNativeAdTemplateView(context, R.layout.my_native)`.
 *
 * Chiều cao: template full-bleed (MediaView phủ card) cần chiều cao cụ thể trên container, xem
 * hướng dẫn mục native ad validator.
 */
class LoadingNativeAdTemplateView : BaseLoadingNativeAdView {

    private val shimmer: ShimmerFrameLayout
    private val template: NativeAdTemplateView

    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs) {
        val a = context.obtainStyledAttributes(attrs, R.styleable.LoadingNativeAdTemplateView)
        val layoutRes = a.getResourceId(R.styleable.LoadingNativeAdTemplateView_adLayout, 0)
        val shimmerRes = a.getResourceId(R.styleable.LoadingNativeAdTemplateView_adShimmerLayout, DEFAULT_SHIMMER)
        a.recycle()
        require(layoutRes != 0) { "LoadingNativeAdTemplateView cần app:adLayout" }
        shimmer = buildShimmer(shimmerRes)
        template = NativeAdTemplateView(context, layoutRes)
        assemble()
    }

    @JvmOverloads
    constructor(
        context: Context,
        @LayoutRes layoutRes: Int,
        @LayoutRes shimmerRes: Int = DEFAULT_SHIMMER,
    ) : super(context, null) {
        shimmer = buildShimmer(shimmerRes)
        template = NativeAdTemplateView(context, layoutRes)
        assemble()
    }

    private fun buildShimmer(@LayoutRes shimmerRes: Int): ShimmerFrameLayout =
        ShimmerFrameLayout(context).also {
            LayoutInflater.from(context).inflate(shimmerRes, it, true)
        }

    private fun assemble() {
        val full = LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        addView(shimmer, full)
        addView(template, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        template.isVisible = false
        shimmer.startShimmer()
    }

    fun resetToShimmer() {
        unbindAd()
        template.isVisible = false
        shimmer.isVisible = true
        shimmer.showShimmer(true)
    }

    override fun setNativeAd(nativeAd: NativeAd) {
        shimmer.isVisible = false
        shimmer.hideShimmer()
        template.isVisible = true
        bindTemplateWhenLaidOut(template, nativeAd)
    }

    private companion object {
        val DEFAULT_SHIMMER = R.layout.loading_native_generic_shimmer
    }
}
