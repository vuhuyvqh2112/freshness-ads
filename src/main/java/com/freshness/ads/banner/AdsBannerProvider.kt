package com.freshness.ads.banner

import android.app.Application
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.widget.FrameLayout
import com.freshness.ads.config.AdBudgets
import com.freshness.ads.config.AdFormat
import com.freshness.ads.config.AdLoadBudget
import com.freshness.ads.config.AdUnitCatalog
import com.freshness.ads.events.AdsEvents
import com.google.android.libraries.ads.mobile.sdk.banner.BannerAdEventCallback
import com.google.android.libraries.ads.mobile.sdk.common.AdValue
import com.freshness.ads.consent.AdInitGate
import com.freshness.ads.datastore.AdsDataStore
import com.google.android.libraries.ads.mobile.sdk.banner.AdView
import com.google.android.libraries.ads.mobile.sdk.banner.BannerAd
import com.google.android.libraries.ads.mobile.sdk.banner.BannerAdRequest
import com.google.android.libraries.ads.mobile.sdk.common.AdLoadCallback
import com.google.android.libraries.ads.mobile.sdk.common.LoadAdError
import timber.log.Timber

internal class AdsBannerProvider(
    private val datastore: AdsDataStore,
    private val catalog: AdUnitCatalog
) : AdsBannerService {

    override val isEnableAd: Boolean
        get() = datastore.isAdEnabled

    /**
     * AdView đã load được, key theo ID THẬT (không phải placement).
     *
     * Phải theo id vì với waterfall một placement có nhiều id: key theo placement sẽ khiến lần sau
     * trả về AdView của tier khác với tier vừa load.
     */
    private val adViewMap = mutableMapOf<String, AdView>()

    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * SDK trả callback trên thread nền (`ads_mobile_sdk` chạy trên ThreadPoolExecutor), trong khi
     * [loadTier] thao tác View và app layer lại tắt shimmer trong [onLoadSuccess] — cả hai đều bắt buộc
     * ở main thread. Không hop về main thì `ShimmerFrameLayout.hideShimmer` ném
     * "Animators may only be run on Looper threads", banner kẹt shimmer và không bao giờ hiện.
     */
    private inline fun runOnMain(crossinline block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block()
        else mainHandler.post { block() }
    }

    override fun init(application: Application) {

    }

    override fun loadBannerAds(
        context: Context,
        bannerView: FrameLayout,
        placement: String,
        size: BannerSize,
        onLoadSuccess: (() -> Unit)?,
        onLoadFail: (() -> Unit)?
    ) {
        if (!isEnableAd) {
            onLoadFail?.invoke()
            return
        }

        val ids = catalog.idsFor(placement)
        if (ids.isEmpty()) {
            Timber.w("$TAG SKIP banner $placement (không có id nào bật)")
            onLoadFail?.invoke()
            return
        }

        // Đã có AdView của bất kỳ tier nào thuộc placement này -> tái sử dụng, không request lại.
        ids.firstNotNullOfOrNull { id -> adViewMap[id] }?.let { adView ->
            (adView.parent as? ViewGroup)?.removeView(adView)
            bannerView.addView(adView)
            onLoadSuccess?.invoke()
            return
        }

        // Defer touching the SDK (AdView construction + load) until MobileAds.initialize
        // has completed; otherwise the next-gen SDK throws IllegalStateException.
        AdInitGate.whenReady(
            // Banner là format bị động: không ai đang chờ, và không có gì gọi lại loadBannerAds khi
            // người dùng vẫn ở trên màn hình. Bỏ cuộc ở ngưỡng mặc định là container trống nguyên
            // phiên, kể cả khi init xong ngay sau đó.
            timeoutMs = AdInitGate.PASSIVE_AWAIT_TIMEOUT_MS,
            onUnavailable = {
                Timber.w("$TAG SKIP banner $placement (SDK not ready)")
                onLoadFail?.invoke()
            }
        ) {
            val budget = catalog.budgetSpecFor(placement, AdBudgets.BANNER).budgetFor(ids.size)
            runOnMain { loadTier(context, bannerView, placement, size, ids, 0, budget, onLoadSuccess, onLoadFail) }
        }
    }

    private fun loadTier(
        context: Context,
        bannerView: FrameLayout,
        placement: String,
        size: BannerSize,
        ids: List<String>,
        index: Int,
        budget: AdLoadBudget,
        onLoadSuccess: (() -> Unit)?,
        onLoadFail: (() -> Unit)?,
    ) {
        if (index >= ids.size) {
            Timber.w("$TAG WATERFALL banner $placement cạn tier sau ${ids.size} lần thử")
            AdsEvents.failedToLoad(placement, FORMAT, "no fill")
            runOnMain { onLoadFail?.invoke() }
            return
        }
        val tierBudget = budget.nextTierBudget(isLastTier = index == ids.lastIndex)
        if (tierBudget <= 0L) {
            Timber.w("$TAG WATERFALL banner $placement hết ngân sách ở tier ${index + 1}/${ids.size}")
            AdsEvents.failedToLoad(placement, FORMAT, "budget exhausted")
            runOnMain { onLoadFail?.invoke() }
            return
        }

        val id = ids[index]
        val adView = AdView(context)

        // Kích thước adaptive tự chọn theo bề ngang và xoay màn hình; container để wrap_content vì
        // chiều cao do Google trả về (anchored large cao hơn anchored thường, inline cao hơn nữa).
        val request = BannerAdRequest.Builder(id, size.toAdSize(context)).build()

        Timber.d("$TAG WATERFALL banner $placement tier=${index + 1}/${ids.size} id=$id cap=${tierBudget}ms")
        bannerView.addView(adView)

        var settled = false
        // Mỗi tier tạo một AdView mới. Tier hỏng PHẢI được gỡ khỏi container trước khi sang tier sau,
        // nếu không các AdView rỗng chồng lên nhau trong FrameLayout.
        fun discardTier() {
            bannerView.removeView(adView)
            runCatching { adView.destroy() }
        }

        val timeout = Runnable {
            if (settled) return@Runnable
            settled = true
            Timber.w("$TAG WATERFALL banner $placement tier=${index + 1} TIMEOUT")
            discardTier()
            loadTier(context, bannerView, placement, size, ids, index + 1, budget, onLoadSuccess, onLoadFail)
        }
        mainHandler.postDelayed(timeout, tierBudget)

        adView.loadAd(request, object : AdLoadCallback<BannerAd> {
            override fun onAdLoaded(ad: BannerAd) = runOnMain {
                if (settled) {
                    // Về sau khi tier đã bị bỏ qua: view đã detach nên không cache được, huỷ luôn.
                    // Banner không có cache dùng chung giữa các màn nên đây là ngoại lệ chấp nhận được
                    // so với native/inter/reward (đều giữ được ad về muộn).
                    Timber.w("$TAG WATERFALL banner $placement tier=${index + 1} LATE_FILL — bỏ")
                    runCatching { ad.destroy() }
                    return@runOnMain
                }
                settled = true
                mainHandler.removeCallbacks(timeout)
                adViewMap[id] = adView
                Timber.d("$TAG WATERFALL banner $placement tier=${index + 1} FILL")
                AdsEvents.loaded(placement, FORMAT)
                ad.adEventCallback = object : BannerAdEventCallback {
                    override fun onAdImpression() = AdsEvents.impression(placement, FORMAT)
                    override fun onAdClicked() = AdsEvents.clicked(placement, FORMAT)
                    override fun onAdPaid(value: AdValue) = AdsEvents.paid(placement, FORMAT, value)
                }
                onLoadSuccess?.invoke()
            }

            override fun onAdFailedToLoad(adError: LoadAdError) = runOnMain {
                if (settled) return@runOnMain
                settled = true
                mainHandler.removeCallbacks(timeout)
                Timber.e("$TAG WATERFALL banner $placement tier=${index + 1} NO_FILL err=${adError.message}")
                discardTier()
                loadTier(context, bannerView, placement, size, ids, index + 1, budget, onLoadSuccess, onLoadFail)
            }
        })
    }

    override fun reset() {
        // Destroy trước khi clear: chỉ clear() sẽ bỏ rơi AdView (giữ Context + surface) mà không ai
        // huỷ. Đáng kể khi Remote Config đổi id banner — entry cũ sẽ không bao giờ được dùng lại.
        adViewMap.values.forEach { adView ->
            (adView.parent as? ViewGroup)?.removeView(adView)
            runCatching { adView.destroy() }
        }
        adViewMap.clear()
    }

    companion object {
        const val TAG = "AdBannerProvider_TAG"
        private val FORMAT = AdFormat.BANNER
    }

}
