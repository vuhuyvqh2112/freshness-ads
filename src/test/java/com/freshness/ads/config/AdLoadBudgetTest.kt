package com.freshness.ads.config

import org.junit.Assert.assertEquals
import org.junit.Test

class AdLoadBudgetTest {

    /** Đồng hồ giả: test không phụ thuộc SystemClock của Android và không cần sleep. */
    private var clock = 0L
    private val now: () -> Long = { clock }

    private fun AdBudgetSpec.budget(tierCount: Int) = budgetFor(tierCount, now)

    @Test
    fun `mot id duoc dung tron deadline goc`() {
        // 26/29 placement hiện chỉ có 1 id. Bất kỳ thứ gì cắt bớt ở đây là ăn thẳng vào show rate.
        val budget = AdBudgets.REWARD.budget(tierCount = 1)
        assertEquals(AdBudgets.REWARD.baseMs, budget.nextTierBudget())
    }

    @Test
    fun `id khong phai cuoi cung duoc tron ngan sach con lai`() {
        // Đây là điểm mấu chốt của mô hình: không có cap riêng cho từng tier. Mỗi id chờ tới khi
        // AdMob trả no-fill thật; cắt ngang chỉ tạo matched request không có impression cho id đó.
        val budget = AdBudgets.INTER_IN_APP.budget(tierCount = 3)
        assertEquals(budget.remaining(), budget.nextTierBudget())
    }

    @Test
    fun `nhieu id thi tong gian theo base cong tierCapMs`() {
        // tierCapMs chỉ còn một việc: mỗi id thêm cộng bấy nhiêu vào TỔNG.
        val spec = AdBudgetSpec(baseMs = 6_000, tierCapMs = 3_000, ceilingMs = 15_000)
        assertEquals(6_000, spec.budget(1).nextTierBudget())
        assertEquals(9_000, spec.budget(2).nextTierBudget())
        assertEquals(12_000, spec.budget(3).nextTierBudget())
    }

    @Test
    fun `tran chan tren khi qua nhieu id`() {
        val spec = AdBudgetSpec(baseMs = 6_000, tierCapMs = 3_000, ceilingMs = 15_000)
        assertEquals(15_000, spec.budget(10).nextTierBudget())
    }

    @Test
    fun `ceiling null thi khong gian du them bao nhieu id`() {
        // Splash: splashTimeoutMs vừa là deadline ad vừa là duration thanh progress, không được vượt.
        val spec = AdBudgets.interSplash(splashTimeoutMs = 30_000)
        assertEquals(30_000, spec.budget(1).nextTierBudget())
        assertEquals(30_000, spec.budget(5).nextTierBudget())
    }

    @Test
    fun `het ngan sach thi khong phat request moi`() {
        val budget = AdBudgetSpec(baseMs = 0, tierCapMs = 3_000, ceilingMs = null).budget(1)
        assertEquals(0L, budget.nextTierBudget())
    }

    @Test
    fun `duoi nguong toi thieu thi dung han`() {
        val budget = AdBudgetSpec(
            baseMs = AdLoadBudget.MIN_TIER_MS - 1,
            tierCapMs = 3_000,
            ceilingMs = null,
        ).budget(1)
        assertEquals(0L, budget.nextTierBudget())
    }

    @Test
    fun `ngan sach tru dan theo thoi gian id truoc da tieu`() {
        val budget = AdBudgetSpec(baseMs = 10_000, tierCapMs = 3_000, ceilingMs = null).budget(1)
        assertEquals(10_000, budget.remaining())

        clock += 4_000 // id trước đã tiêu 4s
        assertEquals(6_000, budget.remaining())
        assertEquals(6_000, budget.nextTierBudget())

        clock += 6_000 // hết sạch
        assertEquals(0L, budget.remaining())
        assertEquals(0L, budget.nextTierBudget())
    }

    @Test
    fun `totalMs khai thang thi bo qua base va tierCap`() {
        // Đúng ví dụ vận hành: tổng 20s, 2 id. id đầu no-fill ở giây 10 thì id sau còn 10s,
        // tới giây 20 là dừng cả lượt.
        val spec = AdBudgetSpec(baseMs = 6_000, tierCapMs = 3_000, ceilingMs = 15_000, totalMs = 20_000)
        val budget = spec.budget(tierCount = 2)
        assertEquals(20_000, budget.remaining())

        clock += 10_000 // id đầu chờ 10s rồi no-fill
        assertEquals(10_000, budget.nextTierBudget())

        clock += 10_000 // id sau tiêu nốt
        assertEquals(0L, budget.nextTierBudget())
    }

    @Test
    fun `totalMs khong duong thi roi ve cach tinh cu`() {
        val spec = AdBudgetSpec(baseMs = 6_000, tierCapMs = 3_000, ceilingMs = 15_000, totalMs = 0)
        assertEquals(9_000, spec.budget(2).nextTierBudget())
    }

    @Test
    fun `totalMs khong bi tran ceiling chan`() {
        // Khai thẳng là chốt cứng: ceilingMs chỉ chặn con số TÍNH RA, không chặn số khai tay.
        val spec = AdBudgetSpec(baseMs = 6_000, tierCapMs = 3_000, ceilingMs = 15_000, totalMs = 40_000)
        assertEquals(40_000, spec.budget(2).nextTierBudget())
    }
}
