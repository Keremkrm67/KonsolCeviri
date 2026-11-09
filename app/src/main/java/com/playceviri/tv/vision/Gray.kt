package com.playceviri.tv.vision

import android.graphics.Rect
import android.media.Image
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** 8 bit gri görüntü (satır satır, boşluksuz). */
class GrayImage(val data: ByteArray, val width: Int, val height: Int)

object Gray {
    /** YUV karesinin Y düzleminden verilen dikdörtgeni kopyalar. */
    fun crop(img: Image, r: Rect): GrayImage {
        val plane = img.planes[0]
        val buf = plane.buffer
        val rs = plane.rowStride
        val ps = plane.pixelStride
        val w = r.width(); val h = r.height()
        val out = ByteArray(w * h)
        if (ps == 1) {
            for (y in 0 until h) {
                buf.position((r.top + y) * rs + r.left)
                buf.get(out, y * w, w)
            }
            buf.rewind()
        } else {
            var i = 0
            for (y in 0 until h) {
                val row = (r.top + y) * rs
                for (x in 0 until w) out[i++] = buf.get((r.left + x) * ps + row)
            }
        }
        return GrayImage(out, w, h)
    }

    fun regionRect(fr: FloatArray, w: Int, h: Int): Rect {
        val l = (fr[0] * w).toInt().coerceIn(0, w - 16)
        val t = (fr[1] * h).toInt().coerceIn(0, h - 16)
        val r = (fr[2] * w).toInt().coerceIn(l + 16, w)
        val b = (fr[3] * h).toInt().coerceIn(t + 16, h)
        return Rect(l and 1.inv(), t and 1.inv(), r and 1.inv(), b and 1.inv())
    }

    fun sub(g: GrayImage, r: Rect): GrayImage {
        val l = r.left.coerceIn(0, g.width - 1); val t = r.top.coerceIn(0, g.height - 1)
        val rr = r.right.coerceIn(l + 1, g.width); val bb = r.bottom.coerceIn(t + 1, g.height)
        val w = rr - l; val h = bb - t
        val out = ByteArray(w * h)
        for (y in 0 until h) System.arraycopy(g.data, (t + y) * g.width + l, out, y * w, w)
        return GrayImage(out, w, h)
    }

    /** Çift doğrusal 2x büyütme (küçük yazılar ML Kit'te daha iyi okunur). */
    fun upscale2(g: GrayImage): GrayImage {
        val w = g.width; val h = g.height
        val ow = w * 2; val oh = h * 2
        val out = ByteArray(ow * oh)
        for (y in 0 until oh) {
            val fy = ((y + 0.5f) / 2f - 0.5f).coerceIn(0f, (h - 1).toFloat())
            val y0 = fy.toInt(); val y1 = min(y0 + 1, h - 1); val wy = fy - y0
            for (x in 0 until ow) {
                val fx = ((x + 0.5f) / 2f - 0.5f).coerceIn(0f, (w - 1).toFloat())
                val x0 = fx.toInt(); val x1 = min(x0 + 1, w - 1); val wx = fx - x0
                val a = g.data[y0 * w + x0].toInt() and 255
                val b = g.data[y0 * w + x1].toInt() and 255
                val c = g.data[y1 * w + x0].toInt() and 255
                val d = g.data[y1 * w + x1].toInt() and 255
                val top = a + (b - a) * wx; val bot = c + (d - c) * wx
                out[y * ow + x] = (top + (bot - top) * wy).toInt().toByte()
            }
        }
        return GrayImage(out, ow, oh)
    }

    /** %2–%98 aralığını 0–255'e yayar. */
    fun stretch(g: GrayImage): GrayImage {
        val hist = IntArray(256)
        for (b in g.data) hist[b.toInt() and 255]++
        val n = g.data.size
        var lo = 0; var acc = 0
        while (lo < 255 && acc + hist[lo] < n * 0.02) { acc += hist[lo]; lo++ }
        var hi = 255; acc = 0
        while (hi > 0 && acc + hist[hi] < n * 0.02) { acc += hist[hi]; hi-- }
        if (hi - lo < 24) return g
        val lut = ByteArray(256) { v -> (((v - lo) * 255) / (hi - lo)).coerceIn(0, 255).toByte() }
        return GrayImage(ByteArray(n) { lut[g.data[it].toInt() and 255] }, g.width, g.height)
    }

    fun otsu(d: ByteArray): Int {
        val hist = IntArray(256)
        for (b in d) hist[b.toInt() and 255]++
        val total = d.size.toDouble()
        var sum = 0.0
        for (i in 0 until 256) sum += i * hist[i].toDouble()
        var sumB = 0.0; var wB = 0.0; var best = 0.0; var th = 128
        for (i in 0 until 256) {
            wB += hist[i]; if (wB == 0.0) continue
            val wF = total - wB; if (wF == 0.0) break
            sumB += i * hist[i].toDouble()
            val mB = sumB / wB; val mF = (sum - sumB) / wF
            val v = wB * wF * (mB - mF) * (mB - mF)
            if (v > best) { best = v; th = i }
        }
        return th
    }

    /**
     * Siyah yazı / beyaz zemin üretir. Altyazılar çoğunlukla açık renkli olduğundan
     * eşiğin üstü "yazı" sayılır; yazı oranı yarıdan fazlaysa (koyu yazı) ters çevrilir.
     */
    fun binarize(g: GrayImage): GrayImage {
        val th = max(otsu(g.data), 90)
        var bright = 0
        for (b in g.data) if ((b.toInt() and 255) > th) bright++
        val textIsBright = bright < g.data.size / 2
        val pad = 12
        val w = g.width + pad * 2; val h = g.height + pad * 2
        val out = ByteArray(w * h) { -1 }
        for (y in 0 until g.height) for (x in 0 until g.width) {
            val v = g.data[y * g.width + x].toInt() and 255
            val isText = if (textIsBright) v > th else v < th
            if (isText) out[(y + pad) * w + x + pad] = 0
        }
        return GrayImage(out, w, h)
    }

    /** prep: 0 kapalı (yalnız küçükse büyüt), 1 hafif, 2 güçlü. */
    fun prepare(g: GrayImage, prep: Int): GrayImage {
        var x = g
        if (prep >= 1 || x.height <= 130) if (x.width * x.height <= 900_000) x = upscale2(x)
        if (prep >= 1) x = stretch(x)
        if (prep >= 2) x = binarize(x)
        return x
    }

    /**
     * Tarayıcıdaki Tesseract için: kontrast açılır; prep>=1 ise siyah-beyaza çevrilip 1 bit/piksel paketlenir
     * (ağda ~8 kat küçük). Dönüş: (görüntü, paketli mi, veri).
     */
    fun forBrowser(g: GrayImage, prep: Int): Triple<GrayImage, Boolean, ByteArray> {
        var x = if (g.height < 50) upscale2(g) else g
        x = stretch(x)
        if (prep == 0) return Triple(x, false, x.data)
        val b = binarize(x)
        val rowBytes = (b.width + 7) shr 3
        val out = ByteArray(rowBytes * b.height)
        for (y in 0 until b.height) {
            val row = y * b.width
            for (xx in 0 until b.width) if (b.data[row + xx].toInt() == 0) {
                val i = y * rowBytes + (xx shr 3)
                out[i] = (out[i].toInt() or (0x80 ushr (xx and 7))).toByte()
            }
        }
        return Triple(b, true, out)
    }

    /** Gri görüntüyü ML Kit'in istediği NV21 dizisine çevirir. */
    fun toNv21(g: GrayImage): Triple<ByteArray, Int, Int> {
        val w = g.width and 1.inv(); val h = g.height and 1.inv()
        val ySize = w * h
        val out = ByteArray(ySize * 3 / 2)
        for (y in 0 until h) System.arraycopy(g.data, y * g.width, out, y * w, w)
        java.util.Arrays.fill(out, ySize, out.size, 128.toByte())
        return Triple(out, w, h)
    }

    /** Kenar yoğunluğu: bölgede yazı benzeri keskin geçiş var mı? (0..1) */
    fun edgeDensity(g: GrayImage, step: Int = 2): Float {
        var edges = 0; var total = 0
        val w = g.width
        var y = 0
        while (y < g.height) {
            var x = step
            val row = y * w
            while (x < w) {
                val d = abs((g.data[row + x].toInt() and 255) - (g.data[row + x - step].toInt() and 255))
                if (d > 60) edges++
                total++
                x += step
            }
            y += step
        }
        return if (total == 0) 0f else edges.toFloat() / total
    }
}
