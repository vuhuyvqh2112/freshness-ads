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
import java.lang.ref.WeakReference

/**
 * Central manager for all ad operations.
 * Host apps should use this as the single entry point for ads.
 */
interface AdsManagerService {

    val topActivity: WeakReference<Activity>

    val bannerAdService: AdsBannerService
    val nativeAdService: NativeAdService
    val adPoolManager: AdPoolManager
    val interAdService: InterAdService
    val openAdService: OpenAdService
    val rewardAdService: RewardAdService

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
     * Show interstitial ad with remote config check
     * @param placement key trong `ads_id_config` (app tự khai hằng placement của mình);
     *   null = bỏ qua bước kiểm công tắc.
     */
    suspend fun showInterAd(
        config: InterAdConfig,
        placement: String? = null,
        onShow: () -> Unit = {}
    ): Boolean

    /**
     * Load interstitial ad
     */
    fun loadInterAd(config: InterAdConfig)

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
    ): Boolean

    /**
     * Show rewarded ad with remote config check.
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
    ): Boolean

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
    ): Boolean

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
