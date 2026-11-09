package com.playceviri.tv.vision

/**
 * "Dijital siyah bant": altyazı yerinde durur, sahne (kamera, karakterler, ışık) oynar.
 * Son birkaç karenin piksel piksel en koyusu alınınca yerinde duran parlak harfler parlak kalır,
 * hareket eden parlak sahne parçaları kararır; harflerin arkası siyaha yaklaşır.
 * Altyazı yeni belirmişse önceki karelerde yoktur ve bu yöntem onu da siler; bu yüzden okuyucu,
 * temizlenmiş karede yazı bulamazsa ham kareye geri döner.
 */
class SceneSuppressor(private val keep: Int = 2, private val maxAgeMs: Long = 900) {
    private class Frame(val g: GrayImage, val at: Long)
    private val past = ArrayDeque<Frame>()
    var lastUsed = 0; private set

    /** Her örnek karede çağrılır; okunacaksa temizlenmiş kopyayı döndürür. */
    fun push(g: GrayImage, now: Long) {
        if (past.isNotEmpty() && (past.last().g.width != g.width || past.last().g.height != g.height)) past.clear()
        past.addLast(Frame(g, now))
        while (past.size > keep + 1) past.removeFirst()
    }

    fun clean(cur: GrayImage, now: Long): GrayImage {
        val d = cur.data; val n = d.size
        val use = past.filter { it.g !== cur && now - it.at <= maxAgeMs && it.g.width == cur.width && it.g.height == cur.height }
        lastUsed = use.size
        if (use.isEmpty()) return cur
        val out = d.copyOf()
        for (f in use) { val p = f.g.data; for (i in 0 until n) if ((p[i].toInt() and 255) < (out[i].toInt() and 255)) out[i] = p[i] }
        return GrayImage(out, cur.width, cur.height)
    }

    fun reset() = past.clear()
}
