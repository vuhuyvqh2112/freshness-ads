package com.freshness.ads.config

import androidx.annotation.Keep

/**
 * Model của payload Remote Config `ads_id_config` — nguồn duy nhất cho ad unit id + công tắc bật/tắt.
 *
 * MỌI field đều nullable và mọi default đều nằm ở getter, KHÔNG ở constructor. Gson dựng object bằng
 * Unsafe nên không chạy constructor Kotlin: field thiếu trong JSON sẽ là `null` bất kể khai báo
 * `= emptyList()` hay `= true`. Khai báo non-null ở đây là mở đường cho NPE khi payload thiếu field.
 * Cùng lý do với việc [com.freshness.ads.remoteconfig.RemoteConfig] dùng `Boolean?` khắp nơi.
 */
@Keep
data class AdIdEntry(
    val id: String? = null,
    /** null = bật. Chỉ `false` tường minh mới tắt id này. */
    val enable: Boolean? = null,
)

@Keep
data class AdPlacementConfig(
    /** Một trong [AdFormat.tag]. Dùng chọn test id khi debug và để log. */
    val format: String? = null,
    /** null = bật. Chỉ `false` tường minh mới tắt cả placement. */
    val enable: Boolean? = null,
    val ids: List<AdIdEntry?>? = null,
    /** Ghi đè ngân sách thời gian mặc định của placement. null = dùng default trong [AdBudgets]. */
    val baseMs: Long? = null,
    val tierCapMs: Long? = null,
    val ceilingMs: Long? = null,
) {
    /**
     * Id còn sống, GIỮ NGUYÊN thứ tự trong payload — thứ tự này chính là thứ tự waterfall
     * (high-floor trước, all-price cuối). Id bị tắt bị loại chứ không đẩy xuống cuối.
     */
    val activeIds: List<String>
        get() {
            if (enable == false) return emptyList()
            return ids.orEmpty().mapNotNull { entry ->
                entry?.id?.takeIf { it.isNotBlank() && entry.enable != false }
            }
        }
}

/** Các setting không thuộc placement nào. Chưa dùng ở phase 01 — [com.freshness.ads.remoteconfig.RemoteConfigProvider] vẫn đọc `ads_remote_config`; sẽ chuyển sang đây ở phase 05. */
@Keep
data class AdSettings(
    val splashTimeoutMs: Long? = null,
    val interMinIntervalMs: Long? = null,
    /** Deadline một lần show reward: xem [com.freshness.ads.remoteconfig.RemoteConfig.rewardTimeoutMs]. */
    val rewardTimeoutMs: Long? = null,
)

@Keep
data class AdIdConfig(
    val version: Int? = null,
    val enableAllAds: Boolean? = null,
    val settings: AdSettings? = null,
    val placements: Map<String, AdPlacementConfig?>? = null,
)
