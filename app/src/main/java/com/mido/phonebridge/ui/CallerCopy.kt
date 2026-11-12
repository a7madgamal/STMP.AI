package com.mido.phonebridge.ui

/** Wording for the new-device screen (WP09 3.4.2). No Android types, so it is JVM-testable. */
internal object CallerCopy {
    // BiometricManager codes, passed as Int.
    private const val NONE_ENROLLED = 11
    private const val NO_HARDWARE = 12
    private const val HW_UNAVAILABLE = 1
    private const val SECURITY_UPDATE_REQUIRED = 15

    fun cannotConfirm(code: Int): String = when (code) {
        NONE_ENROLLED -> "No fingerprint or screen lock is set up on this phone, so nothing was decided."
        NO_HARDWARE -> "This phone has no fingerprint sensor, so nothing was decided."
        HW_UNAVAILABLE -> "The fingerprint sensor is busy right now, so nothing was decided."
        SECURITY_UPDATE_REQUIRED ->
            "A security update is needed before this phone can confirm it's you, so nothing was decided."
        else -> "Can't confirm it's you (code $code), so nothing was decided."
    }
}
