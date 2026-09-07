package com.freshness.ads.config

import android.content.Context
import com.freshness.ads.natives.AdChoicesCorner
import com.freshness.ads.natives.NativeAdOptions
import com.freshness.ads.natives.NativeMediaAspectRatio
import com.freshness.ads.remoteconfig.RemoteConfig
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import timber.log.Timber

private const val TAG = "AdUnitCatalog"

internal const val DEFAULTS_ASSET = "ads_id_config.json"

/**
 * Top-level chứ không phải hàm của companion: nó được gọi từ lambda trong delegation của constructor
 * phụ, nơi Kotlin chưa cho phép chạm tới bất cứ thành viên nào của class.
 */
private fun readDefaultsAsset(context: Context): String? =
    runCatching {
        context.assets.open(DEFAULTS_ASSET).bufferedReader().use { it.readText() }
    }.onFailure {
        Timber.e(it, "$TAG không đọc được assets/$DEFAULTS_ASSET — file có nằm ở app/src/main/assets/ không?")
    }.getOrNull()

internal class AdUnitCatalogProvider(
    private val defaultsJsonLoader: () -> String?,
    /** Theo cờ debuggable của APP HOST, xem [isHostDebuggable]. */
    private val isDebugBuild: Boolean,
) : AdUnitCatalog {

    /** @param useRealIdsInDebug xem [AdsConfig.useRealIdsInDebug]. */
    constructor(context: Context, useRealIdsInDebug: Boolean) : this(
        defaultsJsonLoader = { readDefaultsAsset(context) },
        isDebugBuild = context.isHostDebuggable(),
    ) {
        useRealIds = useRealIdsInDebug
    }

    private val gson = GsonBuilder()
        .registerTypeAdapter(AdIdEntry::class.java, AdIdEntryDeserializer)
        .create()

    /** Payload Remote Config đã parse. null = chưa fetch được lần nào -> dùng [defaults]. */
    @Volatile
    private var remote: AdIdConfig? = null

    /**
     * Bản đóng gói trong APK. Đọc một lần, memoize.
     *
     * Đây là thứ đỡ toàn bộ ở cold start: Remote Config fetch bất đồng bộ trong khi splash request ad ngay,
     * nên nếu chỉ dựa vào Remote Config thì lần mở app đầu tiên của mỗi user mới sẽ trắng ads.
     */
    private val defaults: AdIdConfig by lazy {
        parse(defaultsJsonLoader()) ?: AdIdConfig().also {
            Timber.e("$TAG default asset thiếu/hỏng — mọi placement sẽ resolve rỗng")
        }
    }

    override var useRealIds: Boolean = false
        set(value) {
            if (!isDebugBuild) return
            field = value
            Timber.w("$TAG useRealIds=$value — debug build đang dùng ID THẬT")
        }

    override fun idsFor(placement: String): List<String> {
        val config = configFor(placement) ?: run {
            Timber.w("$TAG placement '$placement' không có trong cả Remote Config lẫn assets/$DEFAULTS_ASSET — gõ đúng tên key chưa?")
            return emptyList()
        }
        val ids = config.activeIds
        if (ids.isEmpty()) return debugTestIdForUndeclared(placement, config)

        // Debug ép test id để không phụ thuộc inventory thật. Chỉ lấy 1 id: test unit luôn fill nên
        // waterfall nhiều tầng ở đây là vô nghĩa. Bật useRealIds khi cần test waterfall thật.
        if (isDebugBuild && !useRealIds) {
            return listOf(AdFormat.from(config.format)?.testId ?: ids.first())
        }
        return ids
    }

    /**
     * Debug: placement CHƯA khai id nào vẫn phải chạy thử được, nếu không thì mọi vị trí mới bị chặn
     * cho tới khi unit thật được tạo trên AdMob — không test được luồng UI của chính nó.
     *
     * Chỉ áp cho ca "chưa khai": `enable: false` hoặc đã khai id rồi tắt đi là tắt CÓ CHỦ Ý, hai ca đó
     * vẫn phải rỗng ở debug, đúng như quy ước ghi trong payload. Release không bao giờ đi vào đây.
     */
    private fun debugTestIdForUndeclared(placement: String, config: AdPlacementConfig): List<String> {
        if (!isDebugBuild || useRealIds) return emptyList()
        if (config.enable == false || config.ids.orEmpty().isNotEmpty()) return emptyList()
        val testId = AdFormat.from(config.format)?.testId ?: return emptyList()
        Timber.w("$TAG DEBUG '$placement' chưa có id thật — dùng test id $testId")
        return listOf(testId)
    }

    override fun isEnabled(placement: String): Boolean = idsFor(placement).isNotEmpty()

    override fun settingsSnapshot(): RemoteConfig {
        val settings = effectiveSettings()
        // Field thiếu -> giữ default của RemoteConfig, không ép về null: mất splashTimeoutMs sẽ khiến
        // splash rơi về fallback cứng 15s, khác hẳn 30s đang chạy production.
        val fallback = RemoteConfig()
        return RemoteConfig(
            enableAllAds = remote?.enableAllAds ?: defaults.enableAllAds ?: fallback.enableAllAds,
            splashTimeoutMs = settings.longOrNull("splashTimeoutMs") ?: fallback.splashTimeoutMs,
            interMinIntervalMs = settings.longOrNull("interMinIntervalMs") ?: fallback.interMinIntervalMs,
            rewardTimeoutMs = settings.longOrNull("rewardTimeoutMs") ?: fallback.rewardTimeoutMs,
            isFetched = true,
            settings = settings,
        )
    }

    override fun budgetSpecFor(placement: String, fallback: AdBudgetSpec): AdBudgetSpec {
        val config = configFor(placement) ?: return fallback
        return AdBudgetSpec(
            baseMs = config.baseMs ?: fallback.baseMs,
            tierCapMs = config.tierCapMs ?: fallback.tierCapMs,
            // ceilingMs = null trong fallback nghĩa là "không giãn" (splash) — payload muốn giãn thì
            // phải khai tường minh, chứ không được vô tình bật lên khi thiếu field.
            ceilingMs = config.ceilingMs ?: fallback.ceilingMs,
        )
    }

    override fun nativeOptionsFor(placement: String, fallback: NativeAdOptions): NativeAdOptions {
        val config = configFor(placement) ?: return fallback
        return NativeAdOptions(
            videoMuted = config.videoMuted ?: fallback.videoMuted,
            mediaAspectRatio = NativeMediaAspectRatio.from(config.mediaAspectRatio) ?: fallback.mediaAspectRatio,
            adChoicesPlacement = AdChoicesCorner.from(config.adChoicesPlacement) ?: fallback.adChoicesPlacement,
        )
    }

    override fun update(json: String?) {
        if (json.isNullOrBlank()) {
            Timber.d("$TAG Remote Config trả rỗng — giữ payload đang dùng")
            return
        }
        val parsed = parse(json)
        if (parsed == null) {
            // Giữ nguyên payload cũ. Đẩy về rỗng ở đây = một lần paste JSON hỏng làm mất ads toàn app.
            Timber.e("$TAG payload hỏng — giữ payload đang dùng")
            return
        }
        remote = parsed
        Timber.d("$TAG đã nạp payload version=${parsed.version} placements=${parsed.placements?.size ?: 0}")
        if (isDebugBuild) Timber.i("$TAG catalog hiệu lực:\n${describe()}")
    }

    override fun describe(): String {
        val keys = (defaults.placements?.keys.orEmpty() + remote?.placements?.keys.orEmpty()).toSortedSet()
        val sb = StringBuilder()
        sb.append("enableAllAds=${remote?.enableAllAds ?: defaults.enableAllAds} settings=${effectiveSettings()}")
        keys.forEach { key ->
            val c = configFor(key) ?: return@forEach
            val source = when {
                remote?.placements?.get(key) != null && defaults.placements?.get(key) != null -> "asset+remote"
                remote?.placements?.get(key) != null -> "remote"
                else -> "asset"
            }
            val budget = listOfNotNull(
                c.baseMs?.let { "base=$it" }, c.tierCapMs?.let { "cap=$it" }, c.ceilingMs?.let { "ceil=$it" },
            ).joinToString(" ")
            sb.append("\n  $key [${c.format}] enable=${c.enable != false} hf=${c.hf ?: "-"} ($source)")
            if (budget.isNotEmpty()) sb.append(" $budget")
            val active = c.activeIds
            c.ids.orEmpty().forEachIndexed { i, e ->
                val id = e?.id ?: "?"
                val on = id in active
                sb.append("\n    ${i + 1}. $id${if (on) "" else " (OFF)"}")
            }
        }
        return sb.toString()
    }

    /**
     * Remote Config ghi đè TỪNG FIELD của placement (xem [AdPlacementConfig.patchedBy]): payload chỉ
     * cần mang phần thay đổi. Placement chỉ có ở một bên thì lấy nguyên bên đó.
     */
    private fun configFor(placement: String): AdPlacementConfig? {
        val base = defaults.placements?.get(placement)
        val patch = remote?.placements?.get(placement)
        return when {
            patch == null -> base
            base == null -> patch
            else -> base.patchedBy(patch)
        }
    }

    /** `settings` gộp theo key: key có trên remote thắng, còn lại giữ của asset. */
    private fun effectiveSettings(): Map<String, Any> {
        val merged = mutableMapOf<String, Any>()
        merged.putAll(defaults.settings.toPrimitiveMap())
        merged.putAll(remote?.settings.toPrimitiveMap())
        return merged
    }

    private fun parse(json: String?): AdIdConfig? {
        if (json.isNullOrBlank()) return null
        return runCatching { gson.fromJson(json, AdIdConfig::class.java) }
            .onFailure { Timber.e(it, "$TAG parse ads_id_config thất bại") }
            .getOrNull()
    }
}

/**
 * Chỉ giữ giá trị nguyên thuỷ (Boolean / Long / Double / String) để [RemoteConfig.settings] không
 * lộ kiểu Gson ra API public. Số không có phần thập phân thành Long, có thì Double.
 */
private fun JsonObject?.toPrimitiveMap(): Map<String, Any> {
    if (this == null) return emptyMap()
    val out = linkedMapOf<String, Any>()
    entrySet().forEach { (key, el) ->
        if (!el.isJsonPrimitive) return@forEach
        val p = el.asJsonPrimitive
        out[key] = when {
            p.isBoolean -> p.asBoolean
            p.isNumber -> p.asString.let { it.toLongOrNull() ?: p.asDouble }
            else -> p.asString
        }
    }
    return out
}

private fun Map<String, Any>.longOrNull(key: String): Long? = when (val v = this[key]) {
    is Number -> v.toLong()
    is String -> v.toLongOrNull()
    else -> null
}
