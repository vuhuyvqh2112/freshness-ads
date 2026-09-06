package com.freshness.ads.loading

import android.os.SystemClock
import kotlinx.coroutines.delay

/**
 * Mặc định cho `minLoadingMs` của interstitial và rewarded: đủ để người dùng ngừng chạm trước khi
 * quảng cáo chiếm màn hình, chưa đủ lâu để thành phiền. Google khuyến nghị có khoảng chờ nhưng
 * không nêu con số, nên đây là lựa chọn của SDK chứ không phải yêu cầu từ chính sách.
 */
const val DEFAULT_MIN_LOADING_MS = 500L

/**
 * Giữ màn chờ hiện đủ [minMs] tính từ [startedAtMs] trước khi cho quảng cáo toàn màn bung ra.
 *
 * Vì sao cần: khi quảng cáo đã preload sẵn, `showAd` bật màn chờ rồi bung ad ngay trong cùng một
 * nhịp — người dùng không kịp thấy gì, và cú chạm vừa rồi rơi thẳng vào quảng cáo vừa xuất hiện.
 * Chính sách AdMob khuyến nghị chèn một khoảng chờ đúng để tránh chuyện đó:
 *
 *   "it is recommended that a delay is inserted after the end of a level and before the display of
 *    an interstitial ad. This delay could take the form of a loading or please wait screen, or a
 *    progress-bar/wheel."
 *   — Recommended interstitial implementations, support.google.com/admob/answer/6201350
 *
 * Google không nêu con số cụ thể; mặc định của SDK là 500ms (xem `InterAdConfig.minLoadingMs` và
 * `RewardAdConfig.minLoadingMs`) — đủ để ngón tay dừng lại, chưa đủ để thành khó chịu.
 *
 * Chỉ chờ phần còn thiếu: ad phải chờ fill lâu hơn [minMs] rồi thì hàm này không cộng thêm gì.
 * Dùng [SystemClock.elapsedRealtime] chứ không phải wall clock để đổi giờ hệ thống không làm lệch.
 */
internal suspend fun awaitMinLoadingWindow(startedAtMs: Long, minMs: Long) {
    if (minMs <= 0L) return
    val remaining = minMs - (SystemClock.elapsedRealtime() - startedAtMs)
    if (remaining > 0L) delay(remaining)
}
