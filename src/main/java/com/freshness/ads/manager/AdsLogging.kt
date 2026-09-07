package com.freshness.ads.manager

import timber.log.Timber

/**
 * Log của SDK đi qua Timber. Cây log được plant khi app host debuggable, hoặc khi Remote Config bật
 * `settings.enableLogForTester` — để tester đọc được log trên bản release mà không cần build riêng.
 */
internal object AdsLogging {
    fun plantIfMissing(reason: String) {
        if (Timber.treeCount > 0) return
        Timber.plant(Timber.DebugTree())
        Timber.i("AdsLogging: bật log ($reason)")
    }
}
