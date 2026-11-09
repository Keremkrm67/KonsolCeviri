package com.playceviri.tv.util

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.os.Process
import com.playceviri.tv.BuildConfig
import com.playceviri.tv.data.Settings
import com.playceviri.tv.service.Live
import com.playceviri.tv.worker.EngineGuard
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Uygulama olay günlüğü + tek ekranlık tanılama raporu. Ekran görüntüsü yerine bu rapor paylaşılır. */
object DiagLog {
    private var dir: File? = null
    private var installed = false
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    @Synchronized
    fun attach(context: Context) {
        dir = context.applicationContext.cacheDir
        if (installed) return
        installed = true
        CrashCatcher.install(context)
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            add("JAVA ÇÖKMESİ (${t.name}): ${e.javaClass.simpleName}: ${e.message?.take(120)} @ ${e.stackTrace.firstOrNull()}")
            prev?.uncaughtException(t, e)
        }
    }

    private fun eventFile(): File? = dir?.let { File(it, "olaylar.log") }

    @Synchronized
    fun add(text: String) {
        val f = eventFile() ?: return
        runCatching {
            f.appendText("${fmt.format(Date())}  $text\n")
            if (f.length() > 160_000) f.writeText(f.readLines().takeLast(300).joinToString("\n", postfix = "\n"))
        }
    }

    fun sessionLogFile(context: Context) = File(context.cacheDir, "oturum.log")
    fun testFile(context: Context) = File(context.applicationContext.cacheDir, "motor_testi.txt")

    private fun reasonName(r: Int) = when (r) {
        1 -> "kendi kapandı"; 2 -> "SİNYAL (öldürüldü)"; 3 -> "BELLEK YETERSİZ"; 4 -> "JAVA ÇÖKMESİ"
        5 -> "NATIVE ÇÖKME"; 6 -> "ANR (yanıt vermedi)"; 7 -> "başlatma hatası"; 8 -> "izin değişti"
        9 -> "AŞIRI KAYNAK"; 10 -> "kullanıcı kapattı"; 11 -> "kullanıcı durdurdu"; 12 -> "bağımlılık öldü"
        14 -> "dondurucu"; else -> "diğer ($r)"
    }

    private fun tombstone(info: ApplicationExitInfo): String = try {
        val bytes = info.traceInputStream?.use { it.readBytes().take(400_000).toByteArray() }
        if (bytes == null) "    (çökme dökümü yok)\n" else {
            val runs = Regex("[\\x20-\\x7e]{8,}").findAll(String(bytes, Charsets.ISO_8859_1)).map { it.value }
                .filter { r -> ".so" in r || "Abort" in r || "signal" in r || "fault" in r || r.contains("chiaki", true) || "Fatal" in r }
                .distinct().take(14).toList()
            if (runs.isEmpty()) "    (döküm var ama okunabilir satır yok)\n" else runs.joinToString("") { "    ${it.take(130)}\n" }
        }
    } catch (e: Exception) { "    (döküm okunamadı: ${e.javaClass.simpleName})\n" }

    private fun exits(context: Context): String {
        if (Build.VERSION.SDK_INT < 30) return "(bu Android sürümünde süreç sonlanma kaydı yok)\n"
        return try {
            val list = context.getSystemService(ActivityManager::class.java)
                .getHistoricalProcessExitReasons(context.packageName, 0, 6)
            if (list.isEmpty()) return "(kayıt yok)\n"
            val sb = StringBuilder()
            list.forEachIndexed { i, x ->
                sb.append("${fmt.format(Date(x.timestamp))}  ${reasonName(x.reason)}  ${x.description ?: ""}  (durum ${x.status}, ${x.processName?.substringAfterLast(':') ?: ""})\n")
                if (x.reason == ApplicationExitInfo.REASON_CRASH_NATIVE && i < 2) sb.append(tombstone(x))
            }
            sb.toString()
        } catch (e: Exception) { "(okunamadı: ${e.javaClass.simpleName})\n" }
    }

    fun summary(context: Context): String {
        val s = Live.state.value
        val st = Settings(context)
        val sb = StringBuilder()
        sb.append("ÖZET · Konsol Çeviri ${BuildConfig.VERSION_NAME} · görev: ${st.role.short}\n")
        sb.append("Akış: ${s.phase} (${s.message.take(80)})\n")
        if (s.tvLink.isNotBlank()) sb.append("TV bağlantısı: ${s.tvLink}\n")
        if (s.game.isNotBlank()) sb.append("Oyun: ${s.game}\n")
        sb.append("Son motor: ${s.engine.ifBlank { "henüz yok" }} · okuma ${s.ocrMs} ms · çeviri ${s.translateMs} ms · toplam ${s.totalMs} ms\n")
        sb.append("Okuma hızı: ${"%.1f".format(s.readsPerSec)}/sn · aynı görüntü atlandı: ${s.skippedSame} · anlamsız diye atılan: ${s.rejected}\n")
        if (s.lastRejected.isNotBlank()) sb.append("Son atılan okuma: \"${s.lastRejected.take(80)}\"\n")
        sb.append("Profil: ${st.profileLabel} (hız ${st.rate}/sn, bekleme ${st.settle} kare, ön işleme ${st.prep}, eşik %${st.minConf})\n")
        sb.append("Çeviri: ${Settings.TR_MODES[st.translateMode]} → ${st.language.label}\n")
        val r = st.region()
        sb.append("Bölge: sol %${(r[0] * 100).toInt()} üst %${(r[1] * 100).toInt()} sağ %${(r[2] * 100).toInt()} alt %${(r[3] * 100).toInt()}${if (st.autoLocate) " · otomatik bul açık" else ""}\n")
        val n = CrashCatcher.count(context)
        sb.append("Kayıtlı native çökme: ${if (n == 0) "yok" else n.toString()}")
        val dis = EngineGuard.disabled(context)
        if (dis.isNotEmpty()) sb.append(" · kapatılan motor: ${dis.joinToString { EngineGuard.label(it) }}")
        sb.append("\n\n")
        val test = runCatching { testFile(context).readText() }.getOrDefault("")
        if (test.isNotBlank()) sb.append("— Son motor testi —\n").append(test).append("\n")
        return sb.toString()
    }

    fun report(context: Context): String {
        val sb = StringBuilder()
        sb.append(summary(context))
        val crashes = CrashCatcher.lastCrashes(context)
        if (crashes.isNotEmpty()) sb.append("— NATIVE ÇÖKME AYRINTISI —\n").append(crashes).append('\n')
        sb.append("— Cihaz: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}, ")
            .append("ABI ${Build.SUPPORTED_ABIS.joinToString("/")}, süreç ${if (Process.is64Bit()) 64 else 32} bit —\n\n")
        sb.append("— Süreç sonlanma nedenleri (Android) —\n").append(exits(context))
        sb.append("\n— Uygulama olayları (son 60) —\n")
        val lines = runCatching { eventFile()?.readLines() ?: emptyList() }.getOrDefault(emptyList())
        if (lines.isEmpty()) sb.append("(kayıt yok)\n") else lines.takeLast(60).forEach { sb.append(it).append('\n') }
        sb.append("\n— PS5 oturum günlüğü: uyarı/hata satırları —\n")
        val sl = sessionLogFile(context)
        if (sl.exists()) {
            val all = runCatching { sl.readLines() }.getOrDefault(emptyList())
            val warn = all.filter { !it.startsWith("[V]") && !it.startsWith("[D]") }.takeLast(20)
            if (warn.isEmpty()) sb.append("(uyarı ya da hata yok)\n") else warn.forEach { sb.append(it.take(150)).append('\n') }
            sb.append("\nSon 4 satır (ham):\n")
            all.takeLast(4).forEach { sb.append(it.take(120)).append('\n') }
        } else sb.append("(henüz günlük yok)\n")
        return sb.toString()
    }

    /** Raporu paylaşılabilir bir dosyaya yazar. */
    fun exportFile(context: Context): File {
        val f = File(context.cacheDir, "playceviri_tanilama.txt")
        f.writeText(report(context))
        return f
    }
}
