package com.playceviri.tv.ui

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import com.playceviri.tv.data.Settings
import com.playceviri.tv.display.DisplayClient
import com.playceviri.tv.display.DisplayService
import com.playceviri.tv.overlay.SubStyle
import com.playceviri.tv.overlay.SubtitleView
import com.playceviri.tv.service.PlayCeviriService

/**
 * Yazı görünümü: konum (%), punto, yazı tipi, renkler, kenar çizgisi, gölge, arka plan, orijinal metin,
 * satır sayısı, süre. Her değişiklik üstteki önizlemede ve (bağlıysa) TV'de anında görünür.
 */
class StyleActivity : BaseActivity() {
    private lateinit var s: Settings
    private lateinit var preview: SubtitleView
    private val refreshers = mutableListOf<() -> Unit>()
    private val handler = Handler(Looper.getMainLooper())
    private var tv: DisplayClient? = null
    private val push = Runnable { pushNow() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        s = Settings(this)
        val root = LinearLayout(this)
        root.addView(text("Yazı görünümü", 26f, ACCENT, true))
        root.addView(note(when (s.role) {
            Settings.Role.PHONE -> "Değişiklikler TV bağlıysa TV'de örnek altyazıyla anında görünür."
            Settings.Role.TV_DISPLAY -> "Değişiklikler bu TV'de saklanır. Telefon bağlanınca telefondaki ayarlar geçerli olur."
            else -> "Değişiklikler hemen uygulanır."
        }))

        val frame = FrameLayout(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Color.rgb(64, 92, 120), Color.rgb(46, 60, 52), Color.rgb(24, 28, 26)))
        }
        preview = SubtitleView(this)
        frame.addView(preview, FrameLayout.LayoutParams(-1, -1))
        root.addView(frame, LinearLayout.LayoutParams(-1, 0).apply { topMargin = dp(10) })
        frame.post {
            val w = frame.width
            frame.layoutParams = frame.layoutParams.apply { height = minOf(w * 9 / 16, (resources.displayMetrics.heightPixels * 0.42f).toInt()) }
        }

        root.addView(section("Konum"))
        add(root, { "Konum: ${Settings.POS_MODES[s.posMode]}" }, { s.posMode = 1 - s.posMode }, { s.posMode = 1 - s.posMode })
        num(root, "Yatay konum", { s.xPct }, { s.xPct = it }, 0, 100, "%", "Kutunun ortası ekranın soldan yüzde kaçında? 50 = tam orta.", prefix = true)
        num(root, "Dikey konum", { s.yPct }, { s.yPct = it }, 0, 100, "%", "Kutunun ortası ekranın üstten yüzde kaçında? 88 = altyazının olağan yeri.", prefix = true)
        num(root, "Kutu genişliği (en fazla)", { s.widthPct }, { s.widthPct = it }, 30, 100, "%", "Uzun cümleler bu genişlikte alt satıra geçer.", prefix = true)
        val quick = listOf("Altta" to (50 to 88), "Altın üstü" to (50 to 76), "Ortada" to (50 to 50), "Üstte" to (50 to 10))
            .map { (n, xy) -> button(n) { s.xPct = xy.first; s.yPct = xy.second; changed() } }
        root.addView(row(quick[0], quick[1])); root.addView(row(quick[2], quick[3]))

        root.addView(section("Yazı"))
        num(root, "Punto", { s.fontPt }, { s.fontPt = it }, 12, 72, " pt", "1080p ekranda 34 pt ≈ olağan altyazı boyu.")
        add(root, { "Yazı tipi: ${Settings.FONTS[s.font]}" }, {
            choiceDialog("Yazı tipi", Settings.FONTS, { it }, s.font) { s.font = it; changed() }
        }, { s.font = (s.font + 1) % Settings.FONTS.size })
        color(root, "Yazı rengi", { s.textColor }) { s.textColor = it }
        add(root, { "Satır sayısı: en fazla ${s.maxLines}" }, { s.maxLines = s.maxLines % 3 + 1 }, { s.maxLines = s.maxLines % 3 + 1 })

        root.addView(section("Kenar çizgisi ve gölge"))
        add(root, { "Kenar çizgisi: " + if (s.outlineOn) "açık" else "kapalı" }, { s.outlineOn = !s.outlineOn }, { s.outlineOn = !s.outlineOn })
        color(root, "Kenar rengi", { s.outlineColor }) { s.outlineColor = it }
        num(root, "Kenar kalınlığı", { s.outlinePx }, { s.outlinePx = it }, 0, 6, " px", "")
        add(root, { "Gölge: " + if (s.shadowOn) "açık" else "kapalı" }, { s.shadowOn = !s.shadowOn }, { s.shadowOn = !s.shadowOn })
        color(root, "Gölge rengi", { s.shadowColor }) { s.shadowColor = it }
        num(root, "Gölge yoğunluğu", { s.shadowPct }, { s.shadowPct = it }, 0, 100, "%", "", prefix = true)

        root.addView(section("Arka plan kutusu"))
        color(root, "Kutu rengi", { s.bgColor }) { s.bgColor = it }
        num(root, "Kutu koyuluğu", { s.bgOpacity }, { s.bgOpacity = it }, 0, 100, "%", "0 = kutu yok (yalnız kenarlı yazı), 100 = tamamen opak.", prefix = true)
        num(root, "Köşe yuvarlaklığı", { s.cornerPx }, { s.cornerPx = it }, 0, 40, "", "")

        root.addView(section("Orijinal metin"))
        add(root, { "Orijinal: ${Settings.ORIG_MODES[s.origMode]}" }, {
            choiceDialog("Orijinal metin", Settings.ORIG_MODES, { it }, s.origMode) { s.origMode = it; changed() }
        }, { s.origMode = (s.origMode + 1) % 3 })
        num(root, "Orijinalin boyutu", { s.origSizePct }, { s.origSizePct = it }, 40, 100, "%", "Çeviri puntosuna göre.", prefix = true)
        color(root, "Orijinalin rengi", { s.origColor }) { s.origColor = it }

        root.addView(section("Süre ve durum"))
        add(root, { "Gösterim süresi: " + if (s.durationSec == 0) "otomatik (oyundaki altyazı kaybolunca)" else "${s.durationSec} sn" },
            { s.durationSec = (s.durationSec + 1) % 11 }, { s.durationSec = (s.durationSec + 1) % 11 }, { d -> s.durationSec = (s.durationSec + d + 11) % 11 })
        add(root, { "Köşedeki durum noktası: " + if (s.statusDot) "göster" else "gizle" }, { s.statusDot = !s.statusDot }, { s.statusDot = !s.statusDot })
        root.addView(note("Durum noktası: yeşil çeviri akıyor, sarı bağlanıyor/bekliyor, kırmızı bağlantı yok. Durum değişince yanında kısa bir açıklama belirir."))

        root.addView(section(""))
        root.addView(button("Varsayılana dön") { s.resetStyle(); changed() })
        root.addView(button("Bitti", primary = true) { finish() })
        setContentView(page(root))

        if (s.role == Settings.Role.PHONE && !PlayCeviriService.running && s.tvPaired) {
            tv = DisplayClient(this, { s.tvHost }, { s.tvPort }, { s.tvName }, { s.tvCode }) { st, _ ->
                if (st == DisplayClient.State.CONNECTED) handler.post { pushNow() }
            }.also { it.start() }
        }
        update()
    }

    override fun onDestroy() { tv?.shutdown(); super.onDestroy() }

    private fun update() {
        refreshers.forEach { it() }
        preview.style = SubStyle.from(s)
        preview.setSubtitle("Bunu bana yaptığına inanamıyorum.", "I can't believe you did this to me.", floatArrayOf(0.8f, 0.2f, 0.6f), animate = false)
        preview.setDot(SubtitleView.DOT_GREEN, "Çeviri çalışıyor", 2500)
    }

    private fun changed() {
        update()
        handler.removeCallbacks(push)
        handler.postDelayed(push, 250)
    }

    private fun pushNow() {
        when (s.role) {
            Settings.Role.PHONE -> if (PlayCeviriService.running) PlayCeviriService.pushStyle(this, true) else tv?.sendStyle(s.styleJson(), true)
            Settings.Role.TV_DISPLAY -> DisplayService.restyle(this)
            Settings.Role.TV_SOLO, Settings.Role.TV_BROWSER -> PlayCeviriService.pushStyle(this, false)
        }
    }

    private fun add(root: LinearLayout, label: () -> String, onClick: () -> Unit, onRight: () -> Unit, onStep: ((Int) -> Unit)? = null) {
        val b = button("") { onClick(); changed() }
        stepper(b) { d -> if (onStep != null) onStep(d) else onRight(); changed() }
        refreshers += { b.text = label() }
        root.addView(b)
    }

    private fun num(root: LinearLayout, title: String, get: () -> Int, set: (Int) -> Unit, min: Int, max: Int, unit: String, explain: String, prefix: Boolean = false) {
        fun fmt(v: Int) = if (prefix) "%$v" else "$v$unit"
        val b = button("") {
            numberDialog(title, min, max, get(), if (prefix) "" else unit, explain) { v -> set(v); changed() }
        }
        stepper(b) { d -> set((get() + d * (if (max - min > 40) 2 else 1)).coerceIn(min, max)); changed() }
        refreshers += { b.text = "$title: ${fmt(get())}  ◀ ▶" }
        root.addView(b)
    }

    private fun color(root: LinearLayout, title: String, get: () -> Int, set: (Int) -> Unit) {
        val b = button("") { pickColor(title, get()) { set(it); changed() } }
        stepper(b) { d ->
            val i = Settings.COLORS.indexOfFirst { it.second == get() }
            set(Settings.COLORS[((if (i < 0) 0 else i) + d + Settings.COLORS.size) % Settings.COLORS.size].second); changed()
        }
        refreshers += {
            b.text = "$title: ${Settings.colorName(get())}  ◀ ▶"
            b.setCompoundDrawablesRelativeWithIntrinsicBounds(null, null, GradientDrawable().apply {
                setColor(get()); cornerRadius = dp(6).toFloat(); setStroke(dp(1), Color.WHITE); setSize(dp(28), dp(28))
            }, null)
        }
        root.addView(b)
    }

    /** 16 hazır renk + ton/doygunluk/parlaklık kaydırıcılı ve HEX kodlu serbest seçici. */
    private fun pickColor(title: String, cur: Int, onPick: (Int) -> Unit) {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), 0) }
        val grid = GridLayout(this).apply { columnCount = 4 }
        var dlg: android.app.AlertDialog? = null
        Settings.COLORS.forEach { (name, c) ->
            val sw = Button(this).apply {
                text = name; textSize = 11f; isAllCaps = false
                setTextColor(if (Color.luminance(c) > 0.5f) Color.BLACK else Color.WHITE)
                background = android.graphics.drawable.StateListDrawable().apply {
                    addState(intArrayOf(android.R.attr.state_focused), GradientDrawable().apply { setColor(c); cornerRadius = dp(8).toFloat(); setStroke(dp(3), Color.parseColor(ACCENT)) })
                    addState(intArrayOf(), GradientDrawable().apply { setColor(c); cornerRadius = dp(8).toFloat(); setStroke(dp(1), if (c == cur) Color.parseColor(ACCENT) else Color.GRAY) })
                }
                setOnClickListener { onPick(c); dlg?.dismiss() }
            }
            grid.addView(sw, GridLayout.LayoutParams().apply { width = dp(76); height = dp(46); setMargins(dp(3), dp(3), dp(3), dp(3)) })
        }
        box.addView(grid)
        box.addView(text("Özel renk", 14f, MUTED, true).apply { setPadding(0, dp(10), 0, 0) })
        val hsv = FloatArray(3); Color.colorToHSV(cur, hsv)
        val sample = View(this).apply { setBackgroundColor(cur) }
        val hex = EditText(this).apply { setText(String.format("#%06X", cur and 0xFFFFFF)); textSize = 15f }
        fun current() = Color.HSVToColor(hsv)
        val names = listOf("Ton", "Doygunluk", "Parlaklık")
        val maxes = listOf(360, 100, 100)
        names.forEachIndexed { i, n ->
            box.addView(text(n, 12f, FAINT))
            box.addView(SeekBar(this).apply {
                max = maxes[i]; progress = if (i == 0) hsv[0].toInt() else (hsv[i] * 100).toInt(); keyProgressIncrement = if (i == 0) 5 else 2
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(sb: SeekBar?, p: Int, u: Boolean) {
                        hsv[i] = if (i == 0) p.toFloat() else p / 100f
                        sample.setBackgroundColor(current()); if (u) hex.setText(String.format("#%06X", current() and 0xFFFFFF))
                    }
                    override fun onStartTrackingTouch(sb: SeekBar?) {}
                    override fun onStopTrackingTouch(sb: SeekBar?) {}
                })
            }, LinearLayout.LayoutParams(-1, dp(40)))
        }
        box.addView(row(sample, hex).apply { minimumHeight = dp(44) })
        dlg = dialog(title).setView(android.widget.ScrollView(this).apply { addView(box) })
            .setPositiveButton("Özel rengi kullan") { _, _ ->
                val t = hex.text.toString().trim().removePrefix("#")
                val c = t.toLongOrNull(16)?.let { (0xFF000000 or it).toInt() } ?: current()
                onPick(c)
            }.setNegativeButton("Vazgeç", null).show()
    }
}
