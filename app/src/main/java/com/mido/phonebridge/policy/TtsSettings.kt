package com.mido.phonebridge.policy

import com.mido.phonebridge.plugins.tts.TtsLimits
import org.json.JSONObject

/**
 * How the phone speaks, when a caller asks it to.
 *
 * **One object, not four fields on [Policy].** The project has already been bitten once by a
 * positional constructor: `applyGate` rebuilt `Policy` with two of five fields and silently reset
 * the rest, and every `withX` since has to name every field it keeps. Four loose fields would be
 * four more chances to drop one in eight copy methods. As a single value, each `withX` carries
 * `tts = tts` once and the whole group moves together.
 *
 * **A preference, parsed leniently.** These decide how loud and how long, not whether a human is
 * asked for anything — a garbled rate must never refuse to start the bridge. Every value is
 * clamped by [TtsLimits] on read AND on use, because a stored `0.0` rate is not a mistake worth
 * crashing for but also not one worth honouring.
 */
data class TtsSettings(
    /** Characters a single utterance may contain. Refused above this, never truncated. */
    val maxChars: Int = TtsLimits.MAX_CHARS,
    /** Speech-rate multiplier. */
    val rate: Float = 1.0f,
    /** Pitch multiplier. */
    val pitch: Float = 1.0f,
    /** How many utterances may WAIT behind the one speaking. Beyond that the route answers busy. */
    val queueDepth: Int = 3,
) {

    /** Every value clamped into the range the engine will actually honour. */
    fun clamped(): TtsSettings = TtsSettings(
        maxChars = TtsLimits.clampCap(maxChars),
        rate = TtsLimits.clampRate(rate),
        pitch = TtsLimits.clampPitch(pitch),
        queueDepth = TtsLimits.clampQueueDepth(queueDepth),
    )

    fun toJson(): JSONObject = JSONObject()
        .put("maxChars", maxChars)
        // Written as a double because JSON has no float, and read back as a float.
        .put("rate", rate.toDouble())
        .put("pitch", pitch.toDouble())
        .put("queueDepth", queueDepth)

    companion object {

        /**
         * Read the stored block, leniently, reporting what it could not read.
         *
         * Absent is the normal state for a file written before this existed, so it means
         * "defaults" and is NOT a warning. A present-but-garbage value IS a warning: someone set
         * something and it did not take.
         */
        fun parse(o: JSONObject?, warnings: MutableList<String>): TtsSettings {
            val defaults = TtsSettings()
            if (o == null) return defaults

            fun int(key: String, fallback: Int): Int {
                if (!o.has(key)) return fallback
                val v = o.opt(key)
                if (v !is Number) {
                    warnings.add("tts.$key is not a number; using $fallback")
                    return fallback
                }
                return v.toInt()
            }

            fun float(key: String, fallback: Float): Float {
                if (!o.has(key)) return fallback
                val v = o.opt(key)
                if (v !is Number) {
                    warnings.add("tts.$key is not a number; using $fallback")
                    return fallback
                }
                return v.toFloat()
            }

            return TtsSettings(
                maxChars = int("maxChars", defaults.maxChars),
                rate = float("rate", defaults.rate),
                pitch = float("pitch", defaults.pitch),
                queueDepth = int("queueDepth", defaults.queueDepth),
            )
        }
    }
}
