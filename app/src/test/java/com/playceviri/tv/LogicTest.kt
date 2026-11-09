package com.playceviri.tv

import com.playceviri.tv.vision.ChangeGate
import com.playceviri.tv.vision.Gray
import com.playceviri.tv.vision.GrayImage
import com.playceviri.tv.vision.TextFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LogicTest {
    private fun ok(s: String, conf: Float = 0.9f, min: Int = 50) = TextFilter.clean(listOf(s to conf), min).text

    @Test fun acceptsRealSubtitles() {
        assertNotNull(ok("I can't believe you did this to me."))
        assertNotNull(ok("JOEL: Get down!"))
        assertNotNull(ok("¿Dónde está mi hermano?"))
        assertNotNull(ok("We need to move. Now.", min = 62))
        assertNotNull(ok("Okay.", min = 50))
        assertNotNull(ok("Thanks, McDonald. Let us go."))
        assertEquals("Hello there friend", TextFilter.clean(listOf("Hello there" to 0.8f, "friend" to 0.7f), 50).text)
    }

    @Test fun rejectsGibberish() {
        assertNull(ok("lIl| ||| ~~ //"))
        assertNull(ok("Xkrtz pqlm wvnb"))
        assertNull(ok("a b c d f g h"))
        assertNull(ok("12:45 300/300 +25"))
        assertNull(ok("hE lLo WoRlD tHiS"))
        assertNull(ok("Fine words here", conf = 0.2f))
        assertNull(TextFilter.clean(listOf("" to 0.9f), 50).text)
    }

    @Test fun similarity() {
        assertTrue(TextFilter.similar("Hello there, Joel.", "Hello there Joel"))
        assertTrue(TextFilter.similar("I can't believe you did this", "I can't belleve you did this"))
        assertFalse(TextFilter.similar("Get down!", "Run, now!"))
    }

    private fun img(w: Int, h: Int, text: Boolean, shift: Int = 0, seed: Int = 0): GrayImage {
        val d = ByteArray(w * h) { 40 }
        if (text) for (y in h / 3 until 2 * h / 3) for (x in 0 until w) {
            if (((x + shift) / 6 + seed) % 3 == 0) d[y * w + x] = 230.toByte()
        }
        return GrayImage(d, w, h)
    }

    @Test fun gateReadsOnceWhenStable() {
        val g = ChangeGate()
        val a = img(480, 120, true)
        assertEquals(ChangeGate.Decision.SKIP, g.decide(a, 1))  // ilk örnek: henüz sabit değil
        assertEquals(ChangeGate.Decision.READ, g.decide(a, 1))  // bir örnek sabit kaldı → oku
        assertEquals(ChangeGate.Decision.SKIP, g.decide(a, 1))  // aynı görüntü tekrar okunmaz
        assertEquals(ChangeGate.Decision.SKIP, g.decide(a, 1))
        assertEquals(2L, g.skippedSame)
        val b = img(480, 120, true, seed = 1)
        assertEquals(ChangeGate.Decision.SKIP, g.decide(b, 1))  // yeni yazı: önce otursun
        assertEquals(ChangeGate.Decision.READ, g.decide(b, 1))
    }

    @Test fun gateImmediateWithSettleZero() {
        val g = ChangeGate()
        assertEquals(ChangeGate.Decision.READ, g.decide(img(480, 120, true), 0))
        assertEquals(ChangeGate.Decision.READ, g.decide(img(480, 120, true, seed = 2), 0))
    }

    @Test fun gateClearsAfterEmpty() {
        val g = ChangeGate()
        g.decide(img(480, 120, true), 0)
        val e = img(480, 120, false)
        assertEquals(ChangeGate.Decision.SKIP, g.decide(e, 0))
        assertEquals(ChangeGate.Decision.SKIP, g.decide(e, 0))
        assertEquals(ChangeGate.Decision.CLEAR, g.decide(e, 0))
        assertEquals(ChangeGate.Decision.SKIP, g.decide(e, 0))
    }

    @Test fun prepareProducesDarkTextOnWhite() {
        val p = Gray.prepare(img(300, 60, true), 2)
        assertEquals(624, p.width)  // 2x + 2*12 kenar
        val black = p.data.count { it.toInt() == 0 }
        val white = p.data.count { it.toInt() == -1 }
        assertTrue("yazı siyah olmalı", black in 1 until white)
    }
}
