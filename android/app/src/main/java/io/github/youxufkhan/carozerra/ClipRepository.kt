package io.github.youxufkhan.carozerra

import android.content.res.AssetManager
import android.graphics.Bitmap
import android.util.LruCache

data class Category(val name: String, val clips: List<String>)

/** The same five groupings the web player uses, in the same order. */
object ClipCatalog {

    val categories: List<Category> = listOf(
        Category("Movies", listOf(
            "movie1.lkd", "movie2.lkd", "movie3.lkd", "movie4.lkd", "movie5.lkd",
            "movie6.lkd", "movie7.lkd", "movie8_f.lkd", "movie9_f.lkd", "movie10_f.lkd",
            "alt_airship.lkd", "alt_diverdolphins.lkd", "alt_dragonrider.lkd",
            "alt_metropolis.lkd", "alt_waterboard.lkd", "alt_wildlife.lkd", "alt_wrc.lkd",
        )),
        Category("Backgrounds", listOf(
            "alt_bgv1.lkd", "alt_bgv2.lkd", "alt_bgv3.lkd", "alt_bgv4.lkd",
        )),
        Category("Stills", listOf(
            "still_bgp01.lkd", "still_bgp02.lkd", "still_bgp03.lkd", "still_bgp04.lkd",
            "still_bgp05.lkd", "still_bgp06.lkd", "still_bgp07.lkd", "still_bgp08.lkd",
            "still_bgp09.lkd",
            "still_bgp_f_10.lkd", "still_bgp_f_11.lkd", "still_bgp_f_12.lkd",
            "still_bgp_f_13.lkd", "still_bgp_f_14.lkd", "still_bgp_f_15.lkd",
            "still_bgp_f_16.lkd", "still_bgp_f_17.lkd", "still_bgp_f_18.lkd",
            "alt_still_bgp_e1.lkd", "alt_still_bgp_e2.lkd", "alt_still_bgp_e3.lkd",
            "alt_still_bgp_e4.lkd", "alt_still_bgp_e5.lkd",
        )),
        Category("Level Meters", listOf(
            "meter_level01.lkd", "meter_level02.lkd", "meter_level03.lkd",
            "meter_level04.lkd", "meter_level05.lkd",
            "meter_level06_f.lkd", "meter_level07_f.lkd",
            "alt_meter_li01.lkd", "alt_meter_li02.lkd", "alt_meter_li03.lkd",
            "alt_meter_li04.lkd", "alt_meter_li05.lkd", "alt_meter_li06.lkd",
            "alt_meter_li07.lkd", "alt_meter_li08.lkd",
        )),
        Category("Color", listOf(
            "color_01_nightcruising.lkd", "color_02_greatbarrierreef.lkd",
            "color_03_redplanet.lkd", "color_04_island.lkd", "color_05_firedragon.lkd",
            "color_06_racingcart.lkd", "color_07_motogp.lkd",
            "color_still_bgp01.lkd", "color_still_bgp02.lkd", "color_still_bgp03.lkd",
            "color_still_bgp04.lkd", "color_still_bgp05.lkd", "color_still_bgp06.lkd",
            "color_still_bgp07.lkd", "color_still_bgp08.lkd", "color_still_bgp09.lkd",
            "color_still_bgp10.lkd",
            "color_bgv01.lkd", "color_bgv02.lkd", "color_bgv03.lkd", "color_bgv04.lkd",
            "color_bgv05.lkd", "color_bgv06.lkd", "color_bgv07.lkd",
        )),
    )

    val all: List<String> = categories.flatMap { it.clips }

    fun categoryOf(name: String): String =
        categories.firstOrNull { name in it.clips }?.name ?: ""
}

/** One clip's render-ready bitmaps: crisp frames plus their downscaled blur copies. */
class RenderClip(val frames: List<Bitmap>, val blur: List<Bitmap>)

/**
 * Decodes on demand and keeps only a few clips alive. One clip costs ~3.9 MB of
 * crisp frames plus ~0.4 MB of blur copies; all 83 at once would be ~326 MB.
 */
class ClipRepository(private val assets: AssetManager) {

    private val clips = LruCache<String, RenderClip>(3)
    private val thumbs = java.util.concurrent.ConcurrentHashMap<String, Bitmap>()

    fun render(name: String): RenderClip = clips.get(name) ?: build(name).also {
        clips.put(name, it)
    }

    fun thumbnail(name: String): Bitmap = thumbs.computeIfAbsent(name) {
        toBitmap(decode(name), 0)
    }

    private fun build(name: String): RenderClip {
        val clip = decode(name)
        val frames = ArrayList<Bitmap>(clip.frames.size)
        val blur = ArrayList<Bitmap>(clip.frames.size)
        val bw = (clip.width / 3).coerceAtLeast(1)
        val bh = (clip.height / 3).coerceAtLeast(1)
        for (i in clip.frames.indices) {
            val full = toBitmap(clip, i)
            frames.add(full)
            blur.add(Bitmap.createScaledBitmap(full, bw, bh, true))
        }
        return RenderClip(frames, blur)
    }

    private fun decode(name: String): LkdClip =
        assets.open("clips/$name").use { LkdDecoder.decode(it.readBytes(), name) }

    private fun toBitmap(clip: LkdClip, index: Int): Bitmap =
        Bitmap.createBitmap(
            clip.frames[index], clip.width, clip.height, Bitmap.Config.ARGB_8888
        )
}
