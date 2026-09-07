# So sánh remote config id ads: freshness-ads 1.1.0 vs modnix tutorial SDK 1.6.0 (GoodDrama)

Nguồn tham chiếu: `MOD082-GoodDrama/app/src/{appDev,appRelease}/assets/modnix_ad_units.json`, `AppRemoteConfig.kt`, javadoc `com.modnix.tutorial.ads.catalog.*` và `ads.remote.*` (AAR 1.6.0 trong gradle cache; không có source).

## Giống nhau (không cần làm gì)
- Asset JSON đóng gói làm default, key Remote Config `ads_id_config` ghi đè; payload hỏng giữ bản đang dùng.
- Id resolve lúc load nên fetch giữa phiên có hiệu lực ở lần load sau.
- Placement không có trong catalog = không cấu hình, không phải tắt.
- modnix cache RC vào SharedPreferences riêng + `loadCached()`; freshness dùng activated cache của Firebase (đã sửa ở 1.1.0) — tương đương.
- modnix tách asset theo flavor (appDev id demo / appRelease id thật); freshness ép test id theo `format` khi app debuggable — cùng mục đích.

## Đáng nâng cấp

| # | modnix làm | freshness hiện tại | Đề xuất |
|---|---|---|---|
| 1 | **Ghi đè theo từng field** (`PlacementAdsPatch`): RC chỉ mang phần thay đổi, vd `{"placements":{"native_home":{"enable":false}}}` | Placement có trên RC là **thay trọn** — muốn tắt 1 placement phải paste lại đủ `ids`, thiếu là mất id | Merge field-by-field: `enable`, `hf`, `format`, `baseMs/tierCapMs/ceilingMs` ghi đè riêng lẻ; `ids` có mặt thì thay cả list. Không phá payload cũ. |
| 2 | **Cờ `hf` per placement**: `ids` = [HF…, all-price cuối]; `hf:false` → chỉ chạy all-price. Ops bật cả tầng HF bằng một boolean | `enable` từng id — linh hoạt nhưng dài, và không có công tắc "tắt hết HF" | Thêm `hf: Boolean?` (null = giữ hành vi cũ, false = chỉ id cuối). Nhận thêm `ids` dạng string thuần `["a","b"]` bên cạnh `{id, enable}`. |
| 3 | **Key RC tuỳ ý của app** qua `AutoRemoteConfig` + `longKey/booleanKey`, SDK fetch cùng lượt (`partnerRemoteConfigs`) | `settings` cứng 3 field; app muốn key riêng phải tự fetch Firebase, dễ lệch interval/throttle với SDK | Cho `settings` nhận key tuỳ ý, thêm `ads.remoteConfig.long("key", default)` / `bool` / `string`. Một payload, một fetch. |
| 4 | Log thông điệp có hướng dẫn: "`x` has no ad-unit id in the asset or remote — is it in the app's src/main/assets/?" và `logCurrentValues()` | Có log warn nhưng không có lệnh in toàn bộ catalog hiệu lực | Thêm `adUnitCatalog.dump()` in bảng placement → ids/enable/budget để tester đối chiếu console. |

Không nên bê sang: `AutoRemoteConfig` prefs riêng (Firebase đã cache), catalog quyết định danh sách placement để SDK tự register (freshness không có luồng FO cố định).

## Đã làm (1.1.0, chưa commit)
Cả 4 mục. `hf` thiếu = giữ hành vi cũ (dùng hết ids) theo quyết định của user. Code: `AdIdConfig.kt` (model + `AdIdEntryDeserializer` + `patchedBy`), `AdUnitCatalogProvider.kt` (merge, settings map, `describe()`), `RemoteConfig.kt` (`settings` + `long/bool/string`). Test: +5 case trong `AdUnitCatalogTest`. Docs mục 3, 14, changelog; README; `samples/ads_id_config.json`. PDF 21 trang.
