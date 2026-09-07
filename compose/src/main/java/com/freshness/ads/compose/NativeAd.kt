package com.freshness.ads.compose

import android.content.Context
import android.view.ViewGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.viewinterop.AndroidView
import com.freshness.ads.di.AdsGraph
import com.freshness.ads.natives.BaseLoadingNativeAdView
import com.freshness.ads.natives.NativeAdResult
import kotlinx.coroutines.flow.map

/**
 * Một slot native trong Compose — bản Compose của `bindNativeAdFromCache`.
 *
 * Preload (idempotent) cho [key]/[placement] khi vào composition, hiện shimmer của [template] trong
 * lúc chờ, bind ad khi về, và **không compose gì** khi no-fill (slot biến mất, không để lại khoảng
 * trống). Rời composition: gỡ ad khỏi view và, nếu [releaseOnDestroy], huỷ ad đã ăn impression
 * (fill chưa kịp hiện được giữ lại cho lần sau).
 *
 * ```
 * NativeAd(
 *     key = "native_detail", placement = "native_detail",
 *     template = { LoadingNativeAdHomeView(it) },          // hoặc LoadingNativeAdTemplateView(it, R.layout.my_native)
 *     modifier = Modifier.fillMaxWidth().height(240.dp),
 * )
 * ```
 *
 * @param key chỗ hiển thị trong cache (mỗi slot một key). @param placement key trong `ads_id_config`.
 * @param template view "shimmer + template" của SDK, hoặc [com.freshness.ads.natives.LoadingNativeAdTemplateView]
 *   với layout của app. Chiều cao đặt bằng [modifier]; template full-bleed cần chiều cao cụ thể.
 */
@Composable
fun NativeAd(
    key: String,
    placement: String,
    template: (Context) -> BaseLoadingNativeAdView,
    modifier: Modifier = Modifier,
    releaseOnDestroy: Boolean = true,
) {
    if (LocalInspectionMode.current) return
    val service = AdsGraph.nativeAdService
    val context = LocalContext.current

    LaunchedEffect(key, placement) { service.preloadIfEmpty(context, key, placement) }

    val result by remember(key) { service.nativeCaches.map { it[key] } }
        .collectAsState(initial = service.nativeCaches.value[key])

    val view = remember(key) { template(context).also { service.registerAdContainer(it) } }
    DisposableEffect(key) {
        onDispose {
            view.unbindAd()
            service.unregisterAdContainer(view)
            if (releaseOnDestroy) service.releaseCachedAdIfImpressed(key)
        }
    }

    if (result is NativeAdResult.Failure) return

    AndroidView(
        factory = {
            (view.parent as? ViewGroup)?.removeView(view)
            view
        },
        modifier = modifier,
        update = { v ->
            val success = result as? NativeAdResult.Success ?: return@AndroidView
            if (v.boundAd !== success.nativeAd) v.setNativeAd(success.nativeAd)
        },
    )
}
