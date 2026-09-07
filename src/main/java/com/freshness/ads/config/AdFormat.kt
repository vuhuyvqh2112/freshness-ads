package com.freshness.ads.config

/**
 * Format của một placement. Giá trị [tag] là thứ ghi trong `ads_id_config.placements.<key>.format`,
 * so khớp phân biệt hoa thường.
 *
 * [testId] là unit mẫu của Google (luôn 100% fill), dùng cho debug build để kiểm tra luồng ad mà không
 * phụ thuộc inventory thật. Copy nguyên từ `AdUnitId.kt` để giữ đúng hành vi debug hiện tại.
 */
enum class AdFormat(val tag: String, val testId: String) {
    BANNER("banner", "ca-app-pub-3940256099942544/9214589741"),
    NATIVE("native", "ca-app-pub-3940256099942544/2247696110"),
    INTERSTITIAL("interstitial", "ca-app-pub-3940256099942544/1033173712"),
    REWARDED("rewarded", "ca-app-pub-3940256099942544/5224354917"),
    REWARDED_INTERSTITIAL("rewardedInter", "ca-app-pub-3940256099942544/5354046379"),
    APP_OPEN("appOpen", "ca-app-pub-3940256099942544/9257395921"),
    ;

    companion object {
        fun from(tag: String?): AdFormat? = entries.firstOrNull { it.tag == tag }
    }
}
