package com.playceviri.tv

import com.playceviri.tv.overlay.ReadingQueue
import com.playceviri.tv.overlay.ReadingQueue.Step
import org.junit.Assert.*
import org.junit.Test

class QueueTest {
    private fun q() = ReadingQueue({ 2500L }, { null })
    private fun it(t: String) = ReadingQueue.Item(t, "", null)

    @Test fun firstShowsImmediately_secondWaits() {
        val q = q()
        val s1 = q.push(it("Merhaba dostum"), 0)
        assertTrue(s1 is Step.Show)
        val until = (s1 as Step.Show).untilMs
        assertTrue("en az 4.5 sn", until >= 4500)
        assertEquals(Step.Keep, q.push(it("İkinci cümle geldi"), 1000))
        assertEquals(1, q.backlog)
        assertEquals(Step.Keep, q.tick(until - 1))
        val s2 = q.tick(until)
        assertEquals("İkinci cümle geldi", (s2 as Step.Show).item.text)
    }

    @Test fun clearWaitsForMinimumAndQueue() {
        val q = q()
        val u = (q.push(it("Bir"), 0) as Step.Show).untilMs
        q.push(it("İki"), 500)
        assertEquals(Step.Keep, q.clear(800))
        val u2 = (q.tick(u) as Step.Show).untilMs
        assertEquals(Step.Hide, q.tick(u2))
        assertNull(q.current)
    }

    @Test fun clearAfterExpiryHidesNow() {
        val q = q()
        val u = (q.push(it("Bir"), 0) as Step.Show).untilMs
        assertEquals(Step.Keep, q.tick(u))
        assertEquals(Step.Hide, q.clear(u + 10))
    }

    @Test fun duplicatesIgnored_backlogShortens() {
        val q = q()
        q.push(it("A cümlesi"), 0)
        assertEquals(Step.Keep, q.push(it("A cümlesi"), 10))
        assertEquals(0, q.backlog)
        for (i in 1..4) q.push(it("Cümle $i"), 100L + i)
        assertEquals(4, q.backlog)
        assertTrue(q.durationFor("Cümle", 3) < q.durationFor("Cümle", 0))
        val long = q.durationFor("x".repeat(300), 0)
        assertEquals(7000L + 2500L, long)
    }

    @Test fun newAfterClearShowsImmediately() {
        val q = q()
        val u = (q.push(it("Bir"), 0) as Step.Show).untilMs
        q.clear(100)
        assertTrue(q.push(it("Yeni"), u + 1) is Step.Show)
    }

    @Test fun upgradeReplacesShownAndQueued() {
        val q = q()
        val u = (q.push(it("Ön çeviri"), 0) as Step.Show).untilMs
        q.push(it("İkinci ön"), 100)
        val n = q.replace("Ön çeviri", "Kaliteli çeviri", "orig")
        assertEquals("Kaliteli çeviri", n!!.text); assertEquals(u + 1000, q.currentUntil)
        assertNull(q.replace("İkinci ön", "İkinci kaliteli", "o"))
        assertEquals("İkinci kaliteli", (q.tick(q.currentUntil) as Step.Show).item.text)
        assertNull(q.replace("yok", "x", ""))
    }
}
