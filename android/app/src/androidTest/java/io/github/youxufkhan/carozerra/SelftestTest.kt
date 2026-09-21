package io.github.youxufkhan.carozerra

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The parallel of carozerra.py --selftest. A packaged build fails quietly — a
 * missing asset, an unsynced assets/ tree, a paint path that draws nothing —
 * and "the process stayed alive" catches none of those.
 */
@RunWith(AndroidJUnit4::class)
class SelftestTest {

    private fun colourSet(bmp: Bitmap, l: Int, t: Int, r: Int, b: Int): Set<Int> {
        val seen = HashSet<Int>()
        var y = t
        while (y < b) {
            var x = l
            while (x < r) { seen.add(bmp.getPixel(x, y)); x += 7 }
            y += 7
        }
        return seen
    }

    /**
     * The faceplate photo alone, contain-fit into a window the same size as the
     * live activity's, with nothing composited into the OEL. The bare faceplate
     * art already clears a naive distinct-colour-count floor on its own (682+
     * colours measured against an ">= 8" threshold), so that floor can't tell
     * "clip painted" from "clip never painted" -- this baseline is what makes
     * the real assertion below possible.
     */
    private fun faceplateOnlyOelColours(windowW: Int, windowH: Int): Set<Int> {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val full = ctx.assets.open("pioneer.png").use { BitmapFactory.decodeStream(it) }
        val face = Bitmap.createBitmap(full, 22, 204, 1559, 503)

        val s = minOf(windowW / Geometry.BASE_W, windowH / Geometry.BASE_H)
        val fw = Geometry.BASE_W * s
        val fh = Geometry.BASE_H * s
        val frameRect = RectF((windowW - fw) / 2f, (windowH - fh) / 2f,
                               (windowW - fw) / 2f + fw, (windowH - fh) / 2f + fh)

        // Must match FaceplateView.facePaint exactly (FILTER_BITMAP_FLAG |
        // ANTI_ALIAS_FLAG) -- a bare-null Paint here nearest-neighbour-scales
        // instead of bilinear-filtering, producing hundreds of edge-pixel
        // colours the live bilinear-filtered render doesn't have. That mismatch
        // alone clears the assertion below even with the clip never painted,
        // silently defeating the whole point of this baseline.
        val facePaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        val canvasBmp = Bitmap.createBitmap(windowW, windowH, Bitmap.Config.ARGB_8888)
        Canvas(canvasBmp).drawBitmap(face, null, frameRect, facePaint)

        val left = frameRect.left + Geometry.SCREEN[0] * frameRect.width()
        val top = frameRect.top + Geometry.SCREEN[1] * frameRect.height()
        val oel = RectF(left, top,
            left + Geometry.SCREEN[2] * frameRect.width(),
            top + Geometry.SCREEN[3] * frameRect.height())

        return colourSet(canvasBmp,
            oel.left.toInt() + 2, oel.top.toInt() + 2,
            oel.right.toInt() - 2, oel.bottom.toInt() - 2)
    }

    @Test
    fun activityDrawsTheFaceplateAndAClip() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->

            // The first clip decodes on a background thread, so the faceplate is
            // up before the OEL is. Sampling too early would pass on the
            // faceplate alone and prove nothing about decoding.
            var loaded = false
            val deadline = System.currentTimeMillis() + 15_000
            while (!loaded && System.currentTimeMillis() < deadline) {
                scenario.onActivity { loaded = it.faceplate.clipLoaded }
                if (!loaded) Thread.sleep(200)
            }
            assertTrue("no clip decoded within 15s", loaded)

            var distinctOnScreen = 0
            var liveOelColours: Set<Int> = emptySet()
            var baselineOelColours: Set<Int> = emptySet()
            scenario.onActivity { activity ->
                val root = activity.window.decorView
                val shot = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
                root.draw(Canvas(shot))

                distinctOnScreen = colourSet(shot, 0, 0, shot.width, shot.height).size

                val oel = activity.faceplate.screenRect()
                liveOelColours = colourSet(shot,
                    oel.left.toInt() + 2, oel.top.toInt() + 2,
                    oel.right.toInt() - 2, oel.bottom.toInt() - 2)

                baselineOelColours = faceplateOnlyOelColours(root.width, root.height)
            }

            assertTrue("whole window: only $distinctOnScreen colours", distinctOnScreen >= 32)
            // The live OEL sample must contain a colour the bare faceplate photo
            // never produces there -- proof the clip actually painted over it,
            // not just that the faceplate art has enough baked-in variation to
            // clear a floor on its own (it does, by ~85x -- that's the bug this
            // replaces).
            assertFalse(
                "OEL rect: live sample is entirely explained by the bare " +
                    "faceplate art ($liveOelColours) -- the clip never painted",
                baselineOelColours.containsAll(liveOelColours)
            )
        }
    }
}
