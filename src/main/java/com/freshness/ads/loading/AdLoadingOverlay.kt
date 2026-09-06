package com.freshness.ads.loading

import android.app.Activity
import android.app.Application
import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.WindowManager
import com.freshness.ads.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Vẽ màn chờ mặc định cho [AdLoading].
 *
 * [AdLoading] chỉ phát trạng thái; không có lớp này thì app host phải tự observe `isLoading` và tự
 * dựng dialog, mà việc đó lại là thứ chính sách AdMob khuyến nghị nên có ("a delay ... could take
 * the form of a loading or please wait screen, or a progress-bar/wheel" — Recommended interstitial
 * implementations). Bắt mỗi app tự làm nghĩa là app nào quên thì quảng cáo bung ra ngay dưới ngón
 * tay đang bấm, đúng tình huống chính sách muốn tránh.
 *
 * Dialog bám activity đang resumed, không huỷ được bằng nút back hay chạm ra ngoài — nó chỉ tồn tại
 * trong khoảng chờ ngắn trước khi quảng cáo bung, và [AdLoadingImpl] đã có timeout tự tắt.
 */
internal class AdLoadingOverlay(private val adLoading: AdLoading) {

    private val scope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())

    private var resumedActivity: Activity? = null
    private var dialog: Dialog? = null

    fun install(app: Application) {
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                resumedActivity = activity
                sync()
            }

            override fun onActivityPaused(activity: Activity) {
                // Gỡ dialog TRƯỚC khi activity chủ của nó biến mất, nếu không WindowManager ném
                // BadTokenException lúc activity bị destroy trong khi dialog còn gắn vào nó.
                if (resumedActivity === activity) {
                    dismiss()
                    resumedActivity = null
                }
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })

        scope.launch {
            adLoading.isLoading.collect { sync() }
        }
    }

    private fun sync() {
        if (adLoading.isLoading.value) show() else dismiss()
    }

    private fun show() {
        if (dialog?.isShowing == true) return
        val activity = resumedActivity ?: return
        if (activity.isFinishing || activity.isDestroyed) return

        // Activity có thể chết ngay giữa lúc này và lúc show(); dialog chỉ là lớp trang trí nên
        // không đáng để làm sập app host.
        runCatching {
            Dialog(activity, android.R.style.Theme_Translucent_NoTitleBar).apply {
                setContentView(R.layout.layout_ads_loading)
                setCancelable(false)
                setCanceledOnTouchOutside(false)
                window?.apply {
                    setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                    setLayout(
                        WindowManager.LayoutParams.MATCH_PARENT,
                        WindowManager.LayoutParams.MATCH_PARENT,
                    )
                    // Chặn mọi thao tác chạm phía dưới trong khoảng chờ: đó chính là mục đích của
                    // màn này — cho ngón tay dừng lại trước khi quảng cáo chiếm màn hình.
                    addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                    setDimAmount(DIM_AMOUNT)
                }
                show()
            }
        }.onSuccess { dialog = it }
            .onFailure { Timber.w(it, "AdLoadingOverlay: không hiện được màn chờ") }
    }

    private fun dismiss() {
        val current = dialog ?: return
        dialog = null
        runCatching { current.dismiss() }
    }

    private companion object {
        const val DIM_AMOUNT = 0.6f
    }
}
