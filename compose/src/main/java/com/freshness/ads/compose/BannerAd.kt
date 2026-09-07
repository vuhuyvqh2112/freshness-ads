package com.freshness.ads.compose

import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.viewinterop.AndroidView
import com.freshness.ads.banner.BannerSize
import com.freshness.ads.di.AdsGraph

/**
 * Banner trong Compose. Nạp theo waterfall của [placement], chiều cao do Google trả về (adaptive)
 * nên không đặt height cố định trong [modifier]. No-fill thì không compose gì.
 *
 * ```
 * BannerAd("banner_home", Modifier.fillMaxWidth())
 * BannerAd("banner_feed", Modifier.fillMaxWidth(), size = BannerSize.INLINE)
 * ```
 */
@Composable
fun BannerAd(
    placement: String,
    modifier: Modifier = Modifier,
    size: BannerSize = BannerSize.ANCHORED,
) {
    if (LocalInspectionMode.current) return
    val context = LocalContext.current
    var failed by remember(placement) { mutableStateOf(false) }
    val container = remember(placement) { FrameLayout(context) }

    LaunchedEffect(placement, size) {
        AdsGraph.bannerAdService.loadBannerAds(
            context = context,
            bannerView = container,
            placement = placement,
            size = size,
            onLoadSuccess = { failed = false },
            onLoadFail = { failed = true },
        )
    }

    if (failed) return

    AndroidView(
        factory = {
            (container.parent as? ViewGroup)?.removeView(container)
            container
        },
        modifier = modifier,
    )
}
