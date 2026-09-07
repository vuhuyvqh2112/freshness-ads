# So sánh phần ads: freshness-ads 1.0.7 vs modnix core-ads 1.6.0 (GoodDrama)

Nguồn: javadoc `core-ads-api`, `core-ads`, `core-ads-compose`, `tutorial` 1.6.0 trong gradle cache; code `MOD082-GoodDrama/ui/ads/*`. Đã kiểm GMA next-gen 1.2.1 (classes.jar) có `AdEventCallback.onAdPaid(AdValue)`, `NativeAdRequest.Builder.setVideoOptions/setMediaAspectRatio/setAdChoicesPlacement`, `RewardedInterstitialAd`, `AdSize.getInlineAdaptiveBannerAdSize`, `MobileAds.setRequestConfiguration`.

## Tương đương, không cần làm
- Waterfall + cache + `loadAndShow*` có timeout; consent gate trước mọi request; premium provider; per-placement enable.
- Native pool `preload(n)` + `poll()` ≈ `AdPoolManager.getAd/releaseAd`; late-fill giữ lại.
- `FullScreenAdCallback.onNextAction` (chạy tiếp đúng 1 lần) ≈ `suspend showInterAd` luôn trả về. `canOverlapNextScreen` ≈ điều hướng trong `onShow`.
- Frequency cap: modnix KHÔNG có (GoodDrama tự viết `InterstitialAds.COOLDOWN_MS`), freshness đã có sẵn.
- Màn chờ trước ad: cả hai có (modnix `PrepareLoadingAdsDialog` + `loadingDialogMinDuration`).

## Đáng nâng cấp (theo giá trị giảm dần)

| # | modnix có | freshness thiếu | Đề xuất |
|---|---|---|---|
| 1 | `AdTrackListener.onPaidImpression(placement, format, AdRevenue)` + `AdEventListener` (loaded/failed/impression/clicked/showed/closed/rewarded); helper Adjust/AppsFlyer/Firebase | **Không có hook nào** cho analytics / ad revenue. App không đo được ROAS, không gửi `ad_impression` lên Firebase | `AdsGraph.addListener(AdsListener)`: interface default-empty, gọi từ mọi provider; `onPaid(placement, format, AdRevenue(valueMicros, currency, precision))` lấy từ `onAdPaid(AdValue)`. Kèm `FirebaseAdRevenueLogger` optional (Firebase Analytics `compileOnly`). |
| 2 | Cache full-screen có TTL (`AdExpirationTracker`) | Inter/reward cache **không hết hạn**: ad nạp từ 2 giờ trước vẫn đem show → `onAdFailedToShow` (expired), user nhìn spinner rồi không có gì | TTL theo format: inter/reward 1h, app-open 4h (đã có). Hết hạn thì huỷ + nạp lại thay vì show. |
| 3 | `NativeAdOptions`: mute video, `mediaAspectRatio`, `adChoicesPlacement`; `setNativeRefillByAd` = reload sau khi video kết thúc / sau khi user click ad quay lại; reload on resume có debounce | Native request để mặc định: video **bật tiếng**, không chọn tỉ lệ media; không có cơ chế reload nào → một fill = một impression suốt phiên | `NativeAdOptions(videoMuted=true, mediaAspectRatio, adChoicesPlacement)` trong `AdsConfig` (mặc định toàn cục) + override per placement trong catalog JSON. Reload sau click-quay-lại và sau video-end cho slot đơn (`bindNativeAdFromCache(refill = true)`). |
| 4 | `AdResult.Error.{Disabled, PremiumUser, ConsentRequired, NotInitialized, LoadFailed, NotAvailable, ShowFailed, InvalidActivity}` | `showInterAd` trả `Boolean`; app không biết vì sao không hiện (cap? no-fill? tắt?) → không log/A-B được | Thêm `showInterAdResult()`/`showRewardAdResult()` trả sealed `AdShowOutcome`; bản Boolean giữ nguyên, delegate. |
| 5 | `FullScreenAdPresenter.isFullScreenAdShowing` (một cờ) | Ba flow riêng (`interAdService.isShowing`, `rewardAdService.isShowing`, `openAdService.isOpenAdShowing`); GoodDrama phải tự viết `AdPresenceState` để pause video | `AdsManagerService.isFullScreenAdShowing: StateFlow<Boolean>` = combine ba flow. |
| 6 | `ResumeAdExclusions.add(Activity::class)` + `skipNextResume()` | Chỉ có `setSkipOpenAd(true)` phải gọi trước mỗi lần rời app | `AdsConfig.openAdExcludedActivities` + `openAdService.excludeActivity()`; tự động không show khi top activity thuộc danh sách (billing, picker, ad activity của SDK khác). |
| 7 | Banner: `BannerAdSize.{ADAPTIVE, MEDIUM_RECTANGLE, LARGE_BANNER, BANNER}` + reload on resume | Chỉ một cỡ: large anchored adaptive | `BannerSize` enum: `ANCHORED` (mặc định, giữ hành vi), `INLINE` (feed/scroll), `MREC`. |
| 8 | Remote `enable_log_for_tester` | Log chỉ khi app debuggable; QA trên release không có gì để gửi | Đọc `settings.enableLogForTester` (đã có `settings` tự do) → plant Timber tree ở release. |
| 9 | Template native = **layout XML của app** + id quy ước (`NativeAdViewIds`), một class holder dùng chung | Mỗi template = 2 class Kotlin + 2 XML (13 template trong SDK); app muốn template riêng phải subclass `BaseNativeAdView` + `BaseLoadingNativeAdView` | `GenericNativeAdView(layoutRes)` bind theo id quy ước (`ad_headline`, `ad_body`, `ad_icon`, `ad_media`, `ad_cta`, `ad_advertiser`, `ad_rating`, `ad_badge`), có `LoadingNativeAdView` chung nhận `layoutRes` + `shimmerRes`. 13 template cũ giữ nguyên. |
| 10 | Rewarded interstitial | Không có format này | `RewardedInterstitialAd` next-gen có sẵn; copy `RewardAdProvider`. Làm khi có app cần. |
| 11 | `core-ads-compose`: `NativeAdView(config)`, `rememberNativeAdState`, `BannerAdView` | Không có Compose | Cần module riêng (`freshness-ads-compose`) vì Compose compiler; đổi repo sang multi-module → coordinate JitPack đổi. Quyết định riêng. |
| 12 | `NativeAfterFullScreen` (fake-inter native nối sau inter/reward cho user campaign), `PrepareAdSlot`, `AdRedundancyChecker` | Không có | Chiến thuật first-open riêng của modnix; freshness là SDK ads thuần, không kéo luồng FO vào. Không đề xuất. |

## Đề xuất thứ tự làm
Đợt 1 (nhỏ, giá trị cao): #1 listener + paid, #2 TTL, #5 cờ gộp, #8 log tester, #6 exclude activity.
Đợt 2: #3 NativeAdOptions + refill, #4 outcome, #7 banner size.
Đợt 3 (cần quyết định): #9 template generic, #10 rewarded inter, #11 Compose module.

## Câu hỏi chưa chốt
- #3: mặc định `videoMuted = true` cho mọi app (đổi hành vi hiện tại) hay giữ bật tiếng?
- #11: có chấp nhận chuyển repo sang multi-module (coordinate thành `com.github.<user>.freshness-ads:core` / `:compose`) không?

## Đã làm (1.1.0, chưa commit) — đợt 1 + đợt 2
- #1 `events/AdsListener.kt` (`AdsListener`, `AdRevenue`), `events/AdsEvents.kt` (bus nội bộ, dispatch main), `events/FirebaseAdImpressionLogger.kt` (`firebase-analytics` compileOnly). Bắn từ inter/reward/open/banner/native/pool. `AdsGraph.addListener/removeListener`, `AdsConfig.logAdImpressionToFirebase`.
- #2 `AdBudgets.FULL_SCREEN_AD_TTL_MS` = 1h; `InterAdRequest.loadedAt` / `RewardAdRequest.loadedAt`, `liveAd()` lọc ad quá hạn ở cả preload guard, late-fill và show.
- #3 `natives/NativeAdOptions.kt` (`videoMuted` mặc định true theo quyết định user, `NativeMediaAspectRatio`, `AdChoicesCorner`); catalog `nativeOptionsFor` + field `videoMuted/mediaAspectRatio/adChoicesPlacement` per placement; `NativeAdProvider.refill` sau click (ON_START) và `onVideoEnd`, cờ `AdsConfig.nativeRefill` + RC `settings.nativeRefill`.
- #4 `manager/AdShowOutcome.kt`; `*Service.showAdOutcome`, `AdsManagerService.showInterAdOutcome/loadAndShowInterAdOutcome/showRewardAdOutcome/loadAndShowRewardAdOutcome`; bản Boolean thành default `= outcome.shown`.
- #5 `AdsManagerService.isFullScreenAdShowing` (combine 3 flow, Eagerly).
- #6 `AdsConfig.openAdExcludedActivities`, `OpenAdService.excludeActivity/includeActivity/isExcluded`; kiểm ở `onAppForegrounded` và `showAdIfAvailable`.
- #7 `banner/BannerSize.kt` ANCHORED/INLINE/MREC, tham số `size` trên `loadBannerAds`.
- #8 `manager/AdsLogging.kt`; `RemoteConfigProvider` đọc `settings.enableLogForTester`.
- Test: +1 `nativeOptionsFor`. Tổng 34 pass, 0 warning. Docs mục 7/8/11/13/14b/17/19, README, sample JSON, PDF.

## Đợt 3 (1.1.0, chưa commit)
- #9 `natives/NativeAdTemplateView.kt` + `LoadingNativeAdTemplateView.kt`: bind layout XML của app theo id quy ước (`res/values/ids.xml`), attrs `adLayout`/`adShimmerLayout`, shimmer mặc định `loading_native_generic_shimmer.xml`.
- #10 `reward/RewardedKind.kt` (adapter RewardedAd / RewardedInterstitialAd), `RewardAdProvider` thành generic `<A>`; `AdFormat.REWARDED_INTERSTITIAL("rewardedInter")`; `AdsGraph.rewardedInterAdService`; `AdsManagerService.loadRewardedInterAd / showRewardedInterAd(Outcome) / loadAndShowRewardedInterAd(Outcome)`; gộp vào `isFullScreenAdShowing`, `reset`, guard app-open.
- #11 module `:compose` (`compose/`), artifact `com.github.vuhuyvqh2112:freshness-ads-compose` CÙNG group với root nên coordinate cũ không đổi; `NativeAd`, `BannerAd`, `rememberFullScreenAdShowing`. Root đổi `constraintlayout` sang `api` (supertype của view template). Đã kiểm `publishToMavenLocal` ra đủ 2 POM, compose POM phụ thuộc `freshness-ads:1.1.0`.
- Test 35 pass, 0 warning. Docs mục 2, 3, 8 (template), 12 (rewarded inter), 14c (Compose), changelog; README; sample JSON; PDF 27 trang. CI upload cả hai AAR.
