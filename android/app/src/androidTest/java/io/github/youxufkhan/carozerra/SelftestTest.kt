package io.github.youxufkhan.carozerra

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
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
     * The real render pipeline (`FaceplateView.onDraw`, via the `renderTo` test
     * hook) with a freshly-constructed view whose `clip` is still null -- a
     * throwaway view+repo, measured/laid out to the same window size, rendered
     * synchronously right after construction, before the background decode
     * thread has had any chance to land. This is deliberately NOT a hand-rolled
     * reimplementation of "what onDraw draws with no clip": a hand-copied
     * baseline silently drifted out of sync with the real pipeline twice
     * (a Paint-filtering mismatch during Task 5's own fix round, then a whole
     * new glow+scanlines stage added by Task 6) because it was separate code
     * that nothing forced to track onDraw's changes. Calling the real method
     * closes that bug class for good: any future onDraw change is automatically
     * reflected in both this baseline and the live sample below.
     */
    private fun currentPipelineBaselineOelColours(
        context: android.content.Context, windowW: Int, windowH: Int,
    ): Set<Int> {
        val blank = FaceplateView(context, ClipRepository(context.assets))
        blank.measure(
            android.view.View.MeasureSpec.makeMeasureSpec(windowW, android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(windowH, android.view.View.MeasureSpec.EXACTLY),
        )
        blank.layout(0, 0, windowW, windowH)
        val canvasBmp = Bitmap.createBitmap(windowW, windowH, Bitmap.Config.ARGB_8888)
        blank.renderTo(Canvas(canvasBmp))

        val oel = blank.screenRect()
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

            // The onboarding control-map card shows automatically for 5s at launch,
            // covering the whole faceplate (Task 13) -- independent of whether the
            // clip decoded. Dismiss it deterministically rather than racing its
            // auto-hide timer, so both samples below see the real faceplate/OEL.
            // If a future overlay ever auto-shows at launch the way `card` does, it
            // must be dismissed here too -- any sibling view drawn on top of
            // FaceplateView will mask this test's sample window regardless of whether
            // the clip itself renders correctly.
            scenario.onActivity { activity -> activity.card.hide() }

            var distinctOnScreen = 0
            var liveOelColours: Set<Int> = emptySet()
            var baselineOelColours: Set<Int> = emptySet()
            scenario.onActivity { activity ->
                val root = activity.window.decorView
                activity.faceplate.scanlines = false
                activity.faceplate.glowIntensity = 0
                activity.faceplate.glow = false
                activity.faceplate.debugHitboxes = false
                activity.faceplate.overlays.meters = false
                val shot = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
                root.draw(Canvas(shot))

                distinctOnScreen = colourSet(shot, 0, 0, shot.width, shot.height).size

                val oel = activity.faceplate.screenRect()
                liveOelColours = colourSet(shot,
                    oel.left.toInt() + 2, oel.top.toInt() + 2,
                    oel.right.toInt() - 2, oel.bottom.toInt() - 2)

                baselineOelColours = currentPipelineBaselineOelColours(activity, root.width, root.height)
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
