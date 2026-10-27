package com.mido.phonebridge.alert

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import com.mido.phonebridge.policy.isAttended

/**
 * Audible/haptic feedback for the two gate surfaces. New in this release — before
 * it, the app was completely silent, so a request you were not looking at simply
 * timed out.
 *
 * Two deliberate levels, and the difference is the point:
 *
 *  - **SECURE** rides a *notification-class* channel (see [ApprovalGate][com.mido.phonebridge.ApprovalGate]),
 *    so it respects silent and DND. That is Mido's choice (option B, 2026-10-18),
 *    made with the consequence stated: on silent you will not hear it and the
 *    request auto-denies at 60s. The full-screen prompt still turns the screen on,
 *    so the visual path survives even when the sound does not.
 *  - **NOTIFY** gets a single quiet tick, and **only while the screen is on**. A
 *    `NOTIFY` card can only exist when someone is attending anyway (screen-off
 *    denies before the card is drawn), so this guard is belt-and-braces — but it is
 *    the stated rule, and it is enforced here rather than assumed from upstream.
 *
 * Deliberately NOT a `MediaPlayer`: a `MediaPlayer` allocates a codec per instance and
 * has a startup latency measured in hundreds of milliseconds, which is wrong for a
 * 200ms effect fired the instant a finger lifts. `SoundPool` decodes once and replays
 * from memory, which is the only path that can land the thud on the same frame as the
 * stamp. `ToneGenerator` is still used for the NOTIFY tick: that one IS a beep by
 * design, and the platform tone needs no asset.
 */
object SoundAlerts {

    private const val TAG = "SoundAlerts"

    /**
     * The rubber-stamp thud, decoded once and reused.
     *
     * `SoundPool` is the right container for a short effect fired on a UI event: it keeps
     * the decoded PCM in memory, so `play()` is immediate. The pool is created lazily and
     * kept for the process lifetime — building one per stamp would re-decode the sample on
     * every tap and add the latency this class exists to avoid.
     */
    private var stampPool: android.media.SoundPool? = null
    private var stampSoundId = 0
    private var stampLoaded = false

    private fun ensureStampLoaded(ctx: Context): Boolean {
        if (stampLoaded) return true
        val pool = stampPool ?: try {
            android.media.SoundPool.Builder()
                .setMaxStreams(2)
                // Feedback for an action the user just took, so it rides the same stream
                // class as the old tone: audible even when the ringer is silent.
                .setAudioAttributes(
                    android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                .build()
                .also { stampPool = it }
        } catch (e: Exception) {
            Log.w(TAG, "stamp pool unavailable: ${e.message}")
            return false
        }
        // `load` is asynchronous; the callback flips the flag so the NEXT stamp plays.
        // The first stamp after a cold start may therefore be silent, which is the honest
        // trade — blocking the UI thread on a decode would be worse.
        stampSoundId = pool.load(ctx, com.mido.phonebridge.R.raw.stamp, 1)
        pool.setOnLoadCompleteListener { _, sampleId, status ->
            if (status == 0 && sampleId == stampSoundId) stampLoaded = true
        }
        return stampLoaded
    }

    /** The subtle NOTIFY tick. Quiet, short, and gone. */
    fun notifyTick(ctx: Context) {
        // The stated rule: subtle only IF the screen is on. Re-checked here, not
        // inherited, because this is the function whose whole contract is that.
        if (!isAttended(ctx)) return
        tone(ctx, volume = 35)
        oneShotVibration(ctx, ms = 20, amplitude = 40)
    }

    /** A short double-tap vibration for a SECURE request that just landed. */
    fun secureVibration(ctx: Context) {
        vibrate(ctx, longArrayOf(0, 60, 80, 60), amplitudes = intArrayOf(0, 90, 0, 90))
    }

    /**
     * The rubber-stamp feedback: one firm, physical thud + a single strong haptic
     * bump. A stamp has weight, and the sound is the weight.
     *
     * The sample is a synthesised knock (low sine burst with a falling pitch plus a short
     * noise transient), decoded once into a [android.media.SoundPool]. It replaced a
     * `ToneGenerator` beep, which was audible but read as a notification chirp rather than
     * something hitting a desk.
     */
    fun stamp(ctx: Context) {
        stampThud(ctx)
        vibrate(ctx, longArrayOf(0, 40), amplitudes = intArrayOf(0, 160))
    }

    private fun stampThud(ctx: Context) {
        try {
            if (!ensureStampLoaded(ctx)) {
                // Not decoded yet (first stamp after a cold start). Fall back to the
                // platform knock rather than going silent, so the moment still lands.
                toneKnock()
                return
            }
            stampPool?.play(stampSoundId, 0.85f, 0.85f, 1, 0, 1.0f)
        } catch (e: Exception) {
            Log.w(TAG, "stamp thud unavailable: ${e.message}")
        }
    }

    /** The old platform-tone knock, kept only as a fallback before the sample decodes. */
    private fun toneKnock() {
        var tg: ToneGenerator? = null
        try {
            tg = ToneGenerator(AudioManager.STREAM_SYSTEM, 70)
            tg.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 90)
        } catch (e: Exception) {
            Log.w(TAG, "tone knock unavailable: ${e.message}")
        } finally {
            val toRelease = tg
            if (toRelease != null) {
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    try { toRelease.release() } catch (e: Exception) { Log.w(TAG, "tone release failed: ${e.message}") }
                }, 200L)
            }
        }
    }

    /**
     * Play one short notification beep. `STREAM_NOTIFICATION` is the right stream:
     * it is the one the ringer/silent switch governs, which is exactly the
     * "notification-class, respects silent" behaviour that was chosen.
     */
    private fun tone(ctx: Context, volume: Int) {
        var tg: ToneGenerator? = null
        try {
            tg = ToneGenerator(AudioManager.STREAM_NOTIFICATION, volume)
            tg.startTone(ToneGenerator.TONE_PROP_BEEP, 120)
        } catch (e: Exception) {
            // A device with no tone generator, or the stream unavailable. Silence is
            // an acceptable degradation for a notification sound; it must never
            // break the prompt itself.
            Log.w(TAG, "tone unavailable: ${e.message}")
        } finally {
            // Release on a small delay so the tone is not cut off; leaking a
            // ToneGenerator holds an audio track open.
            val toRelease = tg
            if (toRelease != null) {
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    try {
                        toRelease.release()
                    } catch (e: Exception) {
                        Log.w(TAG, "tone release failed: ${e.message}")
                    }
                }, 250L)
            }
        }
    }

    private fun oneShotVibration(ctx: Context, ms: Long, amplitude: Int) {
        vibrate(ctx, longArrayOf(0, ms), intArrayOf(0, amplitude))
    }

    private fun vibrate(ctx: Context, pattern: LongArray, amplitudes: IntArray) {
        val v = vibrator(ctx) ?: return
        if (!v.hasVibrator()) return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                // USE_DEFAULT_AMPLITUDE (-1) would work, but explicit amplitudes keep
                // the tick genuinely subtle on devices that map default to full.
                v.vibrate(VibrationEffect.createWaveform(pattern, amplitudes, -1))
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(pattern, -1)
            }
        } catch (e: Exception) {
            Log.w(TAG, "vibration failed: ${e.message}")
        }
    }

    /**
     * The default vibrator. `Context.VIBRATOR_SERVICE` is deprecated from API 31 in
     * favour of `VibratorManager.getDefaultVibrator()`; minSdk is 26, so both paths
     * are needed and neither is optional.
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
