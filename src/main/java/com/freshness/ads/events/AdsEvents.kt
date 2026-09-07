package com.freshness.ads.events

import android.os.Handler
import android.os.Looper
import com.freshness.ads.config.AdFormat
import com.google.android.libraries.ads.mobile.sdk.common.AdValue
import com.google.android.libraries.ads.mobile.sdk.common.PrecisionType
import timber.log.Timber
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Bus nội bộ: provider bắn sự kiện vào đây, [AdsListener] của app nhận trên main thread.
 *
 * Object chứ không phải thành viên của graph vì [com.freshness.ads.natives.NativeAdmobManager] là
 * object không có DI, và một sự kiện quảng cáo là chuyện của cả process.
 */
internal object AdsEvents {

    private val listeners = CopyOnWriteArrayList<AdsListener>()
    private val mainHandler = Handler(Looper.getMainLooper())

    fun add(listener: AdsListener) {
        if (!listeners.contains(listener)) listeners.add(listener)
    }

    fun remove(listener: AdsListener) {
        listeners.remove(listener)
    }

    /**
     * Callback của GMA tới trên luồng nền của nó; listener của app chạm analytics/UI nên đưa về main.
     * Một listener ném exception không được làm gãy provider hay các listener còn lại.
     */
    fun dispatch(block: AdsListener.() -> Unit) {
        if (listeners.isEmpty()) return
        val run = Runnable {
            listeners.forEach { l ->
                runCatching { l.block() }.onFailure { Timber.w(it, "AdsListener ném exception") }
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) run.run() else mainHandler.post(run)
    }

    fun loaded(placement: String, format: AdFormat) = dispatch { onAdLoaded(placement, format) }
    fun failedToLoad(placement: String, format: AdFormat, reason: String?) =
        dispatch { onAdFailedToLoad(placement, format, reason) }
    fun showed(placement: String, format: AdFormat) = dispatch { onAdShowed(placement, format) }
    fun impression(placement: String, format: AdFormat) = dispatch { onAdImpression(placement, format) }
    fun clicked(placement: String, format: AdFormat) = dispatch { onAdClicked(placement, format) }
    fun closed(placement: String, format: AdFormat) = dispatch { onAdClosed(placement, format) }
    fun failedToShow(placement: String, format: AdFormat, reason: String?) =
        dispatch { onAdFailedToShow(placement, format, reason) }
    fun rewarded(placement: String, amount: Int, type: String) = dispatch { onAdRewarded(placement, amount, type) }
    fun paid(placement: String, format: AdFormat, value: AdValue) =
        dispatch { onAdPaid(placement, format, value.toRevenue()) }

    private fun AdValue.toRevenue() = AdRevenue(
        valueMicros = valueMicros,
        currencyCode = currencyCode,
        precision = when (precisionType) {
            PrecisionType.ESTIMATED -> AdRevenue.Precision.ESTIMATED
            PrecisionType.PUBLISHER_PROVIDED -> AdRevenue.Precision.PUBLISHER_PROVIDED
            PrecisionType.PRECISE -> AdRevenue.Precision.PRECISE
            else -> AdRevenue.Precision.UNKNOWN
        },
    )
}
