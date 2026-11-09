package com.playceviri.tv.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import com.playceviri.tv.data.Settings
import com.playceviri.tv.service.PlayCeviriService
import kotlin.math.abs

/**
 * Okuma bölgesi: altyazının çıktığı alanı son kare üzerinde kutuyla seçtirir.
 * Kenarlar kaydırıcılarla (kumanda sol/sağ) ya da telefonda parmakla sürüklenerek ayarlanır.
 */
class RegionActivity : BaseActivity() {
    private lateinit var settings: Settings
    private lateinit var preview: RegionView
    private lateinit var info: TextView
    private val bars = arrayOfNulls<SeekBar>(4)
    private val labels = arrayOfNulls<TextView>(4)
    private var r = FloatArray(4)
    private var gameMode = false
    private val names = listOf("Sol kenar", "Üst kenar", "Sağ kenar", "Alt kenar")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = Settings(this)
        val game = PlayCeviriService.currentGameId
        gameMode = settings.hasGameRegion(game)
        r = settings.region(game).copyOf()

        val root = LinearLayout(this)
        root.addView(text("Okuma bölgesi", 26f, ACCENT, true))
        root.addView(note("Sarı kutu, altyazının okunacağı alandır. Kutuyu oyundaki altyazıyı içine alacak kadar büyük, menü ve göstergeleri (HUD) dışarıda bırakacak kadar küçük tut. Küçük kutu = daha az anlamsız okuma, daha hızlı."))
        preview = RegionView(this)
        root.addView(preview, LinearLayout.LayoutParams(-1, dp(200)).apply { topMargin = dp(12) })
        info = note("")
        root.addView(info)

        for (i in 0..3) {
            val lab = text("", 15f, TEXT, true).apply { setPadding(dp(4), dp(10), 0, 0) }
            val sb = SeekBar(this).apply {
                max = 100; keyProgressIncrement = 1
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) { if (fromUser) setEdge(i, p / 100f) }
                    override fun onStartTrackingTouch(s: SeekBar?) {}
                    override fun onStopTrackingTouch(s: SeekBar?) {}
                })
            }
            labels[i] = lab; bars[i] = sb
            root.addView(lab); root.addView(sb, LinearLayout.LayoutParams(-1, dp(44)))
        }

        root.addView(section("Hazır bölgeler"))
        val presets = Settings.REGION_PRESETS.map { (name, v) -> button(name) { r = v.copyOf(); save() } }
        root.addView(row(presets[0], presets[1])); root.addView(row(presets[2], presets[3]))

        root.addView(section("Seçenekler"))
        val auto = button("") { settings.autoLocate = !settings.autoLocate; update() }
        root.addView(auto)
        root.addView(note("Açıksa kutunun içinde altyazıya en çok benzeyen satırlar ayrıca bulunur. Altyazının yeri oyundan oyuna değişiyorsa işe yarar; sabit yerdeyse kapalı kalsın (daha kararlı)."))
        val gameB = button("") {
            val g = PlayCeviriService.currentGameId
            if (g.isBlank()) { toast("Açık oyun bilinmiyor. Çeviri çalışırken tekrar dene."); return@button }
            gameMode = !gameMode
            if (gameMode) settings.setRegion(r, g) else { settings.clearGameRegion(g); r = settings.region().copyOf() }
            update()
        }
        root.addView(gameB)
        root.addView(button("Görüntüyü yenile") {
            PlayCeviriService.requestSnapshot(this)
            preview.postDelayed({ preview.loadSnapshot(); update() }, 1200)
        })
        root.addView(button("Bitti", primary = true) { finish() })
        setContentView(page(root))

        auto.tag = "auto"; gameB.tag = "game"
        autoB = auto; gameBtn = gameB
        preview.loadSnapshot()
        update()
        bars[3]?.requestFocus()
    }

    private var autoB: Button? = null
    private var gameBtn: Button? = null

    private fun setEdge(i: Int, v: Float) {
        when (i) {
            0 -> r[0] = v.coerceIn(0f, r[2] - 0.05f)
            1 -> r[1] = v.coerceIn(0f, r[3] - 0.04f)
            2 -> r[2] = v.coerceIn(r[0] + 0.05f, 1f)
            3 -> r[3] = v.coerceIn(r[1] + 0.04f, 1f)
        }
        save()
    }

    private fun save() {
        val g = PlayCeviriService.currentGameId
        if (gameMode && g.isNotBlank()) settings.setRegion(r, g) else settings.setRegion(r)
        update()
    }

    private fun update() {
        for (i in 0..3) {
            labels[i]?.text = "${names[i]}: %${(r[i] * 100).toInt()}"
            if (bars[i]?.progress != (r[i] * 100).toInt()) bars[i]?.progress = (r[i] * 100).toInt()
        }
        preview.region = r
        preview.invalidate()
        autoB?.text = "Kutunun içinde altyazıyı otomatik bul: " + if (settings.autoLocate) "açık" else "kapalı"
        val gname = PlayCeviriService.currentGameName
        gameBtn?.text = if (gname.isBlank()) "Bu bölgeyi yalnız bu oyun için kullan (oyun bilinmiyor)"
        else "Yalnız bu oyun için (${gname.take(30)}): " + if (gameMode) "evet" else "hayır, tüm oyunlar"
        info.text = if (preview.hasImage) "Arka plandaki görüntü PS5'ten gelen son kare (${preview.ageText()})."
        else "Henüz görüntü yok. Çeviriyi başlat, oyunda altyazı çıkınca \"Görüntüyü yenile\"ye bas; kutuyu altyazının üstüne getir."
    }

    /** Son kare + sürüklenebilir kutu. */
    inner class RegionView(c: Context) : View(c) {
        var region = FloatArray(4)
        var hasImage = false; private set
        private var bmp: Bitmap? = null
        private var stamp = 0L
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private val frame = RectF()
        private var drag = -1

        fun loadSnapshot() {
            val f = PlayCeviriService.snapshotFile(context)
            bmp = if (f.exists()) BitmapFactory.decodeFile(f.path) else null
            hasImage = bmp != null; stamp = if (f.exists()) f.lastModified() else 0
            invalidate()
        }
        fun ageText(): String { val s = (System.currentTimeMillis() - stamp) / 1000; return if (s < 60) "$s sn önce" else "${s / 60} dk önce" }

        override fun onMeasure(w: Int, h: Int) {
            val width = MeasureSpec.getSize(w)
            setMeasuredDimension(width, minOf(width * 9 / 16, (resources.displayMetrics.heightPixels * 0.45f).toInt()))
        }

        override fun onDraw(c: Canvas) {
            val h = height.toFloat(); val w = minOf(width.toFloat(), h * 16 / 9)
            val left = (width - w) / 2
            frame.set(left, 0f, left + w, h)
            val b = bmp
            if (b != null) c.drawBitmap(b, null, frame, p) else {
                p.color = Color.rgb(36, 42, 52); c.drawRect(frame, p)
                p.color = Color.rgb(70, 78, 90); p.textSize = h / 14; p.textAlign = Paint.Align.CENTER
                c.drawText("görüntü yok", frame.centerX(), frame.centerY(), p)
            }
            val bx = RectF(frame.left + region[0] * w, region[1] * h, frame.left + region[2] * w, region[3] * h)
            p.style = Paint.Style.FILL; p.color = Color.argb(110, 0, 0, 0)
            c.drawRect(frame.left, 0f, frame.right, bx.top, p); c.drawRect(frame.left, bx.bottom, frame.right, h, p)
            c.drawRect(frame.left, bx.top, bx.left, bx.bottom, p); c.drawRect(bx.right, bx.top, frame.right, bx.bottom, p)
            p.style = Paint.Style.STROKE; p.strokeWidth = 3 * resources.displayMetrics.density; p.color = Color.parseColor(ACCENT)
            p.pathEffect = DashPathEffect(floatArrayOf(18f, 10f), 0f)
            c.drawRect(bx, p); p.pathEffect = null; p.style = Paint.Style.FILL
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            val w = frame.width(); val h = frame.height(); if (w <= 0) return false
            val fx = ((e.x - frame.left) / w).coerceIn(0f, 1f); val fy = (e.y / h).coerceIn(0f, 1f)
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    val d = listOf(abs(fx - region[0]) * w, abs(fy - region[1]) * h, abs(fx - region[2]) * w, abs(fy - region[3]) * h)
                    drag = d.indices.minByOrNull { d[it] } ?: -1
                    parent.requestDisallowInterceptTouchEvent(true)
                }
                MotionEvent.ACTION_MOVE -> if (drag >= 0) setEdge(drag, if (drag % 2 == 0) fx else fy)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> drag = -1
            }
            return true
        }
    }
}
