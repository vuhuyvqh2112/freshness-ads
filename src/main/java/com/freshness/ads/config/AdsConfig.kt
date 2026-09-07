package com.freshness.ads.config

import android.app.Activity
import com.freshness.ads.natives.NativeAdOptions

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
 * Mọi cờ "debug" bên dưới đọc theo cờ `debuggable` của APP HOST (ApplicationInfo.FLAG_DEBUGGABLE),
 * KHÔNG phải `BuildConfig.DEBUG` của thư viện: AAR publish lên JitPack là bản release nên
 * `BuildConfig.DEBUG` của SDK luôn false trong app host, dù app đang chạy debug.
 *
 * @param nativePools pool preload cho native: key placement -> số ad giữ sẵn. Pool size phải bằng
 *   SỐ SLOT màn hình bind CÙNG LÚC. Màn chỉ có một slot tĩnh mà khai 2 thì ad thứ hai không có
 *   đường ra: mỗi phiên phát dư một request không bao giờ đổi được impression, show rate tụt xuống.
 *   Placement không khai ở đây vẫn dùng pool được, với [DEFAULT_POOL_SIZE].
 * @param showDefaultLoadingUi SDK tự vẽ màn chờ (spinner che toàn màn) trong lúc nạp/trước khi bung
 *   interstitial và rewarded. Tắt khi app muốn giao diện riêng — lúc đó tự observe
 *   `AdsGraph.adLoading.isLoading` và tự dựng UI, đừng bỏ hẳn: khoảng chờ đó là thứ chính sách
 *   AdMob khuyến nghị nên có.
 * @param useRealIdsInDebug debug build của app host mặc định ép test id của Google (mọi tier đều
 *   fill, không bao giờ rớt tier). Bật lên để debug dùng id thật + waterfall thật — cần khi kiểm tra
 *   hành vi rớt tier. Release bỏ qua cờ này.
 * @param consentDebugGeographyEea debug build ép geography EEA để form UMP hiện trên máy test.
 *   Release bỏ qua.
 * @param consentTestDeviceIds hashed id của máy test cho UMP debug settings (UMP in id này ra logcat
 *   khi chạy lần đầu). Chỉ có tác dụng ở debug build.
 * @param nativeAdOptions tuỳ chọn request native cho mọi placement (mặc định video tắt tiếng). Ghi
 *   đè từng placement trong `ads_id_config` bằng `videoMuted` / `mediaAspectRatio` / `adChoicesPlacement`.
 * @param nativeRefill sau khi người dùng bấm vào native rồi quay lại app, hoặc khi video của native
 *   kết thúc, SDK nạp ad mới thay vào slot đó — mỗi lần thay là thêm một impression cho cùng chỗ đặt.
 *   Remote Config ghi đè bằng `settings.nativeRefill`. Chỉ áp cho slot đơn (`bindNativeAdFromCache`).
 * @param openAdExcludedActivities activity mà app-open KHÔNG được hiện đè lên khi app quay lại
 *   foreground: màn thanh toán, màn chọn file, activity quảng cáo của SDK khác… Bổ sung lúc chạy
 *   bằng `openAdService.excludeActivity`.
 * @param logAdImpressionToFirebase tự gửi sự kiện chuẩn `ad_impression` (kèm value/currency) lên
 *   Firebase Analytics cho mỗi impression có giá, nếu app có Firebase. Không có thì im lặng.
 * @param isPremium nguồn sự thật "đã mua gói bỏ quảng cáo" của app. Trả true là mọi format tắt hết.
 *   Được đọc mỗi lần kiểm tra nên đổi trạng thái mua là có hiệu lực ngay, không cần gọi gì thêm.
 *   App cần logic gate phức tạp hơn thì truyền hẳn [com.freshness.ads.datastore.AdsDataStore] vào
 *   `AdsGraph.install`; khi đó cờ này không được đọc.
 */
data class AdsConfig @JvmOverloads constructor(
    val openAdPlacement: String = DEFAULT_OPEN_AD_PLACEMENT,
    val splashInterstitialPlacement: String = DEFAULT_SPLASH_INTERSTITIAL_PLACEMENT,
    val nativePools: Map<String, Int> = emptyMap(),
    val showDefaultLoadingUi: Boolean = true,
    val useRealIdsInDebug: Boolean = false,
    val consentDebugGeographyEea: Boolean = true,
    val consentTestDeviceIds: List<String> = emptyList(),
    val nativeAdOptions: NativeAdOptions = NativeAdOptions(),
    val nativeRefill: Boolean = true,
    val openAdExcludedActivities: Set<Class<out Activity>> = emptySet(),
    val logAdImpressionToFirebase: Boolean = true,
    val isPremium: (() -> Boolean)? = null,
) {
    companion object {
        const val DEFAULT_OPEN_AD_PLACEMENT = "open_all"
        const val DEFAULT_SPLASH_INTERSTITIAL_PLACEMENT = "inter_splash"

        /** Pool size cho placement không khai trong [nativePools]: một slot, mức an toàn nhất. */
        const val DEFAULT_POOL_SIZE = 1
    }
}
