package io.github.youxufkhan.carozerra

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
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
 * Text is a system monospace face with antialiasing off, tinted OEL cyan and
 * scaled to the zone. At this size that already yields hard pixel edges, and the
 * bloom and scanline passes run over the result — a hand-authored bitmap font
 * would cost ~40 glyphs of work for no visible gain.
 */
class OelOverlays {

    var displayMode: Int = 0            // 0 clean, 1 clock, 2 clock + metadata
        set(value) { field = ((value % 3) + 3) % 3 }
    var textLine: Boolean = false
    var meters: Boolean = false
    var meterLevel: Float = 0f

    private val text = Paint().apply {
        isAntiAlias = false
        color = OEL_CYAN
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
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
        text.textSize = r.height()
        canvas.drawText(label, r.left, r.bottom, text)
    }

    private fun drawMeta(canvas: Canvas, screen: RectF, clipName: String, category: String) {
        val r = Zones.inScreen(Zones.CLOCK, screen)
        text.textSize = r.height() * 0.5f
        canvas.drawText(
            "${clipName.removeSuffix(".lkd")}  $category",
            r.left, r.bottom + r.height() * 0.7f, text
        )
    }

    private fun drawIndicators(canvas: Canvas, screen: RectF, showLoud: Boolean, showEqEx: Boolean) {
        val r = Zones.inScreen(Zones.INDICATORS, screen)
        text.textSize = r.height() * 0.45f
        if (showLoud) canvas.drawText("LOUD", r.left, r.top + r.height() * 0.45f, text)
        if (showEqEx) canvas.drawText("EQ-EX", r.left, r.bottom, text)
    }

    private fun drawTextLine(
        canvas: Canvas, screen: RectF, clipName: String, category: String, frames: Int
    ) {
        val r = Zones.inScreen(Zones.TEXT_LINE, screen)
        // No track title: transport is write-only, so the app has no source for
        // one, and scrolling a guessed title would be the knob that lies.
        val label = "${clipName.removeSuffix(".lkd")}  ·  $category  ·  $frames FRAMES      "
        text.textSize = r.height()
        val w = text.measureText(label)
        scrollOffset = (scrollOffset + r.width() * 0.004f) % w
        canvas.save()
        canvas.clipRect(r)
        var x = r.left - scrollOffset
        while (x < r.right) {
            canvas.drawText(label, x, r.bottom, text)
            x += w
        }
        canvas.restore()
    }
}
