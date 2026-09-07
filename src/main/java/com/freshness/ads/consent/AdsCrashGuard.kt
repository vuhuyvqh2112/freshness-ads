package com.freshness.ads.consent

import com.google.firebase.crashlytics.FirebaseCrashlytics
import timber.log.Timber

/**
 * Last-resort guard for crashes that live entirely inside Google's ad / UMP
 * libraries and therefore cannot be fixed or caught at the call site. Each is
 * raised on a library-owned background/WebView thread, so a try/catch around our
 * own calls never sees it — Android then tears down the whole process for an
 * uncaught exception on that thread.
 *
 * Currently guards two well-identified, library-internal defects:
 *
 * 1. UMP consent_sdk `NoSuchElementException`. The next-gen ads SDK
 *    (ads-mobile-sdk 1.1.1, the latest) bundles UMP 4.0.0, which throws
 *    `java.util.NoSuchElementException` from `Scanner.next()` inside its own
 *    `consent_sdk` classes (e.g. `zzcr.zzl`) while gathering consent, on UMP's
 *    own executor thread. Swallowing it only skips consent for that session.
 *
 * 2. WebView `IllegalArgumentException: reasonPhrase can't be empty`. The ad
 *    SDKs (GMA next-gen / Unity) render creatives in a WebView and intercept
 *    resource loads in `WebViewClient.shouldInterceptRequest`, rebuilding an
 *    `android.webkit.WebResourceResponse` from the upstream response. Over
 *    HTTP/2/3 the status line carries NO reason phrase (RFC 7540 §8.1.2.4), so
 *    the SDK passes "" to the `WebResourceResponse(... String reasonPhrase ...)`
 *    constructor, which validates `reasonPhrase.trim().isEmpty()` and throws.
 *    It surfaces on WebView's own thread inside obfuscated SDK code (e.g.
 *    `xq.a`), so it is equally uncatchable at our call site. Swallowing it only
 *    fails that one intercepted sub-resource; the ad/app stay alive.
 *
 * This installs a process-wide uncaught-exception handler that swallows ONLY
 * those specific, narrowly-matched crashes and delegates EVERY other throwable
 * to the previously installed handler — i.e. Crashlytics keeps reporting all
 * real crashes. Swallowed crashes are still recorded as NON-fatals so their
 * frequency stays visible.
 *
 * Remove the relevant branch once a future UMP / ad SDK release fixes the defect.
 */
internal object AdsCrashGuard {

    @Volatile
    private var installed = false

    /**
     * Install the guard. Must run early (before any consent gathering) and after
     * Crashlytics has set its own handler, so that handler is captured as the
     * delegate for all non-matching crashes. Safe to call more than once.
     */
    @Synchronized
    fun install() {
        if (installed) return
        installed = true

        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            val swallowLabel = knownSwallowableCrashLabel(throwable)
            if (swallowLabel != null) {
                Timber.e(
                    throwable,
                    "AdsCrashGuard: swallowed known library-internal crash [$swallowLabel] on thread '${thread.name}'"
                )
                // Still report it as a NON-fatal so we can track its frequency in
                // Crashlytics (it no longer counts as a crash / crash-free-users hit).
                // Crashlytics là compileOnly: app host không có nó thì NoClassDefFoundError rơi
                // vào runCatching này và chỉ còn dòng Timber ở trên.
                runCatching {
                    FirebaseCrashlytics.getInstance().apply {
                        log("AdsCrashGuard: swallowed [$swallowLabel] on '${thread.name}'")
                        recordException(throwable)
                    }
                }
                return@setDefaultUncaughtExceptionHandler
            }
            // Not ours: preserve normal behaviour (Crashlytics + process kill).
            if (previous != null) {
                previous.uncaughtException(thread, throwable)
            } else {
                // No prior handler (should not happen on Android) — do NOT swallow
                // a real crash; re-raise on a fresh thread so the runtime aborts.
                throw throwable
            }
        }
    }

    /**
     * Returns a short label identifying which known, library-internal defect this
     * throwable is, walking the cause chain; null means "not ours — do not swallow".
     */
    private fun knownSwallowableCrashLabel(throwable: Throwable): String? {
        var t: Throwable? = throwable
        var depth = 0
        while (t != null && depth < 10) {
            if (t is NoSuchElementException && touchesConsentSdk(t)) {
                return "UMP consent_sdk NoSuchElementException"
            }
            if (isWebResourceReasonPhraseCrash(t)) {
                return "WebView WebResourceResponse reasonPhrase-empty"
            }
            t = t.cause
            depth++
        }
        return null
    }

    private fun touchesConsentSdk(t: Throwable): Boolean =
        t.stackTrace.any { frame ->
            val cn = frame.className
            cn.contains("consent_sdk") || cn.startsWith("com.google.android.ump")
        }

    /**
     * True only for the WebView defect: an IllegalArgumentException about the
     * reasonPhrase, whose stack touches android.webkit.WebResourceResponse — the
     * class whose setStatusCodeAndReasonPhrase() throws it. Both conditions together
     * make false positives effectively impossible (only that platform class raises an
     * IllegalArgumentException mentioning "reasonPhrase").
     *
     * The message is matched by substring on purpose. The exact platform string is
     * "reasonPhrase can't be empty." WITH a trailing period (AOSP), so an `==` against
     * the period-less form silently never matched and the crash leaked through. A
     * `contains` is also robust across Android versions and covers the sibling
     * "reasonPhrase can't be null." / "...can't contain non-ASCII characters." throws,
     * which are the same library-internal defect and just as uncatchable.
     */
    private fun isWebResourceReasonPhraseCrash(t: Throwable): Boolean =
        t is IllegalArgumentException &&
            t.message?.contains("reasonPhrase") == true &&
            t.stackTrace.any { it.className == "android.webkit.WebResourceResponse" }
}
