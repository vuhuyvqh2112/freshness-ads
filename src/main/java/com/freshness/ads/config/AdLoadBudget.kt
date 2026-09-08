package com.freshness.ads.config

import android.os.SystemClock

/**
 * Ngân sách thời gian cho MỘT lần load của một placement, chia sẻ giữa tất cả các tier của waterfall.
 *
 * MỘT deadline duy nhất cho cả placement, không có cap riêng cho từng id: mỗi id được chờ tới khi
 * AdMob trả no-fill thật, id nào no-fill mới sang id kế, và cả chuỗi dừng khi [totalMs] cạn.
 *
 * Bản trước có thêm một cap cho mỗi tier để "một tier chậm không nuốt phần của tier sau". Nó phản tác
 * dụng: request bị bỏ ngang ở cap vẫn fill vài trăm ms sau đó, nhưng lúc ấy tier sau đã chạy nên ad
 * về muộn chỉ còn kịp vào cache — ad unit của tier bị cắt nhận một matched request không có
 * impression. Tầng high-floor, tầng cần nhiều thời gian nhất, chính là tầng bị cắt nhiều nhất.
 *
 * Đánh đổi đã chấp nhận: một id treo không callback sẽ nuốt phần của các id sau. Tổng vẫn bị chặn nên
 * deadline của placement không đổi.
 */
class AdLoadBudget(
    private val totalMs: Long,
    /** Nguồn thời gian — tiêm vào để unit test không phụ thuộc `SystemClock` của Android. */
    private val now: () -> Long = { SystemClock.elapsedRealtime() },
) {
    private val startedAt = now()

    fun remaining(): Long = (totalMs - (now() - startedAt)).coerceAtLeast(0L)

    /**
     * Thời gian được phép chờ id kế tiếp = trọn phần ngân sách còn lại. `0` = KHÔNG phát request nữa.
     *
     * Phát một request mà biết trước là không đủ thời gian chờ nó chỉ tạo thêm matched-but-not-shown,
     * nên dưới [MIN_TIER_MS] là dừng hẳn waterfall.
     */
    fun nextTierBudget(): Long {
        val left = remaining()
        return if (left < MIN_TIER_MS) 0L else left
    }

    companion object {
        /** Dưới ngưỡng này thì không còn đáng để phát request mới. */
        const val MIN_TIER_MS = 1_500L
    }
}

/**
 * Tham số ngân sách của một placement. Mọi field ghi đè được từ payload `ads_id_config`.
 *
 * Hai cách khai deadline tổng, [totalMs] thắng nếu có:
 * - khai thẳng [totalMs];
 * - hoặc để tính ra: `min(baseMs + (số_id - 1) * tierCapMs, ceilingMs)`.
 *
 * @param baseMs deadline cho placement chỉ có MỘT id.
 * @param tierCapMs mỗi id thêm cộng bấy nhiêu vào TỔNG. Tên giữ nguyên vì nó là field trong payload
 *   Remote Config đang chạy production — đổi tên là mọi payload cũ mất giá trị này. Nhưng nó KHÔNG
 *   còn là cap của từng tier: không id nào bị cắt ngang nữa, xem [AdLoadBudget].
 * @param ceilingMs `null` = không giãn, tổng luôn bằng [baseMs] (dùng cho splash, xem [AdBudgets]).
 * @param totalMs deadline tổng chốt cứng, bỏ qua ba field trên. Đây là đường mà `settings.adTimeoutMs`
 *   và `placements.<key>.totalMs` đi vào. `null`/`<= 0` = dùng cách tính.
 */
data class AdBudgetSpec(
    val baseMs: Long,
    val tierCapMs: Long,
    val ceilingMs: Long?,
    val totalMs: Long? = null,
) {
    fun budgetFor(tierCount: Int, now: () -> Long = { SystemClock.elapsedRealtime() }): AdLoadBudget {
        totalMs?.takeIf { it > 0 }?.let { return AdLoadBudget(it, now) }
        val extraTiers = (tierCount - 1).coerceAtLeast(0)
        val grown = baseMs + extraTiers * tierCapMs
        val total = if (ceilingMs == null) baseMs else grown.coerceAtMost(maxOf(ceilingMs, baseMs))
        return AdLoadBudget(total, now)
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
        AdBudgetSpec(
            baseMs = splashTimeoutMs,
            tierCapMs = 3_000L,
            ceilingMs = null,
            // Chốt cứng để `settings.adTimeoutMs` toàn cục không đụng vào: con số này còn là duration
            // của thanh progress trên splash. Global ghi đè được ở đây thì thanh chạy hết rồi đứng im
            // (hoặc ngược lại, ad bị cắt trong khi thanh vẫn chạy) — user tưởng treo. Muốn đổi thì
            // đổi `settings.splashTimeoutMs`, cả hai cùng giãn.
            totalMs = splashTimeoutMs,
        )

    /**
     * Ngân sách cho một interstitial. `baseMs` lấy từ `InterAdConfig.timeOut` — chính là deadline mà
     * màn hình gọi đã khai báo, cũng là thời gian `showAd` chịu chờ. Hard-code một hằng ở đây sẽ khiến
     * hai con số lệch nhau: splash khai 30s mà waterfall tự bỏ cuộc ở 9s.
     *
     * `tierCapMs` chỉ còn dùng để giãn TỔNG ngân sách theo số id; nó không còn chặn thời gian chờ
     * của từng id, vì mọi waterfall đều chờ tới khi có no-fill thật.
     */
    fun forInter(timeOutMs: Long, isSplash: Boolean): AdBudgetSpec =
        if (isSplash) interSplash(timeOutMs)
        else INTER_IN_APP.copy(baseMs = timeOutMs)

    /** Tương tự [forInter]: deadline thật nằm ở `RewardAdConfig.timeOut`. */
    fun forReward(timeOutMs: Long): AdBudgetSpec = REWARD.copy(baseMs = timeOutMs)
}
