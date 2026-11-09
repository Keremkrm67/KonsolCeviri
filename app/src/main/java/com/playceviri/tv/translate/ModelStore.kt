package com.playceviri.tv.translate

import android.content.Context
import com.playceviri.tv.util.DiagLog
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Telefonda çalışan çevrimdışı çeviri (Firefox/Bergamot) için İngilizce→Türkçe model dosyaları.
 * İlk istekte TV indirir (~15 MB), saklar ve telefona yerel ağdan verir; telefon internete çıkmaz.
 * Kaynak 1: Mozilla'nın modellerinin Hugging Face yansısı. Kaynak 2: Mozilla'nın kendi dağıtım sunucusu.
 * Dosyalar Mozilla'nın yayımladığı gibi (çoğunlukla zstd sıkıştırılmış) saklanır; açma işini telefon yapar.
 * Lisans: Mozilla Public License 2.0.
 */
class ModelStore(context: Context, private val pair: String = "en-tr") {
    private val dir = File(context.filesDir, "ceviri-model/$pair").apply { mkdirs() }
    private val parts = listOf("model", "lex", "vocab")
    @Volatile var status = "indirilmedi"; private set

    fun ready() = parts.all { File(dir, it).length() > 1000 }

    @Synchronized
    fun part(name: String): ByteArray? {
        if (name !in parts) return null
        if (!ready()) runCatching { download() }.onFailure { status = "indirilemedi: ${it.message}"; DiagLog.add("çeviri modeli indirilemedi: ${it.message}") }
        return File(dir, name).takeIf { it.length() > 1000 }?.readBytes()
    }

    private fun get(url: String, max: Int = 80 shl 20): ByteArray {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000; c.readTimeout = 30_000; c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", "KonsolCeviri")
        try {
            if (c.responseCode !in 200..299) throw IllegalStateException("HTTP ${c.responseCode}")
            val out = java.io.ByteArrayOutputStream()
            c.inputStream.use { inp -> val b = ByteArray(65536); while (true) { val n = inp.read(b); if (n < 0) break; out.write(b, 0, n); if (out.size() > max) throw IllegalStateException("dosya çok büyük") } }
            return out.toByteArray()
        } finally { c.disconnect() }
    }

    private fun kind(fileName: String) = when {
        fileName.startsWith("model.") -> "model"
        fileName.startsWith("lex.") -> "lex"
        fileName.startsWith("vocab.") -> "vocab"
        else -> null
    }

    private fun download() {
        status = "indiriliyor"
        val t0 = System.currentTimeMillis()
        val urls = runCatching { fromHuggingFace() }.getOrNull()?.takeIf { it.size == 3 } ?: fromMozilla()
        for ((k, u) in urls) {
            val b = get(u)
            File(dir, "$k.tmp").writeBytes(b); File(dir, "$k.tmp").renameTo(File(dir, k))
        }
        status = "hazır"
        DiagLog.add("çeviri modeli ($pair) indirildi: ${dir.listFiles()?.sumOf { it.length() }?.shr(10)} KB, ${System.currentTimeMillis() - t0} ms")
    }

    /** Hugging Face yansısı: <çift>/<sürüm>/model.*, lex.*, vocab.* (en yeni sürüm klasörü). */
    private fun fromHuggingFace(): Map<String, String> {
        val repo = "Freonius/jerome-translation-models"
        val list = JSONArray(String(get("https://huggingface.co/api/models/$repo/tree/main/$pair?recursive=true", 4 shl 20)))
        val files = (0 until list.length()).map { list.getJSONObject(it) }.filter { it.optString("type") == "file" }.map { it.getString("path") }
        val byKind = files.mapNotNull { p -> kind(p.substringAfterLast('/'))?.let { it to p } }
            .groupBy({ it.first }, { it.second }).mapValues { (_, v) -> v.maxByOrNull { versionOf(it) }!! }
        return byKind.mapValues { (_, p) -> "https://huggingface.co/$repo/resolve/main/$p" }
    }

    /** Mozilla Remote Settings: Firefox'un kullandığı kayıtlar; en yeni yayın sürümü seçilir. */
    private fun fromMozilla(): Map<String, String> {
        val (from, to) = pair.split("-")
        val data = JSONObject(String(get("https://firefox.settings.services.mozilla.com/v1/buckets/main/collections/translations-models/records", 8 shl 20))).getJSONArray("data")
        val recs = (0 until data.length()).map { data.getJSONObject(it) }.filter {
            it.optString("fromLang") == from && it.optString("toLang") == to && it.optString("filter_expression").isBlank()
        }
        val out = HashMap<String, String>()
        for (k in parts) {
            val r = recs.filter { it.optString("fileType") == k }.maxByOrNull { versionOf(it.optString("version")) } ?: continue
            out[k] = "https://firefox-settings-attachments.cdn.mozilla.net/" + r.getJSONObject("attachment").getString("location")
        }
        if (out.size != 3) throw IllegalStateException("$pair modeli bulunamadı")
        return out
    }

    private fun versionOf(s: String): Double =
        Regex("(\\d+)\\.(\\d+)").find(s)?.let { it.groupValues[1].toDouble() * 1000 + it.groupValues[2].toDouble() } ?: 0.0
}
