package com.freshness.ads.reward

import android.app.Activity
import android.app.Application
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
    ): Boolean

    fun reset()

}

/**
 * @param placement key trong `ads_id_config` (app tự khai hằng placement của mình).
 * @param retryCount CHỈ áp cho tier cuối của waterfall; các tier trước không retry.
 */
data class RewardAdConfig(
    val placement: String,
    val retryCount: Int = 2,
    // How long showAd keeps the loading dialog up while waiting for a fill, and
    // the base budget of the waterfall. Reward is an on-demand, user-initiated
    // format (the user is behind a non-cancelable spinner waiting for their
    // reward), so we wait long enough for a real fill to arrive and be shown
    // instead of bailing early and wasting the matched request. Bounded so the
    // spinner can never hang.
    //
    // FALLBACK ONLY: Remote Config `settings.rewardTimeoutMs` thắng giá trị này
    // khi > 0, xem `RewardAdProvider.effectiveTimeOut`.
    val timeOut: Long = 20_000L,
    val isShowLoading: Boolean = true
) {
    fun asRewardAdRequest() = RewardAdRequest(placement = placement)
}