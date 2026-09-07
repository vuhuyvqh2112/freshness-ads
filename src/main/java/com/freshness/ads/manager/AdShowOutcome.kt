package com.freshness.ads.manager

/**
 * Kết quả của một lần gọi show interstitial / rewarded — trả lời "vì sao không hiện" thay vì một
 * `false` câm. Dùng cho log, A/B và để màn hình quyết định có chờ thêm hay đi tiếp.
 */
enum class AdShowOutcome {
    /** Ad đã hiện và người dùng đã đóng. */
    SHOWN,
    /** Master gate tắt: premium, hoặc `enableAllAds = false`. */
    DISABLED,
    /** Placement tắt trong `ads_id_config` hoặc không có id nào. */
    PLACEMENT_OFF,
    /** Người dùng EEA chưa đồng ý consent — không được request. */
    CONSENT_MISSING,
    /** Chưa đủ khoảng cách tối thiểu giữa hai interstitial. */
    FREQUENCY_CAP,
    /** Đang có ad toàn màn khác, hoặc một lần show cùng placement đang chạy. */
    ALREADY_SHOWING,
    /** Chờ hết `timeOut` mà không có fill. */
    NO_FILL,
    /** Không còn activity nào để show lên. */
    NO_ACTIVITY,
    /** Có ad nhưng `show()` hỏng (hết hạn, activity đang finish…). */
    SHOW_FAILED,
    ;

    val shown: Boolean get() = this == SHOWN
}
