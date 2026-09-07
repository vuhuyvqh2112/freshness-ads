package com.freshness.ads.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import com.freshness.ads.di.AdsGraph

/**
 * `true` khi bất kỳ ad toàn màn nào (interstitial, rewarded, app-open) đang chiếm màn hình — dùng
 * để pause video/nhạc: màn kế tiếp có thể được compose ngay dưới quảng cáo nên `ON_PAUSE` không đủ.
 *
 * ```
 * val adShowing by rememberFullScreenAdShowing()
 * LaunchedEffect(adShowing) { player.playWhenReady = !adShowing }
 * ```
 */
@Composable
fun rememberFullScreenAdShowing(): State<Boolean> = AdsGraph.adsManager.isFullScreenAdShowing.collectAsState()
