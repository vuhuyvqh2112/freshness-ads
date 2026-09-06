package com.freshness.ads.remoteconfig

import androidx.annotation.Keep

/**
 * Các setting ads không thuộc placement nào, dựng từ payload `ads_id_config`.
 *
 * Trước đây class này còn mang 28 cờ `is*Enable` cho từng placement, đọc từ key `ads_remote_config`.
 * Cả hai đã bị bỏ: công tắc placement giờ nằm trong `ads_id_config.placements.<key>.enable` (một nguồn
 * sự thật duy nhất), và `ads_remote_config` không còn được đọc nữa.
 *
 * Ba field chỉ có ở đây vì còn consumer thật; 3 field cũ (`shouldShowIapOnGetTheme`,
 * `isOnboardingEnable`, `isOnboardingSecondEnable`) chưa từng được đọc ở đâu nên đã xoá.
 *
 * Vẫn để nullable: giá trị đi ra từ JSON qua Gson, field thiếu là null chứ không nhận default.
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
    val isFetched: Boolean? = false
)
