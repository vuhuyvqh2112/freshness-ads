package com.freshness.ads.reward

import android.app.Activity
import android.app.Application
import com.freshness.ads.loading.DEFAULT_MIN_LOADING_MS
import com.freshness.ads.manager.AdShowOutcome
import com.google.android.libraries.ads.mobile.sdk.rewarded.RewardItem
import kotlinx.coroutines.flow.StateFlow
import java.lang.ref.WeakReference

interface RewardAdService {

    val isShowing: StateFlow<Boolean>

    fun init(app: Application)

    fun loadAd(config: RewardAdConfig)

    suspend fun showAd(
        activity: WeakReference<Activity>,
        config: RewardAdConfig,
        onShow: () -> Unit = {},
        onReward: (RewardItem) -> Unit = {}
    ): Boolean = showAdOutcome(activity, config, onShow, onReward).shown

    /** Như [showAd] nhưng trả lời vì sao không hiện. */
    suspend fun showAdOutcome(
        activity: WeakReference<Activity>,
        config: RewardAdConfig,
        onShow: () -> Unit = {},
        onReward: (RewardItem) -> Unit = {}
    ): AdShowOutcome

    fun reset()

}

/**
 * Config là KEY của cache: hai config `equals` nhau dùng chung một ad đã nạp. Preload và show cùng
 * một placement phải dùng cùng một config.
 *
 * @param placement key trong `ads_id_config` (app tự khai hằng placement của mình).
 * @param retryCount CHỈ áp cho tier cuối của waterfall; các tier trước không retry.
 * @param timeOut thời gian `showAd` giữ màn chờ để đợi fill, đồng thời là base của ngân sách
 *   waterfall. Reward do người dùng chủ động bấm và đứng sau spinner không huỷ được, nên chờ đủ lâu
 *   để fill thật về kịp thay vì bỏ cuộc sớm và phí matched request. CHỈ LÀ FALLBACK: Remote Config
 *   `settings.rewardTimeoutMs` thắng giá trị này khi > 0.
 * @param isShowLoading bật màn chờ của SDK. Tắt là tắt luôn cả khoảng chờ tối thiểu [minLoadingMs].
 * @param minLoadingMs thời gian tối thiểu màn chờ phải hiện trước khi quảng cáo bung, kể cả khi ad
 *   đã preload sẵn. Xem `awaitMinLoadingWindow` cho phần chính sách. 0 = bung ngay khi có ad.
 */
data class RewardAdConfig @JvmOverloads constructor(
    val placement: String,
    val retryCount: Int = 2,
    val timeOut: Long = 20_000L,
    val isShowLoading: Boolean = true,
    val minLoadingMs: Long = DEFAULT_MIN_LOADING_MS
)