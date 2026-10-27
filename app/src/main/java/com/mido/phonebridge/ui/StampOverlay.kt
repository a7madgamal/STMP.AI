package com.mido.phonebridge.ui

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
import com.mido.phonebridge.R

/**
 * The STMP rubber-stamp approval animation — the one viral moment.
 *
 * A full-screen scrim draws over the current content, then a red ink stamp SLAMS down
 * onto the screen (an overshoot settle — the physical "thunk"), the ink "bleeds" outward
 * for a beat, and the word (APPROVED / DENIED) is revealed as punched-out paper showing
 * through the ink. Then it eases out.
 *
 * Pure Canvas + views, no raster assets, no engine: the stamp is a circle of ink with the
 * glyphs drawn as clear (paper-coloured) text on top, so it is crisp at every density and
 * costs a few draw calls. The "wobble" edge is painted by layering a slightly-smaller
 * offset circle under the main one — the same imperfect-edge trick as the launcher icon.
 *
 * Deliberately NOT a game sting: no bounce-around, no sparkle. One firm stamp, one thud,
 * one beat of settle, done.
 */
class StampOverlay(
    context: Context,
    /**
     * How much to dim the whole screen behind the stamp.
     *
     * **Not always wanted.** On the modal path the Activity is leaving, so a scrim reads as the
     * exit. On the NOTIFY path the card STAYS on screen for the whole ~1.1s animation, and a
     * full-screen dim there is just the screen going dark for no reason (Mido, 2026-10-27:
     * *"clicking the hint results a full screen dim for a few seconds"*). So the caller decides,
     * and the card path passes 0.
     */
    private val scrimAlpha: Int = 0x66,
) : View(context) {

    /** Where the stamp lands and what it says. */
    enum class Verdict(val word: String) {
        APPROVED("APPROVED"),
        DENIED("DENIED");
    }

    /**
     * The stamp comes DOWN out of the screen, so it needs real perspective.
     *
     * **This is a Canvas-level [android.graphics.Camera], not `View.rotationX`.** Rotating the View
     * was the first attempt and it was wrong: the overlay is `MATCH_PARENT`, so `rotationX` tilted
     * the SCRIM along with the stamp and the effect read as the whole screen leaning, not as an
     * object descending onto paper. A Camera transform applied around the stamp's own centre
     * leaves the scrim flat and gives the stamp true perspective.
     */
    private val camera = android.graphics.Camera().apply {
        // Negative Z pulls the camera toward the viewer, which is what produces foreshortening.
        // At 0 there is no perspective at all and rotateX degenerates into a vertical squash.
        setLocation(0f, 0f, -12f * resources.displayMetrics.density)
    }
    private val tiltMatrix = android.graphics.Matrix()

    /** Animated: -55° (tilted away, above the paper) to 0° (flat on the paper). */
    private var tilt = 0f

    /**
     * Ink colour, chosen by the DECISION.
     *
     * Approve is green, deny stays stamp-red (Mido, 2026-10-27: *"make approved green not red, keep
     * reject red"*). Both are ink analogues of `Theme.SUCCESS` / `Theme.DANGER`, so the stamp
     * agrees with the button colour the human just pressed instead of contradicting it.
     */
    private val scrimDim = (scrimAlpha.coerceIn(0, 255) shl 24) // caller-controlled; 0 = no dim

    /**
     * Overall opacity of the stamp art.
     *
     * Not fully opaque: a real stamp on paper lets the sheet through. Mido, 2026-10-27: *"make it a
     * little transparent and grainy"* — the grain is baked into the supplied artwork, so only the
     * opacity is applied here.
     */
    private val inkOpacity = 0.88f

    /** The supplied stamp artwork, keyed to transparency. Loaded once per verdict. */
    private var bitmap: android.graphics.Bitmap? = null

    private val stampPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        isFilterBitmap = true
        isDither = true
    }
    private val dst = RectF()

    // Stamp geometry, animated.
    private var stampScale = 0f
    private var stampAlpha = 0f
    private var bleed = 0f

    /** The word punched out of the ink. */
    private var word: String = Verdict.APPROVED.word

    /** Callback fired once the animation has fully settled and faded. */
    var onFinished: (() -> Unit)? = null

    private val centerX: Float
        get() = width / 2f
    private val centerY: Float
        get() = height / 2f
    private val radius: Float
        get() = maxOf(minOf(width, height) * 0.42f, 1f)

    /** True once [stamp] has been called; the animation starts on the first real layout. */
    private var pending = false

    /**
     * Begin a stamp for [verdict]; calls [onFinished] when done.
     *
     * **The animation starts from [onSizeChanged], not from here.** `radius` is derived from
     * `width`/`height`, which are 0 until the view is laid out. `post {}` was the first attempt and
     * it is NOT a layout callback — it runs after the next traversal, which may be before this view
     * has been measured. The animation then ran with `radius = 1f`, drew nothing visible, and
     * completed in a few milliseconds: the "1ms flash" (Mido, 2026-10-27). `onSizeChanged` fires
     * only when real dimensions exist, so the first animated frame is guaranteed to have geometry.
     */
    fun stamp(verdict: Verdict) {
        word = verdict.word
        stampScale = 0f
        stampAlpha = 0f
        bleed = 0f
        tilt = -55f
        // The supplied artwork, keyed to transparency. Decoded once per stamp; both are small
        // enough (151 KB / 622 KB) that re-decoding is not worth a cache.
        val res = when (verdict) {
            Verdict.APPROVED -> R.drawable.stamp_approved
            Verdict.DENIED -> R.drawable.stamp_rejected
        }
        bitmap = android.graphics.BitmapFactory.decodeResource(resources, res)
        pending = true
        // If we are ALREADY laid out (re-stamp on a visible view), start immediately.
        if (width > 0 && height > 0) {
            pending = false
            buildAnimations()
        }
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (pending && w > 0 && h > 0) {
            pending = false
            buildAnimations()
        }
    }

    private fun buildAnimations() {
        // Slam: scale from 2.2x down to 1.0 with a slight overshoot (the thunk), while the
        // stamp rotates flat out of the screen.
        val slam = ObjectAnimator.ofFloat(this, "stampScale", 2.2f, 0.92f, 1.0f).apply {
            duration = 150
            interpolator = DecelerateInterpolator()
        }
        // The stamp must be OPAQUE before the tilt is worth watching. The first version faded in
        // over 80ms while the tilt finished in 180ms — so by the time the ink was visible it was
        // already flat, and the 3D happened entirely during the transparent phase. Fade faster
        // than the tilt, and run the tilt long enough to be seen as motion.
        val fadeIn = ObjectAnimator.ofFloat(this, "stampAlpha", 0f, 1f).apply {
            duration = 45
            interpolator = LinearInterpolator()
        }
        val flatten = ObjectAnimator.ofFloat(this, "tilt", -55f, 0f).apply {
            duration = 220
            interpolator = DecelerateInterpolator()
        }
        // Ink bleed: after the thunk, the edge spreads for a beat.
        val bleedAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 260
            startDelay = 130
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { bleed = it.animatedValue as Float }
        }
        // Settle + out.
        val settle = ObjectAnimator.ofFloat(this, "stampScale", 1.0f, 0.98f).apply {
            duration = 260
            startDelay = 230
            interpolator = AccelerateDecelerateInterpolator()
        }
        val fadeOut = ObjectAnimator.ofFloat(this, "stampAlpha", 1f, 0f).apply {
            duration = 180
            startDelay = 560
            interpolator = AccelerateDecelerateInterpolator()
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: android.animation.Animator) {
                    onFinished?.invoke()
                }
            })
        }
        AnimatorSet().apply {
            play(slam).with(fadeIn).with(flatten)
            play(bleedAnim).after(slam)
            play(settle).after(bleedAnim)
            play(fadeOut).after(settle)
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (stampAlpha <= 0f) return

        // Scrim stays FLAT. It is drawn before the camera transform so the dim does not lean with
        // the stamp — a tilting scrim looks like the screen falling over.
        if (scrimAlpha > 0) canvas.drawColor(scrimDim)

        val cx = centerX
        val cy = centerY
        val r = radius * stampScale
        if (r <= 0f) return

        // Everything below is drawn THROUGH the 3D transform, pivoted on the stamp's own centre.
        canvas.save()
        if (tilt != 0f) {
            camera.save()
            camera.rotateX(tilt)
            camera.getMatrix(tiltMatrix)
            camera.restore()
            // Camera works in its own space; move the pivot to the stamp centre and back.
            tiltMatrix.preTranslate(-cx, -cy)
            tiltMatrix.postTranslate(cx, cy)
            canvas.concat(tiltMatrix)
        }

        // **The stamp is the supplied artwork, not a drawn disc.**
        //
        // The hand-painted circles and punched-out text are gone: Mido supplied two finished stamps
        // (2026-10-27: *"use those pngs instead for the stamp ... just remove old text or material
        // and use those"*). The 3D tilt, the slam scale, the fade and the sound all still apply —
        // only the pixels changed.
        val art = bitmap ?: return
        if (art.width <= 0 || art.height <= 0) return

        // Fit inside the view with a margin, then apply the slam scale. Using the bitmap's own
        // aspect ratio means the wide APPROVED mark and the tall REJECTED mark both sit correctly.
        val fit = minOf(
            (width * 0.82f) / art.width,
            (height * 0.42f) / art.height
        ) * stampScale
        if (fit <= 0f) return

        val w = art.width * fit
        val h = art.height * fit
        dst.set(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f)

        // `stampAlpha` is 0..1; a Paint alpha is 0..255. Passing the raw float was the bug that
        // made the drawn stamp invisible for four releases.
        stampPaint.alpha = (stampAlpha.coerceIn(0f, 1f) * inkOpacity * 255f).toInt()
        canvas.drawBitmap(art, null, dst, stampPaint)

        canvas.restore()
    }

    // Property setters so ObjectAnimator can drive these by name.
    fun setStampScale(v: Float) { stampScale = v; invalidate() }
    fun setStampAlpha(v: Float) { stampAlpha = v; invalidate() }

    /**
     * The 3D descent angle, in degrees.
     *
     * Applied to the CANVAS through [camera] inside `onDraw`, not to the View. `View.rotationX`
     * would tilt the full-screen scrim along with the stamp, which reads as the whole screen
     * leaning rather than an object pressing down.
     */
    fun setTilt(v: Float) { tilt = v; invalidate() }

    // Scrim gradient not used; kept simple with a flat dim for a paper feel.
}
