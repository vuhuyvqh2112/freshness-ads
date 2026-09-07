# Freshness Ads 1.0.6 — review toàn project + đề xuất nâng cấp DX

Phạm vi: toàn bộ `src/main` (5.5k dòng Kotlin), build config, README, docs, test. Agent `/code-review` chỉ soát commit cuối (docs) — kết quả gộp ở mục C.

## A. Lỗi code (ưu tiên sửa)

| # | Mức | Vị trí | Vấn đề |
|---|---|---|---|
| A1 | **Cao** | `AdUnitCatalogProvider.kt:31,34`, `GoogleMobileAdsConsentManager.kt:47` | `BuildConfig.DEBUG` là của **thư viện**, không phải app host. AAR publish `singleVariant("release")` → app dùng qua JitPack luôn thấy `DEBUG=false`. Hệ quả: (a) debug build của app host **không** được ép test id như README hứa, dev bấm vào ad thật; (b) `USE_REAL_IDS_IN_DEBUG` không ai đổi được; (c) debug geography EEA không bao giờ bật. `AdsInitializer.kt:43` đã làm đúng bằng `FLAG_DEBUGGABLE` — dùng cùng cách. |
| A2 | **Cao** | `RemoteConfigProvider.kt:38-46` | Chỉ nạp catalog khi `fetchAndActivate` **thành công**. Offline hoặc bị Firebase throttle → bỏ qua luôn config đã activate từ phiên trước, rơi về asset default; `isFetched` không bao giờ true → `waitRemoteConfig` treo đủ 5s mỗi lần splash. Sửa: luôn `update(getString(KEY))` trong `addOnCompleteListener` (giá trị activated tồn tại qua các lần mở app). |
| A3 | **Cao** | `RemoteConfigProvider.kt:22` | `setMinimumFetchIntervalInSeconds(10)` ở production. Firebase throttle >5 fetch/giờ → `FETCH_THROTTLED` → kết hợp A2 là mất RC. Đặt 3600 release, 10 chỉ khi host debuggable. |
| A4 | Trung | `InterAdProvider.kt:170-247` | Không có guard `showInProgress` như reward. Hai `showAd` cùng config chồng nhau (double-tap trong 500ms min-loading): cả hai gán `ad.adEventCallback`, cái sau đè cái trước → continuation của lần gọi đầu **không bao giờ resume**, coroutine caller treo. |
| A5 | Trung | `InterAdProvider.kt:203-230`, `RewardAdProvider.kt:229-259` | Callback GMA chạy trên thread nền (chính comment trong code ghi nhận). `reloadAd`/`clearConsumedAd` → mutate `inters`/`Rewards` (`mutableMapOf`) ngoài main, đua với `loadAd` trên main. Hop về main (`scope.launch { … }`) như đã làm với `onShow`. |
| A6 | Trung | `AdsManagerProvider.kt:158` | `loadAndShowInterAd` phát request **trước** khi kiểm frequency cap → tap bị cap vẫn tốn 1 request không có impression. Kiểm `canShowInterNow()` trước `loadInterAd`. |
| A7 | Trung | `AdsGraph.install` → `RemoteConfigProvider.init` | `FirebaseRemoteConfig.getInstance()` ném `IllegalStateException` nếu app chưa có `google-services.json` → crash ngay `Application.onCreate`. SDK nên fail-soft: `FirebaseApp.getApps(ctx).isEmpty()` → log rõ + chạy bằng asset default. |
| A8 | Thấp | `AdsBannerProvider.kt:136` | `isCollapsible` là no-op từ 1.0.0 nhưng vẫn nằm trên API public + docs. Bỏ tham số hoặc wire thật. |
| A9 | Thấp | `AdsManagerProvider.kt:77` | `resetInterTimer()` không có trên interface `AdsManagerService` → host không gọi được. |
| A10 | Thấp | `ConsentProvider.kt:7` | Import `OpenAdProvider.Companion.TAG` → log consent mang tag "OpenAdProvider". |
| A11 | Thấp | `GoogleMobileAdsConsentManager.kt:52` | Hash test device hard-code của một máy cụ thể trong SDK dùng chung. Đưa vào `AdsConfig`. |
| A12 | Thấp | `OpenAdProvider.kt:174,188`, `AdPoolManager.kt:142` | Dùng wall clock (`Date`, `currentTimeMillis`) cho hết hạn 4h / backoff; đổi giờ hệ thống làm lệch. Dùng `elapsedRealtime` như chỗ khác. |
| A13 | Thấp | `InterAdProvider.kt:260`, `RewardAdProvider.kt:307`, `context` ctor param inter/reward | `RETRY_COUNT` và `context` không dùng. Tên `Rewards`, `RewardedAd` viết hoa sai convention. |

## B. Nâng cấp để tích hợp dễ hơn (DX)

1. **Sửa A1 + A7 + A2** — ba thứ khiến người tích hợp lần đầu "làm đúng README mà vẫn sai".
2. **Bật `explicitApi()` + `internal` cho provider.** Hiện `AdsManagerProvider`, `InterAdProvider`, `RemoteFlagAdsDataStore`… đều public, ctor có tham số `_bannerAdService`. Bề mặt public nên chỉ còn: `AdsGraph`, `AdsConfig`, `AdsManagerService`, `*AdConfig`, view template, extension.
3. **Gọn API show:** `showInterAd(config, placement)` có hai khái niệm placement (config.placement để load, `placement` để gate) — gây nhầm. Bỏ tham số thứ hai, gate theo `config.placement`. `InterAdConfig.reload` cho default `true`. Thêm overload ngắn `ads.showInter("inter_next")` / `ads.loadAndShowReward("reward_x") { … }`.
4. **Ẩn `WeakReference<Activity>`** khỏi interface (`topActivity`, `showAd(activity: WeakReference…)`); SDK đã tự track top activity.
5. **Premium gate bằng lambda** `AdsConfig(isPremium = { billing.isPro })` thay vì bắt implement `AdsDataStore` có `var isPurchased` kỳ lạ.
6. **Firebase optional.** Nhiều app nhỏ không dùng Firebase; SDK vẫn chạy được chỉ với asset JSON. Crashlytics chỉ dùng cho 1 `recordException` → `compileOnly` + reflection check.
7. **Sample app module** (`:sample`) tiêu thụ AAR như host thật: kiểm luôn ProGuard rule, manifest merge, và là nơi dev copy code. Hiện `samples/` chỉ có JSON.
8. **CI GitHub Actions:** `assembleRelease` + `test` + `publishToMavenLocal` để bắt lỗi JitPack trước khi tag.
9. **AdMob app id qua manifestPlaceholders** (`${admobAppId}`) trong manifest SDK → host chỉ set 1 dòng Gradle, bớt bước dễ sai (`~` vs `/`).
10. **Compose wrapper** cho native/banner (`NativeAdSlot(placement)`), vì app mới đa số Compose.
11. **Java interop:** biến thể callback cho các `suspend fun`, `@JvmOverloads` trên config.
12. **Test cho waterfall provider** (inter/reward/open) bằng fake `AdUnitCatalog` + clock; hiện chỉ có 2 test file cho budget/catalog.
13. **Logging:** cho phép host tắt/đổi tag (`AdsConfig.logger`), Timber tag hiện là tên class nên lệnh `adb logcat -s` trong docs không lọc được.

## C. Từ agent /code-review (commit docs 0a31b05)

- `README.md:28` ghim `1.0.2` trong khi build là `1.0.6` → copy từ README thì ví dụ 1.0.3+ không compile.
- Docs không nói overlay trắng full-screen sẽ đè lên **SplashActivity** của app khi dùng ví dụ splash (`isShowLoading` default true); cần ghi rõ `isShowLoading = false` cho splash.
- Docs hứa `isCollapsible = true` ra collapsible banner (A8).
- Lệnh `adb logcat -s NativeAds AdPool …` dùng prefix message, không phải Timber tag → không lọc được gì.
- `isShowLoading = false` cũng **bỏ luôn** khoảng chờ 500ms chống bấm nhầm, docs nói ngược.
- "15 giây" trong bảng init-gate không phải thứ `timeOut` chỉnh được; spinner bị chặn bởi `config.timeOut` (6s).
- `minLoadingMs = 0` vẫn hiện dialog trắng 1 frame → flash; công tắc thật là `isShowLoading`.
- Version plugin google-services/crashlytics trong docs bị hạ (4.5.0→4.4.4, 3.0.7→3.0.6) không rõ lý do.
- CSS `.box` không match phần tử nào → mọi callout trong PDF mất padding/heading.
- Câu "loadAndShow vẫn tôn trọng frequency cap như showInterAd" sai nửa đầu (A6).

## Câu hỏi chưa chốt
- A1: có host nào đang dựa vào việc debug build **không** ép test id (tức vô tình chạy id thật ở debug) không? Sửa xong hành vi debug sẽ đổi.
- B6: Firebase có thật sự bắt buộc với mọi app của team, hay chỉ vì app đầu tiên dùng?

## D. Trạng thái sau khi sửa (1.1.0, chưa commit)

Đã sửa: A1–A13, B1–B3 (một phần B3: giữ tham số `placement` nhưng mặc định gate theo `config.placement`, thêm overload ngắn), B5, B6 (Firebase + Crashlytics tuỳ chọn), B8 (CI), B11 (`@JvmOverloads`), B13 (docs lệnh log), toàn bộ mục C (docs + PDF sinh lại, 20 trang).

Chưa làm, lý do:
- B4 ẩn `WeakReference<Activity>`: đổi chữ ký interface public, phá call site `topActivity.get()` trong docs của mọi app host. Để dành cho bản 2.0.
- B7 sample app module: repo là single-project để JitPack cho coordinate ngắn (`com.github.<user>:freshness-ads`); thêm `:sample` là thành multi-module và coordinate đổi. Cần quyết định riêng.
- B9 `manifestPlaceholders` cho AdMob app id: host nào chưa set placeholder là manifest merge fail — phá mọi app đang chạy.
- B10 Compose wrapper: kéo thêm dependency Compose vào SDK cho cả app không dùng Compose.
- B12 test cho provider waterfall: `InterstitialAd.load` là static của GMA, cần Robolectric + fake SDK; ngoài phạm vi lần này.
