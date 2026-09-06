package com.freshness.ads.remoteconfig

import android.app.Application
import com.freshness.ads.config.AdUnitCatalog
import com.google.android.gms.tasks.OnCompleteListener
import com.google.firebase.remoteconfig.FirebaseRemoteConfig
import com.google.firebase.remoteconfig.FirebaseRemoteConfigSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber

class RemoteConfigProvider constructor(
    private val adUnitCatalog: AdUnitCatalog
) : RemoteConfigService {

    private val firebaseRemoteConfig by lazy {
        FirebaseRemoteConfig.getInstance().apply {
            val builder = FirebaseRemoteConfigSettings.Builder()
                .setMinimumFetchIntervalInSeconds(10).build()
            setConfigSettingsAsync(builder)
        }
    }

    private val _remoteConfig = MutableStateFlow(RemoteConfig())
    val remoteConfig = _remoteConfig.asStateFlow()

    override val remoteConfigValue: RemoteConfig
        get() = _remoteConfig.value

    override fun init(app: Application) {
        refreshRemoteConfig()
    }

    override fun refreshRemoteConfig() {
        firebaseRemoteConfig.fetchAndActivate().addOnCompleteListener {
            if (it.isSuccessful) {
                // Nạp catalog TRƯỚC khi công bố settings (isFetched=true). waitRemoteConfig() giải
                // phóng ngay khi thấy cờ đó, nên đảo thứ tự sẽ khiến splash thức dậy và đọc catalog cũ
                // đúng vào lúc request ad đầu tiên của phiên.
                adUnitCatalog.update(firebaseRemoteConfig.getString(KEY_ADS_ID_CONFIG))
                _remoteConfig.value = adUnitCatalog.settingsSnapshot()
                Timber.d("remoteConfig = ${_remoteConfig.value}")
            }
        }
    }

    override suspend fun waitRemoteConfig(): RemoteConfig {
        if (remoteConfigValue.isFetched == true) return remoteConfigValue
        return withTimeoutOrNull(5_000) {
            remoteConfig.filter { it.isFetched == true}.first()
        } ?: remoteConfigValue
    }

    companion object {
        /**
         * Key Remote Config DUY NHẤT cho ads: id, công tắc từng placement/từng id, `enableAllAds` và
         * `settings`. Key cũ `ads_remote_config` không còn được đọc — bản cũ ngoài thị trường được xử
         * lý bằng force update (`app_version`), không duy trì song song hai key.
         */
        private const val KEY_ADS_ID_CONFIG = "ads_id_config"
    }
}