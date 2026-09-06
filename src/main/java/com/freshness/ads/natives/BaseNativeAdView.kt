package com.freshness.ads.natives

import android.content.Context
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.widget.AppCompatRatingBar
import androidx.core.view.isVisible
import com.google.android.libraries.ads.mobile.sdk.nativead.MediaView
import com.google.android.libraries.ads.mobile.sdk.nativead.NativeAd
import com.google.android.libraries.ads.mobile.sdk.nativead.NativeAdView


abstract class BaseNativeAdView(context: Context, attrs: AttributeSet?) : FrameLayout(context, attrs) {

    open fun setNativeAd(nativeAd: NativeAd) {

        val icon = nativeAd.icon
        val starRating = nativeAd.starRating
        val title = nativeAd.headline
        val callAction = nativeAd.callToAction
        val price = nativeAd.price
        val subTitle = nativeAd.body
        val advertiser = nativeAd.advertiser

        // icon.drawable là BitmapDrawable do NativeAd sở hữu: destroy() ad sẽ recycle bitmap bên
        // dưới. Bỏ qua nếu bitmap đã chết để không bao giờ đưa nó vào cây view.
        val iconDrawable = icon?.drawable?.takeUnless { it.hasRecycledBitmap() }
        if (iconDrawable != null) {
            getIconView().setImageDrawable(iconDrawable)
            getIconView().visibility = View.VISIBLE
            getIconView().setOnClickListener { getCallActionButtonView().performClick() }
        } else {
            getIconView().setImageDrawable(null)
            getIconView().visibility = View.GONE
        }

        if (advertiser != null) {
            getAdvertiser()?.text = advertiser
            getAdvertiser()?.isVisible = true
        } else {
            getAdvertiser()?.isVisible = false
        }

        if (starRating != null && starRating > 0f) {
            getRatingView()?.rating = starRating.toFloat()
            getRatingView()?.isVisible = true
        } else {
            getRatingView()?.isVisible = false
        }

        getViewContainerRate_Price()?.isVisible = (!((starRating == null || starRating <= 0f) && price == null))

        if (subTitle != null) {
            getSubTitleView()?.text = subTitle
            getSubTitleView()?.visibility = View.VISIBLE
        } else {
            getSubTitleView()?.visibility = View.GONE
        }

        if (title != null) {
            getTitleView().text = title
            getTitleView().isSelected = true
            getTitleView().visibility = View.VISIBLE
        } else {
            getTitleView().visibility = View.GONE
        }

        if (callAction != null) {
            getCallActionButtonView().text = callAction
            getCallActionButtonView().visibility = View.VISIBLE
        } else {
            getCallActionButtonView().visibility = View.GONE
        }


        if (price != null) {
            getPriceView()?.text = price
            getPriceView()?.visibility = View.VISIBLE
        } else {
            getPriceView()?.visibility = View.GONE
        }

        getAdView().iconView = getIconView()
        getAdView().callToActionView = getCallActionButtonView()
        getAdView().headlineView = getTitleView()
        getAdView().bodyView = getSubTitleView()
        // Next-gen: register the ad + media view in one call (replaces setNativeAd
        // + adView.mediaView). mediaView param is nullable.
        getAdView().registerNativeAd(nativeAd, getMediaView())
    }


    /**
     * Gỡ mọi asset của ad khỏi view.
     *
     * Gọi trước khi [NativeAd.destroy]: sau khi destroy, drawable của icon trỏ tới bitmap đã bị
     * recycle, ImageView còn giữ nó là lần vẽ kế tiếp crash.
     */
    open fun clearAd() {
        getIconView().setImageDrawable(null)
        getIconView().setOnClickListener(null)
    }

    /** true nếu drawable là bitmap đã bị recycle (ad đã destroy). */
    private fun Drawable.hasRecycledBitmap(): Boolean =
        this is BitmapDrawable && bitmap?.isRecycled == true

    abstract fun getTitleView(): TextView
    abstract fun getSubTitleView(): TextView?
    open fun getRatingView(): AppCompatRatingBar?=null
    abstract fun getIconView(): ImageView
    abstract fun getPriceView(): TextView?
    abstract fun getCallActionButtonView(): TextView
    open fun getViewContainerRate_Price(): View ?= null

    abstract fun getAdView(): NativeAdView

    open fun getMediaView(): MediaView? = null
    open fun getAdvertiser(): TextView? = null

}