package com.freshness.ads.reward

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
import com.google.android.libraries.ads.mobile.sdk.rewarded.OnUserEarnedRewardListener
import com.google.android.libraries.ads.mobile.sdk.rewarded.RewardItem
import com.google.android.libraries.ads.mobile.sdk.rewarded.RewardedAd
import com.google.android.libraries.ads.mobile.sdk.rewarded.RewardedAdEventCallback
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import java.lang.ref.WeakReference

class RewardAdProvider constructor(
    private val context: Context,
    private val dataStore: AdsDataStore,
    private val adLoading: AdLoading,
    private val catalog: AdUnitCatalog
) : RewardAdService {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val Rewards = mutableMapOf<RewardAdConfig, RewardAdRequest>()
    private val _isShowing = MutableStateFlow(false)
    override val isShowing = _isShowing.asStateFlow()

    // True from the moment a showAd call is accepted until it fully resolves
    // (ad dismissed / failed / no fill). Guards against a second tap starting a
    // duplicate load/show during the (now longer) load wait. Only ever touched
    // on the Main dispatcher, so a plain flag is enough.
    private var showInProgress = false

    override fun init(app: Application) {
        Timber.d("$TAG RewardProvider initialized")
    }

    /**
     * Deadline thật của một lần reward. Đọc lại từ Remote Config mỗi lần dùng để chỉnh trên Firebase
     * là có hiệu lực ngay lần show kế tiếp, không cần release — giống cách splash đọc
     * `splashTimeoutMs`. Giá trị `<= 0` hoặc thiếu thì rơi về [RewardAdConfig.timeOut] của màn gọi.
     *
     * Dùng CHUNG cho cả ngân sách waterfall lẫn thời gian `showAd` chờ, hai chỗ lệch nhau nghĩa là
     * waterfall bỏ cuộc trong khi user vẫn đang nhìn spinner, hoặc ngược lại.
     */
    private val RewardAdConfig.effectiveTimeOut: Long
        get() = catalog.settingsSnapshot().rewardTimeoutMs?.takeIf { it > 0L } ?: timeOut

    private suspend fun loadAdRewardnal(id: String, retry: Int): RewardedAd? =
        suspendCancellableCoroutine { continuation ->
            if (!dataStore.isAdEnabled) {
                continuation.safeResume(null)
                return@suspendCancellableCoroutine
            }

            val adRequest = AdRequest.Builder(id).build()
            RewardedAd.load(adRequest, object : AdLoadCallback<RewardedAd> {
                override fun onAdLoaded(ad: RewardedAd) {
                    Timber.d("$TAG  Ad loaded: $id")
                    continuation.safeResume(ad)
                }

                override fun onAdFailedToLoad(adError: LoadAdError) {
                    Timber.e("$TAG Failed to load ad: $id, error: $adError")
                    if (retry > 1) {
                        scope.launch {
                            delay(1_000L)
                            continuation.safeResume(loadAdRewardnal(id, retry - 1))
                        }
                    } else {
                        continuation.safeResume(null)
                    }
                }
            })
        }

    /**
     * Waterfall: thử từng id, id nào fill thì dừng.
     *
     * Giống inter, reward KHÔNG cắt tier ở `tierCapMs`: mỗi tier được chờ trọn phần ngân sách còn
     * lại nên chỉ nhảy sang id sau khi AdMob thật sự trả no-fill. Cắt ở cap sẽ bỏ luôn những request
     * chỉ chậm hơn cap vài trăm ms, mà reward là format user đứng sau spinner không huỷ được — hụt
     * một fill ở đây đắt hơn hẳn việc chờ thêm. Tổng thời gian vẫn bị `budget` chặn.
     *
     * Hệ quả: hết tổng ngân sách thì các id còn lại không được thử. Request quá hạn vẫn chạy nền
     * (AdMob không cho huỷ) và fill về muộn được [keepLateAd] nhét vào cache cho lần showAd sau —
     * reward là format user chủ động bấm nên fill để dành gần như chắc chắn dùng được.
     */
    private suspend fun loadWaterfall(config: RewardAdConfig): RewardedAd? {
        val ids = catalog.idsFor(config.placement)
        if (ids.isEmpty()) {
            Timber.w("$TAG SKIP ${config.placement} (không có id nào bật)")
            return null
        }
        val budget = catalog.budgetSpecFor(config.placement, AdBudgets.forReward(config.effectiveTimeOut)).budgetFor(ids.size)

        ids.forEachIndexed { index, id ->
            val tierBudget = budget.nextTierBudget(
                isLastTier = index == ids.lastIndex,
                waitFullRemaining = true,
            )
            if (tierBudget <= 0L) {
                Timber.w("$TAG WATERFALL ${config.placement} hết ngân sách ở tier ${index + 1}/${ids.size}")
                return null
            }
            val retry = if (index == ids.lastIndex) config.retryCount else 0
            Timber.d("$TAG WATERFALL ${config.placement} tier=${index + 1}/${ids.size} id=$id cap=${tierBudget}ms")

            val tierJob = scope.async { loadAdRewardnal(id, retry) }
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

    // getCompleted chỉ hợp lệ bên trong invokeOnCompletion khi error == null, tức job đã xong và
    // không hủy — đúng điều kiện API này đòi.
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun keepLateAd(config: RewardAdConfig, tierJob: Deferred<RewardedAd?>) {
        tierJob.invokeOnCompletion { error ->
            if (error != null) return@invokeOnCompletion
            val late = tierJob.getCompleted() ?: return@invokeOnCompletion
            scope.launch {
                if (getRequest(config).RewardedAd == null) {
                    Timber.w("$TAG LATE_FILL ${config.placement} → cache cho lần show sau")
                    Rewards[config] = getRequest(config).copy(RewardedAd = late)
                }
            }
        }
    }

    override fun loadAd(config: RewardAdConfig) {
        if (Rewards[config]?.RewardedAd != null || !dataStore.isAdEnabled || Rewards[config]?.job?.isActive == true) {
            return
        }
        val job = scope.launch {
            // Wait for the SDK to be initialized before requesting; if it never
            // becomes ready, abort this load (showAd will then resolve to false).
            if (!AdInitGate.awaitReady()) {
                Timber.w("$TAG SKIP reward ${config.placement} (SDK not ready)")
                return@launch
            }
            val loadedAd = loadWaterfall(config)

            val result = getRequest(config)
            if (loadedAd != null) Rewards[config] = result.copy(
                RewardedAd = loadedAd,
            )
        }
        val request = getRequest(config)
        Rewards[config] = request.copy(job = job)
    }

    private fun getRequest(config: RewardAdConfig): RewardAdRequest {
        return Rewards[config] ?: config.asRewardAdRequest()
    }

    override suspend fun showAd(
        activity: WeakReference<Activity>,
        config: RewardAdConfig,
        onShow: () -> Unit,
        onReward: (RewardItem) -> Unit
    ): Boolean =
        suspendCancellableCoroutine { continuation ->
            if (!dataStore.isAdEnabled || isShowing.value || showInProgress) {
                continuation.safeResume(false)
                Timber.e("Hito::showAdReward cannot")
                return@suspendCancellableCoroutine
            }
            showInProgress = true

            scope.launch {
                val loadingStartedAt = SystemClock.elapsedRealtime()
                if (config.isShowLoading) {
                    adLoading.setLoading(true)
                }
                // No ad cached yet: kick off (or wait for) the in-flight load and
                // keep the loading dialog up until it resolves. We re-read the
                // cached ad AFTER the timeout window on purpose — even if join()
                // is cancelled at the cap, the still-running load job may have
                // just cached a fill, and a loaded ad must always be shown.
                val ad = getRequest(config).RewardedAd ?: run {
                    withTimeoutOrNull(config.effectiveTimeOut) {
                        if (getRequest(config).job?.isActive != true) {
                            Timber.i("Hito::showAd isActive = false")
                            loadAd(config)
                        } else {
                            Timber.i("Hito::showAd isActive = true")
                        }
                        getRequest(config).job?.join()
                    }
                    getRequest(config).RewardedAd
                }

                Timber.i("Hito::AdReward = $ad")
                if (ad == null) {
                    // Do NOT clear here: a still-running load job may cache this
                    // fill momentarily; leaving it lets the next showAd reuse it
                    // instead of wasting the matched request.
                    Timber.e("Hito::AdReward = null")
                    showInProgress = false
                    adLoading.setLoading(false)
                    continuation.safeResume(false)
                    return@launch
                }

                ad.adEventCallback = object : RewardedAdEventCallback {
                    override fun onAdDismissedFullScreenContent() {
                        _isShowing.value = false
                        showInProgress = false
                        clearConsumedAd(config)
                        adLoading.setLoading(false)
                        continuation.safeResume(true)
                    }

                    override fun onAdFailedToShowFullScreenContent(
                        fullScreenContentError: FullScreenContentError
                    ) {
                        Timber.e("Hito::onAdFailedToShowFullScreenContent")
                        _isShowing.value = false
                        showInProgress = false
                        clearConsumedAd(config)
                        adLoading.setLoading(false)
                        continuation.safeResume(false)
                    }

                    override fun onAdShowedFullScreenContent() {
                        _isShowing.value = true
                        // Cùng lý do với onReward: đo được callback này chạy trên GMA(BG), mà
                        // onShow là chỗ app ẩn loading của mình / dừng nhạc nền — toàn việc chạm UI.
                        scope.launch { onShow.invoke() }
                    }
                }

                // Ad đã sẵn sàng. Giữ màn chờ cho đủ khoảng tối thiểu trước khi bung: ad preload sẵn
                // thì tới đây mới trôi vài mili giây kể từ cú chạm của người dùng.
                if (config.isShowLoading) {
                    awaitMinLoadingWindow(loadingStartedAt, config.minLoadingMs)
                }

                activity.get()?.let {
                    ad.show(
                        it
                    ) { rewardItem ->
                        Timber.d("User earned the reward: ${rewardItem.amount} ${rewardItem.type}")
                        // GMA next-gen bắn callback này trên luồng nền của nó (thấy rõ trong crash:
                        // "FATAL EXCEPTION: GMA(BG) 5"). Mà onReward chính là chỗ app cộng thưởng —
                        // gần như luôn chạm UI hoặc storage. Đưa về main trước khi giao cho app,
                        // đừng bắt mọi call site tự nhớ.
                        scope.launch { onReward.invoke(rewardItem) }
                    }
                } ?: run {
                    Timber.e("Hito::showAd activity = null")
                    showInProgress = false
                    adLoading.setLoading(false)
                    continuation.safeResume(false)
                }
            }
        }

    override fun reset() {
        Rewards.clear()
        showInProgress = false
        _isShowing.value = false
    }

    /**
     * Drops only the just-shown / failed ad instance. We intentionally do NOT
     * eagerly preload a replacement here: [showAd] already lazily loads on the
     * next request, so a speculative reload after every dismiss would just
     * produce a matched request with no impression for watch-once users — the
     * main cause of the sub-50% reward show rate. The load wait in [showAd]
     * (see [RewardAdConfig.timeOut]) absorbs the first-show latency instead.
     */
    private fun clearConsumedAd(config: RewardAdConfig) {
        Rewards[config] = getRequest(config).copy(RewardedAd = null)
        Timber.d("Reward cleared ${Rewards[config]}")
    }

    companion object {
        private const val RETRY_COUNT = 2
        private const val TAG = "RewardAdProvider"
    }
}

data class RewardAdRequest(
    val placement: String,
    val RewardedAd: RewardedAd? = null,
    val job: Job? = null
)