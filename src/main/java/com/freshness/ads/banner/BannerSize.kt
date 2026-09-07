package com.freshness.ads.banner

import android.content.Context
import android.content.res.Resources
import com.google.android.libraries.ads.mobile.sdk.banner.AdSize

/**
 * Cỡ banner. Mọi cỡ adaptive tự tính theo bề ngang màn hình.
 *
 * - [ANCHORED]: neo đỉnh/đáy màn, bản *large* anchored adaptive (mặc định, hành vi từ 1.0.1).
 *   Container phải `wrap_content` chiều cao.
 * - [INLINE]: đặt trong nội dung cuộn (feed, giữa bài). Cao hơn anchored, Google trả chiều cao theo
 *   nội dung ad — container cũng phải `wrap_content`.
 * - [MREC]: 300×250dp cố định, cho chỗ đặt dạng thẻ.
 */
enum class BannerSize {
    ANCHORED, INLINE, MREC;

    internal fun toAdSize(context: Context): AdSize {
        val displayMetrics = Resources.getSystem().displayMetrics
        val widthDp = (displayMetrics.widthPixels / displayMetrics.density).toInt()
        return when (this) {
            ANCHORED -> AdSize.getLargeAnchoredAdaptiveBannerAdSize(context, widthDp)
            INLINE -> AdSize.getCurrentOrientationInlineAdaptiveBannerAdSize(context, widthDp)
            MREC -> AdSize.MEDIUM_RECTANGLE
        }
    }
}
