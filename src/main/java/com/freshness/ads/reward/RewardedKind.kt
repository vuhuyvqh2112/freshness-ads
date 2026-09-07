package com.freshness.ads.reward

import android.app.Activity
import com.freshness.ads.config.AdFormat
import com.google.android.libraries.ads.mobile.sdk.common.AdEventCallback
import com.google.android.libraries.ads.mobile.sdk.common.AdLoadCallback
import com.google.android.libraries.ads.mobile.sdk.common.AdRequest
import com.google.android.libraries.ads.mobile.sdk.rewarded.RewardItem
import com.google.android.libraries.ads.mobile.sdk.rewarded.RewardedAd
import com.google.android.libraries.ads.mobile.sdk.rewarded.RewardedAdEventCallback
import com.google.android.libraries.ads.mobile.sdk.rewardedinterstitial.RewardedInterstitialAd
import com.google.android.libraries.ads.mobile.sdk.rewardedinterstitial.RewardedInterstitialAdEventCallback

/**
 * Hai format có thưởng của GMA next-gen ([RewardedAd] và [RewardedInterstitialAd]) có cùng hình
 * dạng API nhưng không chung interface. Adapter này gom phần khác nhau lại để [RewardAdProvider]
 * viết một lần cho cả hai.
 */
internal interface RewardedKind<A : Any> {
    val format: AdFormat
    val tag: String
    fun load(request: AdRequest, callback: AdLoadCallback<A>)
    /** Gắn callback sự kiện chung vào ad (mỗi format có kiểu callback riêng, cùng extends [AdEventCallback]). */
    fun attach(ad: A, callback: AdEventCallback)
    fun show(ad: A, activity: Activity, onReward: (RewardItem) -> Unit)
}

internal object RewardedVideoKind : RewardedKind<RewardedAd> {
    override val format = AdFormat.REWARDED
    override val tag = "RewardAdProvider"
    override fun load(request: AdRequest, callback: AdLoadCallback<RewardedAd>) = RewardedAd.load(request, callback)
    override fun attach(ad: RewardedAd, callback: AdEventCallback) {
        ad.adEventCallback = object : RewardedAdEventCallback, AdEventCallback by callback {}
    }
    override fun show(ad: RewardedAd, activity: Activity, onReward: (RewardItem) -> Unit) =
        ad.show(activity) { onReward(it) }
}

internal object RewardedInterstitialKind : RewardedKind<RewardedInterstitialAd> {
    override val format = AdFormat.REWARDED_INTERSTITIAL
    override val tag = "RewardedInterProvider"
    override fun load(request: AdRequest, callback: AdLoadCallback<RewardedInterstitialAd>) =
        RewardedInterstitialAd.load(request, callback)
    override fun attach(ad: RewardedInterstitialAd, callback: AdEventCallback) {
        ad.adEventCallback = object : RewardedInterstitialAdEventCallback, AdEventCallback by callback {}
    }
    override fun show(ad: RewardedInterstitialAd, activity: Activity, onReward: (RewardItem) -> Unit) =
        ad.show(activity) { onReward(it) }
}
