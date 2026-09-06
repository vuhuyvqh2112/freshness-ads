package com.freshness.ads.config

/**
 * Những mảnh cấu hình mà SDK phải biết trước khi chạy, vì chúng không suy ra được từ call site.
 *
 * Placement ở đây là KEY trong `assets/ads_id_config.json`, không phải ad unit id — id được resolve
 * lúc load, và Remote Config ghi đè được. App tự khai hằng placement của mình; SDK cố ý không mang
 * theo danh sách placement nào, vì mỗi app đặt ad ở chỗ khác nhau.
 *
 * Chỉ hai placement phải khai ở đây, vì SDK tự gọi chúng chứ không nhận từ tham số:
 * - [openAdPlacement]: app-open ad load ngầm theo vòng đời process, không có call site nào truyền key vào.
 * - [splashInterstitialPlacement]: interstitial của splash có ngân sách thời gian riêng (không giãn
 *   theo số tier — xem [AdBudgets.interSplash]), nên SDK phải nhận ra nó giữa các interstitial khác.
 *
 * @param nativePools pool preload cho native: key placement -> số ad giữ sẵn. Pool size phải bằng
 *   SỐ SLOT màn hình bind CÙNG LÚC. Màn chỉ có một slot tĩnh mà khai 2 thì ad thứ hai không có
 *   đường ra: mỗi phiên phát dư một request không bao giờ đổi được impression, show rate tụt xuống.
 *   Placement không khai ở đây vẫn dùng pool được, với [DEFAULT_POOL_SIZE].
 */
data class AdsConfig(
    val openAdPlacement: String = DEFAULT_OPEN_AD_PLACEMENT,
    val splashInterstitialPlacement: String = DEFAULT_SPLASH_INTERSTITIAL_PLACEMENT,
    val nativePools: Map<String, Int> = emptyMap(),
) {
    companion object {
        const val DEFAULT_OPEN_AD_PLACEMENT = "open_all"
        const val DEFAULT_SPLASH_INTERSTITIAL_PLACEMENT = "inter_splash"

        /** Pool size cho placement không khai trong [nativePools]: một slot, mức an toàn nhất. */
        const val DEFAULT_POOL_SIZE = 1
    }
}
