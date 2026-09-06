plugins {
    alias(libs.plugins.android.library)
    `maven-publish`
}

// JitPack resolves com.github.<user>:<repo>:<tag>, so the group has to be exactly
// com.github.<user> and the artifact id the repository name. Publishing under any
// other coordinate builds fine locally and then 404s on JitPack.
group = "com.github.vuhuyvqh2112"
version = "1.0.2"

android {
    namespace = "com.freshness.ads"
    // androidx.core 1.19.0 refuses to compile below API 37.
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")

        // Debug build mặc định ép test id của Google (không phụ thuộc inventory thật). Đổi thành true
        // để debug dùng ID THẬT + waterfall thật — bắt buộc khi muốn kiểm tra hành vi rớt tier, vì với
        // test id thì tier nào cũng fill và không bao giờ rớt xuống tier sau. KHÔNG ảnh hưởng release.
        buildConfigField("boolean", "USE_REAL_IDS_IN_DEBUG", "false")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
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
    implementation(libs.androidx.constraintlayout)
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

    // Firebase: app host phải áp cùng BoM để hai bên không lệch version.
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.config)
    // Crashlytics: AdsCrashGuard ghi lại crash nội bộ của UMP dưới dạng non-fatal.
    implementation(libs.firebase.crashlytics)

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
