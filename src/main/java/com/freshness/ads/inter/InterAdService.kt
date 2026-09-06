package com.freshness.ads.inter

import android.app.Activity
import android.app.Application
import com.freshness.ads.loading.DEFAULT_MIN_LOADING_MS
import kotlinx.coroutines.flow.StateFlow
import java.lang.ref.WeakReference

interface InterAdService {

    val isShowing: StateFlow<Boolean>

    fun init(app: Application)

    fun loadAd(config: InterAdConfig)

    suspend fun showAd(
        activity: WeakReference<Activity>,
        config: InterAdConfig,
        onShow: () -> Unit = {}
    ): Boolean

    fun reset()

}

/**
 * @param placement key trong `ads_id_config` (app tự khai hằng placement của mình).
 *   Id cụ thể do [com.freshness.ads.config.AdUnitCatalog] quyết định lúc load, không hard-code ở đây.
 * @param retryCount số lần thử lại — CHỈ áp cho tier CUỐI của waterfall. Các tier trước không retry,
 *   nếu không số request sẽ nhân lên theo số tier và match rate tụt vô ích.
 * @param timeOut trần thời gian [InterAdService.showAd] giữ dialog loading để chờ fill.
 */
data class InterAdConfig(
    val placement: String,
    val reload: Boolean,
    val retryCount: Int = 2,
    val timeOut: Long = 6_000L,
    val isShowLoading: Boolean = true,
    // Thời gian tối thiểu màn chờ phải hiện trước khi quảng cáo bung, kể cả khi ad đã preload sẵn.
    // Không có nó thì ad đã cache sẽ bung ngay trong cùng nhịp với cú chạm vừa rồi. Xem
    // `awaitMinLoadingWindow` cho phần chính sách. Đặt 0 để tắt.
    val minLoadingMs: Long = DEFAULT_MIN_LOADING_MS
) {
    fun asInterAdRequest() = InterAdRequest(
        placement = placement,
        reload = reload
    )
}