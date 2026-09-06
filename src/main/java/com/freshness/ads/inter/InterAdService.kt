package com.freshness.ads.inter

import android.app.Activity
import android.app.Application
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
    val isShowLoading: Boolean = true
) {
    fun asInterAdRequest() = InterAdRequest(
        placement = placement,
        reload = reload
    )
}