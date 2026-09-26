package io.github.youxufkhan.carozerra

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View
import java.util.Calendar

/** Fractions of the 256x64 OEL frame. No two zones overlap. */
object Zones {
    val CLOCK = RectF(0.02f, 0.04f, 0.20f, 0.24f)
    val METERS = RectF(0.80f, 0.08f, 0.98f, 0.62f)
    val INDICATORS = RectF(0.80f, 0.64f, 0.98f, 0.84f)
    val TEXT_LINE = RectF(0.02f, 0.86f, 0.98f, 0.99f)

    fun inScreen(zone: RectF, screen: RectF) = RectF(
        screen.left + zone.left * screen.width(),
        screen.top + zone.top * screen.height(),
        screen.left + zone.right * screen.width(),
        screen.top + zone.bottom * screen.height()
    )
}

const val OEL_CYAN = 0xFF12E0FF.toInt()

/**
 * Text uses Smallest Pixel-7, a 4x5 pixel font: at textSize 10 one font pixel is
 * one screen pixel. [oelText] sizes it in whole OEL pixels (the display is 64
 * rows tall), so each glyph pixel covers exactly 1 or 2 lit OEL cells.
 */
class OelOverlays(typeface: Typeface) {

    var displayMode: Int = 0            // 0 clean, 1 clock, 2 clock + metadata
        set(value) { field = ((value % 3) + 3) % 3 }
    var textLine: Boolean = false
    var meters: Boolean = false
    var meterLevel: Float = 0f

    private val text = Paint().apply {
        isAntiAlias = false
        color = OEL_CYAN
        this.typeface = typeface
    }

    private val meterBg = Paint().apply { color = 0xFF000000.toInt() }
    private val meterOn = Paint().apply { isAntiAlias = false; color = OEL_CYAN }
    private val meterOff = Paint().apply { isAntiAlias = false; color = 0xFF0A3540.toInt() }

    private var peakHold = 0f

    private var scrollOffset = 0f

    fun draw(
        canvas: Canvas, screen: RectF, clipName: String, category: String, frames: Int,
        showLoud: Boolean, showEqEx: Boolean, captureAvailable: Boolean,
    ) {
        if (displayMode >= 1) drawClock(canvas, screen)
        if (displayMode >= 2) drawMeta(canvas, screen, clipName, category)
        if (meters && captureAvailable) drawMeters(canvas, screen)
        if (showEqEx || showLoud) drawIndicators(canvas, screen, showLoud, showEqEx)
        if (textLine) drawTextLine(canvas, screen, clipName, category, frames)
    }

    private fun drawMeters(canvas: Canvas, screen: RectF) {
        val r = Zones.inScreen(Zones.METERS, screen)
        canvas.drawRect(r, meterBg)

        peakHold = if (meterLevel > peakHold) meterLevel else (peakHold - 0.012f).coerceAtLeast(0f)

        val segments = 10
        val colGap = r.width() * 0.12f
        val colW = (r.width() - colGap) / 2f
        val segH = r.height() / segments
        for (col in 0..1) {
            val x0 = r.left + col * (colW + colGap)
            for (s in 0 until segments) {
                val lit = (segments - s) <= Math.round(meterLevel * segments)
                val top = r.top + s * segH
                canvas.drawRect(
                    x0, top + segH * 0.15f, x0 + colW, top + segH * 0.85f,
                    if (lit) meterOn else meterOff
                )
            }
            val peakSeg = segments - Math.round(peakHold * segments)
            if (peakSeg in 0 until segments) {
                val top = r.top + peakSeg * segH
                canvas.drawRect(x0, top, x0 + colW, top + segH * 0.18f, meterOn)
            }
        }
    }

    private fun drawClock(canvas: Canvas, screen: RectF) {
        val r = Zones.inScreen(Zones.CLOCK, screen)
        val now = Calendar.getInstance()
        val label = "%d:%02d".format(
            now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE)
        )
        val px = oelText(screen, 2)
        plated(canvas, label, r.left, r.bottom, 2, px)
    }

    private fun drawMeta(canvas: Canvas, screen: RectF, clipName: String, category: String) {
        val r = Zones.inScreen(Zones.CLOCK, screen)
        val px = oelText(screen, 1)
        plated(canvas, "${clipName.removeSuffix(".lkd")}  $category", r.left, r.bottom + 7 * px, 1, px)
    }

    private fun drawIndicators(canvas: Canvas, screen: RectF, showLoud: Boolean, showEqEx: Boolean) {
        val r = Zones.inScreen(Zones.INDICATORS, screen)
        val px = oelText(screen, 1)
        if (showLoud) plated(canvas, "LOUD", r.left, r.top + 5 * px, 1, px)
        if (showEqEx) plated(canvas, "EQ-EX", r.left, r.top + 12 * px, 1, px)
    }

    private val plate = Paint().apply { color = 0xFF000000.toInt() }

    /**
     * Text on a black cut-out, the way the real unit shows its clock and
     * indicators over the animation — cyan on a lit cyan clip is unreadable.
     * Glyphs are 5 font pixels tall and sit on the baseline.
     */
    private fun plated(canvas: Canvas, label: String, x: Float, baseline: Float, scale: Int, px: Float) {
        val w = text.measureText(label)
        canvas.drawRect(x - px, baseline - (5 * scale + 1) * px, x + w + px, baseline + px, plate)
        canvas.drawText(label, x, baseline, text)
    }

    /** Size [text] so one font pixel is [scale] OEL pixels; returns one OEL pixel. */
    private fun oelText(screen: RectF, scale: Int): Float {
        val px = screen.height() / 64f
        text.textSize = 10f * scale * px
        return px
    }

    private fun drawTextLine(
        canvas: Canvas, screen: RectF, clipName: String, category: String, frames: Int
    ) {
        val r = Zones.inScreen(Zones.TEXT_LINE, screen)
        // No track title: transport is write-only, so the app has no source for
        // one, and scrolling a guessed title would be the knob that lies.
        val label = "${clipName.removeSuffix(".lkd")}  ·  $category  ·  $frames FRAMES      "
        oelText(screen, 1)
        val w = text.measureText(label)
        scrollOffset = (scrollOffset + r.width() * 0.004f) % w
        canvas.save()
        canvas.clipRect(r)
        canvas.drawRect(r, plate)
        var x = r.left - scrollOffset
        while (x < r.right) {
            canvas.drawText(label, x, r.bottom, text)
            x += w
        }
        canvas.restore()
    }
}

/** Every live control, in the manual's own numbering. */
object ControlMap {
    val ROWS: List<Pair<String, String>> = listOf(
        "TA" to "mute",
        "VOLUME turn" to "system volume",
        "VOLUME press" to "blackout · any touch wakes",
        "DISPLAY" to "clean / clock / clock + info",
        "TEXT" to "scrolling text line",
        "FUNCTION" to "glow on/off",
        "AUDIO" to "level meters",
        "NAV left / right" to "previous / next track",
        "NAV up / down" to "animation speed",
        "NAV press" to "play / pause",
        "OPEN" to "this card",
        "BAND" to "close · hold to exit",
        "ENTERTAINMENT" to "clip gallery",
        "EQ-EX" to "scanlines",
        "1 - 6" to "clips 1-6 of the category",
        "EQ" to "glow intensity",
        "SOURCE" to "next category",
        "swipe" to "previous / next clip",
    )

    val ABOUT: List<String> = listOf(
        "CAROZERRA — Pioneer DEH-P7600MP visualizer",
        "83 decoded .lkd animations · MIT licensed",
        "Display font: Smallest Pixel-7 by Sizenko Alexander, styleseven.com",
        "github.com/youxufkhan/carozerra",
    )
}

class CardOverlay(context: Context) : View(context) {

    private val bg = Paint().apply { color = 0xEA080C10.toInt() }
    private val edge = Paint().apply {
        style = Paint.Style.STROKE; strokeWidth = 2f; color = 0x9612E0FF.toInt()
    }
    private val key = Paint().apply {
        isAntiAlias = true; color = OEL_CYAN
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }
    private val value = Paint().apply { isAntiAlias = true; color = 0xFFD6E2EA.toInt() }
    private val dim = Paint().apply { isAntiAlias = true; color = 0xFF8CA2B0.toInt() }

    val isShowing: Boolean get() = visibility == VISIBLE

    init {
        visibility = GONE
        setOnClickListener { hide() }
    }

    fun show() { visibility = VISIBLE; invalidate() }
    fun hide() { visibility = GONE }

    override fun onDraw(canvas: Canvas) {
        // Two columns: 18 rows will not fit legibly in one on a 600px-tall panel.
        val pad = width * 0.04f
        val box = RectF(pad, pad, width - pad, height - pad)
        canvas.drawRoundRect(box, 16f, 16f, bg)
        canvas.drawRoundRect(box, 16f, 16f, edge)

        val rowsPerCol = (ControlMap.ROWS.size + 1) / 2
        // Extra rows reserved below the data grid so the about block never
        // collides with the last data row (it did, at the old row height).
        val rowH = (box.height() - pad * 3f) / (rowsPerCol + 2 + ControlMap.ABOUT.size)
        key.textSize = rowH * 0.58f
        value.textSize = rowH * 0.5f
        dim.textSize = rowH * 0.42f

        canvas.drawText("CAROZERRA — CONTROLS", box.left + pad, box.top + pad + rowH, key)

        val colW = (box.width() - pad * 2f) / 2f
        // Measure the widest key so the value column starts clear of it,
        // instead of a fixed fraction that only fit the shortest labels.
        val valueOffset = ControlMap.ROWS.maxOf { key.measureText(it.first) } + rowH * 0.4f
        ControlMap.ROWS.forEachIndexed { i, (k, v) ->
            val col = i / rowsPerCol
            val row = i % rowsPerCol
            val x = box.left + pad + col * colW
            val y = box.top + pad + rowH * (row + 2.4f)
            canvas.drawText(k, x, y, key)
            // Clip so a long value (e.g. the blackout row) can never bleed
            // into the next column's key, whatever the measured offset is.
            canvas.save()
            canvas.clipRect(x + valueOffset, box.top, x + colW, box.bottom)
            canvas.drawText(v, x + valueOffset, y, value)
            canvas.restore()
        }

        ControlMap.ABOUT.forEachIndexed { i, line ->
            canvas.drawText(
                line, box.left + pad,
                box.bottom - pad - dim.textSize * (ControlMap.ABOUT.size - i - 1) * 1.3f, dim
            )
        }
    }
}
