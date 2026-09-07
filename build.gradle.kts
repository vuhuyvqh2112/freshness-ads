plugins {
    alias(libs.plugins.android.library)
    `maven-publish`
}

// JitPack resolves com.github.<user>:<repo>:<tag>, so the group has to be exactly
// com.github.<user> and the artifact id the repository name. Publishing under any
// other coordinate builds fine locally and then 404s on JitPack.
group = "com.github.vuhuyvqh2112"
version = "1.1.0"

android {
    namespace = "com.freshness.ads"
    // androidx.core 1.19.0 refuses to compile below API 37.
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    // KHÔNG bật buildConfig: `BuildConfig.DEBUG` của thư viện luôn false trong app host (AAR publish
    // là bản release), nên mọi nhánh "debug" trong SDK đọc cờ debuggable của app host thay vì nó.
    buildFeatures {
        viewBinding = true
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    // `api`: mọi view template public (BaseLoadingNativeAdView…) extends ConstraintLayout, nên app
    // (và module :compose) phải thấy được supertype đó để gọi setNativeAd / parent.
    api(libs.androidx.constraintlayout)
    implementation(libs.material)
    // ProcessLifecycleOwner (app-open ad): dùng nội bộ, không lộ ra chữ ký nào.
    implementation(libs.androidx.lifecycle.process)
    // `api` vì lifecycle nằm trong API SURFACE: bindNativeAdFromCache/observeNativeAd là extension
    // public trên LifecycleOwner. Để `implementation` thì app host không gọi được nếu chưa tự khai
    // lifecycle.
    api(libs.androidx.lifecycle.runtime.ktx)
    // Fragment.weakActivity trong AdsExtensions.
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.shimmer)
    implementation(libs.timber)
    implementation(libs.gson)

    // AdMob - GMA Next-Gen SDK. `api` vì call site ở app chạm trực tiếp NativeAd/RewardItem.
    api(libs.ads.mobile.sdk)
    // UMP (User Messaging Platform) cho consent GDPR.
    api(libs.user.messaging.platform)

    // Firebase là TUỲ CHỌN với app host: thiếu Firebase thì SDK chạy bằng assets/ads_id_config.json
    // (xem RemoteConfigProvider). BoM đi cùng để app có Firebase không lệch version.
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.config)
    // Crashlytics chỉ dùng cho MỘT lệnh recordException trong AdsCrashGuard, nên compileOnly: để
    // implementation là ép mọi app host phải áp plugin Crashlytics, không thì crash lúc khởi động
    // (eager component "build ID is missing"). Có class lúc chạy thì ghi non-fatal, không thì thôi.
    compileOnly(platform(libs.firebase.bom))
    compileOnly(libs.firebase.crashlytics)
    // Analytics cũng compileOnly: FirebaseAdImpressionLogger gửi `ad_impression` nếu app có, không thì thôi.
    compileOnly(libs.firebase.analytics)

    // Mediation Unity Ads. Không cần khai gì trong manifest và không cần code init: adapter tự nhận
    // cấu hình từ mediation group phía AdMob lúc chạy.
    //
    // ĐIỀU KIỆN BẮT BUỘC: phải có mediation group trên AdMob gắn Unity vào từng ad unit TRƯỚC khi
    // phát hành. Chưa có group thì adapter nhận cấu hình rỗng, nó init bằng một app id giả và GIỮ
    // LUÔN callback init của GMA — MobileAds.initialize mất ~30s thay vì ~2.2s, đủ để interstitial
    // splash không bao giờ kịp hiện. Bug đó không nằm ở adapter, nó nằm ở việc thiếu cấu hình phía
    // AdMob.
    //
    // exclude GMA legacy: adapter khai phụ thuộc play-services-ads (SDK đời cũ), để nó vào cùng
    // ads-mobile-sdk next-gen là hai SDK quảng cáo cùng tồn tại trong một APK.
    implementation(libs.mediation.unity) {
        exclude(group = "com.google.android.gms", module = "play-services-ads")
        exclude(group = "com.google.android.gms", module = "play-services-ads-lite")
    }
    implementation(libs.unity.ads)

    testImplementation(libs.junit)

    // unity-ads kéo adquality-sdk bằng dải động `(,10.0.0)`. Constraint (không phải implementation
    // trực tiếp) để vừa ghim được version vừa không tự thêm một phụ thuộc mà SDK không dùng đến —
    // constraint theo vào Gradle metadata nên app host cũng hưởng version đã ghim.
    constraints {
        implementation(libs.unity.adquality) {
            because("dải động (,10.0.0) khiến build không reproducible")
        }
    }
}

publishing {
    publications {
        register<MavenPublication>("release") {
            artifactId = "freshness-ads"
            afterEvaluate { from(components["release"]) }

            pom {
                name.set("Freshness Ads")
                description.set(
                    "AdMob (GMA Next-Gen) wrapper: waterfall theo tier, native pool, " +
                        "consent UMP, cấu hình bằng Remote Config."
                )
                licenses {
                    license {
                        name.set("The Apache License, Version 2.0")
                        url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                    }
                }
            }
        }
    }
}
