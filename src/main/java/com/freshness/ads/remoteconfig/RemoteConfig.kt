package com.freshness.ads.remoteconfig

import androidx.annotation.Keep

/**
 * Các setting ads không thuộc placement nào, dựng từ payload `ads_id_config`.
 *
 * Trước đây class này còn mang 28 cờ `is*Enable` cho từng placement, đọc từ key `ads_remote_config`.
 * Cả hai đã bị bỏ: công tắc placement giờ nằm trong `ads_id_config.placements.<key>.enable` (một nguồn
 * sự thật duy nhất), và `ads_remote_config` không còn được đọc nữa.
 *
 * Bốn field có tên là những setting SDK tự dùng. Mọi key khác app host đặt trong `settings` của
 * payload đều đọc được qua [long] / [bool] / [string] — cùng một lần fetch với id quảng cáo, không
 * phải tự gọi Firebase riêng (và không lệch khoảng fetch/throttle với SDK).
 *
 * Vẫn để nullable: giá trị đi ra từ JSON, field thiếu là null chứ không nhận default.
 */
@Keep
data class RemoteConfig(
    /** Master kill switch. Xem `AdsDataStore.isAdEnabled`. */
    val enableAllAds: Boolean? = true,
    /** Vừa là deadline ad của splash, vừa là duration thanh progress. Xem `SplashActivity`. */
    val splashTimeoutMs: Long? = 30_000L,
    /** Khoảng cách tối thiểu giữa hai interstitial bất kỳ. */
    val interMinIntervalMs: Long? = 20_000L,
    /**
     * Deadline của MỘT lần reward: vừa là thời gian `showAd` giữ dialog loading, vừa là `baseMs` của
     * ngân sách waterfall (xem `RewardAdProvider`). Một con số cho cả hai để không lặp lại lỗi cũ:
     * waterfall bỏ cuộc trước khi `showAd` hết kiên nhẫn thì fill về muộn chỉ còn kịp vào cache.
     *
     * `<= 0` hoặc thiếu field = dùng `RewardAdConfig.timeOut` mà màn hình gọi khai báo (20s).
     */
    val rewardTimeoutMs: Long? = 20_000L,
    /**
     * Deadline TỔNG mặc định cho MỘT lần load bất kỳ placement nào: tổng thời gian đi hết waterfall.
     *
     * Ví dụ 20s với placement 2 id: id đầu no-fill ở giây thứ 10 thì id sau còn 10s, tới giây 20 là
     * dừng cả lượt. Không phải deadline của từng id — không id nào bị cắt ngang khi đang chờ.
     *
     * `null`/`<= 0` = giữ cách tính theo `baseMs + (số_id - 1) * tierCapMs`. Placement khai `totalMs`
     * riêng thì bản riêng thắng, và inter splash thì không bao giờ bị đụng (xem `AdBudgets`).
     */
    val adTimeoutMs: Long? = null,
    val isFetched: Boolean? = false,
    /**
     * Toàn bộ `settings` đang có hiệu lực (asset đã được Remote Config ghi đè theo key). Giá trị chỉ
     * là Boolean / Long / Double / String. Đọc qua [long], [bool], [string] thay vì chạm map.
     */
    val settings: Map<String, Any> = emptyMap(),
) {
    /** Số nguyên, nhận cả khi console ghi dạng chuỗi `"3"`. Thiếu hoặc sai kiểu = [default]. */
    fun long(key: String, default: Long): Long = when (val v = settings[key]) {
        is Number -> v.toLong()
        is String -> v.toLongOrNull() ?: default
        else -> default
    }

    /** Boolean, nhận cả `"true"`/`"false"` dạng chuỗi. Thiếu hoặc sai kiểu = [default]. */
    fun bool(key: String, default: Boolean): Boolean = when (val v = settings[key]) {
        is Boolean -> v
        is String -> v.toBooleanStrictOrNull() ?: default
        else -> default
    }

    /** Chuỗi; số/boolean cũng được đổi sang chuỗi. Thiếu = [default]. */
    fun string(key: String, default: String): String = settings[key]?.toString() ?: default
}
