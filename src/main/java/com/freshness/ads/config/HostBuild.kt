package com.freshness.ads.config

import android.content.Context
import android.content.pm.ApplicationInfo

/**
 * App host có đang chạy bản debuggable không.
 *
 * Đây là nguồn DUY NHẤT SDK dùng để quyết định "đang debug": test id, debug geography của UMP,
 * log, khoảng fetch Remote Config. KHÔNG dùng `BuildConfig.DEBUG` của thư viện cho việc này — AAR
 * publish là bản release nên cờ đó luôn false trong app host, kể cả khi app đang chạy debug.
 */
internal fun Context.isHostDebuggable(): Boolean =
    (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
