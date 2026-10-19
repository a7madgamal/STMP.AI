package com.mido.phonebridge.ai

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Chooses which summariser is live, and owns the ONE constant that names the model
 * file — the swap seam Mido asked for ("make switching later easy").
 *
 * To change model: point [MODEL_URL] at a different `.litertlm`, update
 * [EXPECTED_SIZE]/[EXPECTED_SHA256], and restart. Nothing else references the file, so
 * there is no second place to update and no code change to make. Swapping the *runtime*
 * is the same move one level up: implement [SummaryModel] and return it from [active].
 *
 * **The model is REQUIRED (Mido, 2026-10-19).** This used to fall back to a pure
 * heuristic when the file was absent, and the app ran in a permanently
 * degraded mode where the "AI summary" was really a rule. That is exactly the silent
 * fallback the fail-hard rule forbids, and the AI is core functionality rather than a
 * nice-to-have — so the fallback is GONE. A missing model is now a NOT-READY state that
 * the server refuses to serve through (see [readiness] and the `503 model_not_ready`
 * gate in `BridgeService`), and [ModelProvisioner] is how the state clears.
 *
 * **Why the model is a file on disk and not an APK asset.** A ~474 MB asset in
 * `assets/` is compressed into the APK and cannot be memory-mapped by the runtime —
 * LiteRT-LM needs a real path, and the APK would balloon to an unshippable size. So the
 * file lives in the app's external files dir and is fetched by [ModelProvisioner].
 */
object ModelRegistry {

    private const val TAG = "ModelRegistry"

    /**
     * Where the model file must be placed on the device.
     *
     * `/sdcard/Android/data/com.mido.phonebridge/files/models/<MODEL_FILE>` — the
     * app's own external files dir, so no storage permission is needed and an
     * uninstall removes it.
     */
    const val MODEL_ASSET = "models/qwen3_1_7b_dyn_int4.litertlm"

    /**
     * Where [ModelProvisioner] fetches it from. An **ungated** `litert-community`
     * artifact: every small Gemma in that zoo is `gated: auto` (401 without a token),
     * which is why Qwen3 is the shipped family rather than Gemma.
     *
     * **`dynamic_wi4b32_afp32`, never `mixed_int4`.** The mixed-int4 sibling carries a
     * Qwen3 chat template whose line 14 does `'\n' + content` — valid for a STRING, fatal
     * when content arrives as a list of parts, which is what `Contents` always serialises
     * to. Every inference failed with `Failed to apply template: … tried to use + operator
     * on unsupported types string and sequence`, and because the AI is core it took the
     * whole bridge down. This variant carries LiteRT-LM's official Qwen3 config and accepts
     * the system instruction, which is the shape the app actually sends.
     *
     * **Why 1.7B and not 0.6B (Mido, 2026-10-19: *"swap"*).** The 0.6B was fast (1.2s) but
     * could not compose: given a payload-first prompt it still copied whatever distinctive
     * string it saw first, so a Todoist write described itself as
     * `POST https://api.todoist.com/api/v1/tasks` with the actual task text right there in
     * the facts. Six releases of fact-tuning could not fix a model that pattern-matches
     * instead of reading. 1.7B is the next size up in the SAME family and quantisation
     * recipe, so nothing else changes — and the app re-downloads automatically because
     * [MODEL_ASSET] names a different file.
     *
     * Cost, measured/estimated: file 0.34 GB → 0.98 GB, inference ~1.2s → ~3.4s, resident
     * ~700 MiB → ~2 GiB. Both the 10s SECURE budget and the 10s NOTIFY cap still fit. The
     * real risk is memory on a 24/7 service, which is why the `onTrimMemory` release valve
     * exists — a killed service is survivable, an OOM is not.
     */
    const val MODEL_URL =
        "https://huggingface.co/litert-community/Qwen3-1.7B/resolve/main/Qwen3-1.7B_dynamic_wi4b32_afp32.litertlm"

    /**
     * The exact byte count and digest a complete download has. Pinned, and checked by
     * [ModelProvisioner] before the file is allowed onto the real path — a truncated
     * download must never be mistaken for a model. The digest is the repo's
     * `x-linked-etag`, verified against a local `sha256sum` of the fetched file.
     */
    const val EXPECTED_SIZE = 977_184_032L
    const val EXPECTED_SHA256 = "2a72330b504a6596d53a2c00923c57173b10f1b83e6a541213ffec08d1fc5de8"

    /** The expected file name, derived from [MODEL_ASSET] so the two cannot drift. */
    val MODEL_FILE: String get() = MODEL_ASSET.substringAfterLast('/')

    /**
     * The model file on disk. External files dir, falling back to internal — the
     * fallback exists so a device with no external storage still works (internal is
     * where a pushed file would land there).
     */
    fun modelFile(ctx: Context): File {
        val external = ctx.getExternalFilesDir(null)
        val base = external ?: ctx.filesDir
        return File(base, MODEL_ASSET)
    }

    /**
     * Is the model present and the right size? The ONE decision that decides whether
     * the bridge serves at all.
     *
     * Delegates to the pure [ModelReadiness] so the rule is unit-tested rather than
     * discovered on a device — the same split as `isAttended(ctx)` / `isAttended(screen,
     * keyguard)`. This method is the Android half: it stats the file.
     */
    fun readiness(ctx: Context): ModelReadiness.State {
        val f = modelFile(ctx)
        val exists = f.exists()
        return ModelReadiness.of(exists, if (exists) f.length() else 0L, EXPECTED_SIZE)
    }

    @Volatile
    private var cached: SummaryModel? = null

    /**
     * The summariser to use for a request, resolved once and cached.
     *
     * There is exactly one implementation now — the real model. There is no heuristic
     * to fall back to, by design: a request only reaches this call after [readiness]
     * has passed, and if the model then fails to answer, that is a fault the caller
     * reports rather than a degraded answer it hides.
     */
    fun active(ctx: Context): SummaryModel {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            val model = LiteRtLm(modelFile(ctx))
            cached = model
            return model
        }
    }

    /**
     * Load the model ahead of the first request (Mido, 2026-10-19: "warmed up in memory
     * on app load"). Blocking; call off the main thread. A no-op when not ready.
     */
    fun warm(ctx: Context) {
        if (!readiness(ctx).ready) return
        (active(ctx) as? LiteRtLm)?.warm(ctx)
    }

    /**
     * Drop the warm engine, keeping the model FILE. Called from `onTrimMemory` so the OS
     * can reclaim ~700 MiB instead of killing the service; the next request reloads it.
     */
    fun release() {
        (cached as? LiteRtLm)?.release()
    }

    /** Where the model should be, for the debug log and the setup checklist. */
    fun status(ctx: Context): String {
        val r = readiness(ctx)
        return if (r.ready) "${active(ctx).id} ${r.detail}"
        else "not ready — ${r.detail} (fetching from ${MODEL_URL.substringAfterLast('/')})"
    }
}
