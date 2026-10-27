package com.mido.phonebridge.alert

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.SoundPool
import android.media.ToneGenerator
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import com.mido.phonebridge.policy.isAttended

/**
 * Audible/haptic feedback for the two gate surfaces.
 *
 * **FAIL HARD. There are no silent fallbacks in this file.** Mido, 2026-10-27: *"i said a million
 * times no fucking silent fucking fallbacks / remove all now you fuck and fail hard"*.
 *
 * The rule, and why it cost real time to learn: a missing sound that silently becomes a different
 * sound is worse than a missing sound. The stamp spent several releases sounding like a notification
 * beep because a `ToneGenerator` fallback covered for a sample that was never decoded — the app
 * "worked", the wrong thing played, and the actual fault (a listener registered after `load`) was
 * invisible. Every failure in this file now throws, with the cause named.
 */
object SoundAlerts {

    private const val TAG = "SoundAlerts"

    /** Thrown when the stamp sample cannot be decoded or played. Never swallowed. */
    class SoundUnavailable(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

    private var stampPool: SoundPool? = null
    private var stampSoundId = 0
    @Volatile private var stampLoaded = false

    /**
     * Decode the stamp sample NOW, at application start.
     *
     * Without this the first decision after a cold start has nothing decoded. That is a real
     * failure, not a reason to substitute another sound, so it throws and the caller decides.
     */
    fun preload(ctx: Context) {
        ensureStampLoaded(ctx.applicationContext)
    }

    /**
     * Decode the stamp sample once.
     *
     * @throws SoundUnavailable when the pool cannot be built or the sample will not load.
     */
    private fun ensureStampLoaded(ctx: Context): Boolean {
        if (stampLoaded) return true
        val pool = stampPool ?: try {
            SoundPool.Builder()
                .setMaxStreams(2)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                .build()
                .also { stampPool = it }
        } catch (e: Exception) {
            throw SoundUnavailable("cannot build the SoundPool: ${e.message}", e)
        }

        // **THE LISTENER GOES IN BEFORE `load`.** `load` is asynchronous and can complete on
        // another thread before the next line runs; a listener registered afterwards never sees
        // the callback, `stampLoaded` stays false forever, and every stamp used to fall back to
        // the platform BEEP. That ordering was the bug.
        pool.setOnLoadCompleteListener { _, sampleId, status ->
            if (status != 0) {
                // Named, not ignored. A failed decode means the sample is not in the APK or is
                // corrupt; the next `play` will throw with this on the stack.
                throw SoundUnavailable("stamp sample failed to decode (status $status)")
            }
            if (sampleId == stampSoundId) stampLoaded = true
        }
        stampSoundId = pool.load(ctx, com.mido.phonebridge.R.raw.stamp, 1)
        if (stampSoundId == 0) {
            throw SoundUnavailable("SoundPool refused to load res/raw/stamp.ogg")
        }
        return stampLoaded
    }

    /**
     * The rubber-stamp feedback: one firm physical thud + a strong haptic bump.
     *
     * @throws SoundUnavailable when the sample is not decoded yet or cannot play. The caller shows
     *   the failure; this file never substitutes a beep.
     */
    fun stamp(ctx: Context) {
        if (!ensureStampLoaded(ctx)) {
            throw SoundUnavailable("stamp sample not decoded yet")
        }
        val played = stampPool?.play(stampSoundId, 0.85f, 0.85f, 1, 0, 1.0f) ?: 0
        if (played == 0) {
            throw SoundUnavailable("SoundPool refused to play the stamp sample")
        }
        vibrate(ctx, longArrayOf(0, 40), intArrayOf(0, 160))
    }

    /** The subtle NOTIFY tick. Quiet, short, and gone. */
    fun notifyTick(ctx: Context) {
        // The stated rule: subtle only IF the screen is on. Re-checked here, not inherited,
        // because this is the function whose whole contract is that.
        if (!isAttended(ctx)) return
        tone(ctx, volume = 35)
        oneShotVibration(ctx, ms = 20, amplitude = 40)
    }

    /** A short double-tap vibration for a SECURE request that just landed. */
    fun secureVibration(ctx: Context) {
        vibrate(ctx, longArrayOf(0, 60, 80, 60), amplitudes = intArrayOf(0, 90, 0, 90))
    }

    /**
     * Play one short notification beep.
     *
     * `STREAM_NOTIFICATION` is right here: it is the stream the ringer governs, which is exactly
     * the "notification-class, respects silent" behaviour this one is for. Unlike the stamp, this
     * IS a beep by design.
     */
    private fun tone(ctx: Context, volume: Int) {
        val tg = try {
            ToneGenerator(AudioManager.STREAM_NOTIFICATION, volume)
        } catch (e: Exception) {
            throw SoundUnavailable("cannot open a ToneGenerator: ${e.message}", e)
        }
        try {
            if (!tg.startTone(ToneGenerator.TONE_PROP_BEEP, 120)) {
                throw SoundUnavailable("ToneGenerator refused TONE_PROP_BEEP")
            }
        } finally {
            // Release on a small delay so the tone is not cut off; leaking one holds an audio
            // track open.
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                try { tg.release() } catch (e: Exception) {
                    throw SoundUnavailable("tone release failed: ${e.message}", e)
                }
            }, 250L)
        }
    }

    private fun oneShotVibration(ctx: Context, ms: Long, amplitude: Int) {
        vibrate(ctx, longArrayOf(0, ms), intArrayOf(0, amplitude))
    }

    private fun vibrate(ctx: Context, pattern: LongArray, amplitudes: IntArray) {
        val v = vibrator(ctx)
            ?: throw SoundUnavailable("no vibrator service on this device")
        if (!v.hasVibrator()) {
            throw SoundUnavailable("this device has no vibrator")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            v.vibrate(VibrationEffect.createWaveform(pattern, amplitudes, -1))
        } else {
            @Suppress("DEPRECATION")
            v.vibrate(pattern, -1)
        }
    }

    /**
     * The default vibrator. `Context.VIBRATOR_SERVICE` is deprecated from API 31 in favour of
     * `VibratorManager.getDefaultVibrator()`; minSdk is 26, so both paths are needed.
     */
    private fun vibrator(ctx: Context): Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? android.os.VibratorManager)
                ?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            ctx.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
}
