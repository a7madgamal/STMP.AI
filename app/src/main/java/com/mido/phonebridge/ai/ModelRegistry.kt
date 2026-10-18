package com.mido.phonebridge.ai

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Chooses which summariser is live, and owns the ONE constant that names the model
 * file — the swap seam Mido asked for ("make switching later easy").
 *
 * To change model: drop a different `.task` at [MODEL_ASSET] (or change this one
 * string) and restart. Nothing else references the file, so there is no second place
 * to update and no code change to make. Swapping the *runtime* is the same move one
 * level up: implement [SummaryModel] and return it from [active].
 *
 * **Why the model is a file on disk and not an APK asset.** A 550MB asset in
 * `assets/` is compressed into the APK and cannot be memory-mapped by the runtime —
 * MediaPipe needs a real path, and the APK would balloon to an unshippable size.
 * So the file lives in the app's external files dir, and the app is *absent-safe*:
 * with no model it uses [HeuristicSummarizer] and behaves exactly as before.
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
    const val MODEL_ASSET = "models/gemma3-1b-it-int4.task"

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

    @Volatile
    private var cached: SummaryModel? = null

    /**
     * The summariser to use for a request, resolved once and cached.
     *
     * Returns the real model only when its file is present; otherwise the heuristic.
     * Never returns null — every caller gets something that can describe a request,
     * which is what makes the AI a clarity feature rather than a dependency.
     */
    fun active(ctx: Context): SummaryModel {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            val file = modelFile(ctx)
            val model = if (file.exists() && file.length() > 0) {
                Log.i(TAG, "model present (${file.length() / 1024 / 1024} MB): ${file.absolutePath}")
                MediaPipeGemma(file)
            } else {
                Log.i(TAG, "no model at ${file.absolutePath}; using heuristic summariser")
                HeuristicSummarizer
            }
            cached = model
            return model
        }
    }

    /** Where the model should be, for the debug log and the setup checklist. */
    fun status(ctx: Context): String {
        val f = modelFile(ctx)
        return if (f.exists() && f.length() > 0) {
            "${active(ctx).id} ready (${f.length() / 1024 / 1024} MB)"
        } else {
            "no model — heuristic summariser (place ${MODEL_FILE} in ${f.parentFile?.absolutePath})"
        }
    }
}
