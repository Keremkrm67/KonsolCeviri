package com.playceviri.tv.data

import android.app.UiModeManager
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.Configuration
import org.json.JSONObject

/**
 * Tüm kalıcı ayarlar. Görünüm (stil) ayarları telefondan TV'ye JSON olarak da gönderilir,
 * bu yüzden stil alanları [styleJson] / [applyStyleJson] ile tek yerden taşınır.
 */
class Settings(context: Context) {
    private val app = context.applicationContext
    private val sp: SharedPreferences = app.getSharedPreferences("playceviri_ayar", Context.MODE_PRIVATE)

    data class Language(val tag: String, val label: String, val deeplCode: String)

    enum class Role(val label: String, val short: String) {
        TV_BROWSER("TV + telefonda tarayıcı (QR, iPhone/Android) · önerilen", "TV + tarayıcı"),
        TV_DISPLAY("TV ekranı (Android telefon uygulaması işler)", "TV ekranı"),
        PHONE("Telefon (işlemci)", "Telefon"),
        TV_SOLO("Tek başına TV (eski yöntem)", "Tek başına TV"),
    }

    data class Profile(
        val key: String, val label: String, val rate: Int, val settle: Int,
        val prep: Int, val minConf: Int, val explain: String,
    )

    companion object {
        val LANGUAGES = listOf(
            Language("tr", "Türkçe", "TR"), Language("en", "English", "EN-US"),
            Language("de", "Deutsch", "DE"), Language("fr", "Français", "FR"),
            Language("es", "Español", "ES"), Language("it", "Italiano", "IT"),
            Language("pt", "Português", "PT-BR"), Language("ru", "Русский", "RU"),
            Language("ja", "日本語", "JA"), Language("ko", "한국어", "KO"),
            Language("zh", "中文", "ZH"), Language("ar", "العربية", "AR"),
            Language("pl", "Polski", "PL"), Language("nl", "Nederlands", "NL"),
        )

        /** A hızlı, B dengeli, C en doğru. rate = saniyedeki en fazla okuma. */
        val PROFILES = listOf(
            Profile("A", "A · Hızlı", 6, 0, 0, 35,
                "Yazı değişir değişmez okur. En az gecikme; yarım ya da titrek okumalar olabilir."),
            Profile("B", "B · Dengeli", 4, 1, 1, 50,
                "Yazı bir kare sabit kalınca okur, görüntüyü hafif netleştirir. Çoğu oyun için önerilen."),
            Profile("C", "C · En doğru", 3, 2, 2, 62,
                "Yazı iki kare sabit kalınca okur, güçlü netleştirme ve sıkı anlamsız metin filtresi. Biraz daha gecikmeli."),
        )

        val RATES = listOf(2, 3, 4, 5, 6, 8)
        val SETTLES = listOf(0, 1, 2, 3)
        val PREPS = listOf("Kapalı", "Hafif (2x büyüt + kontrast)", "Güçlü (büyüt + kontrast + siyah-beyaz)")
        val MIN_CONFS = listOf(0, 25, 35, 50, 62, 75)
        val TR_MODES = listOf(
            if (com.playceviri.tv.BuildConfig.GAYRIRESMI) "Otomatik · anahtarlı motorlar, ücretsiz motorlar, telefonda Firefox modeli, MyMemory"
            else "Otomatik · anahtarın varsa yapay zekâ, yoksa telefonda Firefox modeli, sonra MyMemory",
            "Yalnız cihaz içi · ML Kit (çevrimdışı)",
            "Yalnız Google · ücretsiz, üyeliksiz",
            "Yalnız MyMemory · ücretsiz, günlük sınırlı",
            "Yalnız DeepL (anahtar gerekir)",
            "Yalnız Microsoft (Edge) · ücretsiz, üyeliksiz",
            "Yalnız Lingva · ücretsiz, üyeliksiz",
            "Yalnız Google (2) · ücretsiz, üyeliksiz",
            "Telefonda çeviri · Firefox modeli, ücretsiz, üyeliksiz",
            "Microsoft · kendi ücretsiz anahtarınla",
            "Groq yapay zekâ · kendi ücretsiz anahtarınla",
            "Gemini yapay zekâ · kendi ücretsiz anahtarınla",
            "LibreTranslate · kendi sunucun",
            "",
            "",
            "Google Cloud Translation · resmî Google, kendi anahtarınla (ucuz, ücretli)",
        )
        /** Bu sürümde seçilebilen çevirici modları (Play sürümünde gayriresmî uçlar yok). */
        val ALLOWED_MODES: List<Int> get() = if (com.playceviri.tv.BuildConfig.GAYRIRESMI) listOf(0, 8, 2, 5, 7, 6, 11, 10, 4, 15, 9, 3, 12, 1) else listOf(0, 8, 11, 10, 4, 15, 9, 3, 12, 1)
        val QUALITIES = listOf("720p (daha hafif)", "1080p (daha net, önerilen)")

        val REGION_PRESETS = listOf(
            "Alt %30" to floatArrayOf(0.05f, 0.70f, 0.95f, 1.0f),
            "Alt yarı (önerilen)" to floatArrayOf(0.02f, 0.50f, 0.98f, 1.0f),
            "Alt %45" to floatArrayOf(0.03f, 0.55f, 0.97f, 1.0f),
            "Tüm ekran" to floatArrayOf(0.01f, 0.03f, 0.99f, 0.99f),
        )

        /** 16 hazır renk (ad, ARGB). */
        val COLORS = listOf(
            "Beyaz" to 0xFFFFFFFF.toInt(), "Sarı" to 0xFFFFD166.toInt(),
            "Açık sarı" to 0xFFFFF3A3.toInt(), "Turuncu" to 0xFFFFA94D.toInt(),
            "Kırmızı" to 0xFFFF6B6B.toInt(), "Pembe" to 0xFFFF8FD8.toInt(),
            "Mor" to 0xFFB197FC.toInt(), "Mavi" to 0xFF74C0FC.toInt(),
            "Camgöbeği" to 0xFF66D9E8.toInt(), "Yeşil" to 0xFF8CE99A.toInt(),
            "Açık yeşil" to 0xFFC0EB75.toInt(), "Gri" to 0xFFADB5BD.toInt(),
            "Koyu gri" to 0xFF495057.toInt(), "Siyah" to 0xFF000000.toInt(),
            "Lacivert" to 0xFF1C2541.toInt(), "Kahverengi" to 0xFF8D6E63.toInt(),
        )
        val FONTS = listOf("Normal", "Kalın", "Dar (condensed)", "Dar kalın", "Tırnaklı (serif)", "Eşaralıklı (mono)")
        val ORIG_MODES = listOf("Gizle", "Çevirinin altında küçük göster", "Çevirinin üstünde küçük göster")
        val POS_MODES = listOf("Sabit konum (% ile)", "Orijinal altyazının üstüne")

        fun colorName(c: Int): String =
            COLORS.firstOrNull { it.second == c }?.first ?: String.format("#%06X", c and 0xFFFFFF)

        fun isTvDevice(c: Context): Boolean {
            val ui = c.getSystemService(UiModeManager::class.java)
            if (ui?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION) return true
            return c.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
        }
    }

    // ───────────── Görev ─────────────
    var role: Role
        get() {
            // Nihai karar: TV'de tek görev var; TV her zaman görüntüyü telefona yollar, telefon okur ve çevirir.
            if (isTvDevice(app)) return Role.TV_BROWSER
            val saved = sp.getString("gorev", null)
            if (saved != null) return runCatching { Role.valueOf(saved) }.getOrDefault(Role.TV_BROWSER)
            return if (isTvDevice(app)) Role.TV_BROWSER else Role.PHONE
        }
        set(v) = sp.edit().putString("gorev", v.name).apply()

    // ───────────── Dil / çeviri ─────────────
    var targetLang: String
        get() = sp.getString("hedef", "tr") ?: "tr"
        set(v) = sp.edit().putString("hedef", v).apply()
    var sourceLang: String
        get() = sp.getString("kaynak", "en") ?: "en"
        set(v) = sp.edit().putString("kaynak", v).apply()
    var translateMode: Int
        get() = sp.getInt("ceviri_modu2", 0).let { if (it in ALLOWED_MODES) it else 0 }
        set(v) = sp.edit().putInt("ceviri_modu2", v).apply()
    var glossary: String
        get() = sp.getString("sozluk", "") ?: ""
        set(v) = sp.edit().putString("sozluk", v).apply()
    val language: Language get() = LANGUAGES.firstOrNull { it.tag == targetLang } ?: LANGUAGES[0]
    val glossaryTerms: List<String>
        get() = glossary.split(',', '\n').map { it.trim() }.filter { it.length >= 2 }.distinct()

    // ───────────── PS5 ─────────────
    var consoleHost: String
        get() = sp.getString("ps5_ip", "") ?: ""
        set(v) = sp.edit().putString("ps5_ip", v.trim()).apply()
    var quality: Int
        get() = sp.getInt("kalite", 1).coerceIn(0, 1)
        set(v) = sp.edit().putInt("kalite", v).apply()

    // ───────────── Okuma profili ─────────────
    /** 0..2 = A/B/C, 3 = Özel */
    var profile: Int
        get() = sp.getInt("profil", 1).coerceIn(0, 3)
        set(v) {
            sp.edit().putInt("profil", v).apply()
            if (v in 0..2) {
                val p = PROFILES[v]
                sp.edit().putInt("hiz", p.rate).putInt("bekle", p.settle)
                    .putInt("onisleme", p.prep).putInt("min_guven", p.minConf).apply()
            }
        }
    var rate: Int
        get() = sp.getInt("hiz", 4).coerceIn(1, 10)
        set(v) { sp.edit().putInt("hiz", v).putInt("profil", 3).apply() }
    var settle: Int
        get() = sp.getInt("bekle", 1).coerceIn(0, 3)
        set(v) { sp.edit().putInt("bekle", v).putInt("profil", 3).apply() }
    var prep: Int
        get() = sp.getInt("onisleme", 1).coerceIn(0, 2)
        set(v) { sp.edit().putInt("onisleme", v).putInt("profil", 3).apply() }
    var minConf: Int
        get() = sp.getInt("min_guven", 50).coerceIn(0, 95)
        set(v) { sp.edit().putInt("min_guven", v).putInt("profil", 3).apply() }
    val profileLabel: String
        get() = if (profile in 0..2) PROFILES[profile].label else "Özel"

    // ───────────── Okuma bölgesi (kesirler 0..1) ─────────────
    fun region(gameId: String? = null): FloatArray {
        if (!gameId.isNullOrBlank()) {
            sp.getString("bolge_oyun_$gameId", null)?.let { s -> parseRegion(s)?.let { return it } }
        }
        return parseRegion(sp.getString("bolge3", null) ?: "") ?: REGION_PRESETS[1].second.copyOf()
    }
    fun setRegion(r: FloatArray, gameId: String? = null) {
        val s = r.joinToString(",")
        if (gameId.isNullOrBlank()) sp.edit().putString("bolge3", s).apply()
        else sp.edit().putString("bolge_oyun_$gameId", s).apply()
    }
    fun clearGameRegion(gameId: String) = sp.edit().remove("bolge_oyun_$gameId").apply()
    fun hasGameRegion(gameId: String?) = !gameId.isNullOrBlank() && sp.contains("bolge_oyun_$gameId")
    private fun parseRegion(s: String): FloatArray? {
        val p = s.split(',').mapNotNull { it.toFloatOrNull() }
        if (p.size != 4) return null
        val l = p[0].coerceIn(0f, 0.95f); val t = p[1].coerceIn(0f, 0.95f)
        val r = p[2].coerceIn(l + 0.04f, 1f); val b = p[3].coerceIn(t + 0.04f, 1f)
        return floatArrayOf(l, t, r, b)
    }
    var autoLocate: Boolean
        get() = sp.getBoolean("oto_bul", false)
        set(v) = sp.edit().putBoolean("oto_bul", v).apply()

    // ───────────── Görünüm (stil) ─────────────
    var posMode: Int
        get() = sp.getInt("s_konum_modu", 0).coerceIn(0, 1)
        set(v) = sp.edit().putInt("s_konum_modu", v).apply()
    var xPct: Int
        get() = sp.getInt("s_x", 50).coerceIn(0, 100)
        set(v) = sp.edit().putInt("s_x", v.coerceIn(0, 100)).apply()
    var yPct: Int
        get() = sp.getInt("s_y", 88).coerceIn(0, 100)
        set(v) = sp.edit().putInt("s_y", v.coerceIn(0, 100)).apply()
    var widthPct: Int
        get() = sp.getInt("s_genislik", 80).coerceIn(30, 100)
        set(v) = sp.edit().putInt("s_genislik", v.coerceIn(30, 100)).apply()
    var fontPt: Int
        get() = sp.getInt("s_punto", 34).coerceIn(12, 72)
        set(v) = sp.edit().putInt("s_punto", v.coerceIn(12, 72)).apply()
    var font: Int
        get() = sp.getInt("s_yazi_tipi", 1).coerceIn(0, FONTS.lastIndex)
        set(v) = sp.edit().putInt("s_yazi_tipi", v).apply()
    var textColor: Int
        get() = sp.getInt("s_renk", 0xFFFFFFFF.toInt())
        set(v) = sp.edit().putInt("s_renk", v or 0xFF000000.toInt()).apply()
    var outlineOn: Boolean
        get() = sp.getBoolean("s_kenar", true)
        set(v) = sp.edit().putBoolean("s_kenar", v).apply()
    var outlineColor: Int
        get() = sp.getInt("s_kenar_renk", 0xFF000000.toInt())
        set(v) = sp.edit().putInt("s_kenar_renk", v or 0xFF000000.toInt()).apply()
    var outlinePx: Int
        get() = sp.getInt("s_kenar_px", 3).coerceIn(0, 6)
        set(v) = sp.edit().putInt("s_kenar_px", v.coerceIn(0, 6)).apply()
    var shadowOn: Boolean
        get() = sp.getBoolean("s_golge", true)
        set(v) = sp.edit().putBoolean("s_golge", v).apply()
    var shadowColor: Int
        get() = sp.getInt("s_golge_renk", 0xFF000000.toInt())
        set(v) = sp.edit().putInt("s_golge_renk", v or 0xFF000000.toInt()).apply()
    var shadowPct: Int
        get() = sp.getInt("s_golge_yogun", 60).coerceIn(0, 100)
        set(v) = sp.edit().putInt("s_golge_yogun", v.coerceIn(0, 100)).apply()
    var bgColor: Int
        get() = sp.getInt("s_kutu_renk", 0xFF080A0E.toInt())
        set(v) = sp.edit().putInt("s_kutu_renk", v or 0xFF000000.toInt()).apply()
    var bgOpacity: Int
        get() = sp.getInt("s_kutu_saydam", 55).coerceIn(0, 100)
        set(v) = sp.edit().putInt("s_kutu_saydam", v.coerceIn(0, 100)).apply()
    var cornerPx: Int
        get() = sp.getInt("s_kose", 12).coerceIn(0, 40)
        set(v) = sp.edit().putInt("s_kose", v.coerceIn(0, 40)).apply()
    var origMode: Int
        get() = sp.getInt("s_orijinal", 0).coerceIn(0, 2)
        set(v) = sp.edit().putInt("s_orijinal", v).apply()
    var origSizePct: Int
        get() = sp.getInt("s_orijinal_boyut", 60).coerceIn(40, 100)
        set(v) = sp.edit().putInt("s_orijinal_boyut", v.coerceIn(40, 100)).apply()
    var origColor: Int
        get() = sp.getInt("s_orijinal_renk", 0xFFADB5BD.toInt())
        set(v) = sp.edit().putInt("s_orijinal_renk", v or 0xFF000000.toInt()).apply()
    var maxLines: Int
        get() = sp.getInt("s_satir", 2).coerceIn(1, 3)
        set(v) = sp.edit().putInt("s_satir", v.coerceIn(1, 3)).apply()
    /** 0 = otomatik (metin uzunluğuna göre), 1..10 saniye sabit. */
    var durationSec: Int
        get() = sp.getInt("s_sure", 0).coerceIn(0, 10)
        set(v) = sp.edit().putInt("s_sure", v.coerceIn(0, 10)).apply()
    /** Okuma sırası: her çevirinin okunabilmesi için eklenen süre (ms). */
    var holdExtraMs: Int
        get() = sp.getInt("s_ek_sure", 2500).coerceIn(0, 8000)
        set(v) = sp.edit().putInt("s_ek_sure", v.coerceIn(0, 8000)).apply()
    var statusDot: Boolean
        get() = sp.getBoolean("s_durum_noktasi", true)
        set(v) = sp.edit().putBoolean("s_durum_noktasi", v).apply()

    private val styleKeys = listOf(
        "s_konum_modu", "s_x", "s_y", "s_genislik", "s_punto", "s_yazi_tipi", "s_renk", "s_kenar",
        "s_kenar_renk", "s_kenar_px", "s_golge", "s_golge_renk", "s_golge_yogun", "s_kutu_renk",
        "s_kutu_saydam", "s_kose", "s_orijinal", "s_orijinal_boyut", "s_orijinal_renk", "s_satir",
        "s_sure", "s_durum_noktasi",
    )

    /** Görünür (varsayılan dahil) tüm stil değerleri; TV tam olarak aynı görünümü kursun diye. */
    fun styleJson(): JSONObject = JSONObject()
        .put("s_konum_modu", posMode).put("s_x", xPct).put("s_y", yPct).put("s_genislik", widthPct)
        .put("s_punto", fontPt).put("s_yazi_tipi", font).put("s_renk", textColor).put("s_kenar", outlineOn)
        .put("s_kenar_renk", outlineColor).put("s_kenar_px", outlinePx).put("s_golge", shadowOn)
        .put("s_golge_renk", shadowColor).put("s_golge_yogun", shadowPct).put("s_kutu_renk", bgColor)
        .put("s_kutu_saydam", bgOpacity).put("s_kose", cornerPx).put("s_orijinal", origMode)
        .put("s_orijinal_boyut", origSizePct).put("s_orijinal_renk", origColor).put("s_satir", maxLines)
        .put("s_sure", durationSec).put("s_durum_noktasi", statusDot)

    fun applyStyleJson(o: JSONObject) {
        val e = sp.edit()
        for (k in styleKeys) {
            if (!o.has(k)) continue
            when (val v = o.get(k)) {
                is Boolean -> e.putBoolean(k, v)
                is Int -> e.putInt(k, v)
                is Number -> e.putInt(k, v.toInt())
            }
        }
        e.apply()
    }

    fun resetStyle() {
        val e = sp.edit()
        styleKeys.forEach { e.remove(it) }
        e.apply()
    }

    // ───────────── TV ekranı eşleşmesi ─────────────
    /** TV tarafı: kullanıcı ekranı açık bıraktıysa uygulama açılınca kendiliğinden başlar. */
    var displayWanted: Boolean
        get() = sp.getBoolean("ekran_acik", false)
        set(v) = sp.edit().putBoolean("ekran_acik", v).apply()

    /** TV tarafı: telefonun girmesi gereken 4 haneli kod. */
    var displayCode: String
        get() {
            val c = sp.getString("ekran_kodu", null)
            if (c != null) return c
            val n = (1000..9999).random().toString()
            sp.edit().putString("ekran_kodu", n).apply()
            return n
        }
        set(v) = sp.edit().putString("ekran_kodu", v).apply()

    /** TV + tarayıcı: QR adresindeki kalıcı anahtar (QR her seferinde aynı kalsın). */
    val browserToken: String
        get() {
            sp.getString("tarayici_anahtar", null)?.let { return it }
            val t = java.util.UUID.randomUUID().toString().replace("-", "").take(12)
            sp.edit().putString("tarayici_anahtar", t).apply()
            return t
        }

    /** Telefon tarafı: bağlanılacak TV. */
    var tvHost: String
        get() = sp.getString("tv_adres", "") ?: ""
        set(v) = sp.edit().putString("tv_adres", v.trim()).apply()
    var tvPort: Int
        get() = sp.getInt("tv_port", 0)
        set(v) = sp.edit().putInt("tv_port", v).apply()
    var tvName: String
        get() = sp.getString("tv_ad", "") ?: ""
        set(v) = sp.edit().putString("tv_ad", v).apply()
    var tvCode: String
        get() = sp.getString("tv_kod", "") ?: ""
        set(v) = sp.edit().putString("tv_kod", v.trim()).apply()
    val tvPaired: Boolean get() = tvCode.length == 4 && (tvName.isNotBlank() || tvHost.isNotBlank())
}
