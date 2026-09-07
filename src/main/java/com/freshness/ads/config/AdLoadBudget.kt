package com.freshness.ads.config

import android.os.SystemClock

/**
 * Ngân sách thời gian cho MỘT lần load của một placement, chia sẻ giữa tất cả các tier của waterfall.
 *
 * Vì sao cần: thêm tier high-floor mà không chặn thời gian là tái tạo đúng regression đã ghi trong
 * `SplashActivity` — tier đầu no-fill chậm khiến tier sau fill quá muộn, user đã rời màn, ad matched
 * nhưng không show được. Ngân sách giữ cho tổng thời gian không vượt deadline của placement, còn
 * [nextTierBudget] giữ cho một tier chậm không nuốt hết phần của các tier sau.
 */
class AdLoadBudget(
    private val totalMs: Long,
    private val tierCapMs: Long,
    /** Nguồn thời gian — tiêm vào để unit test không phụ thuộc `SystemClock` của Android. */
    private val now: () -> Long = { SystemClock.elapsedRealtime() },
) {
    private val startedAt = now()

    fun remaining(): Long = (totalMs - (now() - startedAt)).coerceAtLeast(0L)

    /**
     * Thời gian được phép chờ tier kế tiếp. `0` = KHÔNG bắt đầu tier mới nữa.
     *
     * Phát một request mà biết trước là không đủ thời gian chờ nó chỉ tạo thêm matched-but-not-shown,
     * nên dưới [MIN_TIER_MS] là dừng hẳn waterfall.
     *
     * @param isLastTier tier cuối được dùng TRỌN phần ngân sách còn lại, không bị [tierCapMs] chặn.
     *   Cap tồn tại để dành thời gian cho các tier phía sau; tier cuối không còn ai để dành. Không có
     *   điều này thì placement chỉ có 1 id sẽ tụt từ deadline gốc xuống đúng bằng cap — ví dụ reward
     *   20s còn 5s, app-open 12s còn 4s — tức là ăn thẳng vào show rate, đúng thứ đang muốn bảo vệ.
     * @param waitFullRemaining bỏ [tierCapMs] cho MỌI tier: chỉ chuyển sang id sau khi AdMob thực sự
     *   trả no-fill, hoặc khi hết sạch tổng ngân sách. Cắt tier ở cap sẽ vứt luôn cả những request
     *   đáng lẽ fill chỉ chậm hơn cap vài trăm ms — fill đó về muộn chỉ còn kịp vào cache, không cứu
     *   được lần show đang chờ. Đánh đổi: một id treo không callback sẽ nuốt phần của các id sau,
     *   nên chỉ bật ở nơi đã chấp nhận đánh đổi này: `InterAdProvider` và `RewardAdProvider`.
     */
    fun nextTierBudget(isLastTier: Boolean, waitFullRemaining: Boolean = false): Long {
        val left = remaining()
        if (left < MIN_TIER_MS) return 0L
        return if (isLastTier || waitFullRemaining) left else minOf(tierCapMs, left)
    }

    companion object {
        /** Dưới ngưỡng này thì không còn đáng để phát request mới. */
        const val MIN_TIER_MS = 1_500L
    }
}

/**
 * Tham số ngân sách của một placement.
 *
 * Tổng = `min(baseMs + (số_tier - 1) * tierCapMs, ceilingMs)`.
 * [baseMs] bằng đúng deadline hiện hành → 1 tier giữ nguyên hành vi cũ, mỗi tier thêm được cộng trọn
 * một [tierCapMs], và [ceilingMs] chặn trên theo giới hạn UX thật.
 *
 * @param ceilingMs `null` = không giãn, tổng luôn bằng [baseMs] (dùng cho splash, xem [AdBudgets]).
 */
data class AdBudgetSpec(
    val baseMs: Long,
    val tierCapMs: Long,
    val ceilingMs: Long?,
) {
    fun budgetFor(tierCount: Int, now: () -> Long = { SystemClock.elapsedRealtime() }): AdLoadBudget {
        val extraTiers = (tierCount - 1).coerceAtLeast(0)
        val grown = baseMs + extraTiers * tierCapMs
        val total = if (ceilingMs == null) baseMs else grown.coerceAtMost(maxOf(ceilingMs, baseMs))
        return AdLoadBudget(total, tierCapMs, now)
    }
}

/**
 * Ngân sách mặc định theo placement. Payload `ads_id_config` ghi đè được từng field qua
 * [AdUnitCatalog.budgetSpecFor], nên siết/nới không cần release.
 *
 * Trần được chọn theo mức kiên nhẫn của user ở từng chỗ, không phải theo kỹ thuật.
 */
object AdBudgets {

    /**
     * Hạn dùng của interstitial / rewarded đã nạp. AdMob coi ad toàn màn là hết hạn sau ~1 giờ:
     * đem ad quá hạn đi show là `onAdFailedToShowFullScreenContent` — người dùng nhìn spinner rồi
     * không có gì. Quá hạn thì bỏ và nạp lại. App-open có hạn riêng 4 giờ theo hướng dẫn của Google.
     */
    const val FULL_SCREEN_AD_TTL_MS = 60L * 60 * 1000

    /** Interstitial trong app: user vừa bấm nút và đang nhìn dialog loading. */
    val INTER_IN_APP = AdBudgetSpec(baseMs = 6_000L, tierCapMs = 3_000L, ceilingMs = 15_000L)

    /** Reward: user đứng sau spinner không huỷ được, chịu được lâu hơn. */
    val REWARD = AdBudgetSpec(baseMs = 20_000L, tierCapMs = 5_000L, ceilingMs = 35_000L)

    /** App open: user vừa quay lại app, muốn vào ngay. */
    val OPEN = AdBudgetSpec(baseMs = 12_000L, tierCapMs = 4_000L, ceilingMs = 24_000L)

    /** Native đơn: trước đây KHÔNG có deadline nào; 8s là mức mới. */
    val NATIVE_SINGLE = AdBudgetSpec(baseMs = 8_000L, tierCapMs = 4_000L, ceilingMs = 20_000L)

    /** Banner: nằm trong layout, không chặn thao tác nào của user. */
    val BANNER = AdBudgetSpec(baseMs = 10_000L, tierCapMs = 4_000L, ceilingMs = 20_000L)

    /** Pool native preload nền — không ai đang chờ, nên rộng tay nhất. */
    val NATIVE_POOL = AdBudgetSpec(baseMs = 30_000L, tierCapMs = 8_000L, ceilingMs = 120_000L)

    /**
     * Splash interstitial: KHÔNG giãn theo số tier.
     *
     * `splashTimeoutMs` vừa là deadline ad vừa là `duration` của thanh progress
     * (`SplashActivity.startProgressAnimation`), và splash còn cộng thêm một tầng chờ native fallback
     * sau đó. Cho ngân sách ad vượt quá nó thì thanh progress chạy hết rồi đứng im — user tưởng treo.
     * Muốn splash chờ lâu hơn thì nâng `splashTimeoutMs` trên Remote Config, cả hai cùng giãn.
     */
    fun interSplash(splashTimeoutMs: Long) =
        AdBudgetSpec(baseMs = splashTimeoutMs, tierCapMs = 3_000L, ceilingMs = null)

    /**
     * Ngân sách cho một interstitial. `baseMs` lấy từ `InterAdConfig.timeOut` — chính là deadline mà
     * màn hình gọi đã khai báo, cũng là thời gian `showAd` chịu chờ. Hard-code một hằng ở đây sẽ khiến
     * hai con số lệch nhau: splash khai 30s mà waterfall tự bỏ cuộc ở 9s.
     *
     * Riêng inter và reward, `tierCapMs` chỉ còn dùng để giãn TỔNG ngân sách theo số id; nó không còn
     * chặn thời gian chờ của từng tier, vì hai waterfall này chờ tới khi có no-fill thật.
     */
    fun forInter(timeOutMs: Long, isSplash: Boolean): AdBudgetSpec =
        if (isSplash) interSplash(timeOutMs)
        else INTER_IN_APP.copy(baseMs = timeOutMs)

    /** Tương tự [forInter]: deadline thật nằm ở `RewardAdConfig.timeOut`. */
    fun forReward(timeOutMs: Long): AdBudgetSpec = REWARD.copy(baseMs = timeOutMs)
}
