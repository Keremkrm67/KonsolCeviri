package com.playceviri.tv.vision

import android.graphics.Rect
import kotlin.math.abs
import kotlin.math.max

/**
 * İsteğe bağlı: seçili bölgenin içinde altyazıya en çok benzeyen satır öbeğini bulur
 * (16x16 bloklarda keskin parlaklık geçişlerini sayar). Bulunan dikdörtgen bölgeye göredir.
 */
object SubtitleLocator {
    private const val B = 16

    fun locate(g: GrayImage): Rect? {
        val gw = g.width / B; val gh = g.height / B
        if (gw < 8 || gh < 2) return null
        val texty = BooleanArray(gw * gh)
        var count = 0
        for (r in 0 until gh) for (c in 0 until gw) if (isTextBlock(g, c * B, r * B)) { texty[r * gw + c] = true; count++ }
        if (count < 5 || count.toFloat() / texty.size > 0.35f) return null
        val rowOn = BooleanArray(gh) { r -> (0 until gw).count { texty[r * gw + it] } >= 2 }
        var best: Rect? = null; var bestScore = 0f
        var r = 0
        while (r < gh) {
            if (!rowOn[r]) { r++; continue }
            var end = r; var gap = 0; var k = r + 1
            while (k < gh && gap <= 1) { if (rowOn[k]) { end = k; gap = 0 } else gap++; k++ }
            if (end - r + 1 <= 8) {
                evaluate(texty, gw, r, end, g.width, g.height)?.let { (rect, score) -> if (score > bestScore) { bestScore = score; best = rect } }
            }
            r = end + 1
        }
        return best
    }

    private fun evaluate(texty: BooleanArray, gw: Int, r0: Int, r1: Int, w: Int, h: Int): Pair<Rect, Float>? {
        val col = IntArray(gw)
        for (r in r0..r1) for (c in 0 until gw) if (texty[r * gw + c]) col[c]++
        var bs = 0; var be = 0; var bsum = 0
        var c = 0
        while (c < gw) {
            if (col[c] == 0) { c++; continue }
            var sum = col[c]; var end = c; var gap = 0; var k = c + 1
            while (k < gw && gap <= 5) { if (col[k] > 0) { sum += col[k]; end = k; gap = 0 } else gap++; k++ }
            if (sum > bsum) { bs = c; be = end; bsum = sum }
            c = end + 1
        }
        if (be - bs + 1 < 3 || bsum < 5) return null
        val fill = bsum.toFloat() / ((be - bs + 1) * (r1 - r0 + 1))
        if (fill < 0.12f || fill > 0.75f) return null
        val bottom = (r1 + 1) * B
        val rect = Rect(max((bs - 1) * B, 0), max(r0 * B - 6, 0), minOf((be + 2) * B, w), minOf(bottom + 6, h))
        val mid = gw / 2f
        val score = bsum * (1.2f - abs((bs + be + 1) / 2f - mid) / mid * 1.5f) * (bottom.toFloat() / h + 0.5f)
        return if (score > 0) rect to score else null
    }

    private fun isTextBlock(g: GrayImage, x0: Int, y0: Int): Boolean {
        val w = g.width
        var mn = 255; var mx = 0
        var y = 0
        while (y < B) {
            var x = 0
            while (x < B) { val v = g.data[(y0 + y) * w + x0 + x].toInt() and 255; if (v < mn) mn = v; if (v > mx) mx = v; x += 2 }
            y += 2
        }
        val contrast = mx - mn
        if (contrast < 70) return false
        val th = max(35, contrast / 3)
        var trans = 0
        y = 0
        while (y < B) {
            val row = (y0 + y) * w
            var prev = g.data[row + x0].toInt() and 255
            for (x in 1 until B) {
                val v = g.data[row + x0 + x].toInt() and 255
                if (abs(v - prev) >= th) trans++
                prev = v
            }
            y += 2
        }
        return trans in 12..72
    }
}
