package com.freshness.ads.manager

import android.app.Application
import android.content.pm.ApplicationInfo
import com.freshness.ads.banner.AdsBannerService
import com.freshness.ads.consent.AdsCrashGuard
import com.freshness.ads.consent.ConsentService
import com.freshness.ads.inter.InterAdService
import com.freshness.ads.natives.NativeAdService
import com.freshness.ads.open.OpenAdService
import com.freshness.ads.remoteconfig.RemoteConfigService
import com.freshness.ads.reward.RewardAdService
import timber.log.Timber

/**
 * Initializes all ad services in the correct order.
 *
 * Không tự dựng service: [com.freshness.ads.di.AdsGraph] giữ chúng và gọi [init] một lần trong
 * `Application.onCreate()`. Nhận thẳng instance chứ không phải `Lazy` như bản Hilt, vì [init] chạm
 * tới tất cả — việc trì hoãn khởi tạo đã do graph lo.
 */
internal class AdsInitializer(
    private val consentService: ConsentService,
    private val openAdService: OpenAdService,
    private val bannerAdService: AdsBannerService,
    private val interAdService: InterAdService,
    private val nativeAdService: NativeAdService,
    private val rewardAdService: RewardAdService,
    private val rewardedInterAdService: RewardAdService,
    private val adsManagerService: AdsManagerService,
    private val remoteConfigService: RemoteConfigService,
) {

    /**
     * Initialize all ad services.
     *
     * Không nhận ad unit id nữa: mọi id đến từ `ads_id_config` qua
     * [com.freshness.ads.config.AdUnitCatalog].
     */
    fun init(app: Application) {
        // Plant the log tree only for debuggable (debug) builds, so the release
        // AAB uploaded to Play prints no ad logs. With no tree planted, every
        // Timber.d/w call in the ads module is a cheap no-op in release.
        val isDebuggable = (app.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (isDebuggable) AdsLogging.plantIfMissing("app host debuggable")

        Timber.d("AdsInitializer: Starting initialization... (debuggable=$isDebuggable)")

        // 0. Guard against known library-internal crashes raised on ad/UMP-owned
        // threads (UMP consent NoSuchElementException; WebView WebResourceResponse
        // "reasonPhrase can't be empty"), which can't be caught at our call site.
        // Installed before consent init and after Firebase/Crashlytics auto-init,
        // so Crashlytics stays the delegate for every other crash. See AdsCrashGuard.
        AdsCrashGuard.install()

        // 1. Initialize consent (must be first)
        consentService.init(app)

        // 2. Initialize remote config
        remoteConfigService.init(app)

        // 3. Initialize open ad service
        openAdService.init(app)

        // 4. Initialize other ad services
        bannerAdService.init(app)
        interAdService.init(app)
        nativeAdService.init(app)
        rewardAdService.init(app)
        rewardedInterAdService.init(app)

        // 5. Initialize the ads manager (lifecycle observer, activity tracking)
        adsManagerService.init(app)

        Timber.d("AdsInitializer: Initialization complete")
    }
}
