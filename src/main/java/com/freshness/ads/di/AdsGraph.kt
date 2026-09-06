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
     * Master gate của app host. Mặc định chỉ theo `enableAllAds` trên Remote Config; app có IAP thì
     * truyền bản riêng vào [install] để nối cờ đã mua.
     */
    private var dataStoreOverride: AdsDataStore? = null

    /**
     * Cấu hình của app host, gán trong [install] TRƯỚC khi bất kỳ `by lazy` nào dưới đây bị chạm tới.
     * Đọc sau đó vẫn ra đúng bản đã gán vì mọi service chỉ dựng một lần.
     */
    private var config: AdsConfig = AdsConfig()

    val adUnitCatalog: AdUnitCatalog by lazy { AdUnitCatalogProvider(context) }

    val remoteConfigService: RemoteConfigService by lazy { RemoteConfigProvider(adUnitCatalog) }

    val adsDataStore: AdsDataStore by lazy {
        dataStoreOverride ?: RemoteFlagAdsDataStore(remoteConfigService)
    }

    /**
     * Trạng thái màn chờ của interstitial/rewarded. SDK tự vẽ spinner mặc định; app chỉ cần chạm
     * tới đây khi tắt [AdsConfig.showDefaultLoadingUi] để dựng giao diện riêng.
     */
    val adLoading: AdLoading by lazy { AdLoadingImpl() }

    val consentService: ConsentService by lazy { ConsentProvider(context) }

    val bannerAdService: AdsBannerService by lazy {
        AdsBannerProvider(adsDataStore, adUnitCatalog)
    }

    val interAdService: InterAdService by lazy {
        InterAdProvider(context, adsDataStore, adLoading, adUnitCatalog, config.splashInterstitialPlacement)
    }

    val openAdService: OpenAdService by lazy {
        OpenAdProvider(context, adsDataStore, adUnitCatalog, config.openAdPlacement)
    }

    val rewardAdService: RewardAdService by lazy {
        RewardAdProvider(context, adsDataStore, adLoading, adUnitCatalog)
    }

    val nativeAdService: NativeAdService by lazy {
        NativeAdProvider(adsDataStore, remoteConfigService, adUnitCatalog)
    }

    val adPoolManager: AdPoolManager by lazy {
        AdPoolManager(adsDataStore, adUnitCatalog, context, config.nativePools)
    }

    val adsManager: AdsManagerService by lazy {
        AdsManagerProvider(
            interAdService = interAdService,
            openAdService = openAdService,
            _bannerAdService = bannerAdService,
            _nativeAdService = nativeAdService,
            _adPoolManager = adPoolManager,
            rewardAdService = rewardAdService,
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
     * Nối graph vào [application] và khởi tạo mọi ad service theo đúng thứ tự
     * (xem [AdsInitializer]). Gọi lại lần thứ hai là no-op.
     *
     * @param config placement mà SDK tự gọi + pool size của native. Xem [AdsConfig].
     * @param dataStore master gate riêng của app (IAP…). null = chỉ theo `enableAllAds`.
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
        AdsInitializer(
            consentService = consentService,
            openAdService = openAdService,
            bannerAdService = bannerAdService,
            interAdService = interAdService,
            nativeAdService = nativeAdService,
            rewardAdService = rewardAdService,
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
 * Master gate mặc định: chỉ cờ `enableAllAds` trên Remote Config quyết định. `isPurchased` vẫn
 * đọc/ghi được, nhưng app có IAP thật nên truyền [AdsDataStore] riêng vào [AdsGraph.install] để nối
 * vào nguồn sự thật của mình thay vì set cờ này bằng tay.
 */
private class RemoteFlagAdsDataStore(
    private val remoteConfigService: RemoteConfigService,
) : AdsDataStore {

    override var isPurchased: Boolean = false

    override val isAdEnabled: Boolean
        get() {
            if (isPurchased) return false
            // Chỉ `false` tường minh mới tắt: chưa fetch xong thì field là null và ads phải chạy,
            // nếu không lần mở app đầu tiên sẽ không có ad nào.
            return remoteConfigService.remoteConfigValue.enableAllAds != false
        }
}
