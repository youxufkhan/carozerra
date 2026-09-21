package io.github.youxufkhan.carozerra

import kotlin.math.atan2
import kotlin.math.hypot

enum class Control {
    TA, VOLUME, DISPLAY, TEXT, FUNCTION, AUDIO,
    NAV, NAV_CENTER, OPEN, BAND, ENT, EQEX, PRESET, EQ, SOURCE
}

data class Hit(val control: Control, val data: Int = 0)

/** Centre and half-extents, as fractions of the faceplate. */
data class Box(val cx: Float, val cy: Float, val hw: Float, val hh: Float)

/**
 * Fractions of the cropped faceplate (assets/pioneer.png cropped to
 * 22,204 -> 1581,707). Shares its convention with carozerra.py's G dict so the
 * desktop app can adopt these entries later.
 *
 * Control numbers refer to the DEH-P7600MP manual, "What's What" section 02.
 */
object Geometry {

    const val BASE_W = 1559f
    const val BASE_H = 503f

    /** OEL screen rect: left, top, width, height. */
    val SCREEN = floatArrayOf(0.2250f, 0.3117f, 0.4608f, 0.3213f)

    val LKNOB = Pair(0.1430f, 0.4990f)
    const val LKNOB_HIT = 0.0629f          // radius, fraction of BASE_W
    const val LKNOB_DISC_X = 0.0475f       // knob art crop radii
    const val LKNOB_DISC_Y = 0.0822f

    val RKNOB = Pair(0.8743f, 0.5089f)
    const val RKNOB_HIT = 0.0657f
    const val RKNOB_CENTER_HIT = 0.028f    // the flat centre disc, inside the arrows

    const val PRESETS_Y = 0.8070f
    val PRESETS_X = floatArrayOf(0.3194f, 0.3903f, 0.4606f, 0.5309f, 0.6017f, 0.6735f)
    const val PRESET_HW = 0.0339f
    const val PRESET_HH = 0.0533f

    const val NAV_RIGHT = 0
    const val NAV_DOWN = 1
    const val NAV_LEFT = 2
    const val NAV_UP = 3

    /**
     * Measured off a 5%-fraction grid rendered over the cropped faceplate.
     * Iteration order IS hit-test precedence: small edge buttons before the
     * knobs, because the ENT wedge clips the nav knob's circle at one corner.
     */
    val boxes: Map<Control, Box> = linkedMapOf(
        Control.TA to Box(0.080f, 0.263f, 0.023f, 0.067f),       // 1
        Control.SOURCE to Box(0.080f, 0.720f, 0.023f, 0.065f),   // 14
        Control.TEXT to Box(0.250f, 0.415f, 0.0308f, 0.0497f),   // 4
        Control.DISPLAY to Box(0.250f, 0.600f, 0.0308f, 0.0497f),// 3
        Control.EQ to Box(0.182f, 0.810f, 0.042f, 0.035f),       // 13
        Control.AUDIO to Box(0.7525f, 0.418f, 0.0308f, 0.0497f), // 6
        Control.FUNCTION to Box(0.7525f, 0.6082f, 0.0308f, 0.0497f), // 5
        Control.OPEN to Box(0.893f, 0.207f, 0.015f, 0.033f),     // 8
        Control.BAND to Box(0.9480f, 0.2797f, 0.0390f, 0.0675f), // 9
        Control.ENT to Box(0.948f, 0.725f, 0.042f, 0.055f),      // 10
        Control.EQEX to Box(0.820f, 0.807f, 0.036f, 0.030f),     // 11
    )

    /**
     * Map a point in faceplate pixel space to a control. Pure and testable,
     * exactly like carozerra.py:_hit.
     *
     * Precedence, innermost first: rectangles, then the nav centre, then the
     * nav ring, then the volume knob, then the preset row. Rectangles win over
     * circles because the ENT wedge and the nav knob's circle share a corner,
     * and the wedge is the smaller, more specific target.
     */
    fun hit(bx: Float, by: Float): Hit? {
        for ((control, b) in boxes) {
            if (kotlin.math.abs(bx - b.cx * BASE_W) <= b.hw * BASE_W &&
                kotlin.math.abs(by - b.cy * BASE_H) <= b.hh * BASE_H
            ) return Hit(control)
        }

        val rx = RKNOB.first * BASE_W
        val ry = RKNOB.second * BASE_H
        val rd = hypot((bx - rx).toDouble(), (by - ry).toDouble()).toFloat()
        if (rd <= RKNOB_CENTER_HIT * BASE_W) return Hit(Control.NAV_CENTER)
        if (rd <= RKNOB_HIT * BASE_W) {
            val a = Math.toDegrees(atan2((by - ry).toDouble(), (bx - rx).toDouble()))
            val dir = when {
                a >= -45 && a < 45 -> NAV_RIGHT
                a >= 45 && a < 135 -> NAV_DOWN
                a >= 135 || a < -135 -> NAV_LEFT
                else -> NAV_UP
            }
            return Hit(Control.NAV, dir)
        }

        val lx = LKNOB.first * BASE_W
        val ly = LKNOB.second * BASE_H
        if (hypot((bx - lx).toDouble(), (by - ly).toDouble()) <= LKNOB_HIT * BASE_W) {
            return Hit(Control.VOLUME)
        }

        for (i in PRESETS_X.indices) {
            if (kotlin.math.abs(bx - PRESETS_X[i] * BASE_W) <= PRESET_HW * BASE_W &&
                kotlin.math.abs(by - PRESETS_Y * BASE_H) <= PRESET_HH * BASE_H
            ) return Hit(Control.PRESET, i)
        }
        return null
    }
}
