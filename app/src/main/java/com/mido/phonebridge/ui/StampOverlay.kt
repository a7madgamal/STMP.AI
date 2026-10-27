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
class StampOverlay(context: Context) : View(context) {

    /** Where the stamp lands and what it says. */
    enum class Verdict(val word: String) {
        APPROVED("APPROVED"),
        DENIED("DENIED");
    }

    /**
     * The stamp comes DOWN out of the screen, so it needs a real perspective.
     *
     * `rotationX` alone is a flat skew; `cameraDistance` is what makes the near edge bigger than
     * the far one. A large distance flattens the effect to nothing, a small one warps it into a
     * fisheye — this value is the range where it reads as "a hand pressing down".
     */
    private val cameraDistancePx: Float = 8000f * resources.displayMetrics.density

    /** Animated: -55° (tilted away, above the paper) to 0° (flat on the paper). */
    private var tilt = 0f

    private val ink = 0xFFB23A2F.toInt()
    private val paper = 0xFFF5EEDF.toInt()
    private val scrimDim = 0x66_000000 // faint darkening so the stamp pops against any screen

    private val inkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val inkSoftPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val glyphPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = paper
        style = Paint.Style.FILL
    }

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

    /** Begin a stamp for [verdict]; calls [onFinished] when done. */
    fun stamp(verdict: Verdict) {
        word = verdict.word
        stampScale = 0f
        stampAlpha = 0f
        bleed = 0f
        tilt = -55f
        // A round stamp that is bigger than the available ring gets the word pushed out of
        // bounds, so ADAPT the glyph size to the word length at layout time — here only.
        buildAnimations()
    }

    private fun buildAnimations() {
        // 3D: set the camera ONCE, then animate the X rotation. Doing it per-frame would
        // rebuild the matrix every draw and the perspective would jitter.
        cameraDistance = cameraDistancePx

        // Slam: scale from 2.2x down to 1.0 with a slight overshoot (the thunk), while the
        // stamp rotates flat out of the screen.
        val slam = ObjectAnimator.ofFloat(this, "stampScale", 2.2f, 0.92f, 1.0f).apply {
            duration = 220
            interpolator = DecelerateInterpolator()
        }
        // The tilt lands slightly BEFORE the scale settles, so the face is flat when the ink
        // hits — a stamp that is still angled at contact reads as a hovering sticker.
        val flatten = ObjectAnimator.ofFloat(this, "tilt", -55f, 0f).apply {
            duration = 180
            interpolator = DecelerateInterpolator()
        }
        val fadeIn = ObjectAnimator.ofFloat(this, "stampAlpha", 0f, 1f).apply {
            duration = 80
            interpolator = LinearInterpolator()
        }
        // Ink bleed: after the thunk, the edge spreads for a beat.
        val bleedAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 420
            startDelay = 200
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { bleed = it.animatedValue as Float }
        }
        // Settle + out.
        val settle = ObjectAnimator.ofFloat(this, "stampScale", 1.0f, 0.98f).apply {
            duration = 500
            startDelay = 320
            interpolator = AccelerateDecelerateInterpolator()
        }
        val fadeOut = ObjectAnimator.ofFloat(this, "stampAlpha", 1f, 0f).apply {
            duration = 260
            startDelay = 820
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

        // Scrim.
        canvas.drawColor(scrimDim)

        val cx = centerX
        val cy = centerY
        val r = radius * stampScale
        if (r <= 0f) return

        // Pass 1: the soft, slightly-offset "bleed" halo behind the stamp — the ink soaking
        // into the paper. Alpha follows bleed; radius expands slightly.
        val bleedR = r * (1f + 0.10f * bleed)
        inkSoftPaint.color = colorWithAlpha(ink, (stampAlpha * 0.35f * (1f - bleed)).toInt())
        canvas.drawCircle(cx, cy, bleedR, inkSoftPaint)

        // Pass 2: the scraped imperfect edge — a solid circle slightly offset from centre,
        // then the crisp main circle on top. Together they read as an inked rim, not a
        // vector perfect circle.
        edgePaint.color = colorWithAlpha(ink, (stampAlpha * 0.8f).toInt())
        canvas.drawCircle(cx - r * 0.015f, cy + r * 0.02f, r * 0.97f, edgePaint)

        // Pass 3: the main ink disc.
        inkPaint.color = colorWithAlpha(ink, (stampAlpha).toInt())
        canvas.drawCircle(cx, cy, r, inkPaint)

        // Pass 4: the word, punched out (paper colour on ink). Two-line stacked centre text.
        val label = word
        val textSize = r * 1.18f / maxOf(label.length, 1)
        glyphPaint.textSize = textSize
        glyphPaint.typeface = Theme.handFont(context)
        glyphPaint.textAlign = Paint.Align.CENTER
        glyphPaint.letterSpacing = 0.04f
        val baseline = cy - (glyphPaint.descent() + glyphPaint.ascent()) / 2f
        canvas.drawText(label, cx, baseline, glyphPaint)

        // A faint paper ring just inside the ink rim, so the letters read as stamped-through.
        glyphPaint.style = Paint.Style.STROKE
        glyphPaint.strokeWidth = r * 0.02f
        glyphPaint.color = colorWithAlpha(paper, (stampAlpha * 0.5f).toInt())
        canvas.drawCircle(cx, cy, r * 0.85f, glyphPaint)
        glyphPaint.style = Paint.Style.FILL
    }

    private fun colorWithAlpha(color: Int, alpha: Int): Int =
        (color and 0x00FFFFFF) or ((alpha.coerceIn(0, 255)) shl 24)

    // Property setters so ObjectAnimator can drive these by name.
    fun setStampScale(v: Float) { stampScale = v; invalidate() }
    fun setStampAlpha(v: Float) { stampAlpha = v; invalidate() }

    /**
     * The 3D descent.
     *
     * Set on the VIEW, not the canvas: `Canvas` has no X-axis rotation, and faking it with a
     * matrix would bypass the camera that makes the perspective correct. `rotationX` plus
     * `cameraDistance` is the platform's own 3D path and costs one matrix per frame.
     */
    fun setTilt(v: Float) { tilt = v; rotationX = v; invalidate() }

    // Scrim gradient not used; kept simple with a flat dim for a paper feel.
}
