package com.freshness.ads.events

import com.freshness.ads.config.AdFormat

/**
 * Doanh thu của MỘT impression, lấy từ `onAdPaid(AdValue)` của GMA. Đây là số AdMob ước tính cho
 * impression đó (xem [precision]) — thứ Adjust/AppsFlyer/Firebase cần để tính ROAS.
 */
data class AdRevenue(
    val valueMicros: Long,
    val currencyCode: String,
    val precision: Precision,
) {
    /** Giá trị theo đơn vị tiền tệ, ví dụ 0.0123 USD. */
    val value: Double get() = valueMicros / 1_000_000.0

    enum class Precision { UNKNOWN, ESTIMATED, PUBLISHER_PROVIDED, PRECISE }
}

/**
 * Mọi sự kiện quảng cáo của SDK, gom về một chỗ cho analytics / attribution. Mọi hàm đều có thân
 * rỗng, override cái nào cần. Luôn được gọi trên MAIN THREAD.
 *
 * Đăng ký qua `AdsGraph.addListener`. [onAdPaid] là sự kiện quan trọng nhất: bắn cho MỌI format,
 * mỗi impression một lần, kèm [AdRevenue] để gửi `ad_impression` / `af_ad_revenue` đi. SDK đã tự
 * gửi `ad_impression` lên Firebase Analytics nếu app có Firebase (xem `AdsConfig.logAdImpressionToFirebase`).
 *
 * `placement` là key trong `ads_id_config`, `format` là format của placement đó.
 */
interface AdsListener {
    /** Waterfall của placement kết thúc với một ad. */
    fun onAdLoaded(placement: String, format: AdFormat) = Unit

    /** Waterfall kết thúc mà không có ad (no-fill mọi tier, hết ngân sách, hoặc SDK chưa sẵn sàng). */
    fun onAdFailedToLoad(placement: String, format: AdFormat, reason: String?) = Unit

    /** Ad toàn màn (inter / reward / app-open) vừa chiếm màn hình. */
    fun onAdShowed(placement: String, format: AdFormat) = Unit

    fun onAdImpression(placement: String, format: AdFormat) = Unit

    fun onAdClicked(placement: String, format: AdFormat) = Unit

    /** Ad toàn màn đã đóng. */
    fun onAdClosed(placement: String, format: AdFormat) = Unit

    /** Ad toàn màn có sẵn nhưng show hỏng (hết hạn, activity chết…). */
    fun onAdFailedToShow(placement: String, format: AdFormat, reason: String?) = Unit

    fun onAdRewarded(placement: String, amount: Int, type: String) = Unit

    /** Một impression đã được AdMob định giá. Xem [AdRevenue]. */
    fun onAdPaid(placement: String, format: AdFormat, revenue: AdRevenue) = Unit
}
