package com.freshness.ads.inter

import android.app.Activity
import android.app.Application
import android.os.SystemClock
import com.freshness.ads.config.AdBudgets
import com.freshness.ads.config.AdFormat
import com.freshness.ads.config.AdUnitCatalog
import com.freshness.ads.events.AdsEvents
import com.freshness.ads.manager.AdShowOutcome
import com.google.android.libraries.ads.mobile.sdk.common.AdValue
import com.freshness.ads.consent.AdInitGate
import com.freshness.ads.datastore.AdsDataStore
import com.freshness.ads.extensions.safeResume
import com.freshness.ads.loading.AdLoading
import com.freshness.ads.loading.awaitMinLoadingWindow
import com.google.android.libraries.ads.mobile.sdk.common.AdLoadCallback
import com.google.android.libraries.ads.mobile.sdk.common.AdRequest
import com.google.android.libraries.ads.mobile.sdk.common.FullScreenContentError
import com.google.android.libraries.ads.mobile.sdk.common.LoadAdError
import com.google.android.libraries.ads.mobile.sdk.interstitial.InterstitialAd
import com.google.android.libraries.ads.mobile.sdk.interstitial.InterstitialAdEventCallback
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import java.lang.ref.WeakReference

internal class InterAdProvider(
    private val dataStore: AdsDataStore,
    private val adLoading: AdLoading,
    private val catalog: AdUnitCatalog,
    /** Xem [com.freshness.ads.config.AdsConfig.splashInterstitialPlacement]: interstitial của splash
     *  có ngân sách thời gian riêng, nên phải nhận ra nó giữa các interstitial khác. */
    private val splashPlacement: String,
) : InterAdService {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    /**
     * Mọi thao tác trên map này CHỈ ở main thread. Callback của GMA next-gen tới trên luồng nền của
     * nó nên phải hop về main (scope.launch) trước khi chạm vào đây.
     */
    private val inters = mutableMapOf<InterAdConfig, InterAdRequest>()
    private val _isShowing = MutableStateFlow(false)
    override val isShowing = _isShowing.asStateFlow()

    /**
     * true từ lúc một lần showAd được chấp nhận cho tới khi nó kết thúc hẳn (đóng ad / lỗi / no fill).
     * Không có nó, hai showAd chồng nhau (double-tap trong khoảng min-loading) cùng gán
     * `ad.adEventCallback`: cái sau đè cái trước, lần gọi đầu không bao giờ được resume.
     */
    private var showInProgress = false

    override fun init(app: Application) {
        Timber.d("$TAG InterProvider initialized")
    }

    private suspend fun loadAdInternal(id: String, retry: Int): InterstitialAd? =
        suspendCancellableCoroutine { continuation ->
            if (!dataStore.isAdEnabled) {
                continuation.safeResume(null)
                return@suspendCancellableCoroutine
            }

            val adRequest = AdRequest.Builder(id).build()
            InterstitialAd.load(adRequest, object : AdLoadCallback<InterstitialAd> {
                override fun onAdLoaded(ad: InterstitialAd) {
                    Timber.d("$TAG  Ad loaded: $id")
                    continuation.safeResume(ad)
                }

                override fun onAdFailedToLoad(adError: LoadAdError) {
                    Timber.e("$TAG Failed to load ad: $id, error: $adError")
                    if (retry > 1) {
                        scope.launch {
                            delay(1_000L)
                            continuation.safeResume(loadAdInternal(id, retry - 1))
                        }
                    } else {
                        continuation.safeResume(null)
                    }
                }
            })
        }

    /**
     * Chạy waterfall: thử từng id trong [ids], id nào fill thì dừng.
     *
     * Khác các format còn lại, inter KHÔNG cắt tier ở `tierCapMs`: mỗi tier được chờ trọn phần ngân
     * sách còn lại, nên chỉ nhảy sang id sau khi AdMob thật sự trả no-fill. Cắt ở cap sẽ bỏ luôn
     * những request chỉ chậm hơn cap vài trăm ms, và fill về muộn khi đó chỉ còn kịp vào cache chứ
     * không cứu được lần show đang chờ. Tổng thời gian vẫn bị chặn bởi `budget` nên deadline của
     * placement không đổi.
     *
     * Hệ quả: hết tổng ngân sách thì các id còn lại không được thử. Request quá hạn vẫn chạy (AdMob
     * không cho huỷ) — nên load của tier chạy trong [scope] độc lập và chỉ có `await` bị huỷ. Ad về
     * muộn rơi vào cache qua [keepLateAd] để lần showAd sau dùng lại, thay vì thành một matched
     * request đổ đi.
     */
    private suspend fun loadWaterfall(config: InterAdConfig): InterstitialAd? {
        val ids = catalog.idsFor(config.placement)
        if (ids.isEmpty()) {
            Timber.w("$TAG SKIP ${config.placement} (không có id nào bật)")
            AdsEvents.failedToLoad(config.placement, FORMAT, "no ids")
            return null
        }
        val fallback = AdBudgets.forInter(config.timeOut, isSplash = config.placement == splashPlacement)
        val budget = catalog.budgetSpecFor(config.placement, fallback).budgetFor(ids.size)

        ids.forEachIndexed { index, id ->
            val tierBudget = budget.nextTierBudget(
                isLastTier = index == ids.lastIndex,
                waitFullRemaining = true,
            )
            if (tierBudget <= 0L) {
                Timber.w("$TAG WATERFALL ${config.placement} hết ngân sách ở tier ${index + 1}/${ids.size}")
                AdsEvents.failedToLoad(config.placement, FORMAT, "budget exhausted")
                return null
            }
            // Retry chỉ ở tier cuối — xem KDoc của InterAdConfig.retryCount.
            val retry = if (index == ids.lastIndex) config.retryCount else 0
            Timber.d("$TAG WATERFALL ${config.placement} tier=${index + 1}/${ids.size} id=$id cap=${tierBudget}ms")

            val tierJob = scope.async { loadAdInternal(id, retry) }
            val ad = withTimeoutOrNull(tierBudget) { tierJob.await() }
            if (ad != null) {
                Timber.d("$TAG WATERFALL ${config.placement} tier=${index + 1} FILL")
                AdsEvents.loaded(config.placement, FORMAT)
                return ad
            }
            if (tierJob.isActive) {
                // Timeout ở đây = hết TỔNG ngân sách của placement (tier không còn cap riêng).
                Timber.w("$TAG WATERFALL ${config.placement} tier=${index + 1} TIMEOUT (hết ngân sách) — vẫn hứng ad về muộn")
                keepLateAd(config, tierJob)
            } else {
                Timber.e("$TAG WATERFALL ${config.placement} tier=${index + 1} NO_FILL")
            }
        }
        AdsEvents.failedToLoad(config.placement, FORMAT, "no fill")
        return null
    }

    /** Ad đã nạp còn trong hạn dùng, hoặc null. Xem [AdBudgets.FULL_SCREEN_AD_TTL_MS]. */
    private fun InterAdRequest.liveAd(): InterstitialAd? {
        val ad = interstitialAd ?: return null
        if (SystemClock.elapsedRealtime() - loadedAt <= AdBudgets.FULL_SCREEN_AD_TTL_MS) return ad
        Timber.w("$TAG $placement ad đã hết hạn (${AdBudgets.FULL_SCREEN_AD_TTL_MS / 60_000} phút) — bỏ, nạp lại")
        return null
    }

    /** Tier quá hạn: request vẫn chạy nền, fill về muộn được nhét vào cache cho lần showAd sau. */
    // getCompleted chỉ hợp lệ bên trong invokeOnCompletion khi error == null, tức job đã xong và
    // không hủy — đúng điều kiện API này đòi.
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun keepLateAd(config: InterAdConfig, tierJob: Deferred<InterstitialAd?>) {
        tierJob.invokeOnCompletion { error ->
            if (error != null) return@invokeOnCompletion
            val late = tierJob.getCompleted() ?: return@invokeOnCompletion
            scope.launch {
                if (getRequest(config).liveAd() == null) {
                    Timber.w("$TAG LATE_FILL ${config.placement} → cache cho lần show sau")
                    inters[config] = getRequest(config).copy(interstitialAd = late, loadedAt = SystemClock.elapsedRealtime())
                }
            }
        }
    }

    override fun loadAd(config: InterAdConfig) {
        if (inters[config]?.liveAd() != null || !dataStore.isAdEnabled || inters[config]?.job?.isActive == true) {
            return
        }
        val job = scope.launch {
            // Wait for the SDK to be initialized before requesting; if it never
            // becomes ready, abort this load (showAd will then resolve to false).
            if (!AdInitGate.awaitReady()) {
                Timber.w("$TAG SKIP inter ${config.placement} (SDK not ready)")
                return@launch
            }
            val loadedAd = loadWaterfall(config)

            val result = getRequest(config)
            if (loadedAd != null) inters[config] = result.copy(
                interstitialAd = loadedAd,
                loadedAt = SystemClock.elapsedRealtime(),
            )
        }
        val request = getRequest(config)
        inters[config] = request.copy(job = job)
    }

    private fun getRequest(config: InterAdConfig): InterAdRequest {
        return inters[config] ?: config.asInterAdRequest()
    }

    override suspend fun showAdOutcome(activity: WeakReference<Activity>, config: InterAdConfig, onShow: () -> Unit): AdShowOutcome =
        suspendCancellableCoroutine { continuation ->
            if (!dataStore.isAdEnabled) {
                continuation.safeResume(AdShowOutcome.DISABLED)
                return@suspendCancellableCoroutine
            }
            if (isShowing.value || showInProgress) {
                Timber.w("$TAG showAd ${config.placement} bỏ qua (showing=${isShowing.value} inProgress=$showInProgress)")
                continuation.safeResume(AdShowOutcome.ALREADY_SHOWING)
                return@suspendCancellableCoroutine
            }
            showInProgress = true

            /** Kết thúc một lần show: gỡ cờ, tắt màn chờ, trả kết quả. Luôn chạy trên main. */
            fun settle(result: AdShowOutcome) {
                showInProgress = false
                adLoading.setLoading(false)
                continuation.safeResume(result)
            }

            scope.launch {
                val loadingStartedAt = SystemClock.elapsedRealtime()
                if (config.isShowLoading) {
                    adLoading.setLoading(true)
                }
                val ad = getRequest(config).liveAd() ?: withTimeoutOrNull(config.timeOut) {
                    if (getRequest(config).job?.isActive != true) {
                        loadAd(config)
                    }
                    getRequest(config).job?.join()
                    getRequest(config).liveAd()
                }

                if (ad == null) {
                    Timber.e("$TAG showAd ${config.placement}: không có ad sau ${config.timeOut}ms")
                    reloadAd(config)
                    settle(AdShowOutcome.NO_FILL)
                    return@launch
                }

                // GMA next-gen bắn AdEventCallback trên luồng nền của nó (đo được:
                // "onShow thread=GMA(BG) 2"). State của provider và onShow của app đều thuộc main,
                // nên mọi callback hop về scope (Main) trước khi làm gì.
                ad.adEventCallback = object : InterstitialAdEventCallback {
                    override fun onAdDismissedFullScreenContent() {
                        AdsEvents.closed(config.placement, FORMAT)
                        scope.launch {
                            _isShowing.value = false
                            reloadAd(config)
                            settle(AdShowOutcome.SHOWN)
                        }
                    }

                    override fun onAdFailedToShowFullScreenContent(
                        fullScreenContentError: FullScreenContentError
                    ) {
                        Timber.e("$TAG onAdFailedToShowFullScreenContent ${config.placement}: ${fullScreenContentError.message}")
                        AdsEvents.failedToShow(config.placement, FORMAT, fullScreenContentError.message)
                        scope.launch {
                            _isShowing.value = false
                            reloadAd(config)
                            settle(AdShowOutcome.SHOW_FAILED)
                        }
                    }

                    override fun onAdImpression() = AdsEvents.impression(config.placement, FORMAT)
                    override fun onAdClicked() = AdsEvents.clicked(config.placement, FORMAT)
                    override fun onAdPaid(value: AdValue) = AdsEvents.paid(config.placement, FORMAT, value)

                    override fun onAdShowedFullScreenContent() {
                        _isShowing.value = true
                        AdsEvents.showed(config.placement, FORMAT)
                        // Quảng cáo đã chiếm màn hình: việc của màn chờ kết thúc TẠI ĐÂY, không
                        // phải lúc ad đóng. Tắt muộn hơn thì cờ loading còn true suốt lúc xem
                        // quảng cáo, và spinner hiện lại ngay khi activity app resume lúc đóng ad.
                        adLoading.setLoading(false)
                        scope.launch { onShow.invoke() }
                    }
                }

                // Ad đã sẵn sàng. Giữ màn chờ cho đủ khoảng tối thiểu trước khi bung: ad preload sẵn
                // thì tới đây mới trôi vài mili giây kể từ cú chạm của người dùng.
                if (config.isShowLoading) {
                    awaitMinLoadingWindow(loadingStartedAt, config.minLoadingMs)
                }

                val host = activity.get()
                if (host == null) {
                    Timber.e("$TAG showAd ${config.placement}: activity = null")
                    settle(AdShowOutcome.NO_ACTIVITY)
                    return@launch
                }
                // show() ném = không có callback nào tới, phải tự gỡ cờ.
                runCatching { ad.show(host) }.onFailure { error ->
                    Timber.e("$TAG show() failed: ${error.message}")
                    AdsEvents.failedToShow(config.placement, FORMAT, error.message)
                    reloadAd(config)
                    settle(AdShowOutcome.SHOW_FAILED)
                }
            }
        }

    override fun reset() {
        inters.clear()
        showInProgress = false
        _isShowing.value = false
    }

    private fun reloadAd(config: InterAdConfig) {
        inters[config] = getRequest(config).copy(interstitialAd = null)
        Timber.d("$TAG reload ${config.placement}")
        if (!getRequest(config).reload || getRequest(config).job?.isActive == true) return
        loadAd(config)
    }

    companion object {
        private const val TAG = "InterAdProvider"
        private val FORMAT = AdFormat.INTERSTITIAL
    }
}

internal data class InterAdRequest(
    val placement: String,
    val reload: Boolean = true,
    val interstitialAd: InterstitialAd? = null,
    /** `elapsedRealtime` lúc [interstitialAd] được nạp, cho hạn dùng. */
    val loadedAt: Long = 0L,
    val job: Job? = null
)
