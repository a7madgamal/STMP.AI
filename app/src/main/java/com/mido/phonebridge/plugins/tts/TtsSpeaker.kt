package com.mido.phonebridge.plugins.tts

import android.content.Context
import android.media.AudioAttributes
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.mido.phonebridge.policy.TtsSettings
import java.util.ArrayDeque
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Speaks one string at a time, in arrival order, and does not answer until it is done.
 *
 * **Serial and BOUNDED, decided explicitly** (Mido, 2026-11-02: *"serial bounded"*). A flush
 * would mean the newest request cuts off an older one — and because the route blocks until the
 * utterance finishes, the interrupted caller could not be told the truth with `ok`. Its honest
 * answer would have to be "someone else spoke over me", i.e. a success conditional on timing. A
 * line is duller and it can be trusted.
 *
 * **The wait is the contract, not an implementation detail.** `POST /tts` returns only after
 * `onDone`, so `ok` means the phone actually finished saying it. That is what makes the route
 * useful for announcements: a caller that gets `ok` can assume the human heard the whole
 * sentence. The cost is a held server thread, which is bounded by [TtsLimits.timeoutMsFor] —
 * derived from the cap, never guessed.
 *
 * **No silent fallbacks, in the [com.mido.phonebridge.alert.SoundAlerts] tradition.** An engine
 * that will not initialise, refuses to speak, or reports an error all throw with the cause
 * named. A route that answers `ok` for a sentence nobody heard is worse than one that fails.
 */
object TtsSpeaker {

    /** The engine is missing, disabled, or has no usable voice. */
    class TtsUnavailable(message: String, cause: Throwable? = null) :
        RuntimeException(message, cause)

    /** The line is full. [ahead] is how many utterances are queued or speaking. */
    class TtsBusy(val ahead: Int) : RuntimeException("$ahead utterance(s) ahead in the line")

    /** The engine accepted the text and then failed to say it. */
    class TtsFailed(message: String) : RuntimeException(message)

    private class Job(
        /** Kept per job because a job may outlive the call that enqueued it. */
        val ctx: Context,
        val text: String,
        val settings: TtsSettings,
        /** Counted down exactly once: on done, on error, on stop, or on the caller's timeout. */
        val done: CountDownLatch = CountDownLatch(1),
    ) {
        /** Set before [done] is counted down; read only after it. */
        @Volatile
        var failure: String? = null
    }

    private var engine: TextToSpeech? = null
    private var initFailure: String? = null

    /** Everything is guarded by this, including the engine fields. */
    private val lock = Any()

    /** Waiting jobs, in arrival order. */
    private val line = ArrayDeque<Job>()

    /** The job the engine is currently speaking, or null when idle. */
    private var speaking: Job? = null

    /**
     * Utterance ids are per-job so a callback can be matched to the job that caused it.
     *
     * A single fixed id would work while the line is serial and break the day anything overlaps
     * — and the failure would be a wrong job reported done, which is silent.
     */
    private const val ID_PREFIX = "stmp-tts-"

    /**
     * Speak [text] and return once it has been spoken, or throw.
     *
     * @throws TtsBusy when the line already holds [TtsSettings.queueDepth] entries.
     * @throws TtsUnavailable when the engine cannot be started or has no voice for the locale.
     * @throws TtsFailed when the engine reports an error, or the deadline passes.
     */
    fun speak(ctx: Context, text: String, settings: TtsSettings) {
        val job = Job(ctx.applicationContext, text, settings)
        synchronized(lock) {
            // `queueDepth` counts what is AHEAD of this one, so the one speaking plus the line
            // is the real capacity. Comparing against the line alone would let depth+1 through.
            val ahead = line.size + if (speaking != null) 1 else 0
            if (ahead >= settings.queueDepth) throw TtsBusy(ahead)
            line.addLast(job)
        }
        // Kick the engine outside the lock: initialisation blocks on a latch, and holding the
        // lock across that would deadlock the very callbacks it is waiting for.
        pump()
        await(job, settings)
    }

    /** Block until the job is done, or its deadline passes. */
    private fun await(job: Job, settings: TtsSettings) {
        val timeout = TtsLimits.timeoutMsFor(settings.maxChars)
        val finished = try {
            job.done.await(timeout, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            abandon(job, "interrupted while waiting for the utterance")
            throw TtsFailed("interrupted while the phone was speaking")
        }
        if (!finished) {
            // A caller must never be told "spoken" because we stopped waiting. Dropping the job
            // and failing honestly is the only outcome that stays true.
            abandon(job, "deadline of ${timeout}ms passed before the engine finished")
            throw TtsFailed("the engine did not finish within ${timeout}ms")
        }
        job.failure?.let { throw TtsFailed(it) }
    }

    /** Remove a job we are no longer willing to wait for, stopping it if it is the one speaking. */
    private fun abandon(job: Job, why: String) {
        job.failure = why
        val wasSpeaking: Boolean
        synchronized(lock) {
            line.remove(job)
            wasSpeaking = speaking === job
            if (wasSpeaking) speaking = null
        }
        // Stop only when it is OURS. Stopping unconditionally would silence a job that started
        // after this one, which is the flush behaviour the design refuses.
        if (wasSpeaking) {
            try {
                engine?.stop()
            } catch (e: Exception) {
                com.mido.phonebridge.Debug.log("tts: stop after abandon failed (${e.message})")
            }
        }
        // The line moves on: an abandoned job must not hold the queue.
        pump()
    }

    /**
     * Start the next job if the engine is idle.
     *
     * Split out so every completion path (done, error, stop, abandon, shutdown) calls the same
     * thing — several paths each with their own idea of "what is next" is how ordering bugs and
     * wedged lines appear.
     */
    private fun pump() {
        val next: Job
        synchronized(lock) {
            if (speaking != null) return
            next = line.pollFirst() ?: return
            speaking = next
        }
        try {
            val tts = synchronized(lock) { ensureEngine(next.ctx) }
            synchronized(lock) { configure(tts, next.settings) }
            val id = ID_PREFIX + UUID.randomUUID().toString()
            val queued = tts.speak(next.text, TextToSpeech.QUEUE_ADD, null, id)
            if (queued != TextToSpeech.SUCCESS) {
                throw TtsFailed("the engine refused to speak (speak() returned $queued)")
            }
        } catch (e: Exception) {
            // Report THIS caller's failure, then let the line continue: one bad utterance must
            // not wedge every later one.
            finish(next, e.message ?: "the engine failed to start")
            pump()
        }
    }

    /** Complete a job exactly once. Late callbacks find the latch already at zero. */
    private fun finish(job: Job, failure: String?) {
        synchronized(lock) {
            if (speaking === job) speaking = null
            line.remove(job)
        }
        if (failure != null && job.failure == null) job.failure = failure
        job.done.countDown()
    }

    /**
     * Build and configure the engine once.
     *
     * Callers hold [lock]; `TextToSpeech`'s init callback arrives on the main thread and only
     * counts a latch, so blocking here cannot deadlock against itself.
     */
    private fun ensureEngine(ctx: Context): TextToSpeech {
        engine?.let { return it }
        initFailure?.let { throw TtsUnavailable(it) }

        val latch = CountDownLatch(1)
        var status = -1
        val created = TextToSpeech(ctx) { s ->
            status = s
            latch.countDown()
        }
        val ok = try {
            latch.await(INIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        if (!ok) {
            val why = "the text-to-speech engine did not initialise within ${INIT_TIMEOUT_MS}ms"
            initFailure = why
            throw TtsUnavailable(why)
        }
        if (status != TextToSpeech.SUCCESS) {
            val why = "the text-to-speech engine failed to initialise (status $status)"
            initFailure = why
            throw TtsUnavailable(why)
        }

        // USAGE_NOTIFICATION, deliberately: an utterance is an inbound event about this device,
        // so it belongs to the stream the ringer governs. THAT is the brake Mido chose
        // (2026-11-02: "a silent phone is enough") — silent/vibrate mode silences TTS for free,
        // with no toggle to remember and nothing to keep in sync.
        created.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
        created.setOnUtteranceProgressListener(progress)

        // A device with no voice for its own locale would accept every call and say nothing —
        // the exact silent no-op this file refuses to ship.
        val lang = created.setLanguage(Locale.getDefault())
        if (lang == TextToSpeech.LANG_MISSING_DATA || lang == TextToSpeech.LANG_NOT_SUPPORTED) {
            val why = "no voice is installed for ${Locale.getDefault()}"
            initFailure = why
            throw TtsUnavailable(why)
        }
        engine = created
        return created
    }

    private fun configure(tts: TextToSpeech, settings: TtsSettings) {
        // `setSpeechRate`/`setPitch` report nothing useful (the framework fails them silently), so
        // the real guarantee is that [TtsLimits] clamps the values before they are stored.
        tts.setSpeechRate(TtsLimits.clampRate(settings.rate))
        tts.setPitch(TtsLimits.clampPitch(settings.pitch))
    }

    /**
     * The engine's callbacks.
     *
     * `onDone` is the ONLY path that counts as success. `onStop` is not: it means the utterance
     * did not finish, whatever caused it, and reporting it as done would be the exact "ok for
     * something nobody heard" lie this file exists to prevent.
     */
    private val progress = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) = Unit

        override fun onDone(utteranceId: String?) {
            val job = synchronized(lock) { speaking } ?: return
            finish(job, null)
            pump()
        }

        @Deprecated("Deprecated in the framework; still called on older engines")
        override fun onError(utteranceId: String?) {
            val job = synchronized(lock) { speaking } ?: return
            finish(job, "the engine reported an error")
            pump()
        }

        override fun onError(utteranceId: String?, errorCode: Int) {
            val job = synchronized(lock) { speaking } ?: return
            finish(job, "the engine reported error $errorCode")
            pump()
        }

        override fun onStop(utteranceId: String?, interrupted: Boolean) {
            // Cancelled by [shutdown] or by the service going away. The waiting caller must hear
            // about it rather than keep blocking until its deadline.
            val job = synchronized(lock) { speaking } ?: return
            finish(job, "the utterance was stopped before it finished")
            pump()
        }
    }

    /**
     * Stop everything and release the engine. Called when the service stops.
     *
     * Every waiting caller is failed rather than left to time out: the process is going away, so
     * a request still holding a thread has to learn why instead of waiting for a deadline that
     * will never be interesting.
     */
    fun shutdown() {
        val doomed: List<Job>
        synchronized(lock) {
            doomed = line.toList() + listOfNotNull(speaking)
            line.clear()
            speaking = null
            try {
                engine?.stop()
                engine?.shutdown()
            } catch (e: Exception) {
                com.mido.phonebridge.Debug.log("tts: shutdown failed (${e.message})")
            }
            engine = null
            // A fresh service starts from a clean engine state, and a previous init failure must
            // not poison a later attempt (a voice gets installed, the engine gets enabled).
            initFailure = null
        }
        for (job in doomed) {
            if (job.failure == null) job.failure = "the service stopped before this was spoken"
            job.done.countDown()
        }
    }

    /** Engine initialisation is fast; a build that stalls here has a broken TTS stack. */
    private const val INIT_TIMEOUT_MS = 10_000L
}
