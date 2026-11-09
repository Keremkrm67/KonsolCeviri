package com.playceviri.tv

import com.playceviri.tv.vision.GrayImage
import com.playceviri.tv.vision.SubtitleIsolator
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assume
import org.junit.Test
import java.io.File

class IsolatorTest {
    private fun load(n: String, w: Int, h: Int): GrayImage? = File("/tmp/claude-0/$n").takeIf { it.exists() }?.let { GrayImage(it.readBytes(), w, h) }

    @Test fun subtitleIsFound() {
        val g = load("zor.raw", 1840, 540); Assume.assumeTrue(g != null)
        repeat(4) { SubtitleIsolator.isolate(g!!) }
        val t0 = System.nanoTime()
        val r = SubtitleIsolator.isolate(g!!)
        println("altyazılı: ${(System.nanoTime() - t0) / 1_000_000} ms, satır ${r?.lines}, harf ${r?.glyphs}")
        assertNotNull(r)
        File("/tmp/claude-0/yeni.pgm").outputStream().use { it.write("P5\n${r!!.image.width} ${r.image.height}\n255\n".toByteArray()); it.write(r.image.data) }
    }

    @Test fun simpleSubs() {
        for (n in listOf("sub0.raw", "sub1.raw")) {
            val g = load(n, 1500, 120) ?: continue
            val r = SubtitleIsolator.isolate(g)
            println("$n: ${r?.lines} satır ${r?.glyphs} harf")
            assertNotNull(r)
        }
    }

    @Test fun emptyScenesGiveNothing() {
        for ((n, h) in listOf("bos1.raw" to 540, "bos2.raw" to 354)) {
            val g = load(n, 1840, h) ?: continue
            val r = SubtitleIsolator.isolate(g)
            println("$n: ${if (r == null) "yazı yok (doğru)" else "YANLIŞ: ${r.lines} satır ${r.glyphs} harf"}")
            assertNull(r)
        }
    }

    /** Küçük punto, ince harfler: h/l/k/f (üst uzantı) ve g/y (alt uzantı) kaybolmamalı. */
    @Test fun thinAscendersKept() {
        val texts = listOf("with millions of dollars worth of", "Whatever the hell it is you're talkin' about.", "I just saw a fucking ghost, and I've got to hear your crap?")
        for ((i, t) in texts.withIndex()) {
            val g = load("ince$i.raw", 1840, 300) ?: continue
            val r = SubtitleIsolator.isolate(g)
            val letters = t.count { it.isLetterOrDigit() && it != 'i' && it != 'j' }
            println("ince harf: \"$t\" → ${r?.glyphs} parça / $letters harf")
            assertNotNull(r)
            File("/tmp/claude-0/ince$i.pgm").outputStream().use { it.write("P5\n${r!!.image.width} ${r.image.height}\n255\n".toByteArray()); it.write(r.image.data) }
            org.junit.Assert.assertTrue("harf kayboldu: ${r!!.glyphs}/$letters", r!!.glyphs >= letters * 0.9)
        }
    }
}
