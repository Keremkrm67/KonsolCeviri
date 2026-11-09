package com.playceviri.tv.overlay

/**
 * Okuma sırası: çeviriler oyundaki hızla değil, okunabilecek hızla gösterilir.
 * Her çeviri en az (uzunluğa göre 2–7 sn) + kullanıcının ek süresi kadar ekranda kalır; o sırada gelenler
 * sıraya girer. Sıra 3'ü geçerse bekleyenler daha kısa gösterilir ki çeviri oyunun çok gerisine düşmesin.
 * Oyundaki altyazı kaybolunca çeviri hemen silinmez; süresini doldurup sıra boşalınca silinir.
 */
class ReadingQueue(private val extraMs: () -> Long, private val fixedMs: () -> Long?) {
    data class Item(val text: String, val orig: String, val anchor: FloatArray?)
    sealed class Step { data class Show(val item: Item, val untilMs: Long) : Step(); object Hide : Step(); object Keep : Step() }

    private val q = ArrayDeque<Item>()
    var current: Item? = null; private set
    var currentUntil = 0L; private set
    private var clearWanted = false
    val backlog get() = q.size

    fun durationFor(text: String, backlog: Int): Long {
        fixedMs()?.let { return it }
        val base = (1500L + 55L * text.length).coerceIn(2000L, 7000L) + extraMs()
        return if (backlog >= 3) (base * 0.6).toLong() else base
    }

    /** Yeni çeviri geldi. Hemen gösterilecekse Show döner; değilse sıraya girer (Keep). */
    fun push(item: Item, now: Long): Step {
        clearWanted = false
        val last = q.lastOrNull() ?: current
        if (last != null && last.text == item.text) return Step.Keep
        if (current == null || now >= currentUntil) return showNow(item, now)
        q.addLast(item)
        while (q.size > 6) q.removeFirst()
        return Step.Keep
    }

    /** Oyundaki altyazı kayboldu. Süresi dolmuş ve sıra boşsa hemen gizle. */
    fun clear(now: Long): Step {
        clearWanted = true
        if (q.isEmpty() && (current == null || now >= currentUntil)) { current = null; return Step.Hide }
        return Step.Keep
    }

    /** Ekrandaki çevirinin süresi doldu. */
    fun tick(now: Long): Step {
        if (now < currentUntil) return Step.Keep
        if (q.isNotEmpty()) return showNow(q.removeFirst(), now)
        if (clearWanted) { current = null; clearWanted = false; return Step.Hide }
        return Step.Keep
    }

    /** Ön çeviri kaliteli çeviriyle değişti: ekrandaysa yerinde güncellenir (süresi 1 sn uzar), sıradaysa sıradaki değişir. */
    fun replace(oldText: String, newText: String, orig: String): Item? {
        val cur = current
        if (cur != null && cur.text == oldText) { val n = cur.copy(text = newText, orig = orig); current = n; currentUntil += 1000; return n }
        val i = q.indexOfFirst { it.text == oldText }
        if (i >= 0) q[i] = q[i].copy(text = newText, orig = orig)
        return null
    }

    fun reset() { q.clear(); current = null; currentUntil = 0; clearWanted = false }

    private fun showNow(item: Item, now: Long): Step {
        current = item
        currentUntil = now + durationFor(item.text, q.size)
        return Step.Show(item, currentUntil)
    }
}
