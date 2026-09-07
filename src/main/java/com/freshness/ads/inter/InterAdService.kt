package com.freshness.ads.inter

import android.app.Activity
import android.app.Application
import com.freshness.ads.loading.DEFAULT_MIN_LOADING_MS
import com.freshness.ads.manager.AdShowOutcome
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
    ): Boolean = showAdOutcome(activity, config, onShow).shown

    /** Như [showAd] nhưng trả lời vì sao không hiện. */
    suspend fun showAdOutcome(
        activity: WeakReference<Activity>,
        config: InterAdConfig,
        onShow: () -> Unit = {}
    ): AdShowOutcome

    fun reset()

}

/**
 * Config là KEY của cache: hai config `equals` nhau dùng chung một ad đã nạp. Preload và show cùng
 * một placement phải dùng cùng một config (khai hằng rồi dùng lại), nếu không lần show sẽ không thấy
 * ad đã preload.
 *
 * @param placement key trong `ads_id_config` (app tự khai hằng placement của mình).
 *   Id cụ thể do [com.freshness.ads.config.AdUnitCatalog] quyết định lúc load, không hard-code ở đây.
 * @param reload sau khi ad đóng, tự nạp sẵn ad kế tiếp cho placement này.
 * @param retryCount số lần thử lại — CHỈ áp cho tier CUỐI của waterfall. Các tier trước không retry,
 *   nếu không số request sẽ nhân lên theo số tier và match rate tụt vô ích.
 * @param timeOut trần thời gian [InterAdService.showAd] giữ dialog loading để chờ fill.
 * @param isShowLoading bật màn chờ của SDK cho lần show này. LƯU Ý: tắt là tắt luôn cả khoảng chờ
 *   tối thiểu [minLoadingMs] — màn hình tự vẽ loading riêng thì phải tự giữ khoảng chờ đó.
 * @param minLoadingMs thời gian tối thiểu màn chờ phải hiện trước khi quảng cáo bung, kể cả khi ad
 *   đã preload sẵn. Không có nó thì ad đã cache sẽ bung ngay trong cùng nhịp với cú chạm vừa rồi.
 *   Xem `awaitMinLoadingWindow` cho phần chính sách. 0 = bung ngay khi có ad (màn chờ vẫn loé một
 *   frame nếu [isShowLoading] còn bật).
 */
data class InterAdConfig @JvmOverloads constructor(
    val placement: String,
    val reload: Boolean = true,
    val retryCount: Int = 2,
    val timeOut: Long = 6_000L,
    val isShowLoading: Boolean = true,
    val minLoadingMs: Long = DEFAULT_MIN_LOADING_MS
) {
    internal fun asInterAdRequest() = InterAdRequest(
        placement = placement,
        reload = reload
    )
}