package com.playceviri.tv

import com.playceviri.tv.vision.GrayImage
import com.playceviri.tv.vision.SceneSuppressor
import org.junit.Assert.*
import org.junit.Test

class SuppressorTest {
    private val w = 200; private val h = 60
    /** Sahne: kayan parlak şeritler; yazı: sabit parlak harf çubukları (x 50..150, y 25..35). */
    private fun frame(shift: Int, text: Boolean): GrayImage {
        val d = ByteArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val scene = if (((x + shift) / 12) % 2 == 0) 230 else 60
            val isText = text && y in 25..35 && x in 50..150 && (x / 4) % 2 == 0
            d[y * w + x] = (if (isText) 245 else scene).toByte()
        }
        return GrayImage(d, w, h)
    }
    private fun brightOutsideText(g: GrayImage) = (0 until w * h).count { i -> val y = i / w; val x = i % w; (y !in 25..35 || x !in 50..150) && (g.data[i].toInt() and 255) >= 200 }
    private fun textKept(g: GrayImage) = (0 until w * h).count { i -> val y = i / w; val x = i % w; y in 25..35 && x in 50..150 && (x / 4) % 2 == 0 && (g.data[i].toInt() and 255) >= 240 }

    @Test fun movingSceneDarkens_textStays() {
        val s = SceneSuppressor()
        s.push(frame(0, true), 0); s.push(frame(6, true), 250)
        val cur = frame(12, true); s.push(cur, 500)
        val out = s.clean(cur, 500)
        assertEquals(2, s.lastUsed)
        assertTrue("sahne kararmalı: ${brightOutsideText(cur)} → ${brightOutsideText(out)}", brightOutsideText(out) < brightOutsideText(cur) / 3)
        assertEquals(textKept(cur), textKept(out))
    }

    @Test fun oldFramesIgnored() {
        val s = SceneSuppressor()
        s.push(frame(0, false), 0)
        val cur = frame(12, true); s.push(cur, 2000)
        s.clean(cur, 2000)
        assertEquals(0, s.lastUsed)
    }
}
