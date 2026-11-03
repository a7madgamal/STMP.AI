package com.mido.phonebridge

/**
 * How a caller asks the shell for a tab.
 *
 * A string extra rather than an enum: an Intent extra cannot carry an enum without a
 * Parcelable wrapper, and the value only ever crosses the process boundary between the
 * notification's tap target and [MainActivity]. An unknown value falls back to Home rather
 * than throwing, because a wrong tab is recoverable and a crash on launch is not.
 */
object Tabs {
    const val EXTRA_TAB = "com.mido.phonebridge.EXTRA_TAB"
}
