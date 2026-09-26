package io.github.youxufkhan.carozerra

import java.io.ByteArrayInputStream
import java.util.zip.GZIPInputStream

class LkdFormatException(message: String) : Exception(message)

/** One decoded clip. Each frame is width*height packed ARGB ints, row-major, top-down. */
data class LkdClip(val width: Int, val height: Int, val frames: List<IntArray>)

/**
 * 0x00 "zLKD" | 0x04 version | 0x08 ? | 0x0C ? | 0x10 frame count
 * 0x14 gzip -> tar -> one 24-bit bottom-up BGR BMP of stacked frames
 */
object LkdDecoder {

    private const val HEADER = 20
    private val MAGIC = byteArrayOf(0x7A, 0x4C, 0x4B, 0x44) // "zLKD"

    fun decode(data: ByteArray, name: String = "<lkd>"): LkdClip {
        if (data.size < HEADER || !data.copyOfRange(0, 4).contentEquals(MAGIC)) {
            throw LkdFormatException("$name: not a zLKD file")
        }
        val declaredFrames = le32(data, 0x10)
        val tar = try {
            GZIPInputStream(
                ByteArrayInputStream(data, HEADER, data.size - HEADER)
            ).use { it.readBytes() }
        } catch (e: java.util.zip.ZipException) {
            throw LkdFormatException("$name: corrupt gzip stream (${e.message})")
        } catch (e: java.io.EOFException) {
            throw LkdFormatException("$name: truncated gzip stream")
        }
        return sliceBmp(firstTarMember(tar, name), declaredFrames, name)
    }

    private fun le32(b: ByteArray, o: Int): Int =
        (b[o].toInt() and 0xFF) or
            ((b[o + 1].toInt() and 0xFF) shl 8) or
            ((b[o + 2].toInt() and 0xFF) shl 16) or
            ((b[o + 3].toInt() and 0xFF) shl 24)

    private fun le16(b: ByteArray, o: Int): Int =
        (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)

    /** The archive holds exactly one member: 512-byte header, octal size at 124. */
    private fun firstTarMember(tar: ByteArray, name: String): ByteArray {
        if (tar.size < 512) throw LkdFormatException("$name: truncated tar")
        val field = String(tar, 124, 12, Charsets.US_ASCII).trim { it <= ' ' }
        val size = field.toIntOrNull(8)
            ?: throw LkdFormatException("$name: unreadable tar size '$field'")
        if (size <= 0 || 512 + size > tar.size) {
            throw LkdFormatException("$name: tar member of $size overruns ${tar.size}")
        }
        return tar.copyOfRange(512, 512 + size)
    }

    private fun sliceBmp(bmp: ByteArray, declaredFrames: Int, name: String): LkdClip {
        if (bmp.size < 54 || bmp[0] != 'B'.code.toByte() || bmp[1] != 'M'.code.toByte()) {
            throw LkdFormatException("$name: tar member is not a BMP")
        }
        val pixOff = le32(bmp, 0x0A)
        val w = le32(bmp, 0x12)
        val h = le32(bmp, 0x16)
        val bpp = le16(bmp, 0x1C)
        if (bpp != 24) throw LkdFormatException("$name: expected 24bpp, got $bpp")
        if (w <= 0 || h <= 0) throw LkdFormatException("$name: bad size ${w}x$h")

        val frameCount = if (declaredFrames > 0) declaredFrames else h / w
        if (frameCount <= 0 || h % frameCount != 0) {
            throw LkdFormatException("$name: $h rows does not divide into $frameCount frames")
        }
        val fh = h / frameCount
        val stride = (w * 3 + 3) and 3.inv()
        if (pixOff + stride * h > bmp.size) {
            throw LkdFormatException("$name: pixel data overruns the BMP")
        }

        val frames = ArrayList<IntArray>(frameCount)
        for (f in 0 until frameCount) {
            val px = IntArray(w * fh)
            for (y in 0 until fh) {
                // The BMP is bottom-up: file row 0 is the image's LAST row.
                val fileRow = h - 1 - (f * fh + y)
                var src = pixOff + fileRow * stride
                var dst = y * w
                for (x in 0 until w) {
                    val b = bmp[src].toInt() and 0xFF
                    val g = bmp[src + 1].toInt() and 0xFF
                    val r = bmp[src + 2].toInt() and 0xFF
                    px[dst++] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                    src += 3
                }
            }
            frames.add(px)
        }
        return LkdClip(w, fh, frames)
    }
}
