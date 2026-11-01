package com.mido.phonebridge.plugins.probe

import android.content.Context
import com.mido.phonebridge.BuildConfig
import com.mido.phonebridge.core.Plugin
import com.mido.phonebridge.core.PluginStatus
import com.mido.phonebridge.core.Req
import com.mido.phonebridge.core.Resp
import com.mido.phonebridge.core.Route
import com.mido.phonebridge.core.Verb
import org.json.JSONObject

/**
 * `GET /probe` — prove the gate works, without touching anything.
 *
 * **Why this exists.** `/status` answers "is the process alive" with no approval, which is exactly
 * right for a watcher and exactly wrong for a human asking "will my agent's calls actually reach
 * me?" That question can only be answered by a request that goes through the gate, and every real
 * route has a prerequisite that may not be set up yet: Gmail needs a login, `/notifications` needs
 * the listener permission, Drive needs a scope, a secret route needs a vault entry, SSH needs a
 * connection. A first-run check built on any of those fails for a reason that has nothing to do
 * with the gate.
 *
 * This route has **zero dependencies by construction**: no Google account, no vault, no
 * permission, no model. If it answers, the gate answered.
 *
 * **Approve passes AND deny passes.** The claim being tested is "a human is asked and the answer
 * is honoured", not "the request runs". A denial is the product working correctly — the gate
 * refused and the handler never executed — so a `403 denied` here is a SUCCESSFUL probe, and the
 * skill says so. That is also why the copy below never treats a denial as a failure.
 *
 * **It stays forever** (Mido, 2026-11-01: *"keep it just in case"*), as a human-triggered liveness
 * check. `/status` remains the machine one: ungated, 404 means alive, safe to poll. The two are
 * split deliberately, because a gated route must never be a watcher target — that is the bug that
 * produced 523 approval cards in six days.
 *
 * `aiSummary = false`: the AI is core, and the model is a ~474 MB download that a fresh install
 * has not made yet. A probe that waited for it would answer `503 model_not_ready` at the exact
 * moment the probe matters most.
 */
class ProbePlugin(private val ctx: Context) : Plugin {

    override val id = "probe"
    override val displayName = "Probe"

    override fun routes(): List<Route> = listOf(
        Route(
            "GET", "/probe", "Check that the approval gate reaches you", Verb.READ,
            // Must never depend on the model: this route exists to be callable on a fresh
            // install, where the model is very likely not provisioned.
            aiSummary = false,
            // Nothing in the reply is secret or large, but the record's value is the OUTCOME,
            // not the body — and this route will be called repeatedly while someone is setting
            // up. Keeping the same 20-odd bytes in the log on every call is the growth
            // `/debug` already opts out of.
            auditResponseBody = false,
        ) { respond() }
    )

    override fun status(): PluginStatus =
        PluginStatus("Probe", PluginStatus.State.OK, "GET /probe")

    /**
     * The handler runs ONLY after an approval, so its very existence is the proof.
     *
     * No state is read or written: a probe that changed something could not be called casually.
     */
    private fun respond(): Resp = Resp.ok(
        JSONObject()
            .put("reached", "the gate passed this request to the handler")
            .put("version", BuildConfig.VERSION_NAME)
            .put("build", BuildConfig.VERSION_CODE)
            // Restated here rather than left to /help, because this reply is read on its own by
            // someone following the skill's first-run check.
            .put(
                "note",
                "If you got a 403 with error 'denied', that is also a pass: the human declined " +
                    "and the gate honoured it."
            )
    )
}
