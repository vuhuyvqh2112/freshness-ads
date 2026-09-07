package com.freshness.ads.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Chạy với `isDebugBuild = false` để thấy id thật; nhánh ép test id của debug được kiểm riêng ở
 * [debugBuild_epTestIdTheoFormat].
 */
class AdUnitCatalogTest {

    private fun catalog(
        defaultsJson: String? = DEFAULTS,
        isDebugBuild: Boolean = false,
    ) = AdUnitCatalogProvider({ defaultsJson }, isDebugBuild)

    @Test
    fun `payload hop le giu dung thu tu waterfall`() {
        val catalog = catalog()
        catalog.update(
            """
            { "placements": { "inter_splash": { "format": "interstitial", "ids": [
                { "id": "id/hf" }, { "id": "id/mid" }, { "id": "id/all" } ] } } }
            """
        )

        assertEquals(listOf("id/hf", "id/mid", "id/all"), catalog.idsFor("inter_splash"))
    }

    @Test
    fun debugBuild_placementChuaKhaiId_dungTestId() {
        val catalog = catalog(isDebugBuild = true)
        catalog.update("""{ "placements": { "native_obd_full": { "format": "native", "ids": [] } } }""")

        assertEquals(listOf(AdFormat.NATIVE.testId), catalog.idsFor("native_obd_full"))
        assertTrue(catalog.isEnabled("native_obd_full"))
    }

    @Test
    fun debugBuild_placementTatChuDich_vanRong() {
        val catalog = catalog(isDebugBuild = true)
        // enable:false và "khai id rồi tắt" đều là tắt có chủ ý — debug không được bật hộ.
        catalog.update(
            """
            { "placements": {
                "native_obd_full": { "format": "native", "enable": false, "ids": [] },
                "native_intro_1": { "format": "native", "ids": [ { "id": "id/a", "enable": false } ] } } }
            """
        )

        assertTrue(catalog.idsFor("native_obd_full").isEmpty())
        assertTrue(catalog.idsFor("native_intro_1").isEmpty())
    }

    @Test
    fun releaseBuild_placementChuaKhaiId_vanRong() {
        val catalog = catalog()
        catalog.update("""{ "placements": { "native_obd_full": { "format": "native", "ids": [] } } }""")

        assertTrue(catalog.idsFor("native_obd_full").isEmpty())
    }

    @Test
    fun `rewardTimeoutMs doc tu payload, thieu field thi giu default`() {
        val catalog = catalog()
        catalog.update("""{ "settings": { "rewardTimeoutMs": 45000 } }""")
        assertEquals(45_000L, catalog.settingsSnapshot().rewardTimeoutMs)

        // Thiếu field không được ép về null: reward sẽ mất deadline và rơi về giá trị của call site.
        catalog.update("""{ "settings": { "splashTimeoutMs": 30000 } }""")
        assertEquals(
            com.freshness.ads.remoteconfig.RemoteConfig().rewardTimeoutMs,
            catalog.settingsSnapshot().rewardTimeoutMs,
        )
    }

    @Test
    fun `id bi tat bi loai, cac id con lai giu nguyen thu tu`() {
        val catalog = catalog()
        catalog.update(
            """
            { "placements": { "inter_splash": { "ids": [
                { "id": "id/hf" }, { "id": "id/mid", "enable": false }, { "id": "id/all" } ] } } }
            """
        )

        assertEquals(listOf("id/hf", "id/all"), catalog.idsFor("inter_splash"))
    }

    @Test
    fun `tat ca placement thi khong con id nao`() {
        val catalog = catalog()
        catalog.update(
            """
            { "placements": { "inter_splash": { "enable": false, "ids": [ { "id": "id/hf" } ] } } }
            """
        )

        assertTrue(catalog.idsFor("inter_splash").isEmpty())
        assertFalse(catalog.isEnabled("inter_splash"))
    }

    @Test
    fun `ids rong hoac tat het deu coi nhu placement tat`() {
        val catalog = catalog()
        catalog.update(
            """
            { "placements": {
                "inter_splash": { "ids": [] },
                "native_home": { "ids": [ { "id": "id/a", "enable": false } ] } } }
            """
        )

        assertFalse(catalog.isEnabled("inter_splash"))
        assertFalse(catalog.isEnabled("native_home"))
    }

    @Test
    fun `payload hong giu nguyen payload truoc do`() {
        val catalog = catalog()
        catalog.update("""{ "placements": { "inter_splash": { "ids": [ { "id": "id/tot" } ] } } }""")

        catalog.update("{{{ hong")
        assertEquals(listOf("id/tot"), catalog.idsFor("inter_splash"))

        catalog.update("")
        assertEquals(listOf("id/tot"), catalog.idsFor("inter_splash"))

        catalog.update(null)
        assertEquals(listOf("id/tot"), catalog.idsFor("inter_splash"))
    }

    @Test
    fun `placement thieu trong remote config thi roi ve default`() {
        val catalog = catalog()
        // Payload chỉ khai báo inter_splash -> native_home phải lấy từ asset default.
        catalog.update("""{ "placements": { "inter_splash": { "ids": [ { "id": "id/moi" } ] } } }""")

        assertEquals(listOf("id/moi"), catalog.idsFor("inter_splash"))
        assertEquals(listOf("default/native"), catalog.idsFor("native_home"))
    }

    @Test
    fun `chua fetch remote config thi dung default`() {
        assertEquals(listOf("default/native"), catalog().idsFor("native_home"))
    }

    @Test
    fun `default asset hong thi khong crash, moi placement rong`() {
        val catalog = catalog(defaultsJson = "{{{ hong")

        assertTrue(catalog.idsFor("native_home").isEmpty())
        assertFalse(catalog.isEnabled("native_home"))
    }

    @Test
    fun `placement la khong crash, chi tra rong`() {
        assertTrue(catalog().idsFor("khong_ton_tai").isEmpty())
    }

    @Test
    fun `ids co mat tren remote thi thay ca danh sach, khong gop tung phan tu`() {
        val catalog = catalog()
        // Default cho inter_splash 2 id; payload chỉ khai 1 -> phải ra đúng 1, không gộp với default.
        catalog.update("""{ "placements": { "inter_splash": { "ids": [ { "id": "id/chi-mot" } ] } } }""")

        assertEquals(listOf("id/chi-mot"), catalog.idsFor("inter_splash"))
    }

    @Test
    fun `remote ghi de tung field, field thieu giu cua asset`() {
        val catalog = catalog()
        // Chỉ tắt placement: không phải paste lại ids, và bật lại thì ids của asset vẫn còn nguyên.
        catalog.update("""{ "placements": { "inter_splash": { "enable": false } } }""")
        assertTrue(catalog.idsFor("inter_splash").isEmpty())

        catalog.update("""{ "placements": { "inter_splash": { "enable": true } } }""")
        assertEquals(listOf("default/hf", "default/all"), catalog.idsFor("inter_splash"))

        // Ghi đè ngân sách mà không nói gì tới ids/format: format vẫn là của asset (debug test id cần nó).
        catalog.update("""{ "placements": { "inter_splash": { "baseMs": 9000 } } }""")
        val spec = catalog.budgetSpecFor("inter_splash", AdBudgetSpec(1L, 2L, 3L))
        assertEquals(9_000L, spec.baseMs)
        assertEquals(2L, spec.tierCapMs)
        assertEquals(listOf("default/hf", "default/all"), catalog.idsFor("inter_splash"))
    }

    @Test
    fun `hf false chi chay all-price, hf thieu giu het ids`() {
        val catalog = catalog()
        catalog.update("""{ "placements": { "inter_splash": { "hf": false } } }""")
        assertEquals(listOf("default/all"), catalog.idsFor("inter_splash"))

        catalog.update("""{ "placements": { "inter_splash": { "hf": true } } }""")
        assertEquals(listOf("default/hf", "default/all"), catalog.idsFor("inter_splash"))

        // Không nhắc tới hf -> hành vi cũ, dùng hết ids.
        catalog.update("""{ "placements": { "inter_splash": { "enable": true } } }""")
        assertEquals(listOf("default/hf", "default/all"), catalog.idsFor("inter_splash"))
    }

    @Test
    fun `ids nhan ca chuoi thuan lan object`() {
        val catalog = catalog()
        catalog.update(
            """
            { "placements": { "inter_splash": { "ids": [ "id/a", { "id": "id/b", "enable": false }, "id/c" ] } } }
            """
        )

        assertEquals(listOf("id/a", "id/c"), catalog.idsFor("inter_splash"))
    }

    @Test
    fun `settings key tuy y doc duoc bang getter co kieu, gop theo key voi asset`() {
        val catalog = catalog()
        catalog.update("""{ "settings": { "free_episodes": 5, "rate_exit": "true", "label": "x", "ratio": 1.5 } }""")
        val rc = catalog.settingsSnapshot()

        assertEquals(5L, rc.long("free_episodes", 0L))
        assertTrue(rc.bool("rate_exit", false))
        assertEquals("x", rc.string("label", ""))
        assertEquals(7L, rc.long("khong_co", 7L))
        // Key của asset không bị mất khi remote không nhắc tới.
        assertEquals(30_000L, rc.splashTimeoutMs)
        assertEquals(30_000L, rc.long("splashTimeoutMs", 0L))
    }

    @Test
    fun `nativeOptionsFor ghi de tung field, ten enum khong phan biet hoa thuong`() {
        val catalog = catalog()
        val fallback = com.freshness.ads.natives.NativeAdOptions(videoMuted = true)
        // Chưa có gì trên remote -> đúng fallback.
        assertEquals(fallback, catalog.nativeOptionsFor("native_home", fallback))

        catalog.update("""{ "placements": { "native_home": { "videoMuted": false, "mediaAspectRatio": "portrait", "adChoicesPlacement": "BOTTOM_LEFT" } } }""")
        val opts = catalog.nativeOptionsFor("native_home", fallback)
        assertFalse(opts.videoMuted)
        assertEquals(com.freshness.ads.natives.NativeMediaAspectRatio.PORTRAIT, opts.mediaAspectRatio)
        assertEquals(com.freshness.ads.natives.AdChoicesCorner.BOTTOM_LEFT, opts.adChoicesPlacement)
        // ids của asset vẫn còn: options là patch, không thay trọn placement.
        assertEquals(listOf("default/native"), catalog.idsFor("native_home"))

        // Giá trị lạ -> giữ fallback thay vì crash.
        catalog.update("""{ "placements": { "native_home": { "mediaAspectRatio": "tron" } } }""")
        assertEquals(fallback.mediaAspectRatio, catalog.nativeOptionsFor("native_home", fallback).mediaAspectRatio)
    }

    @Test
    fun `format rewardedInter co test id rieng va debug ep dung no`() {
        assertEquals(AdFormat.REWARDED_INTERSTITIAL, AdFormat.from("rewardedInter"))
        val catalog = catalog(isDebugBuild = true)
        catalog.update("""{ "placements": { "reward_inter_x": { "format": "rewardedInter", "ids": [ "id/that" ] } } }""")
        assertEquals(listOf(AdFormat.REWARDED_INTERSTITIAL.testId), catalog.idsFor("reward_inter_x"))
    }

    @Test
    fun `describe liet ke placement va danh dau id bi tat`() {
        val catalog = catalog()
        catalog.update("""{ "placements": { "inter_splash": { "hf": false } } }""")
        val text = catalog.describe()

        assertTrue(text.contains("inter_splash [interstitial] enable=true hf=false (asset+remote)"))
        assertTrue(text.contains("default/hf (OFF)"))
        assertTrue(text.contains("native_home [native]"))
    }

    @Test
    fun debugBuild_epTestIdTheoFormat() {
        val catalog = catalog(isDebugBuild = true)

        // native_home có format "native" -> test id native, bất kể id thật trong payload.
        assertEquals(listOf(AdFormat.NATIVE.testId), catalog.idsFor("native_home"))
        // Placement tắt thì debug cũng phải tắt, nếu không sẽ không test được nút tắt.
        catalog.update("""{ "placements": { "native_home": { "enable": false, "ids": [ { "id": "x" } ] } } }""")
        assertTrue(catalog.idsFor("native_home").isEmpty())
    }

    @Test
    fun debugBuild_useRealIds_traIdThatVaDayDuTang() {
        val catalog = catalog(isDebugBuild = true)
        catalog.useRealIds = true

        assertEquals(listOf("default/hf", "default/all"), catalog.idsFor("inter_splash"))
    }

    @Test
    fun releaseBuild_useRealIds_khongCoTacDung() {
        val catalog = catalog(isDebugBuild = false)
        catalog.useRealIds = true

        assertFalse(catalog.useRealIds)
    }

    private companion object {
        val DEFAULTS = """
            {
              "version": 2,
              "settings": { "splashTimeoutMs": 30000 },
              "placements": {
                "inter_splash": { "format": "interstitial", "ids": [
                  { "id": "default/hf" }, { "id": "default/all" } ] },
                "native_home": { "format": "native", "ids": [ { "id": "default/native" } ] }
              }
            }
        """
    }
}
