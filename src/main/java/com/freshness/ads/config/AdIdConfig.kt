package com.freshness.ads.config

import androidx.annotation.Keep
import com.google.gson.JsonDeserializationContext
import com.google.gson.JsonDeserializer
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import java.lang.reflect.Type

/**
 * Model của payload Remote Config `ads_id_config` — nguồn duy nhất cho ad unit id + công tắc bật/tắt.
 *
 * MỌI field đều nullable và mọi default đều nằm ở getter, KHÔNG ở constructor. Gson dựng object bằng
 * Unsafe nên không chạy constructor Kotlin: field thiếu trong JSON sẽ là `null` bất kể khai báo
 * `= emptyList()` hay `= true`. Khai báo non-null ở đây là mở đường cho NPE khi payload thiếu field.
 * Cùng lý do với việc [com.freshness.ads.remoteconfig.RemoteConfig] dùng `Boolean?` khắp nơi.
 *
 * `null` còn mang nghĩa "không nói gì" khi Remote Config ghi đè asset theo TỪNG FIELD (xem
 * [AdPlacementConfig.patchedBy]): field thiếu trong payload remote giữ nguyên giá trị của asset.
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
    /**
     * Công tắc cả tầng high-floor. Quy ước: id CUỐI là all-price, mọi id trước nó là high-floor.
     * `false` = chỉ chạy all-price (bỏ mọi id trước). null hoặc `true` = dùng hết `ids`.
     *
     * Mặc định giữ hành vi cũ (dùng hết) để payload đang chạy ngoài thị trường không đổi nghĩa.
     */
    val hf: Boolean? = null,
    /** Mỗi phần tử viết được dạng `"ca-app-pub…"` hoặc `{ "id": "…", "enable": false }`. */
    val ids: List<AdIdEntry?>? = null,
    /** Ghi đè ngân sách thời gian mặc định của placement. null = dùng default trong [AdBudgets]. */
    val baseMs: Long? = null,
    val tierCapMs: Long? = null,
    val ceilingMs: Long? = null,
    /** Native: ghi đè [com.freshness.ads.natives.NativeAdOptions] của `AdsConfig`. null = dùng mặc định. */
    val videoMuted: Boolean? = null,
    /** Tên trong [com.freshness.ads.natives.NativeMediaAspectRatio]: any / landscape / portrait / square. */
    val mediaAspectRatio: String? = null,
    /** Tên trong [com.freshness.ads.natives.AdChoicesCorner]: top_left / top_right / bottom_right / bottom_left. */
    val adChoicesPlacement: String? = null,
) {
    /**
     * Id còn sống, GIỮ NGUYÊN thứ tự trong payload — thứ tự này chính là thứ tự waterfall
     * (high-floor trước, all-price cuối). Id bị tắt bị loại chứ không đẩy xuống cuối.
     */
    val activeIds: List<String>
        get() {
            if (enable == false) return emptyList()
            val alive = ids.orEmpty().mapNotNull { entry ->
                entry?.id?.takeIf { it.isNotBlank() && entry.enable != false }
            }
            // hf=false: chỉ giữ all-price (id cuối), kể cả khi payload liệt kê nhiều tầng.
            return if (hf == false) alive.takeLast(1) else alive
        }

    /**
     * Ghi đè theo TỪNG FIELD: field nào [patch] có thì thắng, field null giữ của bản này. Remote
     * Config vì thế chỉ cần mang phần thay đổi — tắt một placement là `{"enable": false}`, không
     * phải paste lại cả danh sách id. `ids` có mặt là thay cả danh sách (không gộp từng phần tử).
     */
    fun patchedBy(patch: AdPlacementConfig): AdPlacementConfig = AdPlacementConfig(
        format = patch.format ?: format,
        enable = patch.enable ?: enable,
        hf = patch.hf ?: hf,
        ids = patch.ids ?: ids,
        baseMs = patch.baseMs ?: baseMs,
        tierCapMs = patch.tierCapMs ?: tierCapMs,
        ceilingMs = patch.ceilingMs ?: ceilingMs,
        videoMuted = patch.videoMuted ?: videoMuted,
        mediaAspectRatio = patch.mediaAspectRatio ?: mediaAspectRatio,
        adChoicesPlacement = patch.adChoicesPlacement ?: adChoicesPlacement,
    )
}

@Keep
data class AdIdConfig(
    val version: Int? = null,
    val enableAllAds: Boolean? = null,
    /**
     * Setting không thuộc placement nào, KEY TUỲ Ý. SDK đọc `splashTimeoutMs`, `interMinIntervalMs`,
     * `rewardTimeoutMs`; app host thêm key riêng của mình vào đây và đọc bằng
     * `RemoteConfig.long/bool/string` — một payload, một lần fetch, không phải tự gọi Firebase.
     */
    val settings: JsonObject? = null,
    val placements: Map<String, AdPlacementConfig?>? = null,
)

/**
 * Cho `ids` nhận cả chuỗi thuần lẫn object: `["ca-app-pub…/a", { "id": "…/b", "enable": false }]`.
 * Dạng chuỗi là cách viết ngắn cho placement không cần tắt riêng từng tầng.
 */
internal object AdIdEntryDeserializer : JsonDeserializer<AdIdEntry> {
    override fun deserialize(json: JsonElement, typeOfT: Type, context: JsonDeserializationContext): AdIdEntry? =
        when {
            json.isJsonNull -> null
            json.isJsonPrimitive -> AdIdEntry(id = json.asString)
            json.isJsonObject -> {
                val obj = json.asJsonObject
                AdIdEntry(
                    id = obj.get("id")?.takeUnless { it.isJsonNull }?.asString,
                    enable = obj.get("enable")?.takeUnless { it.isJsonNull }?.asBoolean,
                )
            }
            else -> null
        }
}
