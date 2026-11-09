package com.playceviri.tv.vision

/**
 * OCR çıktısını temizler ve anlamsız okumaları ayıklar.
 * Her satır (metin, güven 0..1) olarak gelir; güveni eşiğin altındaki satırlar atılır,
 * kalan metin harf oranı, sesli harf oranı ve kelime yapısına göre denetlenir.
 */
object TextFilter {
    data class Verdict(val text: String?, val reason: String)

    private val vowels = "aeiouyäöüâêîôûàèìòùáéíóúıİãõåæøœ".toSet()
    private val junkEdge = charArrayOf('•', '·', '|', ' ', '_', '~', '-', '=', '*', '#', '>', '<', '"', '\'', '‘', '’', '“', '”', '`', '—', '–')

    fun clean(lines: List<Pair<String, Float>>, minConf: Int): Verdict {
        val min = minConf / 100f
        val kept = lines.map { it.first.trim() to it.second }.filter { it.first.isNotEmpty() }
        if (kept.isEmpty()) return Verdict(null, "boş")
        val confident = kept.filter { it.second < 0f || it.second >= min }
        if (confident.isEmpty()) return Verdict(null, "düşük güven (%${(kept.maxOf { it.second } * 100).toInt()})")
        val joined = confident.joinToString(" ") { it.first }.replace(Regex("\\s+"), " ").trim(*junkEdge)
        return check(joined, minConf)
    }

    fun check(s: String, minConf: Int = 50): Verdict {
        if (s.isEmpty()) return Verdict(null, "boş")
        val letters = s.count { it.isLetter() }
        if (letters < 3) return Verdict(null, "çok kısa")
        val letterRatio = letters.toFloat() / s.count { !it.isWhitespace() }.coerceAtLeast(1)
        val strict = minConf >= 60
        if (letterRatio < (if (strict) 0.72f else 0.6f)) return Verdict(null, "harf oranı düşük")
        val words = s.split(' ').map { w -> w.filter { it.isLetter() } }.filter { it.isNotEmpty() }
        if (words.isEmpty()) return Verdict(null, "kelime yok")
        val latin = words.count { w -> w.all { it in 'A'..'Z' || it in 'a'..'z' || it.lowercaseChar() in vowels } }
        if (latin >= words.size / 2) {
            // Latin alfabesi: sesli harfi olmayan uzun kelimeler ve tek harf yığınları anlamsızlık işaretidir.
            val noVowel = words.count { w -> w.length >= 3 && w.none { it.lowercaseChar() in vowels } }
            if (noVowel * 3 > words.size && words.size >= 2) return Verdict(null, "sesli harfsiz kelimeler")
            val singles = words.count { it.length == 1 && it.lowercase() !in setOf("a", "i", "o", "y", "e") }
            if (singles * 2 > words.size && words.size >= 3) return Verdict(null, "tek harf yığını")
            if (words.size == 1 && words[0].length < 4 && strict) return Verdict(null, "tek kısa kelime")
            val caseFlips = words.count { w -> w.length >= 4 && w.drop(1).count { it.isUpperCase() } in 1 until w.length - 1 && !w.all { it.isUpperCase() } }
            if (caseFlips >= 2 && caseFlips * 2 >= words.size) return Verdict(null, "karışık büyük/küçük harf")
        }
        return Verdict(s, "tamam")
    }

    /** Karşılaştırma için sadeleştirilmiş biçim. */
    fun norm(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

    /** İki okuma neredeyse aynı mı? (OCR'ın tek harflik oynamalarını yok saymak için) */
    fun similar(a: String, b: String): Boolean {
        val x = norm(a); val y = norm(b)
        if (x == y) return true
        if (x.isEmpty() || y.isEmpty()) return false
        val d = levenshtein(x, y, maxOf(x.length, y.length) / 8 + 1)
        return d <= maxOf(x.length, y.length) / 8
    }

    private fun levenshtein(a: String, b: String, limit: Int): Int {
        if (kotlin.math.abs(a.length - b.length) > limit) return limit + 1
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            var rowMin = cur[0]
            for (j in 1..b.length) {
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
                rowMin = minOf(rowMin, cur[j])
            }
            if (rowMin > limit) return limit + 1
            val t = prev; prev = cur; cur = t
        }
        return prev[b.length]
    }
}
