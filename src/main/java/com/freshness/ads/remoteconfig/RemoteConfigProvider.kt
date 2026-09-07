package com.freshness.ads.remoteconfig

import android.app.Application
import com.freshness.ads.config.AdUnitCatalog
import com.freshness.ads.config.isHostDebuggable
import com.freshness.ads.manager.AdsLogging
import com.google.firebase.FirebaseApp
import com.google.firebase.remoteconfig.FirebaseRemoteConfig
import com.google.firebase.remoteconfig.FirebaseRemoteConfigSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber

internal class RemoteConfigProvider(
    private val adUnitCatalog: AdUnitCatalog
) : RemoteConfigService {

    /** null = app host chưa cấu hình Firebase. SDK vẫn chạy bằng asset default. */
    private var firebaseRemoteConfig: FirebaseRemoteConfig? = null

    private val _remoteConfig = MutableStateFlow(RemoteConfig())
    val remoteConfig = _remoteConfig.asStateFlow()

    override val remoteConfigValue: RemoteConfig
        get() = _remoteConfig.value

    override fun init(app: Application) {
        // Firebase là tuỳ chọn. `FirebaseRemoteConfig.getInstance()` ném IllegalStateException khi
        // app chưa có google-services.json — để nó nổ ở đây là app host crash ngay Application.onCreate
        // chỉ vì SDK quảng cáo. Thiếu Firebase thì mọi id/setting lấy từ assets/ads_id_config.json.
        if (FirebaseApp.getApps(app).isEmpty()) {
            Timber.w("$TAG Firebase chưa được khởi tạo — bỏ qua Remote Config, dùng assets/ads_id_config.json")
            _remoteConfig.value = adUnitCatalog.settingsSnapshot()
            return
        }

        // Firebase throttle khi fetch quá 5 lần/giờ (FETCH_THROTTLED). 10s chỉ hợp lúc đang chỉnh
        // console; production giữ 1 giờ để không tự bắn vào chân.
        val minFetchIntervalSec = if (app.isHostDebuggable()) 10L else 3_600L
        firebaseRemoteConfig = FirebaseRemoteConfig.getInstance().apply {
            setConfigSettingsAsync(
                FirebaseRemoteConfigSettings.Builder()
                    .setMinimumFetchIntervalInSeconds(minFetchIntervalSec)
                    .build()
            )
        }

        // Giá trị đã activate ở phiên trước có sẵn ngay trên đĩa: nạp trước khi chạm mạng để request
        // ad đầu tiên (splash) không phải rơi về asset default trong lúc chờ fetch. isFetched vẫn
        // false cho tới khi fetch kết thúc, để waitRemoteConfig() vẫn cho fetch mới một cơ hội.
        applyActivated(fetched = false)
        refreshRemoteConfig()
    }

    override fun refreshRemoteConfig() {
        val rc = firebaseRemoteConfig ?: return
        rc.fetchAndActivate().addOnCompleteListener { task ->
            if (!task.isSuccessful) {
                // Offline hoặc bị throttle: giá trị activated của phiên trước vẫn còn nguyên trên
                // đĩa và vẫn là bản tốt nhất đang có — KHÔNG bỏ qua nó để rơi về asset default.
                Timber.w(task.exception, "$TAG fetch thất bại — dùng giá trị activated/asset default")
            }
            applyActivated(fetched = true)
        }
    }

    /**
     * Nạp catalog TRƯỚC khi công bố settings (isFetched=true). waitRemoteConfig() giải phóng ngay khi
     * thấy cờ đó, nên đảo thứ tự sẽ khiến splash thức dậy và đọc catalog cũ đúng vào lúc request ad
     * đầu tiên của phiên. Payload rỗng (chưa từng fetch) bị catalog bỏ qua, giữ asset default.
     */
    private fun applyActivated(fetched: Boolean) {
        adUnitCatalog.update(firebaseRemoteConfig?.getString(KEY_ADS_ID_CONFIG))
        _remoteConfig.value = adUnitCatalog.settingsSnapshot().copy(isFetched = fetched)
        // Tester đọc log trên bản release: bật bằng settings.enableLogForTester, không cần build riêng.
        if (_remoteConfig.value.bool(KEY_LOG_FOR_TESTER, false)) AdsLogging.plantIfMissing(KEY_LOG_FOR_TESTER)
        Timber.d("$TAG remoteConfig = ${_remoteConfig.value}")
    }

    override suspend fun waitRemoteConfig(): RemoteConfig {
        if (remoteConfigValue.isFetched == true) return remoteConfigValue
        return withTimeoutOrNull(5_000) {
            remoteConfig.filter { it.isFetched == true }.first()
        } ?: remoteConfigValue
    }

    companion object {
        private const val TAG = "RemoteConfig"

        /**
         * Key Remote Config DUY NHẤT cho ads: id, công tắc từng placement/từng id, `enableAllAds` và
         * `settings`. Key cũ `ads_remote_config` không còn được đọc — bản cũ ngoài thị trường được xử
         * lý bằng force update (`app_version`), không duy trì song song hai key.
         */
        private const val KEY_ADS_ID_CONFIG = "ads_id_config"
        private const val KEY_LOG_FOR_TESTER = "enableLogForTester"
    }
}
