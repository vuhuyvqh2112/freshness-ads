package com.freshness.ads.di

import android.app.Application
import android.content.Context
import com.freshness.ads.banner.AdsBannerProvider
import com.freshness.ads.banner.AdsBannerService
import com.freshness.ads.config.AdUnitCatalog
import com.freshness.ads.config.AdsConfig
import com.freshness.ads.config.AdUnitCatalogProvider
import com.freshness.ads.consent.ConsentProvider
import com.freshness.ads.consent.ConsentService
import com.freshness.ads.datastore.AdsDataStore
import com.freshness.ads.events.AdsEvents
import com.freshness.ads.events.AdsListener
import com.freshness.ads.events.FirebaseAdImpressionLogger
import com.freshness.ads.inter.InterAdProvider
import com.freshness.ads.inter.InterAdService
import com.freshness.ads.loading.AdLoading
import com.freshness.ads.loading.AdLoadingImpl
import com.freshness.ads.loading.AdLoadingOverlay
import com.freshness.ads.manager.AdsInitializer
import com.freshness.ads.manager.AdsManagerProvider
import com.freshness.ads.manager.AdsManagerService
import com.freshness.ads.natives.AdPoolManager
import com.freshness.ads.natives.NativeAdProvider
import com.freshness.ads.natives.NativeAdService
import com.freshness.ads.open.OpenAdProvider
import com.freshness.ads.open.OpenAdService
import com.freshness.ads.remoteconfig.RemoteConfigProvider
import com.freshness.ads.remoteconfig.RemoteConfigService
import com.freshness.ads.reward.RewardAdProvider
import com.freshness.ads.reward.RewardAdService
import com.freshness.ads.reward.RewardedInterstitialKind
import com.freshness.ads.reward.RewardedVideoKind
import kotlinx.coroutines.Dispatchers

/**
 * Đồ thị singleton của module ads.
 *
 * Thay cho `@Module`/`@Binds` của Hilt: app host chạy AGP với Kotlin built-in nên không có kapt/KSP
 * để sinh code Dagger. Mỗi service vẫn là một instance duy nhất, dựng lười bằng `by lazy` — cùng
 * vòng đời như `@Singleton` trước đây, chỉ khác là dây được nối tay ở đây.
 *
 * Gọi [install] MỘT LẦN trong `Application.onCreate()`; sau đó dùng [adsManager] ở mọi call site.
 */
object AdsGraph {

    @Volatile
    private var app: Application? = null

    private val context: Context
        get() = requireNotNull(app) {
            "AdsGraph chưa install — gọi AdsGraph.install(app) trong Application.onCreate()"
        }

    /**
     * Master gate của app host. Mặc định theo `enableAllAds` trên Remote Config và
     * [AdsConfig.isPremium]; app cần logic phức tạp hơn thì truyền bản riêng vào [install].
     */
    private var dataStoreOverride: AdsDataStore? = null

    /**
     * Cấu hình của app host, gán trong [install] TRƯỚC khi bất kỳ `by lazy` nào dưới đây bị chạm tới.
     * Đọc sau đó vẫn ra đúng bản đã gán vì mọi service chỉ dựng một lần.
     */
    private var config: AdsConfig = AdsConfig()

    val adUnitCatalog: AdUnitCatalog by lazy { AdUnitCatalogProvider(context, config.useRealIdsInDebug) }

    val remoteConfigService: RemoteConfigService by lazy { RemoteConfigProvider(adUnitCatalog) }

    val adsDataStore: AdsDataStore by lazy {
        dataStoreOverride ?: RemoteFlagAdsDataStore(remoteConfigService, config.isPremium)
    }

    /**
     * Trạng thái màn chờ của interstitial/rewarded. SDK tự vẽ spinner mặc định; app chỉ cần chạm
     * tới đây khi tắt [AdsConfig.showDefaultLoadingUi] để dựng giao diện riêng.
     */
    val adLoading: AdLoading by lazy { AdLoadingImpl() }

    val consentService: ConsentService by lazy { ConsentProvider(context, config) }

    val bannerAdService: AdsBannerService by lazy {
        AdsBannerProvider(adsDataStore, adUnitCatalog)
    }

    val interAdService: InterAdService by lazy {
        InterAdProvider(adsDataStore, adLoading, adUnitCatalog, config.splashInterstitialPlacement)
    }

    val openAdService: OpenAdService by lazy {
        OpenAdProvider(adsDataStore, adUnitCatalog, consentService, config.openAdPlacement, config.openAdExcludedActivities)
    }

    val rewardAdService: RewardAdService by lazy {
        RewardAdProvider(adsDataStore, adLoading, adUnitCatalog, RewardedVideoKind)
    }

    /** Rewarded interstitial: cùng API với rewarded video, placement khai `"format": "rewardedInter"`. */
    val rewardedInterAdService: RewardAdService by lazy {
        RewardAdProvider(adsDataStore, adLoading, adUnitCatalog, RewardedInterstitialKind)
    }

    val nativeAdService: NativeAdService by lazy {
        NativeAdProvider(
            dataStore = adsDataStore,
            catalog = adUnitCatalog,
            defaultOptions = config.nativeAdOptions,
            refillEnabled = { remoteConfigService.remoteConfigValue.bool("nativeRefill", config.nativeRefill) },
        )
    }

    val adPoolManager: AdPoolManager by lazy {
        AdPoolManager(adsDataStore, adUnitCatalog, context, config.nativePools, config.nativeAdOptions)
    }

    val adsManager: AdsManagerService by lazy {
        AdsManagerProvider(
            interAdService = interAdService,
            openAdService = openAdService,
            _bannerAdService = bannerAdService,
            _nativeAdService = nativeAdService,
            _adPoolManager = adPoolManager,
            rewardAdService = rewardAdService,
            rewardedInterAdService = rewardedInterAdService,
            consentService = consentService,
            remoteConfigService = remoteConfigService,
            adUnitCatalog = adUnitCatalog,
            adsDataStore = adsDataStore,
            mainDispatcher = Dispatchers.Main,
            context = context,
            splashInterstitialPlacement = config.splashInterstitialPlacement,
        )
    }

    /**
     * Nhận mọi sự kiện quảng cáo (loaded / impression / click / paid…) cho analytics và attribution.
     * Gọi được trước hoặc sau [install]; listener chạy trên main thread. Xem [AdsListener].
     */
    fun addListener(listener: AdsListener) = AdsEvents.add(listener)

    fun removeListener(listener: AdsListener) = AdsEvents.remove(listener)

    /**
     * Nối graph vào [application] và khởi tạo mọi ad service theo đúng thứ tự
     * (xem [AdsInitializer]). Gọi lại lần thứ hai là no-op.
     *
     * @param config placement mà SDK tự gọi, pool size của native, cờ premium… Xem [AdsConfig].
     * @param dataStore master gate riêng của app. null = theo `enableAllAds` + [AdsConfig.isPremium].
     */
    fun install(
        application: Application,
        config: AdsConfig = AdsConfig(),
        dataStore: AdsDataStore? = null,
    ) {
        if (app != null) return
        app = application
        this.config = config
        dataStoreOverride = dataStore
        if (config.logAdImpressionToFirebase) {
            AdsEvents.add(FirebaseAdImpressionLogger(application))
        }
        AdsInitializer(
            consentService = consentService,
            openAdService = openAdService,
            bannerAdService = bannerAdService,
            interAdService = interAdService,
            nativeAdService = nativeAdService,
            rewardAdService = rewardAdService,
            rewardedInterAdService = rewardedInterAdService,
            adsManagerService = adsManager,
            remoteConfigService = remoteConfigService,
        ).init(application)

        // Sau init: overlay chỉ bám vòng đời activity và observe adLoading, không phụ thuộc thứ tự
        // khởi tạo của các ad service.
        if (config.showDefaultLoadingUi) {
            AdLoadingOverlay(adLoading).install(application)
        }
    }
}

/**
 * Master gate mặc định: `enableAllAds` trên Remote Config AND chưa mua premium.
 *
 * Premium lấy từ [isPremium] (lambda app truyền qua [AdsConfig]) — đọc mỗi lần nên mua xong là tắt
 * ads ngay. `isPurchased` vẫn set tay được cho app chưa nối lambda.
 */
private class RemoteFlagAdsDataStore(
    private val remoteConfigService: RemoteConfigService,
    private val isPremium: (() -> Boolean)?,
) : AdsDataStore {

    override var isPurchased: Boolean = false

    override val isAdEnabled: Boolean
        get() {
            if (isPurchased || isPremium?.invoke() == true) return false
            // Chỉ `false` tường minh mới tắt: chưa fetch xong thì field là null và ads phải chạy,
            // nếu không lần mở app đầu tiên sẽ không có ad nào.
            return remoteConfigService.remoteConfigValue.enableAllAds != false
        }
}
