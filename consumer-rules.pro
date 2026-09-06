# Rule đi kèm module :ads — tự áp vào app khi minify. Đặt ở đây thay vì app/proguard-rules.pro
# để app nào dùng module cũng có sẵn, không phải copy tay.

# Model của payload `ads_id_config`: Gson map theo TÊN FIELD nên obfuscate là parse ra toàn null.
# Đã có @Keep trên từng data class, giữ thêm ở đây cho chắc khi rule của androidx.annotation
# không được áp (ví dụ app tắt default proguard file).
-keep class com.freshness.ads.config.AdIdConfig { *; }
-keep class com.freshness.ads.config.AdIdEntry { *; }
-keep class com.freshness.ads.config.AdPlacementConfig { *; }
-keep class com.freshness.ads.config.AdSettings { *; }
-keep class com.freshness.ads.remoteconfig.RemoteConfig { *; }

# Native ad view được inflate từ XML (thẻ <com.freshness.ads.natives.*>) nên chỉ có tên trong
# layout tham chiếu tới — R8 không thấy call site của constructor 2 tham số.
-keep public class * extends android.view.View {
    public <init>(android.content.Context, android.util.AttributeSet);
}

# GMA Next-Gen SDK + mediation adapter: adapter được load bằng reflection theo tên class.
-keep class com.google.android.libraries.ads.mobile.sdk.** { *; }
-keep class com.google.ads.mediation.** { *; }
-dontwarn com.google.ads.mediation.**

# Unity Ads
-keep class com.unity3d.ads.** { *; }
-keep class com.unity3d.services.** { *; }
-dontwarn com.unity3d.**

# Liftoff Monetize (Vungle)
-keep class com.vungle.ads.** { *; }
-dontwarn com.vungle.ads.**

# WorkManager đi kèm Unity Ads và GMA (androidx.work:work-runtime:2.7.0), app không tự dùng.
# Nó dựng một Room database từ androidx.startup, tức là TRƯỚC cả Application, và Room tìm class
# `*_Impl` sinh lúc build bằng reflection. R8 không thấy call site nào của constructor rỗng đó nên
# xoá đi, Room ném InstantiationException và release crash ngay khi mở app. Rule đi kèm work 2.7.0
# quá cũ để phủ được chuyện này.
-keep class * extends androidx.room.RoomDatabase { <init>(); }
