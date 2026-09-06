# Freshness Ads

SDK quảng cáo AdMob cho Android — waterfall theo tier, native pool, consent UMP, cấu hình bằng Remote Config.

Xây trên **Google Mobile Ads Next-Gen SDK 1.2.1**. minSdk 26.

📄 **[Hướng dẫn sử dụng đầy đủ (PDF)](docs/huong-dan-su-dung-freshness-ads.pdf)** — 15 trang, có ví dụ cho từng format.

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
implementation("com.github.vuhuyvqh2112:freshness-ads:1.0.2")
```

Không cần khai thêm GMA SDK, UMP, Unity adapter hay Firebase Config — POM đã mang theo hết.

### Yêu cầu

| | |
|---|---|
| Firebase | Bắt buộc (Remote Config + Crashlytics). Thêm `google-services.json` và hai plugin Google Services / Crashlytics. |
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
            ),
        )
    }
}
```

Sau đó `AdsGraph.adsManager` là đầu vào duy nhất:

```kotlin
val ads = AdsGraph.adsManager

// Interstitial
ads.loadInterAd(config)
ads.showInterAd(config, placement = "inter_next")

// Native
ads.nativeAdService.preloadIfEmpty(context, key = "native_detail", placement = "native_detail")
bindNativeAdFromCache(binding.nativeAd, ads.nativeAdService, "native_detail", releaseOnDestroy = true)

// Banner — container để wrap_content, xem ghi chú chiều cao ở dưới
ads.bannerAdService.loadBannerAds(this, binding.bannerContainer, "banner_home", isCollapsible = false)

// Rewarded
ads.showRewardAd(config, placement = "reward_unlock", onShow = { … }, onReward = { … })
```

## Cấu hình

Ad unit id **không nằm trong code**. Chúng ở `assets/ads_id_config.json`, và Firebase Remote Config (key `ads_id_config`) ghi đè được **từng placement** — đổi id, tắt một chỗ đặt ad, siết thời gian chờ, tất cả không cần release.

```json
"native_home": {
  "format": "native",
  "enable": true,
  "ids": [
    { "id": "ca-app-pub-xxx/high-floor", "enable": true },
    { "id": "ca-app-pub-xxx/all-price",  "enable": true }
  ]
}
```

Thứ tự trong `ids` là thứ tự waterfall: id đầu không fill thì tự xuống id kế.

Tên placement do app tự đặt. Riêng hai key SDK tự gọi (`open_all`, `inter_splash`) phải khớp với `AdsConfig`.

## Hai điều dễ hỏng nhất

**Mediation Unity Ads** — phải tạo mediation group trên AdMob **trước khi phát hành**. Chưa có group thì adapter init bằng app id giả và giữ luôn callback init của GMA: `MobileAds.initialize` mất ~30 giây thay vì ~2,2 giây, đủ để interstitial splash không bao giờ kịp hiện. Triệu chứng nhìn giống lỗi code nhưng nguyên nhân ở cấu hình AdMob.

Dấu hiệu nhận biết trong logcat — `SKIP banner … (SDK not ready)` hoặc `WATERFALL skip … (SDK not ready)` ngay trước dòng `MobileAds (next-gen) initialized`. Banner/native chờ tới 60 giây nên vẫn lên sau khi init xong, nhưng interstitial và rewarded chỉ chờ 15 giây (có người dùng đang đợi sau spinner) nên vẫn mất. Sửa ở AdMob, đừng nới thời gian chờ.

**Container banner phải `wrap_content`.** Từ 1.0.1 banner dùng *large* anchored adaptive (bản Google thay cho API cũ đã deprecated) — **cao hơn trước**. Container nào khoá `layout_height` cứng sẽ cắt mất quảng cáo.

**Pool size phải bằng số slot hiển thị cùng lúc.** Màn một slot mà khai `2` thì ad thứ hai không có đường ra — mỗi phiên phát dư một request không bao giờ đổi được impression, show rate tụt thẳng.

## ProGuard

Rule đi kèm AAR, tự áp khi minify. Không phải chép gì.

## Test

Debug build mặc định ép test id của Google, nên dev không bao giờ vô tình bấm vào quảng cáo thật. Cần waterfall thật thì `AdsGraph.adUnitCatalog.useRealIds = true` (release bỏ qua cờ này).

## License

Apache 2.0
