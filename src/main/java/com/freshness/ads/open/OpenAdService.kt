package com.freshness.ads.open

import android.app.Activity
import android.app.Application
import kotlinx.coroutines.flow.StateFlow
import java.lang.ref.WeakReference

interface OpenAdService {

    val isOpenAdShowing: StateFlow<Boolean>

    fun init(app: Application)

    suspend fun launchOpenAdFlow(
        activity:  WeakReference<Activity>,
        initialDelayMillis: Long = 0L,
        maxMillis: Long = 12_000L,
        onLoaded: (Boolean) -> Unit = {}
    ): Boolean

    /** Id lấy từ `ads_id_config` (placement `open_all`), không truyền từ ngoài vào nữa. */
    fun loadAd()

    suspend fun showAdIfAvailable(activity: WeakReference<Activity>): Boolean

    /**
     * App-open KHÔNG hiện đè lên activity này khi app quay lại foreground (màn thanh toán, chọn
     * file, activity quảng cáo của SDK khác…). Seed ban đầu từ `AdsConfig.openAdExcludedActivities`.
     */
    fun excludeActivity(activityClass: Class<out Activity>)

    fun includeActivity(activityClass: Class<out Activity>)

    /** true khi [activity] (hoặc lớp cha của nó) nằm trong danh sách loại trừ. */
    fun isExcluded(activity: Activity?): Boolean

    fun reset()

}