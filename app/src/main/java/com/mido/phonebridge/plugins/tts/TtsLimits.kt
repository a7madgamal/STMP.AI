package com.mido.phonebridge.plugins.tts

/**
 * The TTS rules that must hold on every build, independent of the engine.
 *
 * **Pure and separate on purpose.** The cap, the option ranges and the timeout are decisions —
 * "how much may a caller say", "how long do we wait" — and a decision that lives inside the
 * `TextToSpeech` plumbing can only be checked on a phone. Every defect this project paid for
 * twice was a rule sitting in the I/O path where no test could reach it. So the rules are here,
 * and `TtsLimitsTest` exercises them.
 */
object TtsLimits {

    /** Characters a caller may ask the phone to say, before the human's own cap is consulted. */
    const val MAX_CHARS = 400

    /**
     * The ceiling a configured cap may not exceed.
     *
     * **The cap is a preference, so it is clampable; this is the bound the clamp uses.** Without
     * one, "make the cap bigger" becomes "speak a novel": the route blocks until the utterance
     * ends, so an unbounded text pins a server thread for minutes and a distracted human hears
     * their phone read an essay. 4000 characters is roughly four minutes of speech — long enough
     * for a paragraph a person meant to hear, short enough that a mistake is audible and
     * stoppable.
     */
    const val MAX_CHARS_CEILING = 4_000

    /** Lowest and highest speech-rate multiplier the engine accepts. Android's own range. */
    const val MIN_RATE = 0.5f
    const val MAX_RATE = 2.0f

    const val MIN_PITCH = 0.5f
    const val MAX_PITCH = 2.0f

    /** Deepest the line may be, on top of the one actually speaking. */
    const val MAX_QUEUE_DEPTH = 10

    /**
     * Why a text may not be spoken, or null when it may.
     *
     * Returns the REASON rather than throwing, matching the project's rule for pure objects:
     * deciding where a warning surfaces is the caller's job, and a function that only answers
     * can be asserted on.
     */
    fun rejectReason(text: String, maxChars: Int): String? = when {
        text.isBlank() -> "'text' is blank; there is nothing to say"
        text.length > maxChars -> "text is ${text.length} characters; the cap is $maxChars"
        else -> null
    }

    /**
     * How long one utterance may take before we stop waiting.
     *
     * **Derived from the cap, never picked by feel.** The route blocks until the engine reports
     * `onDone`, so this is the longest a server thread may be held for one sentence; a fixed
     * number would be wrong at both ends (too short for a long text, needlessly long for "ok").
     *
     * The rate is the honest conservative floor: a caller may configure 2x, which halves the
     * time, and waiting longer than necessary costs nothing while waiting too little turns a
     * spoken sentence into a false failure.
     */
    fun timeoutMsFor(maxChars: Int): Long = 8_000L + (maxChars.coerceAtMost(MAX_CHARS_CEILING) * 120L)

    /** A configured cap, clamped into the range the app will honour. */
    fun clampCap(configured: Int): Int =
        if (configured <= 0) MAX_CHARS else configured.coerceAtMost(MAX_CHARS_CEILING)

    /** A configured rate/pitch, clamped to the engine's own range. */
    fun clampRate(configured: Float): Float =
        if (configured.isNaN()) 1.0f else configured.coerceIn(MIN_RATE, MAX_RATE)

    fun clampPitch(configured: Float): Float =
        if (configured.isNaN()) 1.0f else configured.coerceIn(MIN_PITCH, MAX_PITCH)

    /** A configured queue depth, clamped; 0 would mean "refuse unless idle", which is a dead route. */
    fun clampQueueDepth(configured: Int): Int = configured.coerceIn(1, MAX_QUEUE_DEPTH)
}
