package com.freshness.ads.inter

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.SystemClock
import com.freshness.ads.config.AdBudgets
import com.freshness.ads.config.AdUnitCatalog
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
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import java.lang.ref.WeakReference

class InterAdProvider constructor(
    private val context: Context,
    private val dataStore: AdsDataStore,
    private val adLoading: AdLoading,
    private val catalog: AdUnitCatalog,
    /** Xem [com.freshness.ads.config.AdsConfig.splashInterstitialPlacement]: interstitial của splash
     *  có ngân sách thời gian riêng, nên phải nhận ra nó giữa các interstitial khác. */
    private val splashPlacement: String,
) : InterAdService {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val inters = mutableMapOf<InterAdConfig, InterAdRequest>()
    private val _isShowing = MutableStateFlow(false)
    override val isShowing = _isShowing.asStateFlow()

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
                return null
            }
            // Retry chỉ ở tier cuối — xem KDoc của InterAdConfig.retryCount.
            val retry = if (index == ids.lastIndex) config.retryCount else 0
            Timber.d("$TAG WATERFALL ${config.placement} tier=${index + 1}/${ids.size} id=$id cap=${tierBudget}ms")

            val tierJob = scope.async { loadAdInternal(id, retry) }
            val ad = withTimeoutOrNull(tierBudget) { tierJob.await() }
            if (ad != null) {
                Timber.d("$TAG WATERFALL ${config.placement} tier=${index + 1} FILL")
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
                if (getRequest(config).interstitialAd == null) {
                    Timber.w("$TAG LATE_FILL ${config.placement} → cache cho lần show sau")
                    inters[config] = getRequest(config).copy(interstitialAd = late)
                }
            }
        }
    }

    override fun loadAd(config: InterAdConfig) {
        if (inters[config]?.interstitialAd != null || !dataStore.isAdEnabled || inters[config]?.job?.isActive == true) {
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
            )
        }
        val request = getRequest(config)
        inters[config] = request.copy(job = job)
    }

    private fun getRequest(config: InterAdConfig): InterAdRequest {
        return inters[config] ?: config.asInterAdRequest()
    }

    override suspend fun showAd(activity: WeakReference<Activity>, config: InterAdConfig, onShow: () -> Unit): Boolean =
        suspendCancellableCoroutine { continuation ->
            if (!dataStore.isAdEnabled || isShowing.value) {
                continuation.safeResume(false)
                Timber.e("Hito::showAdInter cannot")
                return@suspendCancellableCoroutine
            }

            scope.launch {
                val loadingStartedAt = SystemClock.elapsedRealtime()
                if (config.isShowLoading) {
                    adLoading.setLoading(true)
                }
                val ad = getRequest(config).interstitialAd ?: withTimeoutOrNull(config.timeOut) {
                    if (getRequest(config).job?.isActive != true) {
                        Timber.i("Hito::showAd isActive = false")
                        loadAd(config)
                    } else {
                        Timber.i("Hito::showAd isActive = true")
                    }
                    getRequest(config).job?.join()
                    getRequest(config).interstitialAd
                }

                Timber.i("Hito::AdInter = $ad")
                if (ad == null) {
                    Timber.e("Hito::AdInter = null")
                    reloadAd(config)
                    adLoading.setLoading(false)
                    continuation.safeResume(false)
                    return@launch
                }

                ad.adEventCallback = object : InterstitialAdEventCallback {
                    override fun onAdDismissedFullScreenContent() {
                        _isShowing.value = false
                        reloadAd(config)
                        adLoading.setLoading(false)
                        continuation.safeResume(true)
                    }

                    override fun onAdFailedToShowFullScreenContent(
                        fullScreenContentError: FullScreenContentError
                    ) {
                        Timber.e("Hito::onAdFailedToShowFullScreenContent")
                        _isShowing.value = false
                        reloadAd(config)
                        adLoading.setLoading(false)
                        continuation.safeResume(false)
                    }

                    override fun onAdShowedFullScreenContent() {
                        _isShowing.value = true
                        // GMA next-gen bắn AdEventCallback trên luồng nền của nó (đo được:
                        // "onShow thread=GMA(BG) 2"). onShow là chỗ app chạm UI, đưa về main.
                        scope.launch { onShow.invoke() }
                    }
                }

                // Ad đã sẵn sàng. Giữ màn chờ cho đủ khoảng tối thiểu trước khi bung: ad preload sẵn
                // thì tới đây mới trôi vài mili giây kể từ cú chạm của người dùng.
                if (config.isShowLoading) {
                    awaitMinLoadingWindow(loadingStartedAt, config.minLoadingMs)
                }

                activity.get()?.let {
                    ad.show(it)
                } ?: kotlin.run {
                    Timber.e("Hito::showAd activity = null")
                    adLoading.setLoading(false)
                    continuation.safeResume(false)
                }
            }
        }

    override fun reset() {
        inters.clear()
    }

    private fun reloadAd(config: InterAdConfig) {
        inters[config] = getRequest(config).copy(interstitialAd = null)
        Timber.d("ReloadAd ${inters[config]}")
        if (!getRequest(config).reload || getRequest(config).job?.isActive == true) return
        loadAd(config)
    }

    companion object {
        private const val RETRY_COUNT = 2
        private const val TAG = "InterAdProvider"
    }
}

data class InterAdRequest(
    val placement: String,
    val reload: Boolean = true,
    val interstitialAd: InterstitialAd? = null,
    val job: Job? = null
)