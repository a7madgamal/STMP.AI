package com.mido.phonebridge.plugins.audio

import android.content.Context
import com.mido.phonebridge.alert.AudioCues
import com.mido.phonebridge.core.Plugin
import com.mido.phonebridge.core.PluginError
import com.mido.phonebridge.core.PluginStatus
import com.mido.phonebridge.core.Req
import com.mido.phonebridge.core.Resp
import com.mido.phonebridge.core.Route
import com.mido.phonebridge.core.Verb
import org.json.JSONArray
import org.json.JSONObject

/**
 * `POST /audio/{cue}` — play one short attention cue on the phone.
 *
 * **The only gate-exempt route in the app** (Mido, 2026-10-31: *"no card or even notification for
 * those"*). That is not a convenience: a cue exists to make someone look at the phone, so making
 * it require them to look first would be a circular requirement. The exemption is safe only
 * because this route can do exactly one thing — make a short noise — and nothing else.
 *
 * **A closed set of three cues.** Not arbitrary audio, no file, no URL, no volume or duration
 * parameter. The route takes a NAME from a fixed list, so the worst a caller can do is choose
 * which of three sounds plays. A caller that wants to play something else is asking for a
 * different feature, and it should be argued for rather than smuggled in here.
 *
 * **One shot.** A single `SoundPool.play` with loop = 0. No repeat, no stop route, no state to
 * clear, so nothing can keep making noise after the request that asked for it has finished.
 *
 * **Refused when the screen is off.** A cue on a locked, dark phone is noise in a pocket: nobody
 * sees it, and the sound cannot be acted on. The same rule `SoundAlerts.notifyTick` already
 * follows. Answering rather than silently skipping is deliberate — a caller that gets 200 should
 * be able to believe the phone actually made a sound.
 *
 * It still logs like every other request: normal outcome, normal record. Mido, 2026-10-31: *"it
 * still logs as other requests"*.
 */
class AudioPlugin(private val ctx: Context) : Plugin {

    override val id = "audio"
    override val displayName = "Audio"

    override fun routes(): List<Route> = listOf(
        Route(
            "POST", "/audio/{cue}", "PLAY AN ATTENTION CUE", Verb.EXECUTE,
            // No modal, no overlay: see the class comment. The only exemption in the app.
            gateExempt = true,
            // No AI. A cue has nothing to describe — the request IS the description, and the
            // model being unavailable must never stop a sound from being requested.
            aiSummary = false,
            // The response is a tiny JSON object; the request has no body worth keeping.
            auditRequestBody = false,
            // Named input, validated BEFORE the handler: an unknown cue costs no work at all.
            validate = { req -> cueOf(req) }
        ) { req -> play(cueOf(req)) }
    )

    override fun status(): PluginStatus = PluginStatus(
        "Audio",
        PluginStatus.State.OK,
        "POST /audio/{cue} — ${AudioCues.Cue.values().joinToString { it.label }}"
    )

    /** Resolve the cue from the path segment, or refuse with the list of valid names. */
    private fun cueOf(req: Req): AudioCues.Cue {
        val raw = req.pathParams["cue"] ?: ""
        return AudioCues.of(raw) ?: throw PluginError(
            "bad_request",
            "unknown cue '$raw'; this build has " +
                AudioCues.Cue.values().joinToString(", ") { it.label }
        )
    }

    private fun play(cue: AudioCues.Cue): Resp {
        if (!com.mido.phonebridge.policy.isAttended(ctx)) {
            throw PluginError(
                "denied",
                "the screen is off or the phone is locked, so a cue would be heard by nobody"
            )
        }
        try {
            AudioCues.play(ctx, cue)
        } catch (e: AudioCues.CueUnavailable) {
            // A cue that cannot be decoded is a fault on our side, never a silent no-op: the
            // caller must not believe a sound played.
            throw PluginError("audio_unavailable", e.message ?: "the cue could not be played")
        }
        return Resp.ok(
            JSONObject()
                .put("played", cue.label)
                .put("available", JSONArray(AudioCues.Cue.values().map { it.label }))
        )
    }
}
