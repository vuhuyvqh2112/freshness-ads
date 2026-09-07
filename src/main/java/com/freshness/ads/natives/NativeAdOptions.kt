package com.freshness.ads.natives

import com.google.android.libraries.ads.mobile.sdk.common.AdChoicesPlacement
import com.google.android.libraries.ads.mobile.sdk.common.VideoOptions
import com.google.android.libraries.ads.mobile.sdk.nativead.NativeAd
import com.google.android.libraries.ads.mobile.sdk.nativead.NativeAdRequest

/**
 * Tuỳ chọn request native, áp cho mọi placement qua `AdsConfig.nativeAdOptions` và ghi đè được từng
 * placement trong `ads_id_config` (`videoMuted`, `mediaAspectRatio`, `adChoicesPlacement`).
 *
 * @param videoMuted native video bắt đầu KHÔNG tiếng. Mặc định true: ad tự phát tiếng giữa feed là
 *   thứ người dùng gỡ app vì nó, và Google cũng khuyến nghị mute.
 * @param mediaAspectRatio tỉ lệ media ưu tiên — khớp với template đang dùng để ảnh không bị cắt.
 * @param adChoicesPlacement góc đặt icon AdChoices.
 */
data class NativeAdOptions @JvmOverloads constructor(
    val videoMuted: Boolean = true,
    val mediaAspectRatio: NativeMediaAspectRatio = NativeMediaAspectRatio.ANY,
    val adChoicesPlacement: AdChoicesCorner = AdChoicesCorner.TOP_RIGHT,
)

/** Tên viết trong `ads_id_config.placements.<key>.mediaAspectRatio` là tên enum, không phân biệt hoa thường. */
enum class NativeMediaAspectRatio(internal val sdk: NativeAd.NativeMediaAspectRatio) {
    ANY(NativeAd.NativeMediaAspectRatio.ANY),
    LANDSCAPE(NativeAd.NativeMediaAspectRatio.LANDSCAPE),
    PORTRAIT(NativeAd.NativeMediaAspectRatio.PORTRAIT),
    SQUARE(NativeAd.NativeMediaAspectRatio.SQUARE),
    ;

    companion object {
        fun from(name: String?): NativeMediaAspectRatio? = entries.firstOrNull { it.name.equals(name, ignoreCase = true) }
    }
}

/** Tên viết trong `ads_id_config.placements.<key>.adChoicesPlacement` là tên enum, không phân biệt hoa thường. */
enum class AdChoicesCorner(internal val sdk: AdChoicesPlacement) {
    TOP_LEFT(AdChoicesPlacement.TOP_LEFT),
    TOP_RIGHT(AdChoicesPlacement.TOP_RIGHT),
    BOTTOM_RIGHT(AdChoicesPlacement.BOTTOM_RIGHT),
    BOTTOM_LEFT(AdChoicesPlacement.BOTTOM_LEFT),
    ;

    companion object {
        fun from(name: String?): AdChoicesCorner? = entries.firstOrNull { it.name.equals(name, ignoreCase = true) }
    }
}

internal fun NativeAdRequest.Builder.apply(options: NativeAdOptions): NativeAdRequest.Builder = apply {
    setVideoOptions(VideoOptions.Builder().setStartMuted(options.videoMuted).build())
    setMediaAspectRatio(options.mediaAspectRatio.sdk)
    setAdChoicesPlacement(options.adChoicesPlacement.sdk)
}
