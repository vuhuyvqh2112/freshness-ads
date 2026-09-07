package com.freshness.ads.consent

import android.app.Activity
import android.content.Context
import com.google.android.ump.ConsentDebugSettings
import com.google.android.ump.ConsentForm
import com.google.android.ump.ConsentInformation
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.FormError
import com.google.android.ump.UserMessagingPlatform
import timber.log.Timber

/**
 * Cài đặt debug cho UMP. Chỉ được áp khi APP HOST debuggable — release không bao giờ đọc tới.
 *
 * @param forceEea ép geography EEA để form consent hiện trên máy test.
 * @param testDeviceHashedIds hashed id của máy test, UMP in ra logcat ở lần chạy đầu.
 */
internal data class ConsentDebugConfig(
    val forceEea: Boolean,
    val testDeviceHashedIds: List<String>,
)

/**
 * Bọc UMP. `UserMessagingPlatform.getConsentInformation` tự là singleton theo process nên class này
 * không cần singleton riêng — [ConsentProvider] giữ một instance và mọi nơi khác đi qua
 * [ConsentService].
 */
internal class GoogleMobileAdsConsentManager(
    context: Context,
    /** null = không áp debug settings (release). */
    private val debugConfig: ConsentDebugConfig?,
) {
    private val consentInformation: ConsentInformation =
        UserMessagingPlatform.getConsentInformation(context)

    /** Interface definition for a callback to be invoked when consent gathering is complete. */
    fun interface OnConsentGatheringCompleteListener {
        fun consentGatheringComplete(error: FormError?)
    }

    /** Helper variable to determine if the app can request ads. */
    val canRequestAds: Boolean
        get() {
            val value = consentInformation.canRequestAds()
            Timber.d("$TAG canRequestAds=$value")
            return value
        }

    /** Helper variable to determine if the privacy options form is required. */
    val isPrivacyOptionsRequired: Boolean
        get() =
            consentInformation.privacyOptionsRequirementStatus ==
                ConsentInformation.PrivacyOptionsRequirementStatus.REQUIRED

    /**
     * Helper method to call the UMP SDK methods to request consent information and load/show a
     * consent form if necessary.
     */
    fun gatherConsent(
        activity: Activity,
        onConsentGatheringCompleteListener: OnConsentGatheringCompleteListener,
    ) {
        val paramsBuilder = ConsentRequestParameters.Builder()

        debugConfig?.let { debug ->
            val builder = ConsentDebugSettings.Builder(activity)
            if (debug.forceEea) {
                builder.setDebugGeography(ConsentDebugSettings.DebugGeography.DEBUG_GEOGRAPHY_EEA)
            }
            debug.testDeviceHashedIds.forEach { builder.addTestDeviceHashedId(it) }
            paramsBuilder.setConsentDebugSettings(builder.build())
        }

        // Requesting an update to consent information should be called on every app launch.
        consentInformation.requestConsentInfoUpdate(
            activity,
            paramsBuilder.build(),
            {
                UserMessagingPlatform.loadAndShowConsentFormIfRequired(activity) { formError ->
                    onConsentGatheringCompleteListener.consentGatheringComplete(formError)
                }
            },
            { requestConsentError ->
                onConsentGatheringCompleteListener.consentGatheringComplete(requestConsentError)
            },
        )
    }

    /** Helper method to call the UMP SDK method to show the privacy options form. */
    fun showPrivacyOptionsForm(
        activity: Activity,
        onConsentFormDismissedListener: ConsentForm.OnConsentFormDismissedListener,
    ) {
        UserMessagingPlatform.showPrivacyOptionsForm(activity, onConsentFormDismissedListener)
    }

    private companion object {
        const val TAG = "ConsentManager"
    }
}
