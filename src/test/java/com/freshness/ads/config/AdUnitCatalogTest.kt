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
    fun `remote config ghi de tron placement, khong merge tung field`() {
        val catalog = catalog()
        // Default cho inter_splash 2 id; payload chỉ khai 1 -> phải ra đúng 1, không gộp với default.
        catalog.update("""{ "placements": { "inter_splash": { "ids": [ { "id": "id/chi-mot" } ] } } }""")

        assertEquals(listOf("id/chi-mot"), catalog.idsFor("inter_splash"))
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
              "placements": {
                "inter_splash": { "format": "interstitial", "ids": [
                  { "id": "default/hf" }, { "id": "default/all" } ] },
                "native_home": { "format": "native", "ids": [ { "id": "default/native" } ] }
              }
            }
        """
    }
}
