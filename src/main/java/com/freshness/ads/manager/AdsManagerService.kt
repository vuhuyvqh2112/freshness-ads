package com.freshness.ads.manager

import android.app.Activity
import android.app.Application
import com.freshness.ads.banner.AdsBannerService
import com.freshness.ads.inter.InterAdConfig
import com.freshness.ads.inter.InterAdService
import com.freshness.ads.natives.AdPoolManager
import com.freshness.ads.natives.NativeAdService
import com.freshness.ads.open.OpenAdService
import com.freshness.ads.remoteconfig.RemoteConfig
import com.freshness.ads.reward.RewardAdConfig
import com.freshness.ads.reward.RewardAdService
import com.google.android.libraries.ads.mobile.sdk.rewarded.RewardItem
import kotlinx.coroutines.flow.StateFlow
import java.lang.ref.WeakReference

/**
 * Central manager for all ad operations.
 * Host apps should use this as the single entry point for ads.
 */
interface AdsManagerService {

    val topActivity: WeakReference<Activity>

    /**
     * true khi BẤT KỲ ad toàn màn nào (interstitial, rewarded, app-open) đang chiếm màn hình. Dùng để
     * pause video/nhạc: `Activity.onPause` không đủ vì màn kế tiếp có thể được dựng ngay dưới ad.
     */
    val isFullScreenAdShowing: StateFlow<Boolean>

    val bannerAdService: AdsBannerService
    val nativeAdService: NativeAdService
    val adPoolManager: AdPoolManager
    val interAdService: InterAdService
    val openAdService: OpenAdService
    val rewardAdService: RewardAdService
    /** Rewarded interstitial — cùng [RewardAdConfig], placement khai `"format": "rewardedInter"`. */
    val rewardedInterAdService: RewardAdService

    /**
     * Initialize the ads manager. Must be called in Application.onCreate()
     */
    fun init(app: Application)

    /**
     * Launch the splash ad flow (consent → open/inter ad)
     * @return true if ad was shown
     */
    suspend fun launchSplashAdFlow(interConfig: InterAdConfig? = null): Boolean

    /**
     * Show interstitial ad with remote config check.
     *
     * @param placement placement dùng để kiểm công tắc (master gate + `ads_id_config`). null (mặc
     *   định) = kiểm theo chính `config.placement`. Chỉ truyền khi muốn gate bằng một key KHÁC key
     *   nạp ad — trường hợp hiếm.
     */
    suspend fun showInterAd(
        config: InterAdConfig,
        placement: String? = null,
        onShow: () -> Unit = {}
    ): Boolean = showInterAdOutcome(config, placement, onShow).shown

    /** Như [showInterAd] nhưng trả lời VÌ SAO không hiện — xem [AdShowOutcome]. */
    suspend fun showInterAdOutcome(
        config: InterAdConfig,
        placement: String? = null,
        onShow: () -> Unit = {}
    ): AdShowOutcome

    /** Dạng ngắn: dùng [InterAdConfig] mặc định cho [placement]. Preload bằng [loadInterAd] cùng dạng. */
    suspend fun showInterAd(placement: String, onShow: () -> Unit = {}): Boolean =
        showInterAd(InterAdConfig(placement), onShow = onShow)

    /**
     * Load interstitial ad
     */
    fun loadInterAd(config: InterAdConfig)

    /** Dạng ngắn của [loadInterAd] với [InterAdConfig] mặc định. */
    fun loadInterAd(placement: String) = loadInterAd(InterAdConfig(placement))

    /**
     * Nạp (nếu chưa có sẵn) rồi hiện interstitial trong một lệnh.
     *
     * Dùng cho chỗ đặt ad không preload trước được — người dùng bấm rồi mới biết cần quảng cáo.
     * Màn chờ che khoảng nạp; nếu ad đã nằm sẵn trong cache thì nó vẫn hiện đủ
     * [InterAdConfig.minLoadingMs] trước khi quảng cáo bung.
     *
     * Vẫn tôn trọng frequency cap và công tắc placement như [showInterAd].
     */
    suspend fun loadAndShowInterAd(
        config: InterAdConfig,
        placement: String? = null,
        onShow: () -> Unit = {}
    ): Boolean = loadAndShowInterAdOutcome(config, placement, onShow).shown

    /** Như [loadAndShowInterAd] nhưng trả [AdShowOutcome]. */
    suspend fun loadAndShowInterAdOutcome(
        config: InterAdConfig,
        placement: String? = null,
        onShow: () -> Unit = {}
    ): AdShowOutcome

    /** Dạng ngắn của [loadAndShowInterAd] với [InterAdConfig] mặc định. */
    suspend fun loadAndShowInterAd(placement: String, onShow: () -> Unit = {}): Boolean =
        loadAndShowInterAd(InterAdConfig(placement), onShow = onShow)

    /**
     * Show rewarded ad with remote config check.
     *
     * @param placement placement dùng để kiểm công tắc; null = kiểm theo `config.placement`.
     *
     * @param onShow bắn đúng lúc quảng cáo đã hiện toàn màn. Cần callback này chứ không thể chờ hàm
     *   trả về: hàm chỉ trả về SAU KHI người dùng đóng quảng cáo, nên call site nào đang giữ một
     *   spinner chờ fill mà đợi giá trị trả về thì spinner sẽ nằm dưới quảng cáo suốt cả video rồi
     *   loé lên một nhịp lúc đóng.
     */
    suspend fun showRewardAd(
        config: RewardAdConfig,
        placement: String? = null,
        onShow: () -> Unit = {},
        onReward: (RewardItem) -> Unit = {}
    ): Boolean = showRewardAdOutcome(config, placement, onShow, onReward).shown

    /** Như [showRewardAd] nhưng trả [AdShowOutcome]. */
    suspend fun showRewardAdOutcome(
        config: RewardAdConfig,
        placement: String? = null,
        onShow: () -> Unit = {},
        onReward: (RewardItem) -> Unit = {}
    ): AdShowOutcome

    /**
     * Load rewarded ad
     */
    fun loadRewardAd(config: RewardAdConfig)

    /**
     * Nạp (nếu chưa có sẵn) rồi hiện rewarded trong một lệnh.
     *
     * Đây là cách dùng tự nhiên nhất của rewarded: người dùng bấm "xem quảng cáo nhận thưởng" xong
     * mới cần tới ad, nên preload trước thường chỉ tạo request không đổi được impression. Màn chờ
     * che khoảng nạp; ad có sẵn thì vẫn hiện đủ [RewardAdConfig.minLoadingMs] trước khi bung.
     */
    suspend fun loadAndShowRewardAd(
        config: RewardAdConfig,
        placement: String? = null,
        onShow: () -> Unit = {},
        onReward: (RewardItem) -> Unit = {}
    ): Boolean = loadAndShowRewardAdOutcome(config, placement, onShow, onReward).shown

    /** Như [loadAndShowRewardAd] nhưng trả [AdShowOutcome]. */
    suspend fun loadAndShowRewardAdOutcome(
        config: RewardAdConfig,
        placement: String? = null,
        onShow: () -> Unit = {},
        onReward: (RewardItem) -> Unit = {}
    ): AdShowOutcome

    /** Dạng ngắn của [loadAndShowRewardAd] với [RewardAdConfig] mặc định. */
    suspend fun loadAndShowRewardAd(
        placement: String,
        onShow: () -> Unit = {},
        onReward: (RewardItem) -> Unit = {}
    ): Boolean = loadAndShowRewardAd(RewardAdConfig(placement), onShow = onShow, onReward = onReward)

    /** Rewarded interstitial: tương tự [loadRewardAd]. */
    fun loadRewardedInterAd(config: RewardAdConfig)

    /** Rewarded interstitial: tương tự [showRewardAd]. */
    suspend fun showRewardedInterAd(
        config: RewardAdConfig,
        placement: String? = null,
        onShow: () -> Unit = {},
        onReward: (RewardItem) -> Unit = {}
    ): Boolean = showRewardedInterAdOutcome(config, placement, onShow, onReward).shown

    suspend fun showRewardedInterAdOutcome(
        config: RewardAdConfig,
        placement: String? = null,
        onShow: () -> Unit = {},
        onReward: (RewardItem) -> Unit = {}
    ): AdShowOutcome

    /** Rewarded interstitial: tương tự [loadAndShowRewardAd]. */
    suspend fun loadAndShowRewardedInterAd(
        config: RewardAdConfig,
        placement: String? = null,
        onShow: () -> Unit = {},
        onReward: (RewardItem) -> Unit = {}
    ): Boolean = loadAndShowRewardedInterAdOutcome(config, placement, onShow, onReward).shown

    suspend fun loadAndShowRewardedInterAdOutcome(
        config: RewardAdConfig,
        placement: String? = null,
        onShow: () -> Unit = {},
        onReward: (RewardItem) -> Unit = {}
    ): AdShowOutcome

    /** Dạng ngắn của [loadAndShowRewardedInterAd] với [RewardAdConfig] mặc định. */
    suspend fun loadAndShowRewardedInterAd(
        placement: String,
        onShow: () -> Unit = {},
        onReward: (RewardItem) -> Unit = {}
    ): Boolean = loadAndShowRewardedInterAd(RewardAdConfig(placement), onShow = onShow, onReward = onReward)

    /**
     * Load open ad for foreground resume. Id lấy từ `ads_id_config` (placement `open_all`).
     */
    fun loadOpenAd()

    /**
     * Placement này có đang bật không (master gate + `ads_id_config`).
     */
    fun isAdEnabled(placement: String): Boolean

    /**
     * Get current remote config
     */
    val remoteConfig: RemoteConfig

    /**
     * Xoá frequency cap của interstitial — ví dụ sau khi người dùng vừa đi qua một luồng dài, muốn
     * interstitial kế tiếp không bị chặn bởi khoảng cách tối thiểu.
     */
    fun resetInterTimer()

    /**
     * Set a listener to handle showing the open ad on foreground resume.
     * The app module should set this to launch an opaque Activity before showing the ad.
     */
    fun setOnShowOpenAdListener(listener: (() -> Unit)?)

    /**
     * Set whether to skip the open ad on next foreground
     */
    fun setSkipOpenAd(value: Boolean)

    /**
     * Mark splash state
     */
    fun setIsSplash(splash: Boolean)

    /**
     * Reset all ad services
     */
    fun reset()

    /**
     * Whether the app must expose a "Privacy options" entry point in its UI
     * (required by Google UMP policy for users in EEA/UK).
     */
    val isPrivacyOptionsRequired: Boolean

    /**
     * Show the privacy options form so the user can change/withdraw consent.
     * Should only be invoked when [isPrivacyOptionsRequired] is true.
     */
    fun showPrivacyOptionsForm(activity: Activity, onDismissed: () -> Unit = {})
}
