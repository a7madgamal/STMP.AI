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

    // BiometricPrompt error codes, passed as Int (JVM-testable, no Android types).
    private const val ERROR_UNABLE_TO_PROCESS = 2
    private const val ERROR_TIMEOUT = 3
    private const val ERROR_NO_SPACE = 4
    private const val ERROR_CANCELED = 5
    private const val ERROR_LOCKOUT = 7
    private const val ERROR_VENDOR = 8
    private const val ERROR_LOCKOUT_PERMANENT = 9
    private const val ERROR_USER_CANCELED = 10
    private const val ERROR_NEGATIVE_BUTTON = 13
    private const val ERROR_DEVICE_CREDENTIAL_NOT_SET = 14

    /**
     * What the new-device screen says when the fingerprint prompt ended without a decision. The
     * platform's own string ("Authentication canceled") is never shown: it is not our wording, it
     * changes by phone and language, and it says nothing about what happens next.
     */
    fun notConfirmed(code: Int): String = when (code) {
        ERROR_USER_CANCELED, ERROR_CANCELED, ERROR_NEGATIVE_BUTTON ->
            "Not confirmed, so nothing was decided. Tap ALLOW or BLOCK to try again."
        ERROR_TIMEOUT -> "The fingerprint check timed out, so nothing was decided. Tap ALLOW or BLOCK to try again."
        ERROR_LOCKOUT -> "Too many attempts. Wait a moment, then tap ALLOW or BLOCK again. Nothing was decided."
        ERROR_LOCKOUT_PERMANENT ->
            "The fingerprint sensor is locked. Unlock the phone with your PIN, then try again. Nothing was decided."
        ERROR_DEVICE_CREDENTIAL_NOT_SET -> NONE_ENROLLED_TEXT
        ERROR_UNABLE_TO_PROCESS, ERROR_NO_SPACE, ERROR_VENDOR ->
            "The fingerprint could not be read, so nothing was decided. Tap ALLOW or BLOCK to try again."
        else -> "Could not confirm it's you (code $code), so nothing was decided."
    }

    private const val NONE_ENROLLED_TEXT = "No fingerprint or screen lock is set up on this phone, so nothing was decided."
}
