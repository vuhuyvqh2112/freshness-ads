plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
    `maven-publish`
}

// Cùng group với root để JitPack phục vụ artifact này cạnh freshness-ads:
// implementation("com.github.vuhuyvqh2112:freshness-ads-compose:<tag>")
group = "com.github.vuhuyvqh2112"
version = rootProject.version

android {
    namespace = "com.freshness.ads.compose"
    compileSdk {
        version = release(37)
    }
    defaultConfig {
        minSdk = 26
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

dependencies {
    // `api`: app dùng module này chạm thẳng AdsGraph / NativeAdResult / BaseLoadingNativeAdView.
    api(project(":"))
    implementation(libs.androidx.compose.runtime)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
}

publishing {
    publications {
        register<MavenPublication>("release") {
            artifactId = "freshness-ads-compose"
            afterEvaluate { from(components["release"]) }
            pom {
                name.set("Freshness Ads Compose")
                description.set("Jetpack Compose wrappers for Freshness Ads: NativeAd, BannerAd, isFullScreenAdShowing.")
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
