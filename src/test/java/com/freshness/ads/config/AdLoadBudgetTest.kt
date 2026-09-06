package com.freshness.ads.config

import org.junit.Assert.assertEquals
import org.junit.Test

class AdLoadBudgetTest {

    /** Đồng hồ giả: test không phụ thuộc SystemClock của Android và không cần sleep. */
    private var clock = 0L
    private val now: () -> Long = { clock }

    private fun AdBudgetSpec.budget(tierCount: Int) = budgetFor(tierCount, now)

    @Test
    fun `mot tier duoc dung tron deadline goc, khong bi cap chan`() {
        // Đây là ca quan trọng nhất: 26/29 placement hiện chỉ có 1 id. Nếu cap chặn cả tier cuối thì
        // reward tụt từ 20s xuống 5s, app-open 12s xuống 4s — ăn thẳng vào show rate.
        val budget = AdBudgets.REWARD.budget(tierCount = 1)
        assertEquals(AdBudgets.REWARD.baseMs, budget.nextTierBudget(isLastTier = true))
    }

    @Test
    fun `tier khong phai cuoi bi cap chan de danh cho tier sau`() {
        val budget = AdBudgets.REWARD.budget(tierCount = 3)
        assertEquals(AdBudgets.REWARD.tierCapMs, budget.nextTierBudget(isLastTier = false))
    }

    @Test
    fun `waitFullRemaining thi tier khong phai cuoi cung duoc tron ngan sach con lai`() {
        // Inter chờ tới khi AdMob trả no-fill thật, nên tier không bị cap cắt ngang; tổng vẫn giới hạn.
        val budget = AdBudgets.INTER_IN_APP.budget(tierCount = 3)
        val total = budget.remaining()
        assertEquals(total, budget.nextTierBudget(isLastTier = false, waitFullRemaining = true))
        assertEquals(
            AdBudgets.INTER_IN_APP.tierCapMs,
            budget.nextTierBudget(isLastTier = false),
        )
    }

    @Test
    fun `waitFullRemaining van dung khi het ngan sach`() {
        val budget = AdBudgetSpec(baseMs = 0, tierCapMs = 3_000, ceilingMs = null).budget(1)
        assertEquals(0L, budget.nextTierBudget(isLastTier = false, waitFullRemaining = true))
    }

    @Test
    fun `nhieu tier thi tong gian theo base cong cap`() {
        val spec = AdBudgetSpec(baseMs = 6_000, tierCapMs = 3_000, ceilingMs = 15_000)
        // 1 tier = base; mỗi tier thêm cộng trọn một cap.
        assertEquals(6_000, spec.budget(1).nextTierBudget(isLastTier = true))
        assertEquals(9_000, spec.budget(2).nextTierBudget(isLastTier = true))
        assertEquals(12_000, spec.budget(3).nextTierBudget(isLastTier = true))
    }

    @Test
    fun `tran chan tren khi qua nhieu tier`() {
        val spec = AdBudgetSpec(baseMs = 6_000, tierCapMs = 3_000, ceilingMs = 15_000)
        assertEquals(15_000, spec.budget(10).nextTierBudget(isLastTier = true))
    }

    @Test
    fun `ceiling null thi khong gian du them bao nhieu tier`() {
        // Splash: splashTimeoutMs vừa là deadline ad vừa là duration thanh progress, không được vượt.
        val spec = AdBudgets.interSplash(splashTimeoutMs = 30_000)
        assertEquals(30_000, spec.budget(1).nextTierBudget(isLastTier = true))
        assertEquals(30_000, spec.budget(5).nextTierBudget(isLastTier = true))
        // Tier không phải cuối vẫn bị cap 3s để tier sau còn phần.
        assertEquals(3_000, spec.budget(5).nextTierBudget(isLastTier = false))
    }

    @Test
    fun `het ngan sach thi khong phat request moi`() {
        val budget = AdBudgetSpec(baseMs = 0, tierCapMs = 3_000, ceilingMs = null).budget(1)
        assertEquals(0L, budget.nextTierBudget(isLastTier = true))
        assertEquals(0L, budget.nextTierBudget(isLastTier = false))
    }

    @Test
    fun `duoi nguong toi thieu thi dung han`() {
        val budget = AdBudgetSpec(
            baseMs = AdLoadBudget.MIN_TIER_MS - 1,
            tierCapMs = 3_000,
            ceilingMs = null,
        ).budget(1)
        assertEquals(0L, budget.nextTierBudget(isLastTier = true))
    }

    @Test
    fun `ngan sach tru dan theo thoi gian tier truoc da tieu`() {
        val budget = AdBudgetSpec(baseMs = 10_000, tierCapMs = 3_000, ceilingMs = null).budget(1)
        assertEquals(10_000, budget.remaining())

        clock += 4_000 // tier trước đã tiêu 4s
        assertEquals(6_000, budget.remaining())
        assertEquals(6_000, budget.nextTierBudget(isLastTier = true))

        clock += 6_000 // hết sạch
        assertEquals(0L, budget.remaining())
        assertEquals(0L, budget.nextTierBudget(isLastTier = true))
    }
}
