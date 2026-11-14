package com.mido.phonebridge.ui

import com.mido.phonebridge.policy.ExternalDialect

/** What the on-device model section of Settings shows (WP07 section 5, states L1 to L7). */
sealed class LocalModelState {
    data class Ready(val bytes: Long) : LocalModelState()
    data class NotDownloaded(val expected: Long) : LocalModelState()
    data class Incomplete(val have: Long, val expected: Long) : LocalModelState()
    data class WrongFile(val have: Long, val expected: Long) : LocalModelState()
    data class Downloading(val percent: Int, val expected: Long) : LocalModelState()
    data class Failed(val reason: String) : LocalModelState()

    companion object {
        /**
         * Precedence: running, then ready, then failed, then the file's own problem.
         * A failure is only reported when the file is not usable, so a retry that finished
         * the job never keeps showing an old error.
         */
        fun of(
            exists: Boolean,
            length: Long,
            expected: Long,
            running: Boolean,
            percent: Int,
            lastError: String,
        ): LocalModelState = when {
            running -> Downloading(percent.coerceIn(0, 100), expected)
            exists && length == expected -> Ready(length)
            lastError.isNotBlank() -> Failed(lastError)
            !exists -> NotDownloaded(expected)
            length > expected -> WrongFile(length, expected)
            else -> Incomplete(length, expected)
        }
    }
}

/** Remote summariser state, in the order `ExternalModel.configureProblem` checks. */
enum class RemoteState { NOT_SET, NOT_LISTED, NO_MODEL, READY }

enum class NotificationAccess { GRANTED, NOT_CONNECTED, NEEDED }

/** The secret vault row: unlocked, then needs reset, then not created, then locked. */
enum class VaultRow { UNLOCKED, NEEDS_RESET, NOT_CREATED, LOCKED }

/** Pure mappings from raw inputs to the states Settings draws. */
object SettingsState {

    fun remote(url: String, dialect: ExternalDialect?, model: String): RemoteState = when {
        url.isBlank() -> RemoteState.NOT_SET
        dialect == null -> RemoteState.NOT_LISTED
        model.isBlank() -> RemoteState.NO_MODEL
        else -> RemoteState.READY
    }

    fun notificationAccess(granted: Boolean, connected: Boolean): NotificationAccess = when {
        !granted -> NotificationAccess.NEEDED
        !connected -> NotificationAccess.NOT_CONNECTED
        else -> NotificationAccess.GRANTED
    }

    fun vault(unlocked: Boolean, unrecoverable: Boolean, orphaned: Boolean, exists: Boolean): VaultRow = when {
        unlocked -> VaultRow.UNLOCKED
        unrecoverable || orphaned -> VaultRow.NEEDS_RESET
        !exists -> VaultRow.NOT_CREATED
        else -> VaultRow.LOCKED
    }

    /**
     * "The file is 328 MB but 328 MB was expected" says nothing: when the rounded sizes agree, the
     * sentence gives the exact byte counts instead.
     */
    fun wrongFile(have: Long, expected: Long, mb: (Long) -> String): String {
        val a = mb(have)
        val b = mb(expected)
        if (a != b) return "The file is $a but $b was expected."
        return "The file is ${"%,d".format(java.util.Locale.US, have)} bytes but " +
            "${"%,d".format(java.util.Locale.US, expected)} bytes were expected."
    }

    fun rulesCount(n: Int): String = when (n) {
        0 -> "No exceptions"
        1 -> "1 exception"
        else -> "$n exceptions"
    }
}
