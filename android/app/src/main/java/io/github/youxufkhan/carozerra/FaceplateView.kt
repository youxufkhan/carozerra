package io.github.youxufkhan.carozerra

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
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
            val loaded = repo.render(name)
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

    var fps: Int = 16
        set(value) { field = value.coerceIn(4, 30) }

    private val ticker = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            clip?.let { frame = (frame + 1) % it.frames.size }
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
        val r = faceplateRect()
        src.set(0, 0, face.width, face.height)
        canvas.drawBitmap(face, src, r, facePaint)

        val c = clip ?: return          // still decoding; the faceplate is already up
        val bmp = c.frames[frame]
        src.set(0, 0, bmp.width, bmp.height)
        canvas.drawBitmap(bmp, src, screenRect(), crisp)
    }
}
