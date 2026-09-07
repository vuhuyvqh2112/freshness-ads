package com.freshness.ads.consent

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import com.freshness.ads.config.AdsConfig
import com.freshness.ads.config.isHostDebuggable
import com.google.android.libraries.ads.mobile.sdk.MobileAds
import com.google.android.libraries.ads.mobile.sdk.initialization.InitializationConfig
import com.google.android.ump.FormError
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicBoolean

internal class ConsentProvider(
    private val context: Context,
    /** Debug settings của UMP đọc từ đây, và CHỈ khi app host debuggable. */
    config: AdsConfig,
) : ConsentService {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val isMobileAdsInitializeCalled = AtomicBoolean(false)

    private val googleMobileAdsConsentManager = GoogleMobileAdsConsentManager(
        context,
        debugConfig = if (context.isHostDebuggable()) {
            ConsentDebugConfig(
                forceEea = config.consentDebugGeographyEea,
                testDeviceHashedIds = config.consentTestDeviceIds,
            )
        } else {
            null
        },
    )

    private val _isAdInitialized = MutableStateFlow(false)
    override val isAdInitialized = _isAdInitialized.asStateFlow()

    override val canRequestAds: Boolean
        get() = googleMobileAdsConsentManager.canRequestAds

    override val isPrivacyOptionsRequired: Boolean
        get() = googleMobileAdsConsentManager.isPrivacyOptionsRequired

    override fun showPrivacyOptionsForm(activity: Activity, onDismissed: (FormError?) -> Unit) {
        googleMobileAdsConsentManager.showPrivacyOptionsForm(activity) { formError ->
            // Re-evaluate consent: if user just granted consent for the first time,
            // initialize MobileAds so ads can start being requested.
            if (googleMobileAdsConsentManager.canRequestAds) {
                initializeMobileAdsSdk()
            }
            onDismissed(formError)
        }
    }

    override fun init(app: Application) {
        Timber.d("$TAG Initializing consent service")
        // This sample attempts to load ads using consent obtained in the previous session.
        if (googleMobileAdsConsentManager.canRequestAds) {
            initializeMobileAdsSdk()
        }
    }

    override suspend fun ensureConsent(
        activity: WeakReference<Activity>,
        timeoutMs: Long,
    ) {
        if (_isAdInitialized.value) return
        runCatching { gatherConsent(activity) }
        withTimeoutOrNull(timeoutMs) {
            _isAdInitialized.filter { it }.first()
        }
        // On timeout we deliberately leave _isAdInitialized as-is — the splash
        // will continue without ads and a later attempt may resolve consent.
    }

    override fun gatherConsent(activity: WeakReference<Activity>) {
        googleMobileAdsConsentManager.gatherConsent(activity.get() ?: return) { consentError ->
            if (consentError != null) {
                // Consent not obtained in current session.
                Timber.w(String.format("%s: %s", consentError.errorCode, consentError.message))
            }

            if (googleMobileAdsConsentManager.canRequestAds) {
                initializeMobileAdsSdk()
            } else {
                // GDPR-compliant: do NOT initialize MobileAds when the user denied consent.
                // Still mark the consent flow as resolved so the splash flow can proceed.
                Timber.d("$TAG Consent not granted — skipping MobileAds initialization")
                AdInitGate.markSkipped()
                _isAdInitialized.value = true
            }
        }
    }

    private fun initializeMobileAdsSdk() {
        if (isMobileAdsInitializeCalled.getAndSet(true)) {
            // Init is already in flight (e.g. started from init() for a returning
            // user who previously granted consent). Do NOT flip _isAdInitialized
            // here — that would falsely signal "ready" while MobileAds.initialize
            // is still running, letting an ad load before the SDK is initialized
            // (IllegalStateException: MobileAds.initialize must be called first).
            // Let the in-flight init's completion callback set the flag instead.
            return
        }

        // Next-gen SDK: must initialize on a background thread with an explicit
        // InitializationConfig (app id). We read the app id from the same manifest
        // meta-data the legacy SDK used (com.google.android.gms.ads.APPLICATION_ID).
        scope.launch(Dispatchers.IO) {
            val appId = runCatching {
                context.packageManager
                    .getApplicationInfo(context.packageName, PackageManager.GET_META_DATA)
                    .metaData?.getString("com.google.android.gms.ads.APPLICATION_ID")
            }.getOrNull()

            if (appId.isNullOrBlank()) {
                Timber.e("$TAG Missing AdMob APPLICATION_ID meta-data; cannot init next-gen SDK")
                AdInitGate.markSkipped()
                _isAdInitialized.value = true
                return@launch
            }

            MobileAds.initialize(
                context,
                InitializationConfig.Builder(appId).build()
            ) {
                Timber.d("$TAG MobileAds (next-gen) initialized")
                // Open the gate ONLY here — from the real completion callback —
                // so no ad can be requested before the SDK is actually ready.
                AdInitGate.markReady()
                _isAdInitialized.value = true
            }
        }
    }

    private companion object {
        const val TAG = "ConsentProvider"
    }
}