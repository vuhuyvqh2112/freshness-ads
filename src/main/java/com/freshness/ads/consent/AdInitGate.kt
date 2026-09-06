package com.freshness.ads.consent

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Process-wide gate for the next-gen MobileAds SDK initialization state.
 *
 * The SDK throws IllegalStateException ("MobileAds.initialize must be called
 * before using the Google Mobile Ads SDK") if any ad is requested before
 * [com.google.android.libraries.ads.mobile.sdk.MobileAds.initialize] has
 * actually *completed*. Init runs asynchronously and can be triggered from more
 * than one place (app start for a returning user, and the splash consent flow),
 * so callers cannot rely on execution order to know it is done.
 *
 * This gate is the single source of truth used at every ad-load site:
 *  - [ConsentProvider] is the ONLY writer. It calls [markReady] from the real
 *    `MobileAds.initialize` completion callback, and [markSkipped] when init is
 *    deliberately not run (consent denied or missing APPLICATION_ID).
 *  - Every load path calls [awaitReady] / [whenReady] before touching the SDK.
 *
 * It is a plain object (not DI-managed) because SDK init is a single per-process
 * fact and the gate must be reachable from the stateless [com.freshness.ads.natives.NativeAdmobManager]
 * object as well as the Hilt-managed providers.
 */
object AdInitGate {

    enum class State { Pending, Ready, Skipped }

    private val _state = MutableStateFlow(State.Pending)

    /** Default ceiling for load-site waits — generous enough to cover a slow
     *  init on a cold start, short enough never to hang a screen indefinitely.
     *
     *  Dùng cho format do NGƯỜI DÙNG kích hoạt (interstitial, rewarded): có người đang ngồi chờ sau
     *  một spinner, nên thà bỏ quảng cáo còn hơn giữ họ lâu hơn nữa. */
    const val DEFAULT_AWAIT_TIMEOUT_MS = 15_000L

    /**
     * Ceiling cho format BỊ ĐỘNG (banner, native): không ai đang chờ, và không có gì kích hoạt lại
     * lệnh load khi người dùng vẫn ngồi trên màn hình — hết giờ là chỗ đặt ad trống nguyên phiên.
     *
     * Rộng hơn [DEFAULT_AWAIT_TIMEOUT_MS] vì `MobileAds.initialize` có thể mất ~30 giây khi adapter
     * Unity chưa có mediation group trên AdMob (nó giữ luôn callback init của GMA). Ngưỡng 15s ngắn
     * hơn chính khoảng đó, nên cold start nào rơi vào tình huống ấy cũng mất sạch banner + native.
     */
    const val PASSIVE_AWAIT_TIMEOUT_MS = 60_000L

    // Main.immediate so callback-based SDK loads (NativeAdLoader, AdView) run on
    // the main thread, and run synchronously when the gate is already resolved.
    private val scope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())

    /** True once MobileAds.initialize has actually completed. */
    val isReady: Boolean get() = _state.value == State.Ready

    /** True once the init outcome is known (either Ready or Skipped). */
    val isResolved: Boolean get() = _state.value != State.Pending

    /** Mark the SDK as fully initialized. Call ONLY from the real init callback. */
    fun markReady() {
        _state.value = State.Ready
    }

    /** Mark that init was intentionally skipped (no consent / no app id).
     *  A later genuine init can still flip the gate to Ready. */
    fun markSkipped() {
        if (_state.value == State.Pending) _state.value = State.Skipped
    }

    /**
     * Suspend until the SDK init outcome is known, bounded by [timeoutMs].
     * @return true if ads can be loaded (Ready); false if skipped or timed out.
     */
    suspend fun awaitReady(timeoutMs: Long = DEFAULT_AWAIT_TIMEOUT_MS): Boolean {
        if (_state.value != State.Pending) return _state.value == State.Ready
        val resolved = withTimeoutOrNull(timeoutMs) {
            _state.first { it != State.Pending }
        }
        return resolved == State.Ready
    }

    /**
     * Run [onReady] once the SDK is ready, or [onUnavailable] if init is skipped
     * or does not complete within [timeoutMs]. Resolves synchronously when the
     * gate is already settled, otherwise waits on the main scope. Use this for
     * the callback-style (non-suspend) load wrappers.
     */
    fun whenReady(
        timeoutMs: Long = DEFAULT_AWAIT_TIMEOUT_MS,
        onUnavailable: () -> Unit = {},
        onReady: () -> Unit,
    ) {
        when (_state.value) {
            State.Ready -> onReady()
            State.Skipped -> onUnavailable()
            State.Pending -> scope.launch {
                if (awaitReady(timeoutMs)) onReady() else onUnavailable()
            }
        }
    }
}
