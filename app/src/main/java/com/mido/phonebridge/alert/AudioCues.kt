package com.mido.phonebridge.alert

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Short, fixed attention cues an agent can ask the phone to play.
 *
 * **A closed set, not a media player.** The point is a recognisable "come look" signal, so
 * there are three cues with distinct character and no way to supply arbitrary audio. A route
 * that took a file or a URL would be a speaker for hire; a route that takes a NAME from a fixed
 * list can only ever say one of three things.
 *
 * **Fail hard, like [SoundAlerts].** A cue that silently does not play is worse than an error,
 * because the caller believes the human was alerted. Every failure here throws with the cause
 * named, and the route turns that into a coded 500.
 */
object AudioCues {

    /** The entire vocabulary. Adding a cue is an app change, deliberately. */
    enum class Cue(val asset: String, val label: String) {
        /** A single short high blip. The default "look here". */
        PING("cue_ping", "ping"),

        /** Two rising notes. Softer, for "something finished". */
        CHIME("cue_chime", "chime"),

        /** A tremolo burst. Insistent, for "this one matters". */
        ALARM("cue_alarm", "alarm"),
    }

    /** Thrown when a cue cannot be decoded or played. Never swallowed. */
    class CueUnavailable(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

    private var pool: SoundPool? = null
    private val soundIds = HashMap<Cue, Int>()
    private val loaded = HashSet<Cue>()

    @Synchronized
    private fun ensureLoaded(ctx: Context, cue: Cue): Int {
        val p = pool ?: SoundPool.Builder()
            .setMaxStreams(1)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    // SONIFICATION matches the gate sounds: this is an interface cue, not media,
                    // so it follows the same stream and never fights with music playback.
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .build()
            .also { pool = it }

        soundIds[cue]?.let { return it }

        val resId = when (cue) {
            Cue.PING -> com.mido.phonebridge.R.raw.cue_ping
            Cue.CHIME -> com.mido.phonebridge.R.raw.cue_chime
            Cue.ALARM -> com.mido.phonebridge.R.raw.cue_alarm
        }

        // THE LISTENER GOES IN BEFORE `load`, for the reason SoundAlerts records: `load` is
        // asynchronous and can complete before the next line runs. A listener registered
        // afterwards never fires and the cue stays permanently un-loaded.
        val latch = CountDownLatch(1)
        p.setOnLoadCompleteListener { _, sampleId, status ->
            if (status != 0) {
                throw CueUnavailable("cue ${cue.label} failed to decode (status $status)")
            }
            if (soundIds[cue] == sampleId) {
                loaded.add(cue)
                latch.countDown()
            }
        }
        val id = p.load(ctx.applicationContext, resId, 1)
        if (id == 0) throw CueUnavailable("SoundPool refused to load ${cue.label}.ogg")
        soundIds[cue] = id

        // Bounded wait: the route is a request, not a place to hang. A sample that has not
        // decoded within two seconds is a fault, and saying so beats playing nothing.
        if (!latch.await(2, TimeUnit.SECONDS)) {
            throw CueUnavailable("cue ${cue.label} did not decode within 2s")
        }
        return id
    }

    /**
     * Play [cue] ONCE. No loop, no repeat, no state to clear — one shot by design, so there is
     * nothing that can keep making noise after the request that asked for it has finished.
     */
    @Synchronized
    fun play(ctx: Context, cue: Cue) {
        val id = ensureLoaded(ctx, cue)
        val played = pool?.play(id, 0.8f, 0.8f, 1, 0, 1.0f) ?: 0
        if (played == 0) throw CueUnavailable("SoundPool refused to play ${cue.label}")
    }

    /** Parse a cue name from a URL segment. Unknown names are the caller's mistake. */
    fun of(raw: String): Cue? =
        Cue.values().firstOrNull { it.label.equals(raw, ignoreCase = true) }
}
