package io.github.youxufkhan.carozerra

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class LkdDecoderTest {

    // Unit tests run with the module dir (android/app) as working directory.
    private fun movie1(): ByteArray = File("../../assets/clips/movie1.lkd").readBytes()

    private fun litPixelsInRow(clip: LkdClip, frame: Int, y: Int): Int =
        (0 until clip.width).count { x ->
            clip.frames[frame][y * clip.width + x] != 0xFF000000.toInt()
        }

    @Test
    fun decodesDimensionsAndFrameCount() {
        val clip = LkdDecoder.decode(movie1(), "movie1.lkd")
        assertEquals(256, clip.width)
        assertEquals(64, clip.height)
        assertEquals(60, clip.frames.size)
        assertEquals(256 * 64, clip.frames[0].size)
    }

    @Test
    fun channelOrderIsBgrInFileAndArgbOut() {
        // decode.py reports frame0[200,10] == (7, 158, 175), the native OEL cyan.
        // A BGR/RGB swap would yield 0xFFAF9E07 here instead.
        val clip = LkdDecoder.decode(movie1(), "movie1.lkd")
        assertEquals(0xFF079EAF.toInt(), clip.frames[0][10 * 256 + 200])
    }

    @Test
    fun bmpIsFlippedFromBottomUpToTopDown() {
        // The BMP stores rows bottom-up. Frame 0 of movie1 is sparse at the top
        // (83 lit pixels in row 0) and dense at the bottom (198 in row 63); a
        // missing flip swaps those counts while every dimension check still passes.
        val clip = LkdDecoder.decode(movie1(), "movie1.lkd")
        assertEquals(83, litPixelsInRow(clip, 0, 0))
        assertEquals(198, litPixelsInRow(clip, 0, 63))
        assertEquals(0xFF05C7DC.toInt(), clip.frames[0][63 * 256 + 128])
    }

    @Test
    fun everyBundledClipDecodes() {
        val dir = File("../../assets/clips")
        val files = dir.listFiles { f -> f.name.endsWith(".lkd") }!!
        assertEquals(83, files.size)
        for (f in files) {
            val clip = LkdDecoder.decode(f.readBytes(), f.name)
            assertTrue("${f.name}: ${clip.width}x${clip.height}", clip.width > 0 && clip.height > 0)
            assertTrue("${f.name}: no frames", clip.frames.isNotEmpty())
        }
    }

    @Test(expected = LkdFormatException::class)
    fun rejectsBadMagic() {
        LkdDecoder.decode("NOTALKDFILE12345678901234".toByteArray(), "bogus")
    }

    @Test(expected = LkdFormatException::class)
    fun rejectsCorruptGzipStream() {
        // Valid header/magic, but the gzip payload after the 20-byte header is truncated.
        val bytes = movie1()
        val truncated = bytes.copyOfRange(0, 30)
        LkdDecoder.decode(truncated, "truncated.lkd")
    }
}
