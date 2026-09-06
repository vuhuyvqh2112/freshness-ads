package com.freshness.ads.consent

import android.app.Activity
import android.app.Application
import com.google.android.ump.FormError
import kotlinx.coroutines.flow.StateFlow
import java.lang.ref.WeakReference

interface ConsentService {

    fun init(app: Application)

    /**
     * Emits true once the consent flow has resolved (either consent was granted and
     * MobileAds was initialized, or consent was denied and ad initialization was skipped).
     * Consumers should additionally check [canRequestAds] before requesting any ad.
     */
    val isAdInitialized: StateFlow<Boolean>

    /**
     * Whether the user's current consent state allows ad requests.
     * Returns false when the user denied consent (e.g. in EEA) — in that case
     * no ad should be requested or shown to remain GDPR-compliant.
     */
    val canRequestAds: Boolean

    /**
     * Whether the app must expose a "Privacy options" entry point so the user can
     * change/withdraw consent later. Required by Google UMP policy in EEA/UK.
     */
    val isPrivacyOptionsRequired: Boolean

    fun gatherConsent(activity: WeakReference<Activity>)

    /**
     * Gather consent and suspend until the consent flow has resolved or
     * [timeoutMs] elapses. Always returns; on timeout the caller should
     * proceed without blocking the splash. Intended to be called explicitly
     * from SplashActivity so the UMP form is visible early in the flow.
     */
    suspend fun ensureConsent(
        activity: WeakReference<Activity>,
        timeoutMs: Long = 10_000L,
    )

    /**
     * Show the privacy options form so the user can change or withdraw consent.
     * Should only be invoked when [isPrivacyOptionsRequired] is true.
     */
    fun showPrivacyOptionsForm(
        activity: Activity,
        onDismissed: (FormError?) -> Unit = {},
    )
}