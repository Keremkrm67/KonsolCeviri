package com.playceviri.tv.vision

/**
 * Okuma (OCR) hatalarını çeviriden önce düzeltir. Bu sistemde en sık hata ince, uzun harflerin
 * (h, l, k, g, f, t, i) düşmesidir: "wit" → "with", "do ars" → "dollars", "ta in'" → "talkin'".
 * İngilizce kelime sıklık listesiyle (wordfreq, CC-BY-SA 4.0) en olası kelime seçilir.
 * Emin olunmayan kelimeye dokunulmaz.
 */
class OcrFixer(private val freq: Map<String, Int>) {
    companion object {
        /** sozluk-en.txt: "kelime<TAB>sıklık×10" satırları. */
        fun load(text: String): OcrFixer {
            val m = HashMap<String, Int>(100_000)
            text.lineSequence().forEach { l -> val t = l.indexOf('\t'); if (t > 0) m[l.substring(0, t)] = l.substring(t + 1).toIntOrNull() ?: 0 }
            return OcrFixer(m)
        }
        private const val THIN = "hlkgftij"
        /** Bu sıklığın altındaki kelimeler (wordfreq zipf < 4.2) okuma parçası olabilir. */
        private const val RARE = 42
        private val ALPHA = 'a'..'z'
        private val SUFFIX = setOf("s", "re", "ve", "ll", "d", "t", "m")
    }

    data class Result(val text: String, val changes: List<String>)

    /** Sıklık (×10); bilinmiyorsa 0. "talkin'" gibi g'siz söyleyiş "talking" sayılır. */
    private fun f(w0: String): Int {
        val w = w0.lowercase()
        freq[w]?.let { return it }
        if (w.endsWith("in'")) freq[w.dropLast(1) + "g"]?.let { return it - 5 }
        val ap = w.indexOf('\'')
        if (ap > 0) { val a = w.substring(0, ap); val b = w.substring(ap + 1); if (b in SUFFIX) freq[a]?.let { return it - 5 } }
        return 0
    }

    private class Tok(val pre: String, var core: String, val post: String)

    private fun split(t: String): Tok {
        var a = 0; var b = t.length
        while (a < b && !t[a].isLetterOrDigit()) a++
        while (b > a && !t[b - 1].isLetterOrDigit() && t[b - 1] != '\'') b--
        // sondaki kesme işareti kelimeye aittir ("talkin'"), sondaki tırnak değil
        return Tok(t.substring(0, a), t.substring(a, b), t.substring(b))
    }

    private fun matchCase(src: String, w: String) =
        if (src.isNotEmpty() && src[0].isUpperCase()) w.replaceFirstChar { it.uppercase() } else w

    /** Tek kelimeye harf ekleyerek/değiştirerek en iyi aday. */
    private fun best(core: String, onlyThinInsert: Boolean): Pair<String, Int>? {
        val w = core.lowercase()
        var bestW: String? = null; var bestS = 0
        fun consider(c: String, bonus: Int) { val s = f(c); if (s >= 30 && s + bonus > bestS) { bestS = s + bonus; bestW = c } }
        for (i in 0..w.length) for (ch in ALPHA) {
            val thin = ch in THIN
            if (onlyThinInsert && !thin) continue
            consider(w.substring(0, i) + ch + w.substring(i), if (thin) 15 else 0)
        }
        if (!onlyThinInsert) {
            // karıştırılan biçimler: rn→m, vv→w, cl→d, 1→l, 0→o
            listOf("rn" to "m", "vv" to "w", "cl" to "d", "1" to "l", "0" to "o", "ii" to "u").forEach { (a, b) ->
                var i = w.indexOf(a); while (i >= 0) { consider(w.substring(0, i) + b + w.substring(i + a.length), 5); i = w.indexOf(a, i + 1) }
            }
        }
        return bestW?.let { it to bestS }
    }

    fun fix(text: String): Result {
        val toks = text.split(' ').filter { it.isNotEmpty() }.map { split(it) }.toMutableList()
        val changes = ArrayList<String>()
        var i = 0
        while (i < toks.size) {
            val t = toks[i]; val c = t.core
            if (c.isEmpty() || c.any { it.isDigit() } || (c.length > 1 && c.all { it.isUpperCase() })) { i++; continue }
            val known = f(c)
            // 1) seyrek/bilinmeyen parça: komşusuyla arasına 1-2 harf girince yaygın bir kelime oluyor mu?
            //    ("do"+"ll"+"ars" → "dollars", "ta"+"lk"+"in'" → "talkin'")
            if (known < RARE && c.length >= 2) {
                fun joinScore(a0: String, b0: String, rareOne: String = c): Pair<String, Int>? {
                    val a = a0.lowercase(); val b = b0.lowercase()
                    var bw: String? = null; var bs = 0
                    val floor = maxOf(40, f(rareOne) + 10)
                    for (ch in THIN) { val m = a + ch + b; val sc = f(m); if (sc >= floor && sc > bs) { bs = sc; bw = m } }
                    for (c1 in ALPHA) for (c2 in ALPHA) {
                        if (c1 !in THIN && c2 !in THIN) continue
                        // boşluk bırakan düşme çoğu zaman iki ince harftir ("ll", "lk"): küçük öncelik
                        val m = a + c1 + c2 + b; val raw = f(m); val sc = raw + if (c1 in THIN && c2 in THIN) 6 else 0
                        if (raw >= floor && sc > bs) { bs = sc; bw = m }
                    }
                    return bw?.let { it to bs }
                }
                val back = if (i > 0 && toks[i - 1].post.isEmpty() && t.pre.isEmpty() && toks[i - 1].core.length >= 1) joinScore(toks[i - 1].core, c) else null
                val fwd = if (i + 1 < toks.size && t.post.isEmpty() && toks[i + 1].pre.isEmpty() && toks[i + 1].core.isNotEmpty()) joinScore(c, toks[i + 1].core) else null
                if (back != null && (fwd == null || back.second >= fwd.second)) {
                    val merged = matchCase(toks[i - 1].core, back.first)
                    changes += "${toks[i - 1].core} $c → $merged"
                    toks[i - 1] = Tok(toks[i - 1].pre, merged, t.post); toks.removeAt(i); continue
                }
                if (fwd != null) {
                    val merged = matchCase(c, fwd.first)
                    changes += "$c ${toks[i + 1].core} → $merged"
                    toks[i] = Tok(t.pre, merged, toks[i + 1].post); toks.removeAt(i + 1); i++; continue
                }
            }
            // 2) tek harf (a, I dışında): sona harf eklenir ("o" → "of")
            if (c.length == 1 && c.lowercase() !in setOf("a", "i")) {
                val cand = ALPHA.map { c.lowercase() + it }.filter { f(it) >= 50 }.maxByOrNull { f(it) + if (it[1] in THIN) 15 else 0 }
                if (cand != null) { changes += "$c → $cand"; t.core = matchCase(c, cand) }
                i++; continue
            }
            // 3) bilinmeyen kelime: harf ekle/düzelt
            if (known == 0 && c.length >= 2) {
                best(c, false)?.let { (w, _) -> changes += "$c → $w"; t.core = matchCase(c, w) }
            } else if (known in 1 until RARE && c.length >= 2) {
                // 4) nadir ama gerçek kelime ("wit", "wort"): ince harf eklenince çok daha yaygın bir kelime oluyorsa
                best(c, true)?.let { (w, s) -> if (s - 15 - known >= 15) { changes += "$c → $w"; t.core = matchCase(c, w) } }
            }
            i++
        }
        return Result(toks.joinToString(" ") { it.pre + it.core + it.post }, changes)
    }
}
