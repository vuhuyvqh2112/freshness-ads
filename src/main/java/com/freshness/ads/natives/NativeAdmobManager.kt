package com.freshness.ads.natives

import android.os.Handler
import android.os.Looper
import com.freshness.ads.config.AdLoadBudget
import com.freshness.ads.consent.AdInitGate
import com.google.android.libraries.ads.mobile.sdk.common.LoadAdError
import com.google.android.libraries.ads.mobile.sdk.nativead.NativeAd
import com.google.android.libraries.ads.mobile.sdk.nativead.NativeAdLoader
import com.google.android.libraries.ads.mobile.sdk.nativead.NativeAdLoaderCallback
import com.google.android.libraries.ads.mobile.sdk.nativead.NativeAdRequest
import timber.log.Timber


object NativeAdmobManager {
    private const val TAG = "NativeAdmobManager"

    private val mainHandler = Handler(Looper.getMainLooper())

    private fun buildRequest(nativeId: String): NativeAdRequest =
        NativeAdRequest.Builder(nativeId, listOf(NativeAd.NativeAdType.NATIVE)).build()

    /**
     * Load native theo waterfall: thử [ids] lần lượt, id nào fill thì dừng.
     *
     * Ba thứ phải đúng cùng lúc, nên gom vào một chỗ thay vì để mỗi provider tự viết:
     *
     * 1. **Chặn SDK-not-ready MỘT lần trước vòng lặp.** Nếu để mỗi tier tự kiểm thì khi SDK chưa init,
     *    cả list sẽ trượt liên tiếp trong vài mili-giây và waterfall coi như đã cạn dù chưa hề phát
     *    request nào.
     * 2. **Cap thời gian mỗi tier.** Một tier treo không được nuốt phần của các tier sau.
     * 3. **Hứng ad về muộn.** AdMob không có API huỷ request: hết cap chỉ là "thôi chờ", request vẫn
     *    chạy. Không hứng thì mỗi lần quá hạn là một matched request đổ đi — đúng thứ đang cần tránh.
     *    Ad về muộn đi vào [onLateAd] để cache dùng lần sau, KHÔNG hiển thị đè lên UI hiện tại.
     *
     * [onLoadSuccess] / [onLoadFail] được gọi đúng MỘT lần.
     */
    fun loadWaterfall(
        isEnableAd: Boolean,
        placement: String,
        ids: List<String>,
        budget: AdLoadBudget,
        onLoadSuccess: (NativeAd) -> Unit,
        onLoadFail: () -> Unit,
        onLateAd: ((NativeAd) -> Unit)? = null,
    ) {
        if (!isEnableAd || ids.isEmpty()) {
            Timber.d("$TAG WATERFALL skip placement=$placement (enable=$isEnableAd tiers=${ids.size})")
            onLoadFail()
            return
        }

        AdInitGate.whenReady(
            // Native cũng bị động như banner: hết giờ là slot nằm shimmer tới hết phiên vì không có
            // gì kích hoạt lại lệnh load.
            timeoutMs = AdInitGate.PASSIVE_AWAIT_TIMEOUT_MS,
            onUnavailable = {
                Timber.w("$TAG WATERFALL skip placement=$placement (SDK not ready)")
                onLoadFail()
            }
        ) {
            WaterfallRun(placement, ids, budget, onLoadSuccess, onLoadFail, onLateAd).start()
        }
    }

    /**
     * Một lượt chạy waterfall. Tách thành class để mỗi tier có cờ "đã chốt" riêng — callback của SDK
     * và watchdog hết-cap đều có thể tới, chỉ cái nào tới trước được quyền đẩy waterfall đi tiếp.
     */
    private class WaterfallRun(
        private val placement: String,
        private val ids: List<String>,
        private val budget: AdLoadBudget,
        private val onLoadSuccess: (NativeAd) -> Unit,
        private val onLoadFail: () -> Unit,
        private val onLateAd: ((NativeAd) -> Unit)?,
    ) {
        /** true sau khi waterfall đã kết thúc (thành công hoặc cạn tier). Mọi ad tới sau là ad muộn. */
        private var finished = false

        fun start() = runTier(0)

        private fun runTier(index: Int) {
            if (finished) return
            if (index >= ids.size) {
                Timber.w("$TAG WATERFALL placement=$placement cạn tier sau ${ids.size} lần thử")
                finish(null)
                return
            }

            val tierBudget = budget.nextTierBudget(isLastTier = index == ids.lastIndex)
            if (tierBudget <= 0L) {
                // Không đủ thời gian để chờ tier này -> phát request bây giờ chỉ tạo thêm
                // matched-but-not-shown. Dừng hẳn.
                Timber.w("$TAG WATERFALL placement=$placement hết ngân sách ở tier ${index + 1}/${ids.size}")
                finish(null)
                return
            }

            val id = ids[index]
            val startedAt = android.os.SystemClock.elapsedRealtime()
            var tierSettled = false
            Timber.d("$TAG WATERFALL placement=$placement tier=${index + 1}/${ids.size} id=$id cap=${tierBudget}ms")

            val timeout = Runnable {
                if (tierSettled || finished) return@Runnable
                tierSettled = true
                Timber.w("$TAG WATERFALL placement=$placement tier=${index + 1} TIMEOUT sau ${tierBudget}ms")
                runTier(index + 1)
            }
            mainHandler.postDelayed(timeout, tierBudget)

            NativeAdLoader.load(
                buildRequest(id),
                object : NativeAdLoaderCallback {
                    override fun onNativeAdLoaded(nativeAd: NativeAd) {
                        val latency = android.os.SystemClock.elapsedRealtime() - startedAt
                        if (tierSettled || finished) {
                            // LATE_FILL: về sau khi tier đã bị bỏ qua. Giữ lại cho lần sau thay vì vứt.
                            Timber.w("$TAG WATERFALL placement=$placement tier=${index + 1} LATE_FILL latency=${latency}ms")
                            handOffLate(nativeAd)
                            return
                        }
                        tierSettled = true
                        mainHandler.removeCallbacks(timeout)
                        Timber.d("$TAG WATERFALL placement=$placement tier=${index + 1} FILL latency=${latency}ms")
                        finish(nativeAd)
                    }

                    override fun onAdFailedToLoad(adError: LoadAdError) {
                        val latency = android.os.SystemClock.elapsedRealtime() - startedAt
                        if (tierSettled || finished) return
                        tierSettled = true
                        mainHandler.removeCallbacks(timeout)
                        Timber.e("$TAG WATERFALL placement=$placement tier=${index + 1} NO_FILL latency=${latency}ms err=${adError.message}")
                        runTier(index + 1)
                    }
                }
            )
        }

        private fun finish(ad: NativeAd?) {
            if (finished) {
                ad?.let { handOffLate(it) }
                return
            }
            finished = true
            if (ad != null) onLoadSuccess(ad) else onLoadFail()
        }

        /** Ad về muộn: đưa cho chủ sở hữu cache, không có ai nhận thì huỷ để khỏi rò bộ nhớ. */
        private fun handOffLate(ad: NativeAd) {
            val sink = onLateAd
            if (sink != null) sink(ad) else runCatching { ad.destroy() }
        }
    }
}
