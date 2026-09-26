package io.github.youxufkhan.carozerra

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GeometryTest {

    private fun hitAtFraction(fx: Float, fy: Float): Hit? =
        Geometry.hit(fx * Geometry.BASE_W, fy * Geometry.BASE_H)

    private fun hitAtCentreOf(control: Control): Hit? {
        val b = Geometry.boxes.getValue(control)
        return hitAtFraction(b.cx, b.cy)
    }

    @Test
    fun everyBoxResolvesToItsOwnControlAtItsCentre() {
        for (control in Geometry.boxes.keys) {
            assertEquals("centre of $control", control, hitAtCentreOf(control)?.control)
        }
    }

    @Test
    fun noTwoBoxesIntersect() {
        // Circular knob regions are excluded: they are resolved by precedence,
        // not by separation (see Geometry.hit). Boxes must never overlap.
        val entries = Geometry.boxes.entries.toList()
        for (i in entries.indices) for (j in i + 1 until entries.size) {
            val (ca, a) = entries[i]
            val (cb, b) = entries[j]
            val overlaps = kotlin.math.abs(a.cx - b.cx) < (a.hw + b.hw) &&
                kotlin.math.abs(a.cy - b.cy) < (a.hh + b.hh)
            assert(!overlaps) { "$ca overlaps $cb" }
        }
    }

    @Test
    fun noBoxSitsUnderTheScreen() {
        // The clip is drawn opaque over SCREEN, so any button inside it is
        // painted over and can't be seen.
        val (sl, st, sw, sh) = Geometry.SCREEN.toList()
        for ((control, b) in Geometry.boxes) {
            val overlaps = b.cx - b.hw < sl + sw && b.cx + b.hw > sl &&
                b.cy - b.hh < st + sh && b.cy + b.hh > st
            org.junit.Assert.assertFalse("$control sits under SCREEN", overlaps)
        }
    }

    @Test
    fun presetsResolveByIndex() {
        for (i in 0 until 6) {
            val h = hitAtFraction(Geometry.PRESETS_X[i], Geometry.PRESETS_Y)
            assertEquals(Control.PRESET, h?.control)
            assertEquals(i, h?.data)
        }
    }

    @Test
    fun navSectorsResolveByAngle() {
        val (cx, cy) = Geometry.RKNOB
        val r = Geometry.RKNOB_HIT * 0.8f          // inside the ring, outside the centre
        val aspect = Geometry.BASE_W / Geometry.BASE_H
        fun at(dxFrac: Float, dyFrac: Float) =
            hitAtFraction(cx + dxFrac, cy + dyFrac * aspect)
        assertEquals(Geometry.NAV_RIGHT, at(r, 0f)?.data)
        assertEquals(Geometry.NAV_LEFT, at(-r, 0f)?.data)
        assertEquals(Geometry.NAV_DOWN, at(0f, r)?.data)
        assertEquals(Geometry.NAV_UP, at(0f, -r)?.data)
    }

    @Test
    fun navCentreBeatsNavRing() {
        val (cx, cy) = Geometry.RKNOB
        assertEquals(Control.NAV_CENTER, hitAtFraction(cx, cy)?.control)
    }

    @Test
    fun volumeKnobCentreResolvesToVolume() {
        val (cx, cy) = Geometry.LKNOB
        assertEquals(Control.VOLUME, hitAtFraction(cx, cy)?.control)
    }

    @Test
    fun bareFaceplateResolvesToNothing() {
        assertNull(hitAtFraction(0.45f, 0.10f))   // the black strip above the screen
    }
}
