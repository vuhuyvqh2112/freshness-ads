package com.freshness.ads.natives

import android.content.Context
import android.util.AttributeSet
import android.view.ViewTreeObserver
import androidx.constraintlayout.widget.ConstraintLayout
import com.google.android.libraries.ads.mobile.sdk.nativead.NativeAd

/**
 * Base cho mọi container "shimmer + native template".
 *
 * Class này sở hữu vòng đời của các lần bind bị hoãn (post / pre-draw). Trước đây mỗi subclass tự
 * gọi `doOnPreDraw { template.setNativeAd(ad) }` hoặc tự viết `attachAdWhenLaidOut`, các callback
 * đó giữ strong ref tới [NativeAd] và KHÔNG bị huỷ khi view bị recycle. Nếu ad bị
 * [NativeAd.destroy] trong khoảng chờ đó (AdPoolManager.releaseAd khi ViewHolder bị recycle),
 * callback vẫn chạy và gắn drawable của ad đã destroy vào ImageView -> frame kế tiếp crash
 * "Canvas: trying to use a recycled bitmap".
 *
 * Quy tắc dùng:
 * - Subclass gọi [bindTemplateWhenLaidOut] trong [setNativeAd] thay vì tự hoãn.
 * - Người dùng view (adapter) PHẢI gọi [unbindAd] trước khi destroy ad.
 */
abstract class BaseLoadingNativeAdView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : ConstraintLayout(context, attrs) {

    /** Template đang giữ ad. Lưu lại để [unbindAd] gỡ được drawable của ad khỏi view. */
    private var boundTemplate: BaseNativeAdView? = null

    private var pendingPreDraw: ViewTreeObserver.OnPreDrawListener? = null
    private var pendingBind: Runnable? = null

    /** Ad đang gắn (hoặc đang chờ gắn) trên view này. */
    var boundAd: NativeAd? = null
        private set

    abstract fun setNativeAd(nativeAd: NativeAd)

    /**
     * Bind ad ở frame kế tiếp, thay cho `itemView.post { adView.setNativeAd(ad) }` ở phía adapter.
     * Lần post trước đó (nếu có) bị huỷ, và [unbindAd] cũng huỷ được lần post này.
     */
    fun setNativeAdOnNextFrame(nativeAd: NativeAd) {
        cancelPendingBind()
        boundAd = nativeAd
        val runnable = Runnable {
            pendingBind = null
            setNativeAd(nativeAd)
        }
        pendingBind = runnable
        post(runnable)
    }

    /**
     * Gắn ad khi [template] đã có kích thước thật.
     *
     * Retry qua từng frame pre-draw: một [ViewTreeObserver.OnPreDrawListener] đơn lẻ là không đủ vì
     * khi bind chạy trong layout pass của RecyclerView, cú lật GONE->VISIBLE mới chỉ xếp hàng
     * relayout cho frame sau, preDraw của frame hiện tại vẫn thấy template 0x0. Đăng ký ad lúc
     * children còn 0x0 khiến SDK bỏ qua MediaView và phần click -> ad đầu tiên trắng, bấm không ăn.
     */
    protected fun bindTemplateWhenLaidOut(
        template: BaseNativeAdView,
        nativeAd: NativeAd,
        retriesLeft: Int = MAX_LAYOUT_RETRIES
    ) {
        cancelPendingPreDraw()
        boundAd = nativeAd
        boundTemplate = template

        val laidOut = template.width > 0 && template.height > 0 && !template.isLayoutRequested
        if (laidOut || retriesLeft <= 0) {
            template.setNativeAd(nativeAd)
            return
        }

        val listener = object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                if (template.viewTreeObserver.isAlive) {
                    template.viewTreeObserver.removeOnPreDrawListener(this)
                }
                // unbindAd() đã chạy trong lúc chờ -> ad có thể đã bị destroy, KHÔNG gắn nữa
                if (pendingPreDraw !== this) return true
                pendingPreDraw = null
                bindTemplateWhenLaidOut(template, nativeAd, retriesLeft - 1)
                return true
            }
        }
        pendingPreDraw = listener
        template.viewTreeObserver.addOnPreDrawListener(listener)
    }

    /**
     * Huỷ mọi lần bind đang chờ và gỡ tham chiếu tới ad khỏi view.
     *
     * PHẢI gọi trước khi [NativeAd.destroy] (ví dụ trong `onViewRecycled`), nếu không ImageView vẫn
     * giữ drawable của ad và sẽ vẽ bitmap đã bị recycle.
     */
    fun unbindAd() {
        cancelPendingBind()
        cancelPendingPreDraw()
        boundAd = null
        boundTemplate?.clearAd()
        boundTemplate = null
    }

    private fun cancelPendingBind() {
        pendingBind?.let { removeCallbacks(it) }
        pendingBind = null
    }

    private fun cancelPendingPreDraw() {
        val listener = pendingPreDraw ?: return
        pendingPreDraw = null
        val observer = boundTemplate?.viewTreeObserver
        if (observer != null && observer.isAlive) {
            observer.removeOnPreDrawListener(listener)
        }
    }

    companion object {
        private const val MAX_LAYOUT_RETRIES = 5
    }
}
