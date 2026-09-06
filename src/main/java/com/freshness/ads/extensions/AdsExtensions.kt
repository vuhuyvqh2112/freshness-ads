package com.freshness.ads.extensions

import android.app.Activity
import android.view.View
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.freshness.ads.natives.BaseLoadingNativeAdView
import com.freshness.ads.natives.NativeAdResult
import com.freshness.ads.natives.NativeAdService
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.lang.ref.WeakReference
import kotlin.coroutines.resume


val Activity.weakActivity: WeakReference<Activity>
    get() = WeakReference(this)

val Fragment.weakActivity: WeakReference<Activity>
    get() = WeakReference(activity)

fun <T> CancellableContinuation<T>.safeResume(value: T) {
    if (isActive) {
        resume(value)
    }
}

/**
 * Bind a [NativeAdResult] flow to a native ad container. Encapsulates the
 * Success / Idle / Loading / Failure boilerplate so screens don't repeat it.
 *
 * - Success → call [render] and show the container.
 * - Idle/Loading → show the container (shimmer takes over).
 * - Failure → hide the container so we never leave a blank ad slot.
 *
 * Collection runs while the owner is at least STARTED.
 */
fun LifecycleOwner.observeNativeAd(
    container: View,
    flow: Flow<NativeAdResult?>,
    render: (NativeAdResult.Success) -> Unit,
) {
    lifecycleScope.launch {
        repeatOnLifecycle(Lifecycle.State.STARTED) {
            flow.collect { result ->
                when (result) {
                    is NativeAdResult.Success -> {
                        render(result)
                        container.visibility = View.VISIBLE
                    }
                    is NativeAdResult.Idle, is NativeAdResult.Loading -> {
                        container.visibility = View.VISIBLE
                    }
                    is NativeAdResult.Failure -> {
                        container.visibility = View.GONE
                    }
                    else -> Unit
                }
            }
        }
    }
}

/**
 * Chờ tới khi [key] có kết quả dứt điểm (Success/Failure) hoặc hết [timeoutMs], rồi trả lời "có ad
 * sẵn để show không". Gọi được ngay sau khi vừa phát lệnh load, vì nó chờ chính lần load đó.
 */
suspend fun NativeAdService.awaitReady(key: String, timeoutMs: Long): Boolean {
    if (isReady(key)) return true
    withTimeoutOrNull(timeoutMs) {
        nativeCaches
            .map { it[key] }
            .first { it is NativeAdResult.Success || it is NativeAdResult.Failure }
    }
    return isReady(key)
}

/**
 * Bind one of [NativeAdService.nativeCaches] entries (keyed by [cacheKey]) to a
 * [BaseLoadingNativeAdView].
 *
 * If [releaseOnDestroy] is true, the cached ad is released when the owner is
 * destroyed — but only if it actually recorded an impression. An unshown fill
 * (user left before the ad became viewable) is kept for reuse on the next visit
 * instead of being destroyed and re-requested, which wasted the fill and hurt
 * match rate. Use this for one-off screens. For ads that should survive across
 * screens (rolling preload / reusable pool), pass false (the default).
 */
fun LifecycleOwner.bindNativeAdFromCache(
    container: BaseLoadingNativeAdView,
    nativeAdService: NativeAdService,
    cacheKey: String,
    releaseOnDestroy: Boolean = false,
) {
    // Đăng ký để provider gỡ ad khỏi view này trước khi destroy nó
    nativeAdService.registerAdContainer(container)

    val flow = nativeAdService.nativeCaches.map { it[cacheKey] }
    observeNativeAd(container, flow) { success ->
        container.setNativeAd(success.nativeAd)
    }
    lifecycle.addObserver(LifecycleEventObserver { _, event ->
        if (event == Lifecycle.Event.ON_DESTROY) {
            // Gỡ ad khỏi view TRƯỚC khi release (release có thể destroy ad)
            container.unbindAd()
            nativeAdService.unregisterAdContainer(container)
            if (releaseOnDestroy) {
                nativeAdService.releaseCachedAdIfImpressed(cacheKey)
            }
        }
    })
}

/** Bind a standalone [StateFlow] of NativeAdResult to a container, for a slot the screen owns
 *  itself rather than one held in [NativeAdService.nativeCaches]. */
fun LifecycleOwner.bindNativeAdFlow(
    container: BaseLoadingNativeAdView,
    flow: StateFlow<NativeAdResult>,
    nativeAdService: NativeAdService? = null,
) {
    // Truyền nativeAdService để provider gỡ ad khỏi view trước khi destroy (một lần load mới có
    // thể destroy ad cũ trong khi view vẫn đang hiển thị nó).
    nativeAdService?.registerAdContainer(container)

    observeNativeAd(container, flow) { success ->
        container.setNativeAd(success.nativeAd)
    }

    if (nativeAdService != null) {
        lifecycle.addObserver(LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_DESTROY) {
                container.unbindAd()
                nativeAdService.unregisterAdContainer(container)
            }
        })
    }
}
