package com.playceviri.tv.vision

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Oyun sahnesinden altyazıyı ayıklar. Oyun altyazıları parlak (beyaz/sarı) harflerden oluşur ve harflerin
 * hemen çevresinde koyu kenar ya da gölge bulunur. Yöntem:
 *  1) Parlak VE birkaç piksel yakınında belirgin koyu komşusu olan pikseller işaretlenir (gökyüzü gibi
 *     geniş parlak alanların içi elenir).
 *  2) Bağlı parçalar bulunur; harf boyunda olmayanlar (çok büyük, çok küçük, çok ince-uzun) atılır.
 *  3) Kalan parçalar satırlara gruplanır; en az birkaç harf yan yana dizilmiş satırlar tutulur, tek başına
 *     duran şekiller (simgeler, sahne parçaları) atılır.
 * Çıktı: beyaz zemin üzerinde siyah yazı (Tesseract'ın en iyi okuduğu biçim). Yazı yoksa null.
 */
object SubtitleIsolator {
    class Result(val image: GrayImage, val lines: Int, val glyphs: Int)

    /** İki geçiş: önce harfler bulunur ve altyazının gerçek parlaklığı ölçülür; sonra yalnız o parlaklıktaki
     *  pikseller harf sayılır (altyazının arkasındaki açık renkli zemin böylece elenir). */
    fun isolate(g: GrayImage): Result? {
        val first = run(g, 0) ?: return null
        return run(g, first.level)?.result ?: first.result
    }

    private class Pass(val result: Result, val level: Int)

    /** level: altyazının ölçülen parlaklığı (0 = henüz bilinmiyor). Zemini açık renkli noktalarda yalnız bu
     *  parlaklığa çok yakın pikseller harf sayılır; koyu zeminde kural gevşek kalır (harfler incelmesin). */
    private fun run(g: GrayImage, level: Int): Pass? {
        val w = g.width; val h = g.height
        val px = IntArray(w * h) { g.data[it].toInt() and 255 }
        // 1) Beyaz top-hat: parlaklık - "açma" (önce en koyu, sonra en parlak süzgeç, 11x11).
        //    İnce parlak çizgiler (harfler) yüksek değer verir; geniş parlak alanlar (gökyüzü, torba) ~0 olur.
        val r = 5
        // van Herk / Gil-Werman: pencere boyundan bağımsız, piksel başına sabit sürede en küçük/en büyük süzgeç
        fun filt(src: IntArray, horiz: Boolean, isMax: Boolean): IntArray {
            val o = IntArray(w * h)
            val n = if (horiz) w else h; val lines = if (horiz) h else w
            val k = 2 * r + 1
            val pre = IntArray(n + 2 * r); val suf = IntArray(n + 2 * r); val buf = IntArray(n + 2 * r)
            val pad = if (isMax) 0 else 255
            for (li in 0 until lines) {
                for (i in 0 until n + 2 * r) {
                    val j = i - r
                    buf[i] = if (j < 0 || j >= n) pad else if (horiz) src[li * w + j] else src[j * w + li]
                }
                val len = n + 2 * r
                var i = 0
                while (i < len) {
                    val end = min(i + k, len)
                    pre[i] = buf[i]
                    for (q in i + 1 until end) pre[q] = if (isMax) max(pre[q - 1], buf[q]) else min(pre[q - 1], buf[q])
                    suf[end - 1] = buf[end - 1]
                    for (q in end - 2 downTo i) suf[q] = if (isMax) max(suf[q + 1], buf[q]) else min(suf[q + 1], buf[q])
                    i = end
                }
                for (x in 0 until n) {
                    val a0 = x; val b0 = x + 2 * r
                    val v = if (isMax) max(suf[a0], pre[b0]) else min(suf[a0], pre[b0])
                    if (horiz) o[li * w + x] = v else o[x * w + li] = v
                }
            }
            return o
        }
        fun minH(s0: IntArray) = filt(s0, true, false)
        fun minV(s0: IntArray) = filt(s0, false, false)
        fun maxH(s0: IntArray) = filt(s0, true, true)
        fun maxV(s0: IntArray) = filt(s0, false, true)
        val opened = maxV(maxH(minV(minH(px))))
        val top = IntArray(w * h) { px[it] - opened[it] }
        val TH = 45
        // Harf pikseli çevresindeki en parlak noktaya yakın olmalı: açık renkli zemin (torba, duvar) harften
        // biraz daha koyu kaldığı için elenir.
        val localMax = maxV(maxH(px))
        // Tolerans zemine göre: koyu zeminde geniş (bulanık harf kenarları da alınır), açık zeminde dar
        // (yalnız zeminden parlak olan harf çekirdeği alınır).
        fun floorAt(i: Int) = if (level > 0 && opened[i] >= level - 70) level - 10 else 140
        val keep = BooleanArray(w * h) { top[it] >= TH && px[it] >= floorAt(it) && px[it] >= localMax[it] - maxOf(8, (localMax[it] - opened[it]) / 3) }
        // 2) bağlı parçalar
        val label = IntArray(w * h)
        val stack = IntArray(w * h)
        data class Comp(val x0: Int, val y0: Int, val x1: Int, val y1: Int, val area: Int, val id: Int) {
            val cx get() = (x0 + x1) / 2f; val cy get() = (y0 + y1) / 2f
            val ch get() = y1 - y0 + 1; val cw get() = x1 - x0 + 1
        }
        val comps = ArrayList<Comp>()
        var next = 0
        for (start in keep.indices) {
            if (!keep[start] || label[start] != 0) continue
            next++
            var sp = 0; stack[sp++] = start; label[start] = next
            var x0 = w; var y0 = h; var x1 = 0; var y1 = 0; var area = 0
            while (sp > 0) {
                val p = stack[--sp]
                val x = p % w; val y = p / w
                area++
                if (x < x0) x0 = x; if (x > x1) x1 = x; if (y < y0) y0 = y; if (y > y1) y1 = y
                if (x > 0 && keep[p - 1] && label[p - 1] == 0) { label[p - 1] = next; stack[sp++] = p - 1 }
                if (x < w - 1 && keep[p + 1] && label[p + 1] == 0) { label[p + 1] = next; stack[sp++] = p + 1 }
                if (y > 0 && keep[p - w] && label[p - w] == 0) { label[p - w] = next; stack[sp++] = p - w }
                if (y < h - 1 && keep[p + w] && label[p + w] == 0) { label[p + w] = next; stack[sp++] = p + w }
            }
            comps.add(Comp(x0, y0, x1, y1, area, next))
        }
        val maxH = min(h * 0.5f, 120f)
        val glyphs = comps.filter { c ->
            c.ch in 6..maxH.toInt() && c.cw <= c.ch * 8 && c.area >= 6 &&
                c.area.toFloat() / (c.cw * c.ch) >= 0.08f &&
                !(c.cw >= 7 && c.ch >= 7 && c.cw * 2 > c.ch && c.area.toFloat() / (c.cw * c.ch) > 0.9f)   // dolu kare blok; dik çubuk (I, l, i gövdesi) değil
        }
        if (glyphs.size < 3) return null
        // 3) satırlara grupla (boyu benzer, merkezi aynı hizada, aralarında büyük boşluk olmayan harfler)
        val sorted = glyphs.sortedBy { it.cy }
        val lines = ArrayList<MutableList<Comp>>()
        for (c in sorted) {
            val line = lines.lastOrNull { l -> val my = l.map { it.cy }.average(); val mh = l.map { it.ch }.average(); abs(c.cy - my) < mh * 0.55 }
            if (line != null) line.add(c) else lines.add(mutableListOf(c))
        }
        val kept = ArrayList<Comp>()
        val bands = ArrayList<FloatArray>()   // [üst, alt, harf boyu]
        var lineCount = 0
        for (l in lines) {
            if (l.size < 3) continue
            val mh = l.map { it.ch }.sorted()[l.size / 2].toFloat()
            // satır içinde en büyük kesintisiz harf dizisini al (aradaki boşluk < 2.5 harf boyu)
            val byX = l.filter { it.ch >= mh * 0.35f && it.ch <= mh * 1.9f }.sortedBy { it.x0 }
            var best = emptyList<Comp>(); var cur = ArrayList<Comp>()
            for (c in byX) {
                if (cur.isNotEmpty() && c.x0 - cur.maxOf { it.x1 } > mh * 2.5f) { if (cur.size > best.size) best = cur; cur = ArrayList() }
                cur.add(c)
            }
            if (cur.size > best.size) best = cur
            if (best.size < 5) continue   // altyazı satırı en az 5 harftir; daha kısası sahne parçasıdır
            kept.addAll(best); lineCount++
            bands.add(floatArrayOf(best.minOf { it.y0 }.toFloat(), best.maxOf { it.y1 }.toFloat(), mh, best.minOf { it.x0 }.toFloat(), best.maxOf { it.x1 }.toFloat()))
        }
        if (kept.isEmpty()) return null
        // noktalama/aksan gibi küçük parçaları, tutulan satırlara yakınsa geri al
        val ids = HashSet<Int>(); kept.forEach { ids.add(it.id) }
        val clip = HashMap<Int, IntArray>()
        val bx0 = kept.minOf { it.x0 }; val bx1 = kept.maxOf { it.x1 }; val by0 = kept.minOf { it.y0 }; val by1 = kept.maxOf { it.y1 }
        for (c in comps) {
            if (c.id in ids || c.area < 3) continue
            // yalnız bir satırın hizasında ve harften küçük parçalar (nokta, virgül, kesme, aksan)
            val ok = bands.any { b -> c.y0 >= b[0] - b[2] * 0.4f && c.y1 <= b[1] + b[2] * 0.3f &&
                c.x0 >= b[3] - b[2] && c.x1 <= b[4] + b[2] && c.ch <= b[2] * 0.6f && c.cw <= b[2] * 0.6f }
            if (ok) { ids.add(c.id); continue }
            // Harfe sahneden bir parça yapışınca parça satırdan uzun olur ve tümden atılırdı (eksik h/l/k/g).
            // Satır hizasındaki kısmı alınır, satır dışına taşan kısmı boyanmaz.
            val b = bands.firstOrNull { b -> c.y0 < b[1] && c.y1 > b[0] && c.x0 >= b[3] - b[2] && c.x1 <= b[4] + b[2] &&
                c.ch > b[2] * 1.9f && c.cw <= b[2] * 3f } ?: continue
            clip[c.id] = intArrayOf((b[0] - b[2] * 0.15f).toInt(), (b[1] + b[2] * 0.15f).toInt())
        }
        val pad = 16
        val ox0 = max(0, bx0 - 4); val oy0 = max(0, by0 - 4); val ox1 = min(w - 1, bx1 + 4); val oy1 = min(h - 1, by1 + 4)
        // Çıktı: yazı alanı küçük yazıda 2 kat büyütülür; harf kenarı top-hat değerinden yumuşak hesaplanır,
        // yalnız seçilen harflerin (1 piksel genişletilmiş) alanı boyanır.
        val mh = kept.map { it.ch }.sorted()[kept.size / 2]
        val scale = if (mh < 34) 2 else 1
        val mask = BooleanArray(w * h)
        for (y in oy0..oy1) for (x in ox0..ox1) { val l = label[y * w + x]; if (l != 0 && (l in ids || clip[l]?.let { y in it[0]..it[1] } == true))
            for (dy in -1..1) for (dx in -1..1) { val yy = y + dy; val xx = x + dx; if (yy in 0 until h && xx in 0 until w) mask[yy * w + xx] = true } }
        val sw = (ox1 - ox0 + 1) * scale; val shh = (oy1 - oy0 + 1) * scale
        val ow2 = sw + pad * 2; val oh2 = shh + pad * 2
        val out2 = ByteArray(ow2 * oh2) { -1 }
        for (Y in 0 until shh) for (X in 0 until sw) {
            val fx = ox0 + (X + 0.5f) / scale - 0.5f; val fy = oy0 + (Y + 0.5f) / scale - 0.5f
            val x0 = fx.toInt().coerceIn(0, w - 1); val y0 = fy.toInt().coerceIn(0, h - 1)
            if (!mask[y0 * w + x0]) continue
            val x1 = min(x0 + 1, w - 1); val y1 = min(y0 + 1, h - 1)
            val ax = (fx - x0).coerceIn(0f, 1f); val ay = (fy - y0).coerceIn(0f, 1f)
            val t0 = top[y0 * w + x0] + (top[y0 * w + x1] - top[y0 * w + x0]) * ax
            val t1 = top[y1 * w + x0] + (top[y1 * w + x1] - top[y1 * w + x0]) * ax
            val p0 = px[y0 * w + x0] + (px[y0 * w + x1] - px[y0 * w + x0]) * ax
            val p1 = px[y1 * w + x0] + (px[y1 * w + x1] - px[y1 * w + x0]) * ax
            val pv = p0 + (p1 - p0) * ay; val ci = y0 * w + x0
            // harfe değen açık renkli sahne parçası boyanmasın: piksel, çevresindeki en parlak noktaya yakın olmalı
            if (t0 + (t1 - t0) * ay >= TH && pv >= floorAt(ci)) out2[(Y + pad) * ow2 + X + pad] = 0
        }
        // altyazı parlaklığı: seçilen harf piksellerinin %75'lik değeri
        val vals = ArrayList<Int>()
        for (y in oy0..oy1) for (x in ox0..ox1) { val l = label[y * w + x]; if (l != 0 && l in ids) vals.add(px[y * w + x]) }
        vals.sort()
        val level = if (vals.isEmpty()) 0 else vals[vals.size * 3 / 4]
        return Pass(Result(GrayImage(out2, ow2, oh2), lineCount, kept.size), level)
    }

    fun pack(b: GrayImage): ByteArray {
        val rowBytes = (b.width + 7) shr 3
        val out = ByteArray(rowBytes * b.height)
        for (y in 0 until b.height) {
            val row = y * b.width
            for (x in 0 until b.width) if (b.data[row + x].toInt() == 0) {
                val i = y * rowBytes + (x shr 3)
                out[i] = (out[i].toInt() or (0x80 ushr (x and 7))).toByte()
            }
        }
        return out
    }
}
