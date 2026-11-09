package com.playceviri.tv.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.text.InputType
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.metallic.chiaki.lib.DiscoveryService
import com.metallic.chiaki.lib.Regist
import com.playceviri.tv.BuildConfig
import com.playceviri.tv.data.SecretStore
import com.playceviri.tv.data.Settings
import com.playceviri.tv.display.DisplayClient
import com.playceviri.tv.display.DisplayFinder
import com.playceviri.tv.display.DisplayProtocol
import com.playceviri.tv.display.DisplayService
import com.playceviri.tv.link.ConsoleLink
import com.playceviri.tv.net.SonyProbe
import com.playceviri.tv.overlay.SubtitleOverlay
import com.playceviri.tv.service.Live
import com.playceviri.tv.service.Phase
import com.playceviri.tv.service.PlayCeviriService
import com.playceviri.tv.setup.SetupServer
import com.playceviri.tv.util.DiagLog
import com.playceviri.tv.worker.EngineGuard
import com.playceviri.tv.worker.WorkerClient
import com.playceviri.tv.worker.WorkerProtocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

class MainActivity : BaseActivity() {
    private lateinit var settings: Settings
    private lateinit var secrets: SecretStore
    private var ui: CoroutineScope? = null
    private var setupServer: SetupServer? = null
    private var regist: Regist? = null
    private var testOverlay: SubtitleOverlay? = null
    private var testClient: WorkerClient? = null

    private lateinit var status: TextView
    private lateinit var detail: TextView
    private lateinit var checklist: TextView
    private var startButton: Button? = null
    private var previewMain: TextView? = null
    private var previewOrig: TextView? = null
    private var codeView: TextView? = null
    private val refreshers = mutableListOf<() -> Unit>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DiagLog.attach(this)
        settings = Settings(this)
        secrets = SecretStore(this)
        rebuild()
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
    }

    override fun onStart() {
        super.onStart()
        secretCache = null
        val s = CoroutineScope(Dispatchers.Main + SupervisorJob())
        ui = s
        s.launch { Live.state.collect { refresh() } }
        s.launch { DisplayService.info.collect { refresh() } }
        s.launch { while (isActive) { delay(1500); refresh() } }
        if (settings.role == Settings.Role.TV_DISPLAY && settings.displayWanted && !DisplayService.info.value.running &&
            android.provider.Settings.canDrawOverlays(this)) DisplayService.start(this)
        refresh()
    }

    override fun onStop() { ui?.cancel(); ui = null; super.onStop() }

    override fun onDestroy() {
        setupServer?.stop(); regist?.stop(); testOverlay?.detach(); testClient?.unbind()
        super.onDestroy()
    }

    // ───────────────────────── arayüz ─────────────────────────

    private fun rebuild() {
        refreshers.clear(); startButton = null; previewMain = null; previewOrig = null; codeView = null
        val root = LinearLayout(this)
        root.addView(text("Konsol Çeviri", 30f, ACCENT, true))
        root.addView(text("PS5 oyunlarındaki altyazıyı okuyup seçtiğin dile çevirir. · sürüm ${BuildConfig.VERSION_NAME}", 15f, MUTED))
        status = text("", 17f, TEXT, true).apply { setPadding(0, dp(18), 0, dp(2)) }
        detail = text("", 13.5f, MUTED)
        checklist = text("", 14f, MUTED).apply { setPadding(0, dp(6), 0, 0) }
        root.addView(status); root.addView(detail); root.addView(checklist)

        val role = settings.role
        if (role == Settings.Role.TV_DISPLAY) buildDisplay(root) else buildProcessor(root, role)

        if (!isTv()) {
            root.addView(section("Bu cihazın görevi"))
            root.addView(button("Görev: ${role.label}") { pickRole() })
        }
        root.addView(note(roleExplain(role)))

        root.addView(note(when (role) {
            Settings.Role.TV_DISPLAY -> "Not: Bu modda TV hiçbir ağır iş yapmaz; donma ve gecikme bu yüzden azalır. Ekranın sağ üstündeki nokta durumu gösterir: yeşil çeviri akıyor, sarı bekliyor, kırmızı bağlantı yok."
            Settings.Role.TV_BROWSER -> "Not: Altyazı Latin harfleriyle (İngilizce, İspanyolca, Fransızca, Almanca vb.) okunur. Sen oyunu HDMI'dan oynarken TV, PS5'e ikinci bir Uzaktan Oynatma oturumu olarak bağlanır; yalnız altyazı bölgesini kırpıp telefona yollar. Yazıyı telefondaki sayfa okur, TV okumaz. Telefon bağlı değilken TV'nin köşesinde QR kod görünür."
            Settings.Role.PHONE -> "Not: Altyazı Latin harfleriyle (İngilizce, İspanyolca, Fransızca, Almanca vb.) okunur. Sen oyunu TV'de HDMI'dan oynarken telefon PS5'e ikinci bir Uzaktan Oynatma oturumu olarak bağlanır, görüntüyü ekrana çizmeden okur. Telefonu şarjda tutman önerilir."
            else -> "Not: Altyazı Latin harfleriyle okunur. Oyun HDMI'dan oynanırken Konsol Çeviri PS5'e ikinci bir Remote Play oturumu olarak bağlanır. TV yavaşsa ya da donuyorsa görevi \"TV + telefonda tarayıcı\" yap."
        }).apply { setPadding(0, dp(22), 0, 0); setTextColor(Color.parseColor(FAINT)) })

        setContentView(page(root))
        startButton?.requestFocus()
        refresh()
    }

    private fun roleExplain(r: Settings.Role) = when (r) {
        Settings.Role.TV_BROWSER -> "TV PS5'e bağlanır ve altyazı bölgesini telefona yollar; yazıyı telefonun tarayıcısı okur (iPhone ya da Android, uygulama kurmadan). Çeviri TV'de gösterilir. Telefon ekranı açık ve sayfa önde kalmalı."
        Settings.Role.TV_DISPLAY -> "Bu TV yalnızca ekran: telefondan gelen hazır çeviriyi oyunun üstüne yazar. PS5'e bağlanma, yazı okuma ve çeviri telefonda yapılır. Telefona da Konsol Çeviri'yi kurup görevini \"Telefon\" yap."
        Settings.Role.PHONE -> "Bu telefon işlemci: PS5 görüntüsünü alır, altyazıyı okur, çevirir ve TV'ye gönderir. TV'de Konsol Çeviri \"TV ekranı\" görevinde açık olmalı."
        Settings.Role.TV_SOLO -> "Eski yöntem: her şeyi TV yapar. Telefon gerekmez ama zayıf TV işlemcilerinde gecikme ve donma olabilir."
    }

    private fun buildDisplay(root: LinearLayout) {
        codeView = text("", 26f, ACCENT, true).apply { setPadding(0, dp(14), 0, dp(2)) }
        root.addView(codeView)
        startButton = button("Ekranı başlat", primary = true) {
            if (DisplayService.info.value.running) { settings.displayWanted = false; DisplayService.stop(this) } else {
                if (!android.provider.Settings.canDrawOverlays(this)) { toast("Önce \"Üstte gösterme izni\"ni aç."); openOverlayPermission(); return@button }
                settings.displayWanted = true
                DisplayService.start(this)
            }
        }
        root.addView(startButton)
        root.addView(section("Ekran"))
        root.addView(button("Üstte gösterme izni") { openOverlayPermission() })
        root.addView(button("Örnek altyazıyı göster") { showSample() })
        root.addView(button("Yazı görünümü (konum, punto, renkler…)") { startActivity(Intent(this, StyleActivity::class.java)) })
        root.addView(note("Telefon bağlıyken görünüm telefondan da değiştirilebilir; değişiklik burada anında görünür."))
        root.addView(button("Ekran kodunu değiştir") {
            settings.displayCode = (1000..9999).random().toString()
            if (DisplayService.info.value.running) { DisplayService.stop(this); DisplayService.start(this) }
            toast("Yeni kod: ${settings.displayCode}. Telefonda \"TV'ye bağlan\" ile yeniden gir.")
            refresh()
        })
        root.addView(button("Tanılama kaydı") { showDiagnostics() })
    }

    private fun buildProcessor(root: LinearLayout, role: Settings.Role) {
        if (role == Settings.Role.PHONE) {
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = rounded("#0B0E13", BORDER, 1)
                setPadding(dp(16), dp(12), dp(16), dp(12))
            }
            previewMain = text("", 20f, TEXT, true).apply { gravity = Gravity.CENTER }
            previewOrig = text("", 14f, FAINT).apply { gravity = Gravity.CENTER }
            card.addView(text("SON ÇEVİRİ", 11f, FAINT, true))
            card.addView(previewMain); card.addView(previewOrig)
            root.addView(card, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        }
        startButton = button("Çeviriyi Başlat", primary = true) { toggle() }
        root.addView(startButton)
        if (role == Settings.Role.TV_BROWSER) {
            val qrBox = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                background = rounded(CARD, BORDER, 1); setPadding(dp(14), dp(12), dp(14), dp(12))
            }
            val img = ImageView(this)
            val qrText = text("", 15f, TEXT)
            qrBox.addView(img, LinearLayout.LayoutParams(dp(150), dp(150)))
            qrBox.addView(qrText, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(16) })
            root.addView(qrBox, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
            var shownUrl = ""
            refreshers += {
                val url = PlayCeviriService.browserUrl
                if (url.isBlank()) {
                    img.visibility = android.view.View.GONE
                    qrText.text = "Telefonu bağlamak için önce \"Çeviriyi Başlat\"a bas. Sonra burada (ve oyun sırasında TV'nin köşesinde) QR kod çıkar."
                } else {
                    img.visibility = android.view.View.VISIBLE
                    if (url != shownUrl) { img.setImageBitmap(com.playceviri.tv.util.Qr.bitmap(url, dp(150))); shownUrl = url }
                    qrText.text = "📱 Telefonun kamerasıyla okut (iPhone ya da Android).\nAçılan sayfada \"Okumayı başlat\"a bas.\n\nDurum: ${PlayCeviriService.browserState}\nAdres: $url"
                }
            }
        }

        root.addView(section("Kurulum"))
        if (role == Settings.Role.PHONE) {
            root.addView(button("Adım adım kurulum rehberi") { openGuide() })
            root.addView(button("PSN girişi (ikinci hesapla)") { startActivity(Intent(this, LoginActivity::class.java)) })
            root.addView(button("PS5 ile eşleştir") { showPairing() })
            root.addView(button("TV'ye bağlan (TV ekranındaki 4 haneli kod)") { connectTv() })
            root.addView(button("Örnek altyazıyı TV'de göster") { tvSample() })
        } else {
            root.addView(button("TV'de PSN girişi (otomatik, ikinci hesapla)") { startActivity(Intent(this, LoginActivity::class.java)) })
            root.addView(button("Telefonla kurulum (PSN hesabı, PS5 adresi, DeepL)") { showPhoneSetup() })
            root.addView(button("PS5 ile eşleştir") { showPairing() })
            root.addView(button("Üstte gösterme izni") { openOverlayPermission() })
            root.addView(button("Örnek altyazıyı göster") { showSample() })
        }

        root.addView(section("Okuma"))
        val regionB = button("") { startActivity(Intent(this, RegionActivity::class.java)) }
        refreshers += { regionB.text = "Okuma bölgesi: ${regionLabel()}" }
        root.addView(regionB)
        val profB = button("") { settings.profile = (settings.profile + 1).let { if (it > 2) 0 else it }; refresh() }
        stepper(profB) { d -> settings.profile = ((settings.profile.coerceAtMost(2)) + d + 3) % 3; refresh() }
        val profNote = note("")
        refreshers += {
            profB.text = "Okuma profili: ${settings.profileLabel}"
            profNote.text = if (settings.profile in 0..2) Settings.PROFILES[settings.profile].explain
            else "Özel: aşağıdaki ayarlar elle değiştirildi. Profil düğmesine basınca A/B/C'ye dönülür."
        }
        root.addView(profB); root.addView(profNote)
        val rateB = button("") { cycle(Settings.RATES, settings.rate, 1) { settings.rate = it } }
        stepper(rateB) { d -> cycle(Settings.RATES, settings.rate, d) { settings.rate = it } }
        refreshers += { rateB.text = "Okuma hızı: saniyede en fazla ${settings.rate}" }
        val settleB = button("") { cycle(Settings.SETTLES, settings.settle, 1) { settings.settle = it } }
        stepper(settleB) { d -> cycle(Settings.SETTLES, settings.settle, d) { settings.settle = it } }
        refreshers += { settleB.text = "Bekleme: " + if (settings.settle == 0) "yazı değişince hemen oku" else "yazı ${settings.settle} kare sabit kalınca oku" }
        val prepB = button("") { settings.prep = (settings.prep + 1) % 3; refresh() }
        stepper(prepB) { d -> settings.prep = (settings.prep + d + 3) % 3; refresh() }
        refreshers += { prepB.text = "Netleştirme: ${Settings.PREPS[settings.prep]}" }
        val confB = button("") { cycle(Settings.MIN_CONFS, settings.minConf, 1) { settings.minConf = it } }
        stepper(confB) { d -> cycle(Settings.MIN_CONFS, settings.minConf, d) { settings.minConf = it } }
        refreshers += { confB.text = "Anlamsız metin eşiği: " + if (settings.minConf == 0) "kapalı" else "güven %${settings.minConf} altını at" }
        root.addView(rateB); root.addView(settleB); root.addView(prepB); root.addView(confB)
        val srcB = button("") { settings.sourceLang = if (settings.sourceLang == "en") "auto" else "en"; refresh() }
        refreshers += { srcB.text = "Altyazı dili: " + if (settings.sourceLang == "en") "İngilizce (önerilen)" else "Otomatik algıla" }
        val qB = button("") { settings.quality = 1 - settings.quality; refresh() }
        refreshers += { qB.text = "Görüntü kalitesi: ${Settings.QUALITIES[settings.quality]}" }
        root.addView(srcB); root.addView(qB)
        root.addView(note("Ayarlar çeviri çalışırken de geçerli olur; yeniden başlatmaya gerek yok (görüntü kalitesi hariç)."))

        root.addView(section("Çeviri"))
        val langB = button("") {
            choiceDialog("Hedef dil", Settings.LANGUAGES, { it.label }, Settings.LANGUAGES.indexOfFirst { it.tag == settings.targetLang }) {
                settings.targetLang = Settings.LANGUAGES[it].tag; refresh()
            }
        }
        refreshers += { langB.text = "Hedef dil: ${settings.language.label}" }
        val trB = button("") {
            choiceDialog("Çeviri motoru", Settings.ALLOWED_MODES, { Settings.TR_MODES[it] }, Settings.ALLOWED_MODES.indexOf(settings.translateMode)) { settings.translateMode = Settings.ALLOWED_MODES[it]; refresh() }
        }
        refreshers += { trB.text = "Çeviri motoru: ${Settings.TR_MODES[settings.translateMode].substringBefore(" (").substringBefore(" ·")}" }
        root.addView(langB); root.addView(trB)
        root.addView(note("Otomatik: önce ücretsiz çevrimiçi motorlar (DeepL anahtarın varsa önce DeepL), internet yoksa ya da yanıt gecikirse cihaz içi ML Kit. Üyelik gerekmez."))
        root.addView(button("Çevrimdışı çeviri modelini indir (internetsiz kullanım için)") { runEngineTest(listOf("tr:mlkit")) })
        root.addView(button("Sözlük: çevrilmeyecek adlar") {
            inputDialog("Sözlük", "Örn: Ellie, Joel, Kratos", settings.glossary, false,
                "Virgülle ayır. Bu kelimeler (karakter, yer adları) çeviride olduğu gibi kalır.") { settings.glossary = it; refresh() }
        })
        if (role == Settings.Role.PHONE) root.addView(button("DeepL anahtarı (isteğe bağlı)") {
            inputDialog("DeepL anahtarı", "xxxxxxxx-…:fx", "", false, "Varsa yapıştır. Boş bırakıp Tamam'a basarsan değişmez.") { if (it.length >= 20) { secrets.deeplKey = it; toast("DeepL anahtarı kaydedildi.") } }
        })

        root.addView(section("Görünüm"))
        root.addView(button("Yazı görünümü (konum, punto, renkler…)") { startActivity(Intent(this, StyleActivity::class.java)) })
        if (role == Settings.Role.PHONE) root.addView(note("Görünümü telefondan değiştirirsin; TV bağlıysa her değişiklik TV'de örnek altyazıyla anında görünür."))

        root.addView(section("Sorun giderme"))
        root.addView(button("Motor testi (yazı okuma + çeviri, PS5 gerekmez)") { runEngineTest((if (com.playceviri.tv.BuildConfig.GAYRIRESMI) listOf("ocr:mlkit", "tr:google", "tr:microsoft", "tr:google2", "tr:lingva") else listOf("ocr:mlkit")) +
            listOf("tr:mymemory", "tr:groq", "tr:gemini", "tr:mskey", "tr:deepl", "tr:libre", "tr:mlkit")) })
        root.addView(button("Tanılama kaydı (paylaşılabilir)") { showDiagnostics() })
        root.addView(button("Gizlilik politikası") { showLegal("Gizlilik politikası", "yasal/gizlilik.txt") })
        root.addView(button("Kullanım koşulları") { showLegal("Kullanım koşulları", "yasal/kosullar.txt") })
        root.addView(button("Açık kaynak lisansları") { showLegal("Açık kaynak lisansları", "yasal/lisanslar.txt") })
        root.addView(button("Bağlantı testi (Sony girişi açılmazsa)") { connectionTest() })
        root.addView(button("PSN hesabını sil (yanlış hesapla girdiysen)") {
            dialog("PSN hesabını sil").setMessage("Kayıtlı PSN hesabı ve o hesapla yapılmış PS5 eşleşmesi silinir. PS5 adresi ve DeepL anahtarı kalır. Sonra ikinci hesabınla girişi yeniden yaparsın.")
                .setPositiveButton("Sil") { _, _ -> secrets.forgetPsn(); secretCache = null; refresh(); toast("PSN hesabı silindi.") }.setNegativeButton("Vazgeç", null).show()
        })
        root.addView(button("PS5 eşleşmesini sil") { secrets.forgetConsole(); secretCache = null; refresh(); toast("Eşleşme silindi.") })
        if (role == Settings.Role.PHONE) root.addView(button("TV eşleşmesini sil") { settings.tvCode = ""; settings.tvName = ""; settings.tvHost = ""; refresh() })
        root.addView(button("Kapatılan motorları yeniden aç") { EngineGuard.reset(this); toast("Motorlar yeniden denenecek."); refresh() })
    }

    private fun <T : Comparable<T>> cycle(list: List<T>, cur: T, d: Int, set: (T) -> Unit) {
        val i = list.indexOf(cur).let { if (it < 0) list.indexOfFirst { v -> v >= cur }.coerceAtLeast(0) else it }
        set(list[(i + d + list.size) % list.size]); refresh()
    }

    private fun regionLabel(): String {
        val r = settings.region(PlayCeviriService.currentGameId)
        val preset = Settings.REGION_PRESETS.firstOrNull { it.second.contentEquals(r) }?.first
        val base = preset ?: "özel (üst %${(r[1] * 100).toInt()} – alt %${(r[3] * 100).toInt()}, sol %${(r[0] * 100).toInt()} – sağ %${(r[2] * 100).toInt()})"
        return base + (if (settings.autoLocate) " · içinde otomatik bul" else "") +
            (if (settings.hasGameRegion(PlayCeviriService.currentGameId)) " · bu oyuna özel" else "")
    }

    private fun pickRole() {
        val roles = Settings.Role.values().toList()
        choiceDialog("Bu cihazın görevi", roles, { it.label }, roles.indexOf(settings.role)) { i ->
            val r = roles[i]
            if (r == settings.role) return@choiceDialog
            if (PlayCeviriService.running) PlayCeviriService.stop(this)
            if (r != Settings.Role.TV_DISPLAY && DisplayService.info.value.running) DisplayService.stop(this)
            settings.role = r
            rebuild()
            dialog("Görev: ${r.short}").setMessage(roleExplain(r)).setPositiveButton("Tamam", null).show()
        }
    }

    // ───────────────────────── durum ─────────────────────────

    private data class SecretView(val psn: String, val psnName: String, val paired: Boolean, val deepl: Boolean)
    private var secretCache: SecretView? = null
    private var secretAt = 0L
    private var lastRefresh = 0L
    private var refreshQueued = false

    /** Keystore çözmesi pahalı: gizli bilgiler en fazla 3 sn'de bir okunur. */
    private fun sv(): SecretView {
        val now = SystemClock.elapsedRealtime()
        secretCache?.let { if (now - secretAt < 3000) return it }
        return SecretView(secrets.psnAccountId, secrets.psnName, secrets.isConsolePaired, secrets.deeplKey.isNotBlank())
            .also { secretCache = it; secretAt = now }
    }

    private fun refresh() {
        if (!::status.isInitialized) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastRefresh < 300) {
            if (!refreshQueued) { refreshQueued = true; status.postDelayed({ refreshQueued = false; refresh() }, 320) }
            return
        }
        lastRefresh = now
        val role = settings.role
        refreshers.forEach { it() }
        if (role == Settings.Role.TV_DISPLAY) { refreshDisplay(); return }
        val s = Live.state.value
        val on = PlayCeviriService.running && s.phase != Phase.IDLE && s.phase != Phase.ERROR
        startButton?.text = if (on) "Çeviriyi Durdur" else "Çeviriyi Başlat"
        status.text = when (s.phase) {
            Phase.STREAMING -> "● Çeviri çalışıyor" + if (s.totalMs > 0) " · son altyazı ${s.totalMs} ms'de geldi" else " · altyazı bekleniyor"
            Phase.IDLE -> "Hazır"
            Phase.ERROR -> "✗ ${s.message}"
            else -> s.message
        }
        status.setTextColor(Color.parseColor(when (s.phase) { Phase.STREAMING -> GOOD; Phase.ERROR -> BAD; else -> TEXT }))
        detail.text = buildString {
            if (s.phase == Phase.STREAMING) {
                if (s.message != "Çeviri çalışıyor") append(s.message).append('\n')
                append("Okuma ${s.ocrMs} ms · çeviri ${s.translateMs} ms")
                if (s.engine.isNotBlank()) append(" · ${s.engine}")
                append("\nSaniyede ${"%.1f".format(s.readsPerSec)} okuma · aynı görüntü atlandı ${s.skippedSame} · anlamsız diye atılan ${s.rejected}")
                if (s.lastRejected.isNotBlank()) append("\nSon atılan: \"${s.lastRejected.take(70)}\"")
            }
            if (s.game.isNotBlank()) append(if (isEmpty()) "" else "\n").append("Oyun: ${s.game}")
            if (role == Settings.Role.PHONE && s.tvLink.isNotBlank() && on) append(if (isEmpty()) "" else "\n").append("📺 ${s.tvLink}")
        }
        previewMain?.text = s.lastTranslated.ifBlank { if (on) "…" else "Çeviri burada ve TV'de görünür." }
        previewOrig?.text = s.lastOriginal
        val sec = sv()
        val items = mutableListOf(
            mark(sec.psn.isNotBlank(), "PSN hesabı" + if (sec.psnName.isNotBlank()) ": ${sec.psnName}" else ""),
            mark(sec.paired, "PS5 eşleşmesi" + if (settings.consoleHost.isNotBlank()) " (${settings.consoleHost})" else ""),
        )
        if (role == Settings.Role.PHONE) items += mark(settings.tvPaired, if (settings.tvPaired) "TV ekranı: ${settings.tvName.ifBlank { settings.tvHost }}" else "TV ekranı bağlanmadı (\"TV'ye bağlan\")")
        else items += mark(android.provider.Settings.canDrawOverlays(this), "Üstte gösterme izni")
        items += if (sec.deepl) "✓ DeepL anahtarı var" else "– DeepL anahtarı yok (ücretsiz motorlar çevirir)"
        val dis = EngineGuard.disabled(this)
        if (dis.isNotEmpty()) items += "⚠ Bu cihazda kapatılan motor: ${dis.joinToString { EngineGuard.label(it) }}"
        checklist.text = items.joinToString("\n")
    }

    private fun refreshDisplay() {
        val i = DisplayService.info.value
        val overlay = android.provider.Settings.canDrawOverlays(this)
        startButton?.text = if (i.running) "Ekranı durdur" else "Ekranı başlat"
        codeView?.text = if (i.running) "Kod: ${settings.displayCode}" else ""
        status.text = when {
            !i.running -> "Ekran kapalı · \"Ekranı başlat\"a bas"
            !i.phoneConnected -> "Telefon bekleniyor…"
            i.phonePhase == "STREAMING" -> "● Çeviri akıyor (telefondan)"
            else -> "Telefon bağlı: ${i.phone}"
        }
        status.setTextColor(Color.parseColor(if (i.running && i.phoneConnected) GOOD else TEXT))
        detail.text = buildString {
            if (i.running) append("Telefonda: Konsol Çeviri › TV'ye bağlan › bu TV'yi seç › kodu yaz.\nTV adresi: ${i.ip.ifBlank { "?" }}:${i.port}")
            if (i.phoneConnected && i.phoneMsg.isNotBlank()) append("\nTelefonun durumu: ${i.phoneMsg}")
            if (i.lastText.isNotBlank()) append("\nSon altyazı: ${i.lastText.take(80)}")
        }
        checklist.text = listOf(
            mark(overlay, "Üstte gösterme izni"),
            mark(i.running, "Ekran açık"),
            mark(i.phoneConnected, if (i.phoneConnected) "Telefon bağlı: ${i.phone}" else "Telefon bağlı değil"),
        ).joinToString("\n")
    }

    private fun mark(ok: Boolean, s: String) = (if (ok) "✓ " else "✗ ") + s

    private fun toggle() {
        if (PlayCeviriService.running && Live.state.value.phase != Phase.ERROR) PlayCeviriService.stop(this)
        else {
            if (settings.role != Settings.Role.PHONE && !android.provider.Settings.canDrawOverlays(this)) {
                toast("Önce \"Üstte gösterme izni\"ni aç."); openOverlayPermission(); return
            }
            Live.reset()
            PlayCeviriService.start(this)
        }
    }

    private fun openOverlayPermission() {
        try { startActivity(Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))) }
        catch (e: Exception) { toast("İzin ekranı açılamadı. Bilgisayardan: adb shell appops set $packageName SYSTEM_ALERT_WINDOW allow") }
    }

    private fun showSample() {
        if (DisplayService.info.value.running) { DisplayService.sample(this); return }
        val o = testOverlay ?: SubtitleOverlay(this, settings).also { testOverlay = it }
        if (!o.attach()) { toast("Önce üstte gösterme iznini aç."); return }
        o.refreshStyle()
        o.show("Örnek altyazı burada görünür.", "This is how the subtitle will look.", fixedMs = 4000)
    }

    // ───────────────────────── TV bağlantısı (telefon) ─────────────────────────

    private fun connectTv() {
        val wait = dialog("TV aranıyor").setMessage("Aynı Wi-Fi'daki Konsol Çeviri ekranları aranıyor…\n\nTV'de Konsol Çeviri açık, görevi \"TV ekranı\" ve \"Ekranı başlat\"a basılmış olmalı.").show()
        thread {
            val found = DisplayFinder.find(this, 4500)
            runOnUiThread {
                wait.dismiss()
                val labels = found.map { "${it.name}  (${it.host})" } + "Adresi elle yaz…"
                choiceDialog(if (found.isEmpty()) "TV bulunamadı" else "TV'yi seç", labels, { it }, -1) { i ->
                    if (i < found.size) askCode(found[i].name, found[i].host, found[i].port)
                    else inputDialog("TV adresi", "192.168.1.34", settings.tvHost, false,
                        "TV'de Konsol Çeviri ekranında yazan adres (iki noktadan öncesi).") { ip -> if (ip.isNotBlank()) askCode("", ip, DisplayProtocol.DEFAULT_PORT) }
                }
            }
        }
    }

    private fun askCode(name: String, host: String, port: Int) {
        inputDialog("TV kodu", "4 haneli kod", "", true, "TV ekranında büyük sarı yazıyla görünen 4 haneli kodu yaz.") { code ->
            if (code.length != 4) { toast("Kod 4 haneli olmalı."); return@inputDialog }
            settings.tvName = name; settings.tvHost = host; settings.tvPort = port; settings.tvCode = code
            testTv(showSample = true)
        }
    }

    private fun testTv(showSample: Boolean) {
        if (PlayCeviriService.running) {
            startService(Intent(this, PlayCeviriService::class.java).setAction("com.playceviri.tv.TVLINK"))
            if (showSample) PlayCeviriService.pushStyle(this, true)
            toast("TV bağlantısı yenileniyor…"); return
        }
        val wait = dialog("TV'ye bağlanılıyor").setMessage("Bağlanılıyor…").show()
        lateinit var client: DisplayClient
        client = DisplayClient(this, { settings.tvHost }, { settings.tvPort }, { settings.tvName }, { settings.tvCode }) { st, msg ->
            runOnUiThread {
                wait.setMessage(msg)
                if (st == DisplayClient.State.CONNECTED) {
                    if (showSample) client.sendStyle(settings.styleJson(), true)
                    wait.setMessage("✓ $msg\n\nTV'de örnek altyazı görünmeli. Görünmüyorsa TV'de \"Üstte gösterme izni\"ni kontrol et.")
                    window.decorView.postDelayed({ client.shutdown() }, 4000)
                    refresh()
                } else if (st == DisplayClient.State.DENIED) {
                    wait.setMessage("✗ $msg"); client.shutdown()
                    settings.tvCode = ""; refresh()
                }
            }
        }
        client.start()
        window.decorView.postDelayed({ if (client.state != DisplayClient.State.CONNECTED && client.state != DisplayClient.State.DENIED) { client.shutdown(); runCatching { wait.setMessage("✗ TV'ye ulaşılamadı. TV'de ekran açık mı, aynı Wi-Fi'da mısınız?") } } }, 15_000)
    }

    private fun tvSample() {
        if (!settings.tvPaired) { toast("Önce \"TV'ye bağlan\"."); return }
        testTv(showSample = true)
    }

    // ───────────────────────── kurulum ─────────────────────────

    private fun openGuide() {
        setupServer?.stop()
        val s = SetupServer(this, localOnly = true) { msg -> runOnUiThread { toast(msg); secretCache = null; refresh() } }
        if (!s.start()) { toast("Kurulum sayfası açılamadı."); return }
        setupServer = s
        startActivity(Intent(this, GuideActivity::class.java).putExtra("url", s.url()))
    }

    private fun showPhoneSetup() {
        setupServer?.stop()
        val s = SetupServer(this, localOnly = false) { msg -> runOnUiThread { toast(msg); secretCache = null; refresh() } }
        if (!s.start()) { toast("Telefon bağlantısı açılamadı."); return }
        setupServer = s
        val url = s.url() ?: run { toast("TV'nin ev ağındaki adresi bulunamadı. Wi-Fi ya da kablo bağlı mı?"); s.stop(); return }
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(12), dp(24), 0) }
        box.addView(TextView(this).apply { text = "Telefonun kamerasıyla QR kodu okut. Telefon TV ile aynı Wi-Fi'da olmalı. Adres 20 dakika geçerli."; textSize = 15f })
        box.addView(ImageView(this).apply { setImageBitmap(qr(url, dp(240))) }, LinearLayout.LayoutParams(dp(240), dp(240)).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = dp(12) })
        box.addView(TextView(this).apply { text = url; textSize = 13f; gravity = Gravity.CENTER; setPadding(0, dp(10), 0, 0) })
        dialog("Telefonla kurulum").setView(box).setPositiveButton("Kapat") { _, _ -> s.stop() }.setOnDismissListener { s.stop() }.show()
    }

    private fun qr(content: String, size: Int): Bitmap {
        val m = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size)
        val px = IntArray(size * size) { i -> if (m.get(i % size, i / size)) Color.BLACK else Color.WHITE }
        return Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888)
    }

    private fun showPairing() {
        if (secrets.psnAccountId.isBlank()) { toast("Önce PSN girişini yap (ikinci hesapla)."); return }
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(12), dp(24), 0) }
        val hostField = EditText(this).apply { hint = "PS5 adresi (örn. 192.168.1.20)"; setText(settings.consoleHost); inputType = InputType.TYPE_CLASS_PHONE }
        val pinField = EditText(this).apply { hint = "PS5'teki 8 haneli kod"; inputType = InputType.TYPE_CLASS_NUMBER }
        box.addView(TextView(this).apply { text = "PS5'te ikinci hesaba geç, sonra Ayarlar › Sistem › Uzaktan Oynatma › Cihazı Bağla ekranını aç ve ekrandaki 8 haneli kodu yaz."; textSize = 15f })
        val found = TextView(this).apply { text = "PS5 aranıyor…"; textSize = 13f }
        box.addView(found); box.addView(hostField); box.addView(pinField)
        val disco: DiscoveryService? = ConsoleLink.discover { hosts ->
            runOnUiThread {
                val h = hosts.firstOrNull() ?: return@runOnUiThread
                found.text = "Bulundu: ${h.hostName ?: "PS5"} (${h.hostAddr})" + if (h.state.name == "STANDBY") " · uyku modunda" else ""
                if (hostField.text.isBlank()) hostField.setText(h.hostAddr)
            }
        }
        dialog("PS5 ile eşleştir").setView(box)
            .setPositiveButton("Eşleştir") { _, _ ->
                val host = hostField.text.toString().trim()
                if (host.isEmpty()) { toast("PS5 adresi boş."); return@setPositiveButton }
                settings.consoleHost = host
                toast("Eşleştiriliyor, birkaç saniye sürebilir…")
                regist = ConsoleLink.pair(host, pinField.text.toString(), secrets) { r ->
                    runOnUiThread {
                        when (r) {
                            is ConsoleLink.PairResult.Success -> { toast("PS5 eşleşti."); DiagLog.add("PS5 eşleşti ($host)") }
                            is ConsoleLink.PairResult.Failure -> dialog("Eşleşme olmadı").setMessage(r.reason).setPositiveButton("Tamam", null).show()
                        }
                        secretCache = null; refresh()
                    }
                }
            }
            .setNegativeButton("Vazgeç", null)
            .setOnDismissListener { disco?.dispose() }
            .show()
    }

    // ───────────────────────── test ve tanılama ─────────────────────────

    private val stepNames = mapOf(
        "ocr:mlkit" to "Yazı okuma · ML Kit", "tr:mlkit" to "Çeviri · ML Kit (cihaz içi, çevrimdışı)",
        "tr:google" to "Çeviri · Google ücretsiz", "tr:microsoft" to "Çeviri · Microsoft ücretsiz", "tr:google2" to "Çeviri · Google (2) ücretsiz", "tr:lingva" to "Çeviri · Lingva ücretsiz", "tr:groq" to "Çeviri · Groq (anahtar)", "tr:gemini" to "Çeviri · Gemini (anahtar)", "tr:mskey" to "Çeviri · Microsoft (anahtar)", "tr:libre" to "Çeviri · LibreTranslate", "tr:mymemory" to "Çeviri · MyMemory ücretsiz", "tr:deepl" to "Çeviri · DeepL",
    )

    private fun runEngineTest(steps: List<String>) {
        EngineGuard.reset(this)
        val client = testClient ?: WorkerClient(this) { msg -> runOnUiThread { toast(msg) } }.also { testClient = it }
        client.bind()
        val dlg = dialog("Motor testi").setMessage("Başlıyor…").setPositiveButton("Kapat", null).show()
        thread {
            val sb = StringBuilder()
            for (step in steps) {
                runOnUiThread { dlg.setMessage(sb.toString() + "⏳ ${stepNames[step]} çalışıyor…" + if (step == "tr:mlkit") "\n(İlk seferde çeviri modeli iner, 1-2 dk sürebilir)" else "") }
                if (step == "tr:deepl" && secrets.deeplKey.isBlank()) { sb.append("➖ ${stepNames[step]}: anahtar yok, atlandı\n"); continue }
                val t0 = SystemClock.elapsedRealtime()
                val r = runBlocking {
                    client.call(WorkerProtocol.MSG_TEST, Bundle().apply {
                        putString(WorkerProtocol.K_STEP, step); putString(WorkerProtocol.K_TARGET, settings.targetLang)
                        putBundle("anahtarlar", secrets.translatorKeys().toBundle())
                    }, if (step == "tr:mlkit") 300_000 else 30_000)
                }
                val ms = SystemClock.elapsedRealtime() - t0
                val name = stepNames[step]
                when {
                    r == null -> sb.append("💥 $name: ÇÖKTÜ ya da yanıt vermedi ($ms ms) → bu motor bu cihazda kapatıldı\n")
                    r.getBoolean(WorkerProtocol.K_OK) && step.startsWith("ocr") -> {
                        val o = r.getString(WorkerProtocol.K_ORIGINAL) ?: ""
                        sb.append("${if (o.contains("believe", true)) "✅" else "⚠️"} $name ($ms ms): \"$o\"\n")
                    }
                    r.getBoolean(WorkerProtocol.K_OK) -> sb.append("✅ $name ($ms ms): \"${r.getString(WorkerProtocol.K_TRANSLATED)}\"\n")
                    else -> sb.append("❌ $name: ${r.getString(WorkerProtocol.K_ERROR) ?: "yanıt yok"}\n")
                }
            }
            val result = sb.toString()
            runCatching { DiagLog.testFile(this).writeText(SimpleDateFormat("HH:mm:ss", Locale.US).format(Date()) + "\n" + result) }
            runOnUiThread { dlg.setMessage(result + "\nBu sonuç tanılama kaydına da yazıldı."); refresh() }
        }
    }

    private fun showDiagnostics() {
        val report = DiagLog.report(this)
        val b = dialog("Tanılama kaydı").setMessage(report).setPositiveButton("Kapat", null)
        if (!isTv()) b.setNeutralButton("Paylaş") { _, _ ->
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
                .putExtra(Intent.EXTRA_SUBJECT, "Konsol Çeviri tanılama").putExtra(Intent.EXTRA_TEXT, report), "Tanılama kaydını paylaş"))
        }
        b.show()
    }

    private fun connectionTest() {
        val dlg = dialog("Bağlantı testi sonucu").setMessage("Test ediliyor (15-30 sn)…").setPositiveButton("Kapat", null).show()
        thread {
            val r = SonyProbe.run()
            DiagLog.add("bağlantı testi yapıldı")
            runCatching { DiagLog.testFile(this).appendText("\n— Bağlantı testi —\n$r") }
            runOnUiThread { dlg.setMessage(r) }
        }
    }

    /** Yasal metinler uygulamanın içinden okunabilsin (Play: gizlilik politikası ve lisans bildirimi). */
    private fun showLegal(title: String, asset: String) {
        val text = runCatching { assets.open(asset).bufferedReader().readText() }.getOrDefault("Metin açılamadı.")
        val tv = android.widget.TextView(this).apply {
            this.text = text; textSize = 15f; setTextColor(0xFFF1F3F5.toInt()); setPadding(48, 24, 48, 24)
            isFocusable = true
        }
        val sv = android.widget.ScrollView(this).apply { addView(tv); isFocusable = true }
        dialog(title).setView(sv).setPositiveButton("Kapat", null).show()
    }
}
