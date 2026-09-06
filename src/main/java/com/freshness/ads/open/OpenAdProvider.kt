package com.freshness.ads.open

import android.app.Activity
import android.app.Application
import android.content.Context
import com.freshness.ads.config.AdBudgets
import com.freshness.ads.config.AdUnitCatalog
import com.freshness.ads.consent.AdInitGate
import com.freshness.ads.consent.GoogleMobileAdsConsentManager
import com.freshness.ads.datastore.AdsDataStore
import com.freshness.ads.extensions.safeResume
import com.google.android.libraries.ads.mobile.sdk.appopen.AppOpenAd
import com.google.android.libraries.ads.mobile.sdk.appopen.AppOpenAdEventCallback
import com.google.android.libraries.ads.mobile.sdk.common.AdLoadCallback
import com.google.android.libraries.ads.mobile.sdk.common.AdRequest
import com.google.android.libraries.ads.mobile.sdk.common.FullScreenContentError
import com.google.android.libraries.ads.mobile.sdk.common.LoadAdError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import java.lang.ref.WeakReference
import java.util.Date

class OpenAdProvider constructor(
    private val context: Context,
    private val dataStore: AdsDataStore,
    private val catalog: AdUnitCatalog,
    /** Xem [com.freshness.ads.config.AdsConfig.openAdPlacement]. App-open load ngầm theo vòng đời
     *  process nên không có call site nào truyền key vào — SDK phải biết sẵn. */
    private val placement: String,
) : OpenAdService {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private val googleMobileAdsConsentManager: GoogleMobileAdsConsentManager by lazy {
        GoogleMobileAdsConsentManager.getInstance(context)
    }

    override fun init(app: Application) {

    }

    override suspend fun launchOpenAdFlow(
        activity: WeakReference<Activity>,
        initialDelayMillis: Long,
        maxMillis: Long,
        onLoaded: (Boolean) -> Unit
    ) = suspendCancellableCoroutine { continuation ->
        val mustDelayJob = scope.launch {
            delay(initialDelayMillis)
        }
        scope.launch {
            if (!dataStore.isAdEnabled) {
                mustDelayJob.join()
                continuation.safeResume(false)
                return@launch
            }
            // Don't request the open ad before MobileAds.initialize has completed.
            if (!AdInitGate.awaitReady()) {
                Timber.w("$TAG SKIP open ad flow (SDK not ready)")
                onLoaded.invoke(false)
                mustDelayJob.join()
                continuation.safeResume(false)
                return@launch
            }
            kotlin.runCatching {
                withTimeout(maxMillis) {
                    loadAdWaterfall()
                }
            }.onSuccess {
                onLoaded.invoke(it)
                mustDelayJob.join()
                val result = showAdIfAvailable(activity)
                continuation.safeResume(result)
            }.onFailure { error ->
                onLoaded.invoke(false)
                continuation.safeResume(false)
                Timber.d("$TAG Failure: ${error.message}")
            }
        }
    }

    private var appOpenAd: AppOpenAd? = null
    private var isLoadingAd = false

    private val _isOpenAdShowing = MutableStateFlow(false)
    override val isOpenAdShowing = _isOpenAdShowing.asStateFlow()

    var isShowingAd: Boolean
        get() = _isOpenAdShowing.value
        set(value) {
            _isOpenAdShowing.value = value
        }

    private var loadTime: Long = 0

    override fun loadAd() {
        scope.launch {
            // Gate the request behind SDK init; awaitReady returns immediately
            // once init has completed (the common case for non-cold loads).
            if (!AdInitGate.awaitReady()) {
                Timber.w("$TAG SKIP open ad (SDK not ready)")
                return@launch
            }
            loadAdWaterfall()
        }
    }

    /**
     * Waterfall cho app-open: thử từng id, id nào fill thì dừng.
     *
     * Cờ [isLoadingAd] được bật MỘT lần trước vòng lặp và chỉ tắt khi cả waterfall kết thúc. Bật/tắt
     * theo từng tier sẽ khiến tier thứ hai bị chính guard `isLoadingAd` chặn lại và waterfall chết
     * ngay sau tier đầu.
     */
    private suspend fun loadAdWaterfall(): Boolean {
        if (!dataStore.isAdEnabled) return false
        if (isAdAvailable()) return true
        if (isLoadingAd) return false

        val ids = catalog.idsFor(placement)
        if (ids.isEmpty()) {
            Timber.w("$TAG SKIP open ad (không có id nào bật)")
            return false
        }
        val budget = catalog.budgetSpecFor(placement, AdBudgets.OPEN).budgetFor(ids.size)

        isLoadingAd = true
        try {
            ids.forEachIndexed { index, id ->
                val tierBudget = budget.nextTierBudget(isLastTier = index == ids.lastIndex)
                if (tierBudget <= 0L) {
                    Timber.w("$TAG WATERFALL open hết ngân sách ở tier ${index + 1}/${ids.size}")
                    return false
                }
                Timber.d("$TAG WATERFALL open tier=${index + 1}/${ids.size} id=$id cap=${tierBudget}ms")

                // Load chạy trong scope riêng nên khi hết cap chỉ có `await` bị huỷ; request vẫn chạy
                // và fill về muộn vẫn được gán vào [appOpenAd] để lần resume sau dùng.
                val tierJob = scope.async { loadSingle(id) }
                val loaded = withTimeoutOrNull(tierBudget) { tierJob.await() }
                when {
                    loaded == true -> {
                        Timber.d("$TAG WATERFALL open tier=${index + 1} FILL")
                        return true
                    }
                    loaded == null -> Timber.w("$TAG WATERFALL open tier=${index + 1} TIMEOUT — vẫn hứng ad về muộn")
                    else -> Timber.e("$TAG WATERFALL open tier=${index + 1} NO_FILL")
                }
            }
            return false
        } finally {
            isLoadingAd = false
        }
    }

    /** Một request đơn. Ad fill (kể cả về muộn) luôn được gán vào [appOpenAd]. */
    private suspend fun loadSingle(id: String): Boolean = suspendCancellableCoroutine {
        val request = AdRequest.Builder(id).build()
        AppOpenAd.load(
            request,
            object : AdLoadCallback<AppOpenAd> {
                override fun onAdLoaded(ad: AppOpenAd) {
                    appOpenAd = ad
                    loadTime = Date().time
                    Timber.d("$TAG onAdLoaded id=$id")
                    it.safeResume(true)
                }

                override fun onAdFailedToLoad(loadAdError: LoadAdError) {
                    Timber.d("OpenAdProvider onAdFailedToLoad: %s", loadAdError.message)
                    it.safeResume(false)
                }
            },
        )
    }

    private fun wasLoadTimeLessThanNHoursAgo(numHours: Long): Boolean {
        val dateDifference: Long = Date().time - loadTime
        val numMilliSecondsPerHour: Long = 3600000
        return dateDifference < numMilliSecondsPerHour * numHours
    }

    private fun isAdAvailable(): Boolean {
        return appOpenAd != null && wasLoadTimeLessThanNHoursAgo(4)
    }

    override suspend fun showAdIfAvailable(activity: WeakReference<Activity>) =
        suspendCancellableCoroutine {
            if (!dataStore.isAdEnabled) {
                it.safeResume(false)
                return@suspendCancellableCoroutine
            }
            if (isShowingAd) {
                Timber.d("$TAG The app open ad is already showing.")
                it.safeResume(false)
                return@suspendCancellableCoroutine
            }

            if (!isAdAvailable()) {
                Timber.d("$TAG The app open ad is not ready yet.")
                if (googleMobileAdsConsentManager.canRequestAds) {
                    loadAd()
                }
                it.safeResume(false)
                return@suspendCancellableCoroutine
            }

            // Chốt activity + ad TRƯỚC khi bật isShowingAd. Bật cờ rồi mới phát hiện không có chỗ
            // show thì cờ kẹt true vĩnh viễn: mọi lần gọi sau đều rơi vào nhánh "already showing" ở
            // trên, app-open chết hẳn dù vẫn tiếp tục được request.
            val host = activity.get()
            val ad = appOpenAd
            if (host == null || ad == null) {
                Timber.w("$TAG No host activity/ad to show on.")
                it.safeResume(false)
                return@suspendCancellableCoroutine
            }

            Timber.d("$TAG Will show ad.")

            ad.adEventCallback =
                object : AppOpenAdEventCallback {
                    override fun onAdDismissedFullScreenContent() {
                        // Set the reference to null so isAdAvailable() returns false.
                        appOpenAd = null
                        isShowingAd = false
                        Timber.d("$TAG onAdDismissedFullScreenContent. ${googleMobileAdsConsentManager.canRequestAds}")
                        if (googleMobileAdsConsentManager.canRequestAds) {
                            Timber.d("$TAG onAdDismissedFullScreenContent. loadAd")
                            loadAd()
                        }
                        it.safeResume(true)
                    }

                    override fun onAdFailedToShowFullScreenContent(adError: FullScreenContentError) {
                        appOpenAd = null
                        isShowingAd = false
                        Timber.d(
                            "OpenAdProvider onAdFailedToShowFullScreenContent: %s",
                            adError.message
                        )
                        if (googleMobileAdsConsentManager.canRequestAds) {
                            loadAd()
                        }
                        it.safeResume(false)
                    }

                    override fun onAdShowedFullScreenContent() {
                        Timber.d("$TAG onAdShowedFullScreenContent.")
                    }
                }
            isShowingAd = true
            // show() ném = không có callback nào tới, nên phải tự gỡ cờ ở đây.
            runCatching { ad.show(host) }.onFailure { error ->
                Timber.e("$TAG show() failed: ${error.message}")
                appOpenAd = null
                isShowingAd = false
                it.safeResume(false)
            }
        }

    override fun reset() {
        appOpenAd = null
    }

    companion object {
        const val TAG = "OpenAdProvider"
    }
}