package com.freshness.ads.manager

import android.app.Activity
import android.app.Application
import android.app.Application.ActivityLifecycleCallbacks
import android.content.Context
import android.os.Bundle
import android.os.SystemClock
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.freshness.ads.banner.AdsBannerService
import com.freshness.ads.config.AdUnitCatalog
import com.freshness.ads.consent.ConsentService
import com.freshness.ads.datastore.AdsDataStore
import com.freshness.ads.inter.InterAdConfig
import com.freshness.ads.inter.InterAdService
import com.freshness.ads.natives.AdPoolManager
import com.freshness.ads.natives.NativeAdService
import com.freshness.ads.open.OpenAdService
import com.freshness.ads.remoteconfig.RemoteConfig
import com.freshness.ads.remoteconfig.RemoteConfigService
import com.freshness.ads.reward.RewardAdConfig
import com.freshness.ads.reward.RewardAdService
import com.google.android.libraries.ads.mobile.sdk.rewarded.RewardItem
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import timber.log.Timber
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicBoolean

internal class AdsManagerProvider(
    override val interAdService: InterAdService,
    override val openAdService: OpenAdService,
    private val _bannerAdService: AdsBannerService,
    private val _nativeAdService: NativeAdService,
    private val _adPoolManager: AdPoolManager,
    override val rewardAdService: RewardAdService,
    override val rewardedInterAdService: RewardAdService,
    private val consentService: ConsentService,
    private val remoteConfigService: RemoteConfigService,
    private val adUnitCatalog: AdUnitCatalog,
    private val adsDataStore: AdsDataStore,
    private val mainDispatcher: CoroutineDispatcher,
    private val context: Context,
    /** Xem [com.freshness.ads.config.AdsConfig.splashInterstitialPlacement]. */
    private val splashInterstitialPlacement: String,
) : AdsManagerService, LifecycleEventObserver {

    private val scope = CoroutineScope(mainDispatcher + SupervisorJob())

    override val isFullScreenAdShowing: StateFlow<Boolean> = combine(
        interAdService.isShowing,
        rewardAdService.isShowing,
        rewardedInterAdService.isShowing,
        openAdService.isOpenAdShowing,
    ) { inter, reward, rewardedInter, open -> inter || reward || rewardedInter || open }
        .stateIn(scope, SharingStarted.Eagerly, false)

    private val _shouldShowForegroundAd = AtomicBoolean(false)
    private var _skipOpenAd = false
    private var isSplash = false
    private var onShowOpenAdListener: (() -> Unit)? = null

    // Frequency cap: minimum gap between two interstitials (any placement).
    // Prevents back-to-back interstitials when the user clicks through screens quickly.
    private var lastInterShownAt = 0L
    private val DEFAULT_INTER_MIN_INTERVAL_MS = 20_000L

    private fun canShowInterNow(): Boolean {
        if (lastInterShownAt == 0L) return true
        val minInterval = remoteConfigService.remoteConfigValue.interMinIntervalMs
            ?: DEFAULT_INTER_MIN_INTERVAL_MS
        return SystemClock.elapsedRealtime() - lastInterShownAt >= minInterval
    }

    private fun markInterShown() {
        lastInterShownAt = SystemClock.elapsedRealtime()
    }

    override fun resetInterTimer() {
        lastInterShownAt = 0L
    }

    private var _topActivity: WeakReference<Activity> = WeakReference(null)
    override val topActivity: WeakReference<Activity> get() = _topActivity

    override val bannerAdService: AdsBannerService get() = _bannerAdService
    override val nativeAdService: NativeAdService get() = _nativeAdService
    override val adPoolManager: AdPoolManager get() = _adPoolManager

    override val remoteConfig: RemoteConfig
        get() = remoteConfigService.remoteConfigValue

    override fun init(app: Application) {
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
        app.registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                _topActivity = WeakReference(activity)
            }

            override fun onActivityStarted(activity: Activity) {
                _topActivity = WeakReference(activity)
            }

            override fun onActivityResumed(activity: Activity) {
                _topActivity = WeakReference(activity)
            }

            override fun onActivityPaused(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }

    override fun setIsSplash(splash: Boolean) {
        isSplash = splash
        Timber.d("AdsManager: Set is splash: $splash")
    }

    override fun setOnShowOpenAdListener(listener: (() -> Unit)?) {
        onShowOpenAdListener = listener
    }

    override fun setSkipOpenAd(value: Boolean) {
        _skipOpenAd = value
    }

    override fun loadInterAd(config: InterAdConfig) {
        if (!consentService.canRequestAds) return
        interAdService.loadAd(config)
    }

    override suspend fun showInterAdOutcome(
        config: InterAdConfig,
        placement: String?,
        onShow: () -> Unit
    ): AdShowOutcome {
        if (!adsDataStore.isAdEnabled) return AdShowOutcome.DISABLED
        if (!consentService.canRequestAds) return AdShowOutcome.CONSENT_MISSING
        val gate = placement ?: config.placement
        if (!isAdEnabled(gate)) {
            Timber.d("AdsManager: Inter ad disabled by remote config: $gate")
            return AdShowOutcome.PLACEMENT_OFF
        }
        if (!canShowInterNow()) {
            Timber.d("AdsManager: Inter frequency cap active, skip ${config.placement}")
            return AdShowOutcome.FREQUENCY_CAP
        }
        val outcome = interAdService.showAdOutcome(_topActivity, config, onShow)
        if (outcome.shown) markInterShown()
        return outcome
    }

    override suspend fun loadAndShowInterAdOutcome(
        config: InterAdConfig,
        placement: String?,
        onShow: () -> Unit
    ): AdShowOutcome {
        // Kiểm cap TRƯỚC khi nạp: showInterAd sẽ từ chối ngay khi cap còn hiệu lực, nạp trước đó
        // chỉ phát một request không bao giờ đổi được impression.
        if (!canShowInterNow()) {
            Timber.d("AdsManager: Inter frequency cap active, skip load+show ${config.placement}")
            return AdShowOutcome.FREQUENCY_CAP
        }
        // loadAd tự bỏ qua khi đã có ad trong cache hoặc job đang chạy, nên gọi thẳng là đủ —
        // không cần kiểm tra trạng thái ở đây rồi lệch với logic bên trong provider.
        loadInterAd(config)
        return showInterAdOutcome(config, placement, onShow)
    }

    override fun loadRewardAd(config: RewardAdConfig) {
        if (!consentService.canRequestAds) return
        rewardAdService.loadAd(config)
    }

    override suspend fun loadAndShowRewardAdOutcome(
        config: RewardAdConfig,
        placement: String?,
        onShow: () -> Unit,
        onReward: (RewardItem) -> Unit
    ): AdShowOutcome {
        loadRewardAd(config)
        return showRewardAdOutcome(config, placement, onShow, onReward)
    }

    override suspend fun showRewardAdOutcome(
        config: RewardAdConfig,
        placement: String?,
        onShow: () -> Unit,
        onReward: (RewardItem) -> Unit
    ): AdShowOutcome = showRewardedOutcome(rewardAdService, config, placement, onShow, onReward)

    override fun loadRewardedInterAd(config: RewardAdConfig) {
        if (!consentService.canRequestAds) return
        rewardedInterAdService.loadAd(config)
    }

    override suspend fun loadAndShowRewardedInterAdOutcome(
        config: RewardAdConfig,
        placement: String?,
        onShow: () -> Unit,
        onReward: (RewardItem) -> Unit
    ): AdShowOutcome {
        loadRewardedInterAd(config)
        return showRewardedInterAdOutcome(config, placement, onShow, onReward)
    }

    override suspend fun showRewardedInterAdOutcome(
        config: RewardAdConfig,
        placement: String?,
        onShow: () -> Unit,
        onReward: (RewardItem) -> Unit
    ): AdShowOutcome = showRewardedOutcome(rewardedInterAdService, config, placement, onShow, onReward)

    /** Gate chung cho hai format có thưởng: master gate, consent, công tắc placement. */
    private suspend fun showRewardedOutcome(
        service: RewardAdService,
        config: RewardAdConfig,
        placement: String?,
        onShow: () -> Unit,
        onReward: (RewardItem) -> Unit
    ): AdShowOutcome {
        if (!adsDataStore.isAdEnabled) return AdShowOutcome.DISABLED
        if (!consentService.canRequestAds) return AdShowOutcome.CONSENT_MISSING
        val gate = placement ?: config.placement
        if (!isAdEnabled(gate)) {
            Timber.d("AdsManager: Rewarded ad disabled by remote config: $gate")
            return AdShowOutcome.PLACEMENT_OFF
        }
        return service.showAdOutcome(_topActivity, config, onShow = onShow, onReward = onReward)
    }

    override fun loadOpenAd() {
        if (!consentService.canRequestAds) return
        openAdService.loadAd()
    }

    /**
     * Công tắc của một placement = master gate AND catalog.
     *
     * Master gate ([AdsDataStore.isAdEnabled]) phủ IAP và `enableAllAds`. Phần còn lại giờ nằm trọn
     * trong `ads_id_config`: placement tắt, hoặc không còn id nào bật, đều ra false. Khối `when` ánh
     * xạ 28 cờ `is*Enable` trước đây bị bỏ vì hai nguồn sự thật cho cùng một công tắc.
     */
    override fun isAdEnabled(placement: String): Boolean {
        if (!adsDataStore.isAdEnabled) return false
        return adUnitCatalog.isEnabled(placement)
    }

    override suspend fun launchSplashAdFlow(interConfig: InterAdConfig?): Boolean = coroutineScope {
        if (!adsDataStore.isAdEnabled) {
            nativeAdService.disableNativeAds()
            return@coroutineScope false
        }
        // Bounded consent gather — never let a stuck UMP form freeze the splash.
        consentService.ensureConsent(activity = _topActivity, timeoutMs = 10_000L)

        if (!consentService.canRequestAds) {
            // GDPR: user denied consent — skip ad request and let splash continue without an ad.
            Timber.d("AdsManager: Consent not granted, skipping splash ad")
            nativeAdService.disableNativeAds()
            return@coroutineScope false
        }

        val result = when {
            interConfig != null && isAdEnabled(splashInterstitialPlacement) -> {
                Timber.d("AdsManager: Launching splash with interstitial")
                interAdService.loadAd(interConfig)
                interAdService.showAd(_topActivity, interConfig).also { shown ->
                    if (shown) markInterShown()
                }
            }

            interConfig != null -> {
                // Splash interstitial disabled by remote (isInterSplashEnable=false) →
                // skip it; the splash caller then falls back to the native full-screen.
                Timber.d("AdsManager: splash interstitial disabled by remote, skipping")
                false
            }

            else -> {
                Timber.d("AdsManager: Launching splash with open ad")
                openAdService.launchOpenAdFlow(_topActivity)
            }
        }

        openAdService.loadAd()

        if (!adsDataStore.isAdEnabled) {
            nativeAdService.disableNativeAds()
        }

        return@coroutineScope result
    }

    override fun onStateChanged(source: LifecycleOwner, event: Lifecycle.Event) {
        when (event) {
            Lifecycle.Event.ON_START -> onAppForegrounded()
            Lifecycle.Event.ON_STOP -> onAppBackgrounded()
            else -> Unit
        }
    }

    private fun onAppBackgrounded() {
        if (consentService.canRequestAds) {
            openAdService.loadAd()
        }
        _shouldShowForegroundAd.set(true)
    }

    private fun onAppForegrounded() {
        Timber.d("AdsManager: onAppForegrounded shouldShow=${_shouldShowForegroundAd.get()} isSplash=$isSplash isInterShowing=${interAdService.isShowing.value} skipOpenAd=$_skipOpenAd")

        // Recover ad init on a warm process restart that skipped the splash flow:
        // there, consent was never gathered so the SDK never initialized and ads
        // (incl. native pools) stayed stuck. The splash handles its own consent,
        // so only do this OUTSIDE the splash flow and only when not yet resolved.
        if (!isSplash && !consentService.isAdInitialized.value) {
            scope.launch { runCatching { consentService.ensureConsent(_topActivity) } }
        }

        if (_shouldShowForegroundAd.getAndSet(false)
            && !isSplash
            && !interAdService.isShowing.value
            && !rewardAdService.isShowing.value
            && !rewardedInterAdService.isShowing.value
            && !_skipOpenAd
            && !openAdService.isExcluded(_topActivity.get())
            && consentService.canRequestAds
        ) {
            val listener = onShowOpenAdListener
            if (listener != null) {
                // Delegate to app layer to show opaque Activity first (policy compliance)
                listener.invoke()
            } else {
                scope.launch {
                    openAdService.showAdIfAvailable(topActivity)
                }
            }
        }
        _skipOpenAd = false
    }

    override fun reset() {
        Timber.d("AdsManager: reset")
        openAdService.reset()
        interAdService.reset()
        nativeAdService.reset()
        _bannerAdService.reset()
        rewardAdService.reset()
        rewardedInterAdService.reset()
    }

    override val isPrivacyOptionsRequired: Boolean
        get() = consentService.isPrivacyOptionsRequired

    override fun showPrivacyOptionsForm(activity: Activity, onDismissed: () -> Unit) {
        consentService.showPrivacyOptionsForm(activity) { formError ->
            if (formError != null) {
                Timber.w("AdsManager: Privacy options form error: ${formError.errorCode} ${formError.message}")
            }
            onDismissed()
        }
    }
}
