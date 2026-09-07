# Freshness Ads

SDK quảng cáo AdMob cho Android — waterfall theo tier, native pool, consent UMP, cấu hình bằng Remote Config.

Xây trên **Google Mobile Ads Next-Gen SDK 1.2.1**. minSdk 26.

📄 **[Hướng dẫn sử dụng đầy đủ (PDF)](docs/huong-dan-su-dung-freshness-ads.pdf)** — 27 trang, có ví dụ cho từng format và lịch sử thay đổi theo phiên bản.

---

## Cài đặt

`settings.gradle.kts`:

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}
```

`app/build.gradle.kts`:

```kotlin
implementation("com.github.vuhuyvqh2112:freshness-ads:1.1.0")
```

App dùng Jetpack Compose thêm `implementation("com.github.vuhuyvqh2112:freshness-ads-compose:1.1.0")` (kéo theo SDK chính).

Không cần khai thêm GMA SDK, UMP, Unity adapter hay Firebase Config — POM đã mang theo hết.

### Yêu cầu

| | |
|---|---|
| Firebase | Tuỳ chọn (từ 1.1.0). Có `google-services.json` + plugin Google Services thì id/setting ghi đè được bằng Remote Config; thêm plugin Crashlytics thì crash nội bộ của UMP được ghi non-fatal. Không có Firebase thì SDK chạy bằng file cấu hình trong assets. |
| AdMob app id | `<meta-data android:name="com.google.android.gms.ads.APPLICATION_ID" …>` trong manifest. Là **app id** (dấu `~`), không phải ad unit id (dấu `/`). |
| File cấu hình | Chép [`samples/ads_id_config.json`](samples/ads_id_config.json) vào `app/src/main/assets/`. |

## Dùng

```kotlin
class MyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AdsGraph.install(
            application = this,
            config = AdsConfig(
                openAdPlacement = "open_all",
                splashInterstitialPlacement = "inter_splash",
                nativePools = mapOf("native_home" to 1),
                // đã mua gói bỏ quảng cáo → mọi format tắt, đọc lại mỗi lần nên có hiệu lực ngay
                isPremium = { billing.hasRemoveAds },
            ),
        )
    }
}
```

Sau đó `AdsGraph.adsManager` là đầu vào duy nhất:

```kotlin
val ads = AdsGraph.adsManager

// Interstitial — preload trước rồi hiện (config mặc định, chỉ cần tên placement)
ads.loadInterAd("inter_next")
ads.showInterAd("inter_next")

// …hoặc nạp và hiện trong một lệnh, cho chỗ không preload trước được
ads.loadAndShowInterAd("inter_next")

// Cần chỉnh timeout/retry thì khai một InterAdConfig và dùng lại nó ở cả preload lẫn show —
// config chính là key của cache
val interNext = InterAdConfig("inter_next", timeOut = 10_000L)
ads.loadInterAd(interNext); ads.showInterAd(interNext)

// Native
ads.nativeAdService.preloadIfEmpty(context, key = "native_detail", placement = "native_detail")
bindNativeAdFromCache(binding.nativeAd, ads.nativeAdService, "native_detail", releaseOnDestroy = true)

// Banner — container để wrap_content, xem ghi chú chiều cao ở dưới
ads.bannerAdService.loadBannerAds(this, binding.bannerContainer, "banner_home")

// Rewarded — thường không preload, bấm rồi mới nạp
ads.loadAndShowRewardAd("reward_unlock", onReward = { … })

// Rewarded interstitial — placement khai "format": "rewardedInter"
ads.loadAndShowRewardedInterAd("reward_inter_level_end", onReward = { … })

// Cần biết vì sao không hiện (cap / no-fill / tắt…) thì dùng bản Outcome
val outcome: AdShowOutcome = ads.showInterAdOutcome(interNext)

// Pause video/nhạc khi bất kỳ ad toàn màn nào đang hiện
ads.isFullScreenAdShowing.collect { showing -> player.playWhenReady = !showing }
```

## Analytics và doanh thu

```kotlin
AdsGraph.addListener(object : AdsListener {
    override fun onAdPaid(placement: String, format: AdFormat, revenue: AdRevenue) {
        adjust.trackAdRevenue("admob_sdk", revenue.value, revenue.currencyCode)
    }
})
```

Mọi format, mỗi impression một `onAdPaid`, cùng loaded / failed / impression / click / showed / closed / rewarded. App có Firebase Analytics thì SDK tự gửi sự kiện chuẩn `ad_impression` (tắt bằng `logAdImpressionToFirebase = false`).

Template native từ layout XML của app: `LoadingNativeAdTemplateView(context, R.layout.my_native)` với id quy ước (`native_ad_view`, `media_view`, `icon`, `primary`, `body`, `cta`…), không cần subclass.

Compose: `NativeAd(key, placement, template = { LoadingNativeAdHomeView(it) })`, `BannerAd(placement)`, `rememberFullScreenAdShowing()`.

Native video mặc định **tắt tiếng**; chỉnh bằng `AdsConfig(nativeAdOptions = NativeAdOptions(videoMuted = false, mediaAspectRatio = …))` hoặc per placement trong JSON. Slot native đơn tự nạp ad mới sau khi user bấm vào ad rồi quay lại, và sau khi video kết thúc (`nativeRefill`). Interstitial/rewarded đã nạp hết hạn sau 1 giờ và được nạp lại. Banner có `BannerSize.ANCHORED / INLINE / MREC`. App-open không đè lên activity trong `openAdExcludedActivities`.

`onShow` và `onReward` được gọi trên main thread, chạm UI thẳng trong đó được.

## Màn chờ

SDK tự vẽ spinner che toàn màn trong lúc nạp và **giữ ít nhất 500ms trước khi quảng cáo toàn màn bung ra**, kể cả khi ad đã preload sẵn. Khoảng chờ đó là thứ [chính sách AdMob khuyến nghị](https://support.google.com/admob/answer/6201350) — nó cho ngón tay đang bấm kịp dừng lại, thay vì cú chạm rơi thẳng vào quảng cáo vừa xuất hiện.

Chỉnh bằng `minLoadingMs` trong `InterAdConfig` / `RewardAdConfig`. Muốn giao diện riêng thì `AdsConfig(showDefaultLoadingUi = false)` rồi tự observe `AdsGraph.adLoading.isLoading` — đừng bỏ hẳn khoảng chờ. Riêng **splash** đặt `isShowLoading = false` trong config của nó, nếu không màn chờ trắng sẽ phủ lên splash của app.

## Cấu hình

Ad unit id **không nằm trong code**. Chúng ở `assets/ads_id_config.json`, và Firebase Remote Config (key `ads_id_config`) ghi đè được **từng field của từng placement** — đổi id, tắt một chỗ đặt ad, bật tầng high-floor, siết thời gian chờ, tất cả không cần release và chỉ cần gửi phần thay đổi.

```json
"native_home": {
  "format": "native",
  "enable": true,
  "hf": false,
  "ids": [
    "ca-app-pub-xxx/high-floor",
    { "id": "ca-app-pub-xxx/mid", "enable": false },
    "ca-app-pub-xxx/all-price"
  ]
}
```

Thứ tự trong `ids` là thứ tự waterfall: id đầu không fill thì tự xuống id kế. Id **cuối** là all-price; `hf: false` bỏ mọi id trước nó (thiếu `hf` là dùng hết). Trên Remote Config, `{"placements":{"native_home":{"enable":false}}}` là đủ để tắt một chỗ, không phải paste lại `ids`.

Key riêng của app đặt trong `settings` và đọc cùng một lần fetch với id quảng cáo:

```kotlin
val freeEpisodes = ads.remoteConfig.long("free_episodes_count", 3L)
```

Debug build in bảng catalog đang có hiệu lực sau mỗi lần nạp Remote Config (`AdsGraph.adUnitCatalog.describe()`), để đối chiếu với console.

Tên placement do app tự đặt. Riêng hai key SDK tự gọi (`open_all`, `inter_splash`) phải khớp với `AdsConfig`.

## Hai điều dễ hỏng nhất

**Mediation Unity Ads** — phải tạo mediation group trên AdMob **trước khi phát hành**. Chưa có group thì adapter init bằng app id giả và giữ luôn callback init của GMA: `MobileAds.initialize` mất ~30 giây thay vì ~2,2 giây, đủ để interstitial splash không bao giờ kịp hiện. Triệu chứng nhìn giống lỗi code nhưng nguyên nhân ở cấu hình AdMob.

Dấu hiệu nhận biết trong logcat — `SKIP banner … (SDK not ready)` hoặc `WATERFALL skip … (SDK not ready)` ngay trước dòng `MobileAds (next-gen) initialized`. Banner/native chờ tới 60 giây nên vẫn lên sau khi init xong, nhưng interstitial và rewarded chỉ chờ 15 giây (có người dùng đang đợi sau spinner) nên vẫn mất. Sửa ở AdMob, đừng nới thời gian chờ.

**Container banner phải `wrap_content`.** Từ 1.0.1 banner dùng *large* anchored adaptive (bản Google thay cho API cũ đã deprecated) — **cao hơn trước**. Container nào khoá `layout_height` cứng sẽ cắt mất quảng cáo.

**Pool size phải bằng số slot hiển thị cùng lúc.** Màn một slot mà khai `2` thì ad thứ hai không có đường ra — mỗi phiên phát dư một request không bao giờ đổi được impression, show rate tụt thẳng.

## ProGuard

Rule đi kèm AAR, tự áp khi minify. Không phải chép gì.

## Test

Khi **app host** chạy bản debuggable, SDK ép test id của Google nên dev không bao giờ vô tình bấm vào quảng cáo thật. Cần waterfall thật thì `AdsConfig(useRealIdsInDebug = true)` (release bỏ qua cờ này). Form consent trên máy test: `AdsConfig(consentTestDeviceIds = listOf("<hash UMP in ra logcat>"))`.

Log lọc theo tên class (Timber tag): `adb logcat | grep -E "InterAdProvider|RewardAdProvider|OpenAdProvider|NativeAdmobManager|AdPoolManager|AdBannerProvider|ConsentProvider|AdsManagerProvider"`.

## License

Apache 2.0
