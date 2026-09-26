package io.github.youxufkhan.carozerra

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.view.View

class FaceplateView(
    context: Context,
    private val repo: ClipRepository,
) : View(context) {

    private val face: Bitmap = context.assets.open("pioneer.png").use {
        // The source PNG is 1600x893 with a transparent margin; the visible
        // faceplate is the 1559x503 box at (22,204). Cropping keeps the app's
        // coordinate space identical to carozerra.py's.
        val full = BitmapFactory.decodeStream(it)
        Bitmap.createBitmap(full, 22, 204, 1559, 503)
    }

    private val facePaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val crisp = Paint()                       // no filtering: hard OEL pixels
    private val frameRect = RectF()
    private val src = Rect()

    private var clip: RenderClip? = null
    private var frame = 0

    var clipName: String = ClipCatalog.all.first()
        set(value) {
            field = value
            loadClip(value)
        }

    /**
     * Decoding is a gunzip, a tar walk, 60 BMP slices and 60 scaled bitmaps —
     * around 4.3 MB. On a weak head-unit SoC that is a visible stall if it runs
     * on the UI thread, so it never does. The faceplate paints immediately and
     * the OEL fills in when the clip lands.
     */
    private fun loadClip(name: String) {
        Thread {
            val loaded = try {
                repo.render(name)
            } catch (e: Throwable) {
                android.util.Log.e("FaceplateView", "decode failed for $name", e)
                return@Thread
            }
            post {
                if (clipName == name) {
                    clip = loaded
                    frame = 0
                    invalidate()
                }
            }
        }.start()
    }

    init { loadClip(clipName) }

    /** False until the first background decode lands. The selftest waits on it. */
    val clipLoaded: Boolean get() = clip != null

    var categoryIndex: Int = 0
        private set

    private val categoryClips: List<String>
        get() = ClipCatalog.categories[categoryIndex].clips

    private var indexInCategory = 0

    fun nextClip(delta: Int) {
        val n = categoryClips.size
        indexInCategory = ((indexInCategory + delta) % n + n) % n
        clipName = categoryClips[indexInCategory]
    }

    fun selectPreset(i: Int) {
        if (i in categoryClips.indices) {
            indexInCategory = i
            clipName = categoryClips[i]
        }
    }

    fun cycleCategory() {
        categoryIndex = (categoryIndex + 1) % ClipCatalog.categories.size
        indexInCategory = 0
        clipName = categoryClips[0]
    }

    fun select(name: String) {
        val catIdx = ClipCatalog.categories.indexOfFirst { name in it.clips }
        if (catIdx < 0) return
        categoryIndex = catIdx
        indexInCategory = categoryClips.indexOf(name).coerceAtLeast(0)
        clipName = name
    }

    var onControl: ((Hit) -> Boolean)? = null
    var onVolumeDrag: ((Float) -> Unit)? = null
    var onKnobTap: (() -> Unit)? = null
    var onKnobLongPress: (() -> Unit)? = null
    var onBandLongPress: (() -> Unit)? = null

    var blackout: Boolean = false
        set(value) { field = value; invalidate() }

    var volumePercent: Int = 50
        set(value) { field = value.coerceIn(0, 100); invalidate() }

    private val knob: Bitmap = run {
        val rx = (Geometry.LKNOB_DISC_X * Geometry.BASE_W).toInt()
        val ry = (Geometry.LKNOB_DISC_Y * Geometry.BASE_H).toInt()
        val cx = (Geometry.LKNOB.first * Geometry.BASE_W).toInt()
        val cy = (Geometry.LKNOB.second * Geometry.BASE_H).toInt()
        Bitmap.createBitmap(face, cx - rx, cy - ry, 2 * rx, 2 * ry)
    }
    private val knobClip = android.graphics.Path()

    private fun drawKnob(canvas: Canvas) {
        val r = faceplateRect()
        val cx = r.left + Geometry.LKNOB.first * r.width()
        val cy = r.top + Geometry.LKNOB.second * r.height()
        val radius = Geometry.LKNOB_DISC_X * r.width()
        // -140deg at 0%, +140deg at 100%: the same sweep as the desktop app.
        val angle = -140f + volumePercent / 100f * 280f

        canvas.save()
        canvas.translate(cx, cy)
        // Clip first, rotate second: the circular mask must not rotate with the art.
        knobClip.reset()
        knobClip.addCircle(0f, 0f, radius, android.graphics.Path.Direction.CW)
        canvas.clipPath(knobClip)
        canvas.rotate(angle)
        src.set(0, 0, knob.width, knob.height)
        canvas.drawBitmap(knob, src, RectF(-radius, -radius, radius, radius), facePaint)
        canvas.restore()
    }

    private var downX = 0f
    private var downY = 0f
    private var downAt = 0L
    private var draggingKnob = false
    private var knobAngle = 0f
    private var dragPct = 0f
    private var swipeCandidate = false

    private val tapSlopPx get() = 0.02f * faceplateRect().width()

    private var wokeThisGesture = false

    override fun onTouchEvent(event: android.view.MotionEvent): Boolean {
        if (event.actionMasked == android.view.MotionEvent.ACTION_DOWN) {
            wokeThisGesture = false
        }
        if (blackout && event.actionMasked == android.view.MotionEvent.ACTION_DOWN) {
            wokeThisGesture = true
            onKnobTap?.invoke()
            return true
        }
        if (wokeThisGesture) return true

        val (bx, by) = toBase(event.x, event.y).let { Pair(it[0], it[1]) }
        when (event.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> {
                downX = event.x; downY = event.y; downAt = event.eventTime
                val hit = Geometry.hit(bx, by)
                draggingKnob = hit?.control == Control.VOLUME
                swipeCandidate = hit == null
                if (draggingKnob) {
                    knobAngle = angleToKnob(bx, by)
                    dragPct = volumePercent.toFloat()
                }
                return true
            }
            android.view.MotionEvent.ACTION_MOVE -> {
                if (draggingKnob) {
                    val a = angleToKnob(bx, by)
                    var d = a - knobAngle
                    if (d > 180f) d -= 360f
                    if (d < -180f) d += 360f
                    if (kotlin.math.abs(d) > 0.5f) {
                        knobAngle = a
                        dragPct = (dragPct + d / 280f * 100f).coerceIn(0f, 100f)
                        onVolumeDrag?.invoke(dragPct)
                    }
                }
                return true
            }
            android.view.MotionEvent.ACTION_UP -> {
                val travelled = kotlin.math.hypot(event.x - downX, event.y - downY)
                val held = event.eventTime - downAt

                if (draggingKnob) {
                    draggingKnob = false
                    // A tap is a short, still press. Without this every volume
                    // adjustment would also fire the knob's press action.
                    if (travelled < tapSlopPx) {
                        if (held >= 600L) onKnobLongPress?.invoke() else onKnobTap?.invoke()
                    }
                    return true
                }
                if (swipeCandidate && travelled > 4 * tapSlopPx &&
                    kotlin.math.abs(event.x - downX) > kotlin.math.abs(event.y - downY)
                ) {
                    nextClip(if (event.x < downX) 1 else -1)
                    return true
                }
                val hit = Geometry.hit(bx, by)
                if (hit?.control == Control.BAND && held >= 600L) {
                    onBandLongPress?.invoke()
                } else {
                    hit?.let { onControl?.invoke(it) }
                }
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun angleToKnob(bx: Float, by: Float): Float {
        val lx = Geometry.LKNOB.first * Geometry.BASE_W
        val ly = Geometry.LKNOB.second * Geometry.BASE_H
        return Math.toDegrees(
            kotlin.math.atan2((by - ly).toDouble(), (bx - lx).toDouble())
        ).toFloat()
    }

    var fps: Int = 16
        set(value) { field = value.coerceIn(4, 30) }

    var glow: Boolean = true
        set(value) { field = value; invalidate() }

    /** 0 = off, 1 = soft, 2 = full. Cycled by the EQ button. */
    var glowIntensity: Int = 2
        set(value) { field = value.coerceIn(0, 2); invalidate() }

    var scanlines: Boolean = true
        set(value) { field = value; invalidate() }

    private val pixelFont: Typeface = context.resources.getFont(R.font.smallest_pixel_7)

    val overlays = OelOverlays(pixelFont)

    private val flashPaint = Paint().apply {
        isAntiAlias = false
        color = OEL_CYAN
        textAlign = Paint.Align.CENTER
        typeface = pixelFont
    }
    private val flashPlate = Paint().apply { color = 0xFF000000.toInt() }

    private var flashText: String? = null
    private var flashUntil = 0L

    /** A short message centred on the OEL — used when a control has nothing honest to do. */
    fun flash(message: String) {
        flashText = message
        flashUntil = android.os.SystemClock.elapsedRealtime() + 1500L
        invalidate()
    }

    private val bloom = Paint(Paint.FILTER_BITMAP_FLAG).apply {
        blendMode = android.graphics.BlendMode.SCREEN
    }
    private val scanPaint = Paint().apply { color = 0x46000000 }
    private var scanLines = FloatArray(0)
    private var scanForHeight = -1f

    var levelProvider: (() -> Float)? = null
    var availableProvider: (() -> Boolean)? = null

    private val ticker = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            clip?.let { frame = (frame + 1) % it.frames.size }
            levelProvider?.let { overlays.meterLevel = it() }
            invalidate()
            ticker.postDelayed(this, (1000L / fps))
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        ticker.postDelayed(tick, (1000L / fps))
    }

    override fun onDetachedFromWindow() {
        ticker.removeCallbacks(tick)
        super.onDetachedFromWindow()
    }

    /** Contain-fit: same math as carozerra.py's fit(). */
    fun faceplateRect(): RectF {
        val s = minOf(width / Geometry.BASE_W, height / Geometry.BASE_H)
        val fw = Geometry.BASE_W * s
        val fh = Geometry.BASE_H * s
        frameRect.set((width - fw) / 2f, (height - fh) / 2f,
                      (width - fw) / 2f + fw, (height - fh) / 2f + fh)
        return frameRect
    }

    /** Screen pixels -> faceplate pixel space, the inverse of the contain fit. */
    fun toBase(x: Float, y: Float): FloatArray {
        val r = faceplateRect()
        val s = r.width() / Geometry.BASE_W
        return floatArrayOf((x - r.left) / s, (y - r.top) / s)
    }

    fun screenRect(): RectF {
        val r = faceplateRect()
        val left = r.left + Geometry.SCREEN[0] * r.width()
        val top = r.top + Geometry.SCREEN[1] * r.height()
        return RectF(
            left, top,
            left + Geometry.SCREEN[2] * r.width(),
            top + Geometry.SCREEN[3] * r.height()
        )
    }

    override fun onDraw(canvas: Canvas) {
        if (blackout) { canvas.drawColor(0xFF000000.toInt()); return }
        val r = faceplateRect()
        src.set(0, 0, face.width, face.height)
        canvas.drawBitmap(face, src, r, facePaint)
        drawKnob(canvas)

        val c = clip ?: return
        val screen = screenRect()
        val bmp = c.frames[frame]
        src.set(0, 0, bmp.width, bmp.height)
        canvas.drawBitmap(bmp, src, screen, crisp)

        if (glow && glowIntensity > 0) {
            val blur = c.blur[frame]
            src.set(0, 0, blur.width, blur.height)
            val alphas = if (glowIntensity == 1) intArrayOf(120) else intArrayOf(217, 128)
            for (a in alphas) {
                bloom.alpha = a
                canvas.drawBitmap(blur, src, screen, bloom)
            }
        }

        overlays.draw(
            canvas, screen, clipName,
            ClipCatalog.categoryOf(clipName), c.frames.size,
            glowIntensity == 2, scanlines, availableProvider?.invoke() ?: false,
        )

        if (scanlines) {
            if (scanForHeight != screen.height()) buildScanLines(screen)
            canvas.drawLines(scanLines, scanPaint)
        }

        flashText?.let {
            if (android.os.SystemClock.elapsedRealtime() > flashUntil) {
                flashText = null
            } else {
                val s = screenRect()
                val px = s.height() / 64f
                flashPaint.textSize = 20f * px     // font pixel = 2 OEL pixels
                val baseline = s.centerY() + 5 * px
                val half = flashPaint.measureText(it) / 2 + 2 * px
                canvas.drawRect(s.centerX() - half, baseline - 12 * px, s.centerX() + half, baseline + 2 * px, flashPlate)
                canvas.drawText(it, s.centerX(), baseline, flashPaint)
            }
        }

        if (debugHitboxes) drawHitboxes(canvas)
    }

    /** Test-only entry point: `onDraw` is protected, this exposes the same real render path. */
    fun renderTo(canvas: Canvas) = onDraw(canvas)

    /** Debug builds only: strokes every hitbox so the measurements can be checked. */
    var debugHitboxes: Boolean = false
        set(value) { field = value; invalidate() }

    private val debugPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = 0xFFFF3B30.toInt()
    }
    private val debugLabel = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFD60A.toInt()
        textSize = 22f
    }

    private fun drawHitboxes(canvas: Canvas) {
        val r = faceplateRect()
        fun fx(v: Float) = r.left + v * r.width()
        fun fy(v: Float) = r.top + v * r.height()

        for ((control, b) in Geometry.boxes) {
            canvas.drawRect(
                fx(b.cx - b.hw), fy(b.cy - b.hh),
                fx(b.cx + b.hw), fy(b.cy + b.hh), debugPaint
            )
            canvas.drawText(control.name, fx(b.cx - b.hw), fy(b.cy - b.hh) - 4f, debugLabel)
        }
        for (i in Geometry.PRESETS_X.indices) {
            canvas.drawRect(
                fx(Geometry.PRESETS_X[i] - Geometry.PRESET_HW),
                fy(Geometry.PRESETS_Y - Geometry.PRESET_HH),
                fx(Geometry.PRESETS_X[i] + Geometry.PRESET_HW),
                fy(Geometry.PRESETS_Y + Geometry.PRESET_HH), debugPaint
            )
        }
        canvas.drawCircle(fx(Geometry.LKNOB.first), fy(Geometry.LKNOB.second),
            Geometry.LKNOB_HIT * r.width(), debugPaint)
        canvas.drawCircle(fx(Geometry.RKNOB.first), fy(Geometry.RKNOB.second),
            Geometry.RKNOB_HIT * r.width(), debugPaint)
        canvas.drawCircle(fx(Geometry.RKNOB.first), fy(Geometry.RKNOB.second),
            Geometry.RKNOB_CENTER_HIT * r.width(), debugPaint)
    }

    /** One drawLines call beats ~64 drawLine calls per frame. */
    private fun buildScanLines(screen: RectF) {
        val step = maxOf(2f, screen.height() / 64f)
        val n = (screen.height() / step).toInt()
        val pts = FloatArray(n * 4)
        var y = screen.top
        for (i in 0 until n) {
            pts[i * 4] = screen.left
            pts[i * 4 + 1] = y
            pts[i * 4 + 2] = screen.right
            pts[i * 4 + 3] = y
            y += step
        }
        scanLines = pts
        scanForHeight = screen.height()
    }
}
