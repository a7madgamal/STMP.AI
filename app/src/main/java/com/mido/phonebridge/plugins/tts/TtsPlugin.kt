package com.mido.phonebridge.plugins.tts

import android.content.Context
import com.mido.phonebridge.core.ApprovalRow
import com.mido.phonebridge.core.Plugin
import com.mido.phonebridge.core.PluginSetting
import com.mido.phonebridge.core.PluginError
import com.mido.phonebridge.core.PluginStatus
import com.mido.phonebridge.core.Req
import com.mido.phonebridge.core.Resp
import com.mido.phonebridge.core.Route
import com.mido.phonebridge.core.Verb
import com.mido.phonebridge.policy.PolicyStore
import org.json.JSONObject

/**
 * `POST /tts` — say a string out loud on the phone, and answer when it has finished.
 *
 * **Gate-exempt, and the exemption is bounded by the CALLER, not by the route.** Mido,
 * 2026-11-02: *"exempt as long as known ip"*. That works without any new mechanism because the
 * caller check runs before routing: an unnamed address is refused before a route is even
 * resolved, so `gateExempt` here means "any caller you have already named may speak", not
 * "anyone at all". A stranger cannot make the phone talk.
 *
 * **Why exempt at all.** The whole point is delivering something to someone who is NOT looking at
 * the phone — that is exactly why the screen-off rule was dropped from both this route and
 * `/audio` in the same change. Requiring a tap first would make the feature usable only when it
 * is unnecessary.
 *
 * **What keeps that honest.** Only two things can happen: text is spoken, or the caller is
 * refused with a coded error. No arbitrary audio, no file, no URL, no volume or duration
 * parameter — the same closed-vocabulary argument as `/audio`, applied to text instead of cues.
 * The cap bounds how much can be said, the queue bounds how many callers can pile up, and the
 * phone's own silent switch bounds everything, because the utterance rides the notification
 * stream (Mido: *"a silent phone is enough"*).
 *
 * **`aiSummary = false`. The text IS the description.** Asking a model to summarise a sentence a
 * human is about to hear is worse than useless — the model is the least reliable component in the
 * app, and a route that goes silent because inference timed out would fail exactly when someone
 * wanted to be told something. The audit stores the spoken text verbatim, which is the real
 * record of what was said.
 */
class TtsPlugin(private val ctx: Context) : Plugin {

    override val id = "tts"
    override val displayName = "Speak"

    /**
     * Speak's settings, declared here.
     *
     * **These write to the POLICY file, not to `PluginSettingsStore`** — and that is deliberate.
     * A cap that bounds what a caller may send is a rule, not a preference, and it lives beside
     * the approval rules that already govern this route. Splitting it would mean two places
     * decide what a request may contain.
     *
     * The renderer does not care which backing store a plugin uses; it only renders descriptors
     * and calls [PluginSetting.Action] for anything live. That is what keeps the settings screen
     * generic while letting each plugin keep its values where they already belong.
     */
    override fun settings(): List<PluginSetting> = listOf(
        PluginSetting.Text(
            id = "maxChars",
            label = "Character cap",
            description = "Longer text is refused, never truncated, so a caller always knows " +
                "the whole message was spoken or not spoken at all.",
            default = ttsPolicy().maxChars.toString(),
            numeric = true,
        ),
        PluginSetting.Text(
            id = "rate",
            label = "Speech rate",
            description = "Multiplier. Clamped to the range the engine will honour.",
            default = ttsPolicy().rate.toString(),
            placeholder = "1.0",
        ),
        PluginSetting.Text(
            id = "pitch",
            label = "Pitch",
            description = "Multiplier. Clamped to the range the engine will honour.",
            default = ttsPolicy().pitch.toString(),
            placeholder = "1.0",
        ),
        PluginSetting.Text(
            id = "queueDepth",
            label = "Queue depth",
            description = "How many utterances may wait behind the one speaking. Beyond it the " +
                "route answers busy rather than queueing forever.",
            default = ttsPolicy().queueDepth.toString(),
            numeric = true,
        ),
        PluginSetting.Action(
            id = "sample",
            label = "Speak a sample",
            description = "Hear the current rate and pitch without sending a request.",
            run = { c ->
                com.mido.phonebridge.alert.SoundAlerts.secureVibration(c)
            },
        ),
    )

    override fun routes(): List<Route> = listOf(
        Route(
            "POST", "/tts", "SAY TEXT ALOUD", Verb.EXECUTE,
            gateExempt = true,
            // The request IS the description; a summariser here can only lose information.
            aiSummary = false,
            // Refuse pre-gate: an over-cap text can never succeed, so it must not cost the work
            // of starting an engine — and the caller gets the real length and the real cap.
            validate = { req -> requireWithinCap(req) }
        ) { req -> speak(req) }
    )

    override fun status(): PluginStatus {
        val settings = ttsPolicy()
        return PluginStatus(
            "Speak",
            PluginStatus.State.OK,
            "POST /tts — cap ${settings.maxChars} chars, line ${settings.queueDepth}, " +
                "rate ${settings.rate}, pitch ${settings.pitch}"
        )
    }

    /** The stored preferences, clamped. Read per call so a change in Options takes effect at once. */
    private fun ttsPolicy() = PolicyStore.from(ctx).get().tts.clamped()

    /**
     * The text, validated against the CURRENT cap.
     *
     * Throws the caller's mistake as a 400 naming both numbers: a cap the caller cannot see is a
     * cap they cannot satisfy.
     */
    private fun requireWithinCap(req: Req): String {
        val text = req.body.optString("text")
        if (text.isEmpty() && !req.body.has("text")) {
            throw PluginError("bad_request", "missing required 'text'")
        }
        val settings = ttsPolicy()
        TtsLimits.rejectReason(text, settings.maxChars)?.let { reason ->
            // `text_too_long` is its own code rather than a generic `bad_request`, because the
            // fix is specific (shorten it) and the caller needs the two numbers to do that.
            throw PluginError(
                if (text.length > settings.maxChars) "text_too_long" else "bad_request",
                reason
            )
        }
        return text
    }

    private fun speak(req: Req): Resp {
        val text = requireWithinCap(req)
        val settings = ttsPolicy()
        try {
            TtsSpeaker.speak(ctx, text, settings)
        } catch (e: TtsSpeaker.TtsBusy) {
            // A full line is a "try again in a moment", not a fault: the request was fine.
            throw PluginError(
                "tts_busy",
                "${e.ahead} utterance(s) already queued or speaking; the line holds " +
                    "${settings.queueDepth}"
            )
        } catch (e: TtsSpeaker.TtsUnavailable) {
            // No usable engine is a broken install / disabled voice — the caller can do nothing
            // about it, and pretending to speak would be the worst answer.
            throw PluginError("tts_unavailable", e.message ?: "no usable text-to-speech engine")
        } catch (e: TtsSpeaker.TtsInCall) {
            // **Not a fault, and NOT a success.**
            //
            // Before this existed the engine accepted the text during a call, played it into a
            // muted stream, reported `onDone`, and the route answered `ok` — so a caller was told
            // the human had been spoken to when nobody heard a word. Mido, 2026-11-10: *"tts failed
            // silently when the phone was in a call"*.
            //
            // The message says what happened and what to do, because "tts_failed" for a busy phone
            // teaches the caller to retry the wrong way.
            throw PluginError(
                "tts_in_call",
                e.message ?: "the phone is on a call, so speech would be inaudible"
            )
        } catch (e: TtsSpeaker.TtsFailed) {
            throw PluginError("tts_failed", e.message ?: "the phone could not speak this")
        }
        return Resp.ok(
            JSONObject()
                .put("spoken", text.length)
                .put("maxChars", settings.maxChars)
        )
    }

    /**
     * The card's rows — there are none, because there is no card.
     *
     * Declared explicitly rather than left to a default: the gate asks every plugin for its rows,
     * and a gate-exempt route never reaches that path. Returning empty here is the honest answer
     * if the exemption is ever removed, and it makes the intent readable instead of implicit.
     */
    override fun describeRows(req: Req): List<ApprovalRow> = emptyList()
}
