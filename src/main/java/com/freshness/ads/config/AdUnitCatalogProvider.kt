package com.freshness.ads.config

import android.content.Context
import com.freshness.ads.BuildConfig
import com.freshness.ads.remoteconfig.RemoteConfig
import com.google.gson.Gson
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
        Timber.e(it, "$TAG không đọc được assets/$DEFAULTS_ASSET")
    }.getOrNull()

class AdUnitCatalogProvider internal constructor(
    private val defaultsJsonLoader: () -> String?,
    private val isDebugBuild: Boolean,
) : AdUnitCatalog {

    constructor(context: Context) : this(
        defaultsJsonLoader = { readDefaultsAsset(context) },
        isDebugBuild = BuildConfig.DEBUG,
    ) {
        // Debug muốn dùng id thật thì bật cờ trong ads/build.gradle.kts, không cần sửa code.
        useRealIds = BuildConfig.USE_REAL_IDS_IN_DEBUG
    }

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
            Timber.w("$TAG placement '$placement' không có trong cả Remote Config lẫn default")
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
        val effective = remote ?: defaults
        val settings = effective.settings
        // Field thiếu -> giữ default của RemoteConfig, không ép về null: mất splashTimeoutMs sẽ khiến
        // splash rơi về fallback cứng 15s, khác hẳn 30s đang chạy production.
        val fallback = RemoteConfig()
        return RemoteConfig(
            enableAllAds = effective.enableAllAds ?: fallback.enableAllAds,
            splashTimeoutMs = settings?.splashTimeoutMs ?: fallback.splashTimeoutMs,
            interMinIntervalMs = settings?.interMinIntervalMs ?: fallback.interMinIntervalMs,
            rewardTimeoutMs = settings?.rewardTimeoutMs ?: fallback.rewardTimeoutMs,
            isFetched = true,
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
    }

    /**
     * Remote Config ghi đè theo TỪNG PLACEMENT, không merge từng field: placement có trong payload thì
     * payload thắng trọn vẹn, không có thì rơi về default. Merge từng field sẽ khiến việc tắt một id trở
     * nên khó đoán (không rõ id nào đến từ đâu).
     */
    private fun configFor(placement: String): AdPlacementConfig? =
        remote?.placements?.get(placement) ?: defaults.placements?.get(placement)

    private fun parse(json: String?): AdIdConfig? {
        if (json.isNullOrBlank()) return null
        return runCatching { Gson().fromJson(json, AdIdConfig::class.java) }
            .onFailure { Timber.e(it, "$TAG parse ads_id_config thất bại") }
            .getOrNull()
    }
}
