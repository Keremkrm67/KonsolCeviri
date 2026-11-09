package com.playceviri.tv.vision

import kotlin.math.abs

/**
 * Hangi karede yazı okunacağına karar verir:
 *  - Bölge görüntüsü önceki örnekle aynıysa "sabit" sayılır.
 *  - Görüntü [settle] örnek boyunca sabit kaldığında ve son okunan görüntüden farklıysa okunur.
 *  - Bölgede yazı benzeri kenar kalmadıysa birkaç örnek sonra "boş" denir (altyazı silinir).
 * Böylece aynı görüntü tekrar tekrar okunmaz (gecikme ve ısınma düşer), yarım/titrek yazılar beklenir.
 */
class ChangeGate {
    private var last: IntArray? = null
    private var processed: IntArray? = null
    private var stableCount = 0
    private var emptyCount = 0
    private var lastReadAt = 0L
    /** Yazı varken en geç bu kadar ms'de bir yeniden okunur (benzer görünen yeni altyazılar kaçmasın). */
    var maxAgeMs = 1200L
    var skippedSame = 0L; private set

    enum class Decision { SKIP, READ, CLEAR }

    fun reset() { last = null; processed = null; stableCount = 0; emptyCount = 0; lastReadAt = 0 }

    /** Okunan görüntü sonuçsuz kaldıysa (ör. anlamsız) tekrar okunabilsin diye işaret kaldırılır. */
    fun forgetProcessed() { processed = null }

    fun decide(g: GrayImage, settle: Int): Decision {
        val edge = Gray.edgeDensity(g)
        if (edge < 0.004f) {
            emptyCount++
            last = null; stableCount = 0
            if (emptyCount == 3) { processed = null; return Decision.CLEAR }
            return Decision.SKIP
        }
        emptyCount = 0
        val sig = signature(g)
        val prev = last
        last = sig
        stableCount = if (prev != null && same(prev, sig)) stableCount + 1 else 0
        val now = System.nanoTime() / 1_000_000
        if (lastReadAt == 0L) lastReadAt = now
        // Arka plan (oyun sahnesi) sürekli oynadığında görüntü hiç "sabit" olmayabilir; bu yüzden yazı varken
        // en geç maxAgeMs'de bir her durumda okunur.
        val stale = now - lastReadAt >= maxAgeMs
        if (stableCount < settle && !stale) return Decision.SKIP
        val p = processed
        if (p != null && same(p, sig) && !stale) { skippedSame++; return Decision.SKIP }
        processed = sig
        lastReadAt = now
        return Decision.READ
    }

    /** 48x12 hücrelik ortalama parlaklık + yatay kenar imzası. */
    private fun signature(g: GrayImage): IntArray {
        val cw = 64; val ch = 16
        val out = IntArray(cw * ch * 2)
        val w = g.width; val h = g.height
        for (cy in 0 until ch) {
            val y0 = cy * h / ch; val y1 = maxOf(y0 + 1, (cy + 1) * h / ch)
            for (cx in 0 until cw) {
                val x0 = cx * w / cw; val x1 = maxOf(x0 + 1, (cx + 1) * w / cw)
                var sum = 0; var edge = 0; var n = 0
                var y = y0
                while (y < y1) {
                    var x = x0
                    val row = y * w
                    var prevV = g.data[row + x0].toInt() and 255
                    while (x < x1) {
                        val v = g.data[row + x].toInt() and 255
                        sum += v; edge += abs(v - prevV); prevV = v; n++
                        x += 2
                    }
                    y += 2
                }
                val i = (cy * cw + cx) * 2
                out[i] = if (n == 0) 0 else sum / n
                out[i + 1] = if (n == 0) 0 else edge / n
            }
        }
        return out
    }

    private fun same(a: IntArray, b: IntArray): Boolean {
        var diffCells = 0
        var i = 0
        while (i < a.size) {
            if (abs(a[i] - b[i]) > 10 || abs(a[i + 1] - b[i + 1]) > 8) diffCells++
            i += 2
        }
        return diffCells <= (a.size / 2) / 100 // hücrelerin en fazla %1'i değişmişse aynı
    }
}
