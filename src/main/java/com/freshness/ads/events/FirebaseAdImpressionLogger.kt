package com.freshness.ads.events

import android.content.Context
import android.os.Bundle
import com.freshness.ads.config.AdFormat
import com.google.firebase.analytics.FirebaseAnalytics
import timber.log.Timber

/**
 * Gửi sự kiện chuẩn `ad_impression` của Firebase cho mỗi impression có giá — đúng schema Google
 * dùng để tính ad revenue trong Analytics / GA4 và để Adjust/AppsFlyer đọc lại qua Firebase.
 *
 * `firebase-analytics` là `compileOnly`: app không có nó thì NoClassDefFoundError rơi vào
 * `runCatching` và logger im lặng — không bắt app phải thêm dependency chỉ vì SDK quảng cáo.
 */
internal class FirebaseAdImpressionLogger(private val context: Context) : AdsListener {

    @Volatile
    private var unavailable = false

    override fun onAdPaid(placement: String, format: AdFormat, revenue: AdRevenue) {
        if (unavailable) return
        runCatching {
            val params = Bundle().apply {
                putString(FirebaseAnalytics.Param.AD_PLATFORM, "admob")
                putString(FirebaseAnalytics.Param.AD_SOURCE, "admob")
                putString(FirebaseAnalytics.Param.AD_FORMAT, format.tag)
                putString(FirebaseAnalytics.Param.AD_UNIT_NAME, placement)
                putDouble(FirebaseAnalytics.Param.VALUE, revenue.value)
                putString(FirebaseAnalytics.Param.CURRENCY, revenue.currencyCode)
            }
            FirebaseAnalytics.getInstance(context).logEvent(FirebaseAnalytics.Event.AD_IMPRESSION, params)
        }.onFailure {
            // Thiếu firebase-analytics hoặc Firebase chưa init: tắt hẳn, không thử lại mỗi impression.
            unavailable = true
            Timber.d("FirebaseAdImpressionLogger tắt: ${it.javaClass.simpleName} ${it.message}")
        }
    }
}
