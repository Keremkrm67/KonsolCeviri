package com.playceviri.tv.overlay

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.view.View
import com.playceviri.tv.data.Settings
import kotlin.math.max
import kotlin.math.min

/** Ayarların çizim anındaki kopyası (her karede SharedPreferences okunmasın diye). */
data class SubStyle(
    val posMode: Int, val xPct: Int, val yPct: Int, val widthPct: Int, val fontPt: Int, val font: Int,
    val textColor: Int, val outlineOn: Boolean, val outlineColor: Int, val outlinePx: Int,
    val shadowOn: Boolean, val shadowColor: Int, val shadowPct: Int,
    val bgColor: Int, val bgOpacity: Int, val cornerPx: Int,
    val origMode: Int, val origSizePct: Int, val origColor: Int, val maxLines: Int,
    val durationSec: Int, val statusDot: Boolean,
) {
    companion object {
        fun from(s: Settings) = SubStyle(
            s.posMode, s.xPct, s.yPct, s.widthPct, s.fontPt, s.font, s.textColor, s.outlineOn, s.outlineColor,
            s.outlinePx, s.shadowOn, s.shadowColor, s.shadowPct, s.bgColor, s.bgOpacity, s.cornerPx,
            s.origMode, s.origSizePct, s.origColor, s.maxLines, s.durationSec, s.statusDot,
        )
    }
}

/**
 * Tüm ekranı kaplayan, yalnızca altyazı kutusunu ve durum noktasını çizen görünüm.
 * Aynı sınıf ayar ekranındaki canlı önizlemede de kullanılır (küçük 16:9 kutu içinde).
 */
class SubtitleView(context: Context) : View(context) {
    var style: SubStyle = SubStyle.from(Settings(context)); set(v) { field = v; relayout() }
    private var text: String = ""
    private var orig: String = ""
    /** "Orijinalin üstüne" modu için karedeki konum (0..1). */
    private var anchor: FloatArray? = null
    private var subAlpha = 0f
    private var animator: ValueAnimator? = null

    var dotColor: Int = 0; private set
    private var dotLabel: String = ""
    private var dotLabelUntil = 0L

    private val fill = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val origPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)
    private val labelPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private var mainLayout: StaticLayout? = null
    private var origLayout: StaticLayout? = null
    private val box = RectF()

    fun setSubtitle(t: String, o: String, anchorFrac: FloatArray?, animate: Boolean = true) {
        text = t; orig = o; anchor = anchorFrac
        relayout()
        fadeTo(1f, if (animate) 120 else 0)
    }

    fun clearSubtitle(animate: Boolean = true) = fadeTo(0f, if (animate) 160 else 0)
    val isShowing get() = subAlpha > 0.01f && text.isNotEmpty()

    fun setDot(color: Int, label: String, labelMs: Long = 4000) {
        dotColor = color
        if (label != dotLabel) { dotLabel = label; dotLabelUntil = System.currentTimeMillis() + labelMs }
        invalidate()
        postDelayed({ invalidate() }, labelMs + 50)
    }

    private fun fadeTo(target: Float, ms: Long) {
        animator?.cancel()
        if (ms <= 0) { subAlpha = target; invalidate(); return }
        animator = ValueAnimator.ofFloat(subAlpha, target).setDuration(ms).apply {
            addUpdateListener { subAlpha = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) { relayout() }

    /** Punto → piksel: 1080 satırlık ekranda 1 punto ≈ 1.5 piksel. Telefon ve TV aynı oranı kullanır. */
    private fun ptToPx(pt: Float) = pt * height / 1080f * 1.5f

    private fun typeface(i: Int): Typeface = when (i) {
        0 -> Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
        1 -> Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        2 -> Typeface.create("sans-serif-condensed", Typeface.NORMAL)
        3 -> Typeface.create("sans-serif-condensed", Typeface.BOLD)
        4 -> Typeface.create(Typeface.SERIF, Typeface.BOLD)
        else -> Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }

    private fun relayout() {
        if (width == 0 || height == 0 || text.isEmpty()) { mainLayout = null; origLayout = null; invalidate(); return }
        val s = style
        val pad = ptToPx(s.fontPt * 0.45f)
        val maxW = when {
            s.posMode == 1 && anchor != null -> max(anchor!![2] * width, width * 0.3f)
            else -> width * s.widthPct / 100f
        }.coerceAtMost(width * 0.98f)
        val inner = max(40f, maxW - pad * 2).toInt()
        fill.textSize = ptToPx(s.fontPt.toFloat()); fill.typeface = typeface(s.font)
        mainLayout = build(text, fill, inner, s.maxLines)
        origLayout = if (s.origMode != 0 && orig.isNotBlank()) {
            origPaint.textSize = fill.textSize * s.origSizePct / 100f
            origPaint.typeface = typeface(if (s.font % 2 == 1) s.font - 1 else s.font)
            build(orig, origPaint, inner, 2)
        } else null
        invalidate()
    }

    private fun build(t: String, p: TextPaint, w: Int, lines: Int): StaticLayout =
        StaticLayout.Builder.obtain(t, 0, t.length, p, w)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setMaxLines(lines).setEllipsize(TextUtils.TruncateAt.END)
            .setIncludePad(false).setLineSpacing(0f, 1.05f).build()

    private fun layoutWidth(l: StaticLayout): Float { var m = 0f; for (i in 0 until l.lineCount) m = max(m, l.getLineWidth(i)); return m }

    private var qr: android.graphics.Bitmap? = null
    private var qrLabel = ""
    private val qrRect = RectF()

    /** TV'de telefon bağlı değilken köşede QR kod gösterir (oyundan çıkmadan okutulabilsin). */
    fun setQr(b: android.graphics.Bitmap?, label: String) { qr = b; qrLabel = label; invalidate() }

    override fun onDraw(canvas: Canvas) {
        drawSubtitle(canvas)
        drawDot(canvas)
        drawQr(canvas)
    }

    private fun drawQr(canvas: Canvas) {
        val b = qr ?: return
        val size = height * 0.24f
        val pad = size * 0.06f
        val right = width - height * 0.03f; val bottom = height * 0.97f
        labelPaint.textSize = max(14f, height / 50f)
        val tw = labelPaint.measureText(qrLabel)
        val boxW = max(size, tw) + pad * 2
        bg.color = Color.argb(215, 8, 10, 14)
        canvas.drawRoundRect(right - boxW, bottom - size - pad * 3 - labelPaint.textSize, right, bottom, pad * 2, pad * 2, bg)
        qrRect.set(right - pad - size - (boxW - size - pad * 2) / 2, bottom - pad - size, right - pad - (boxW - size - pad * 2) / 2, bottom - pad)
        canvas.drawBitmap(b, null, qrRect, null)
        canvas.drawText(qrLabel, right - boxW + pad, qrRect.top - pad * 0.8f, labelPaint)
    }

    private fun drawSubtitle(canvas: Canvas) {
        val main = mainLayout ?: return
        if (subAlpha <= 0.01f) return
        val s = style
        val pad = ptToPx(s.fontPt * 0.45f)
        val o = origLayout
        val gap = if (o != null) fill.textSize * 0.25f else 0f
        val contentW = max(layoutWidth(main), o?.let { layoutWidth(it) } ?: 0f)
        val contentH = main.height + (o?.height ?: 0) + gap
        val bw = contentW + pad * 2; val bh = contentH + pad * 1.2f
        var cx: Float; var cy: Float
        val a = anchor
        if (s.posMode == 1 && a != null) {
            cx = (a[1] + a[2] / 2f) * width; cy = a[0] * height + bh / 2f
        } else {
            cx = width * s.xPct / 100f; cy = height * s.yPct / 100f
        }
        cx = cx.coerceIn(bw / 2f + 4, width - bw / 2f - 4)
        cy = cy.coerceIn(bh / 2f + 4, height - bh / 2f - 4)
        box.set(cx - bw / 2f, cy - bh / 2f, cx + bw / 2f, cy + bh / 2f)
        val alpha = (subAlpha * 255).toInt()
        if (s.bgOpacity > 0) {
            bg.color = s.bgColor
            bg.alpha = (s.bgOpacity * 255 / 100f * subAlpha).toInt()
            val r = ptToPx(s.cornerPx / 1.5f)
            canvas.drawRoundRect(box, r, r, bg)
        }
        var y = box.top + pad * 0.6f
        val left = cx - main.width / 2f
        if (o != null && s.origMode == 2) { drawLayout(canvas, o, origPaint, s.origColor, left, y, alpha, true); y += o.height + gap }
        drawLayout(canvas, main, fill, s.textColor, left, y, alpha, false)
        y += main.height + gap
        if (o != null && s.origMode == 1) drawLayout(canvas, o, origPaint, s.origColor, left, y, alpha, true)
    }

    private fun drawLayout(c: Canvas, l: StaticLayout, p: TextPaint, color: Int, x: Float, y: Float, alpha: Int, small: Boolean) {
        val s = style
        val scale = height / 1080f
        c.save(); c.translate(x, y)
        if (s.shadowOn && s.shadowPct > 0) {
            p.style = Paint.Style.FILL
            val sc = Color.argb(min(254, alpha * s.shadowPct / 100), Color.red(s.shadowColor), Color.green(s.shadowColor), Color.blue(s.shadowColor))
            p.setShadowLayer(max(1f, 6f * scale * s.shadowPct / 60f), 0f, 2.5f * scale, sc)
            p.color = color; p.alpha = alpha
            l.draw(c)
            p.clearShadowLayer()
        }
        if (s.outlineOn && s.outlinePx > 0) {
            p.style = Paint.Style.STROKE
            p.strokeJoin = Paint.Join.ROUND
            p.strokeWidth = s.outlinePx * 2f * scale * (if (small) 0.7f else 1f)
            p.color = s.outlineColor; p.alpha = alpha
            l.draw(c)
        }
        p.style = Paint.Style.FILL
        p.color = color; p.alpha = alpha
        l.draw(c)
        c.restore()
    }

    private fun drawDot(canvas: Canvas) {
        if (!style.statusDot || dotColor == 0) return
        val r = max(5f, height / 140f)
        val x = width - r * 4; val y = r * 4
        dot.color = Color.argb(150, 0, 0, 0)
        canvas.drawCircle(x, y, r * 1.6f, dot)
        dot.color = dotColor
        canvas.drawCircle(x, y, r, dot)
        if (System.currentTimeMillis() < dotLabelUntil && dotLabel.isNotEmpty()) {
            labelPaint.textSize = max(14f, height / 48f)
            val tw = labelPaint.measureText(dotLabel)
            val pad = labelPaint.textSize * 0.4f
            dot.color = Color.argb(170, 8, 10, 14)
            canvas.drawRoundRect(x - r * 2.2f - tw - pad * 2, y - labelPaint.textSize * 0.8f, x - r * 2.2f, y + labelPaint.textSize * 0.6f, pad, pad, dot)
            canvas.drawText(dotLabel, x - r * 2.2f - tw - pad, y + labelPaint.textSize * 0.3f, labelPaint)
        }
    }

    companion object {
        val DOT_GREEN = Color.rgb(74, 222, 128)
        val DOT_YELLOW = Color.rgb(255, 209, 102)
        val DOT_RED = Color.rgb(255, 99, 132)
    }
}
