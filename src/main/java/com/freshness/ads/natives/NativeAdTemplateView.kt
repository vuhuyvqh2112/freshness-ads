package com.freshness.ads.natives

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.annotation.LayoutRes
import androidx.appcompat.widget.AppCompatRatingBar
import com.freshness.ads.R
import com.google.android.libraries.ads.mobile.sdk.nativead.MediaView
import com.google.android.libraries.ads.mobile.sdk.nativead.NativeAdView

/**
 * Template native dựng từ MỘT layout XML của app, bind theo id quy ước — không cần subclass
 * [BaseNativeAdView] cho mỗi thiết kế.
 *
 * Root của layout phải là `NativeAdView` với id `native_ad_view`. Các asset tuỳ chọn, thiếu id nào
 * thì asset đó không hiển thị: `media_view` (MediaView), `icon` (ImageView), `primary` (headline),
 * `body`, `cta`, `advertiser`, `rating_bar` (AppCompatRatingBar), `price`, `rate_price_container`.
 * Đây cũng là id các template có sẵn của SDK đang dùng, nên copy XML của SDK ra sửa là chạy.
 *
 * Dùng trong XML: `<com.freshness.ads.natives.NativeAdTemplateView app:adLayout="@layout/my_native" />`,
 * hoặc từ code `NativeAdTemplateView(context, R.layout.my_native)`. Thường không dùng trực tiếp mà
 * qua [LoadingNativeAdTemplateView] để có shimmer.
 */
class NativeAdTemplateView : BaseNativeAdView {

    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs) {
        val layoutRes = context.obtainStyledAttributes(attrs, R.styleable.LoadingNativeAdTemplateView).use {
            it.getResourceId(R.styleable.LoadingNativeAdTemplateView_adLayout, 0)
        }
        require(layoutRes != 0) { "NativeAdTemplateView cần app:adLayout trỏ tới layout có NativeAdView id native_ad_view" }
        inflate(layoutRes)
    }

    constructor(context: Context, @LayoutRes layoutRes: Int) : super(context, null) {
        inflate(layoutRes)
    }

    private lateinit var adView: NativeAdView

    private fun inflate(@LayoutRes layoutRes: Int) {
        LayoutInflater.from(context).inflate(layoutRes, this, true)
        adView = findViewById(R.id.native_ad_view)
            ?: error("Layout $layoutRes thiếu NativeAdView với id native_ad_view")
    }

    // Asset bắt buộc của BaseNativeAdView mà layout không có -> view rời, không nằm trong cây, để
    // logic bind chung vẫn chạy mà không phải null-check khắp nơi.
    private val detachedText by lazy { TextView(context) }
    private val detachedIcon by lazy { ImageView(context) }

    override fun getAdView(): NativeAdView = adView
    override fun getTitleView(): TextView = findViewById(R.id.primary) ?: detachedText
    override fun getIconView(): ImageView = findViewById(R.id.icon) ?: detachedIcon
    override fun getCallActionButtonView(): TextView = findViewById(R.id.cta) ?: detachedText
    override fun getSubTitleView(): TextView? = findViewById(R.id.body)
    override fun getMediaView(): MediaView? = findViewById(R.id.media_view)
    override fun getAdvertiser(): TextView? = findViewById(R.id.advertiser)
    override fun getRatingView(): AppCompatRatingBar? = findViewById<View>(R.id.rating_bar) as? AppCompatRatingBar
    override fun getPriceView(): TextView? = findViewById(R.id.price)
    override fun getViewContainerRate_Price(): View? = findViewById(R.id.rate_price_container)
}

private inline fun <T> android.content.res.TypedArray.use(block: (android.content.res.TypedArray) -> T): T =
    try { block(this) } finally { recycle() }
