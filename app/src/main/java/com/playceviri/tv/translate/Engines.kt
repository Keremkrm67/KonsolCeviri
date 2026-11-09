package com.playceviri.tv.translate

import android.os.SystemClock
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.languageid.LanguageIdentificationOptions
import com.google.mlkit.nl.languageid.LanguageIdentifier
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/** Ortak: hata sonrası bekleme ve kalıcı kapatma. */
abstract class OnlineEngine(val name: String) {
    @Volatile var coolUntil = 0L
    @Volatile var disabledReason: String? = null
    @Volatile var lastError: String? = null
    open fun available(): Boolean = disabledReason == null && SystemClock.elapsedRealtime() >= coolUntil
    fun cool(ms: Long, why: String) { coolUntil = SystemClock.elapsedRealtime() + ms; lastError = why }
    fun reset() { coolUntil = 0; disabledReason = null; lastError = null }

    protected fun http(url: String, method: String = "GET", body: String? = null, headers: Map<String, String> = emptyMap()): Pair<Int, String> {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = method
            c.connectTimeout = 2500; c.readTimeout = 3500
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) KonsolCeviri")
            headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
            if (body != null) { c.doOutput = true; c.outputStream.use { it.write(body.toByteArray()) } }
            val code = c.responseCode
            val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.readText() ?: ""
            return code to text
        } finally { c.disconnect() }
    }

    abstract fun translate(text: String, src: String, target: Target, context: String?): String?

    data class Target(val tag: String, val deepl: String)
}

/** translate.googleapis.com "gtx" ucu: ücretsiz, anahtar ve üyelik gerektirmez (resmi olmayan uç). */
class GoogleFreeEngine : OnlineEngine("Google") {
    override fun translate(text: String, src: String, target: Target, context: String?): String? {
        val tl = when (target.tag) { "zh" -> "zh-CN"; else -> target.tag }
        val sl = if (src == "auto") "auto" else src
        return try {
            val (code, body) = http("https://translate.googleapis.com/translate_a/single?client=gtx&sl=$sl&tl=$tl&dt=t&q=" +
                URLEncoder.encode(text, "UTF-8"))
            when (code) {
                200 -> {
                    val parts = JSONArray(body).getJSONArray(0)
                    val sb = StringBuilder()
                    for (i in 0 until parts.length()) parts.optJSONArray(i)?.optString(0)?.let { if (it != "null") sb.append(it) }
                    sb.toString().trim().ifEmpty { null }
                }
                429 -> { cool(60_000, "Google çok fazla istek (429)"); null }
                else -> { cool(20_000, "Google HTTP $code"); null }
            }
        } catch (e: IOException) { cool(8_000, "bağlantı yok (${e.javaClass.simpleName})"); null }
        catch (e: Exception) { cool(15_000, "Google yanıtı okunamadı"); null }
    }
}

/** MyMemory: ücretsiz, üyeliksiz; anonim kullanımda günlük ~5000 karakter sınırı var. */
class MyMemoryEngine(private val email: () -> String = { "" }) : OnlineEngine("MyMemory") {
    override fun translate(text: String, src: String, target: Target, context: String?): String? {
        val sl = if (src == "auto") "en" else src
        val tl = when (target.tag) { "zh" -> "zh-CN"; "pt" -> "pt-BR"; else -> target.tag }
        return try {
            // e-posta verilirse günlük sınır 5.000'den 50.000 karaktere çıkar (kayıt/onay gerekmez)
            val de = email().trim().takeIf { it.contains('@') }?.let { "&de=" + URLEncoder.encode(it, "UTF-8") } ?: ""
            val (code, body) = http("https://api.mymemory.translated.net/get?langpair=$sl|$tl$de&q=" + URLEncoder.encode(text, "UTF-8"))
            if (code != 200) { cool(30_000, "MyMemory HTTP $code"); return null }
            val o = JSONObject(body)
            if (o.optBoolean("quotaFinished") || body.contains("MYMEMORY WARNING")) {
                cool(6 * 3600_000L, "MyMemory günlük sınırı doldu"); return null
            }
            val t = o.optJSONObject("responseData")?.optString("translatedText")?.trim()?.let { android.text.Html.fromHtml(it, 0).toString() }
            if (t.isNullOrEmpty() || t.equals(text, true)) null else t
        } catch (e: IOException) { cool(8_000, "bağlantı yok (${e.javaClass.simpleName})"); null }
        catch (e: Exception) { cool(15_000, "MyMemory yanıtı okunamadı"); null }
    }
}

/** Google'ın Chrome sözlük eklentisi ucu (clients5): gtx'ten ayrı sınır, ücretsiz ve üyeliksiz. */
class GoogleDictEngine : OnlineEngine("Google (2)") {
    override fun translate(text: String, src: String, target: Target, context: String?): String? {
        val tl = if (target.tag == "zh") "zh-CN" else target.tag
        return try {
            val (code, body) = http("https://clients5.google.com/translate_a/t?client=dict-chrome-ex&sl=${if (src == "auto") "auto" else src}&tl=$tl&q=" +
                URLEncoder.encode(text, "UTF-8"))
            if (code == 429) { cool(60_000, "Google (2) çok fazla istek"); return null }
            if (code != 200) { cool(20_000, "Google (2) HTTP $code"); return null }
            // Yanıt: ["çeviri"] ya da [["çeviri","en"]]
            val a = JSONArray(body)
            val first = a.opt(0)
            val t = (if (first is JSONArray) first.optString(0) else a.optString(0)).trim()
            t.ifEmpty { null }
        } catch (e: IOException) { cool(8_000, "bağlantı yok (${e.javaClass.simpleName})"); null }
        catch (e: Exception) { cool(15_000, "Google (2) yanıtı okunamadı"); null }
    }
}

/** Microsoft Edge'in yerleşik çeviri ucu: anahtar/üyelik yok, kısa ömürlü anonim bilet alınır. */
class EdgeEngine : OnlineEngine("Microsoft") {
    @Volatile private var token: String? = null
    @Volatile private var tokenAt = 0L
    private fun auth(): String? {
        val now = SystemClock.elapsedRealtime()
        token?.let { if (now - tokenAt < 8 * 60_000L) return it }
        val (code, body) = http("https://edge.microsoft.com/translate/auth")
        if (code != 200 || body.isBlank() || body.length > 4000) return null
        token = body.trim(); tokenAt = now
        return token
    }
    override fun translate(text: String, src: String, target: Target, context: String?): String? {
        val tl = when (target.tag) { "zh" -> "zh-Hans"; "pt" -> "pt-br"; else -> target.tag }
        return try {
            val tk = auth() ?: run { cool(30_000, "Microsoft bilet alınamadı"); return null }
            val from = if (src == "auto") "" else "&from=$src"
            val (code, body) = http("https://api-edge.cognitive.microsofttranslator.com/translate?api-version=3.0$from&to=$tl", "POST",
                JSONArray().put(JSONObject().put("Text", text)).toString(),
                mapOf("Authorization" to "Bearer $tk", "Content-Type" to "application/json"))
            when (code) {
                200 -> JSONArray(body).getJSONObject(0).getJSONArray("translations").getJSONObject(0).getString("text").trim().ifEmpty { null }
                401 -> { token = null; cool(5_000, "Microsoft bilet süresi doldu"); null }
                429 -> { cool(60_000, "Microsoft çok fazla istek"); null }
                else -> { cool(20_000, "Microsoft HTTP $code"); null }
            }
        } catch (e: IOException) { cool(8_000, "bağlantı yok (${e.javaClass.simpleName})"); null }
        catch (e: Exception) { cool(15_000, "Microsoft yanıtı okunamadı"); null }
    }
}

/** Lingva: Google çevirisini aktaran açık kaynak, üyeliksiz ön yüz; birkaç kamu sunucusu sırayla denenir. */
class LingvaEngine : OnlineEngine("Lingva") {
    private val hosts = listOf("https://lingva.ml", "https://lingva.lunar.icu", "https://translate.plausibility.cloud")
    @Volatile private var hostIdx = 0
    override fun translate(text: String, src: String, target: Target, context: String?): String? {
        val tl = if (target.tag == "zh") "zh" else target.tag
        val enc = URLEncoder.encode(text, "UTF-8").replace("+", "%20")
        repeat(hosts.size) {
            val host = hosts[hostIdx % hosts.size]
            try {
                val (code, body) = http("$host/api/v1/${if (src == "auto") "auto" else src}/$tl/$enc")
                if (code == 200) {
                    val t = JSONObject(body).optString("translation").trim()
                    if (t.isNotEmpty()) return t
                }
            } catch (_: Exception) {}
            hostIdx++
        }
        cool(30_000, "Lingva sunucularına ulaşılamadı"); return null
    }
}

/** Microsoft Translator resmî API'si (Azure F0: ayda 2 milyon karakter ücretsiz), kullanıcının kendi anahtarıyla. */
class MicrosoftEngine(private val key: () -> String, private val region: () -> String) : OnlineEngine("Microsoft (anahtar)") {
    override fun available() = key().isNotBlank() && super.available()
    override fun translate(text: String, src: String, target: Target, context: String?): String? {
        val k = key().trim(); if (k.isEmpty()) return null
        val tl = when (target.tag) { "zh" -> "zh-Hans"; "pt" -> "pt-br"; else -> target.tag }
        val from = if (src == "auto") "" else "&from=$src"
        val h = mutableMapOf("Ocp-Apim-Subscription-Key" to k, "Content-Type" to "application/json")
        region().trim().takeIf { it.isNotEmpty() }?.let { h["Ocp-Apim-Subscription-Region"] = it }
        return try {
            val (code, body) = http("https://api.cognitive.microsofttranslator.com/translate?api-version=3.0$from&to=$tl", "POST",
                JSONArray().put(JSONObject().put("Text", text)).toString(), h)
            when (code) {
                200 -> JSONArray(body).getJSONObject(0).getJSONArray("translations").getJSONObject(0).getString("text").trim().ifEmpty { null }
                401 -> { cool(60_000, "Microsoft anahtarı ya da bölgesi hatalı"); null }
                403 -> { cool(10 * 60_000L, "Microsoft aylık ücretsiz kota doldu"); null }
                429 -> { cool(30_000, "Microsoft çok fazla istek"); null }
                else -> { cool(20_000, "Microsoft HTTP $code"); null }
            }
        } catch (e: IOException) { cool(8_000, "bağlantı yok (${e.javaClass.simpleName})"); null }
        catch (e: Exception) { cool(15_000, "Microsoft yanıtı okunamadı"); null }
    }
}

/** Google Cloud Translation (resmî Google Translate API'si), kullanıcının kendi anahtarıyla; ücretli ama ucuz. */
class GoogleCloudEngine(private val key: () -> String) : OnlineEngine("Google Cloud") {
    override fun available() = key().isNotBlank() && super.available()
    override fun translate(text: String, src: String, target: Target, context: String?): String? {
        val k = key().trim(); if (k.isEmpty()) return null
        val tl = if (target.tag == "zh") "zh-CN" else target.tag
        val body = JSONObject().put("q", text).put("target", tl).put("format", "text")
        if (src != "auto") body.put("source", src)
        return try {
            val (code, resp) = http("https://translation.googleapis.com/language/translate/v2", "POST", body.toString(),
                mapOf("X-Goog-Api-Key" to k, "Content-Type" to "application/json"))
            when (code) {
                200 -> JSONObject(resp).getJSONObject("data").getJSONArray("translations").getJSONObject(0).getString("translatedText")
                    .let { android.text.Html.fromHtml(it, 0).toString().trim() }.ifEmpty { null }
                400, 401, 403 -> { cool(60_000, "Google Cloud anahtarı hatalı ya da faturalandırma kapalı"); null }
                429 -> { cool(30_000, "Google Cloud çok fazla istek"); null }
                else -> { cool(20_000, "Google Cloud HTTP $code"); null }
            }
        } catch (e: IOException) { cool(8_000, "bağlantı yok (${e.javaClass.simpleName})"); null }
        catch (e: Exception) { cool(15_000, "Google Cloud yanıtı okunamadı"); null }
    }
}

/** Yapay zekâ çevirmenleri için ortak yönerge: oyun diyaloğu, ton korunur, yalnız çeviri döner. */
internal fun aiPrompt(target: OnlineEngine.Target, context: String?): String {
    val lang = when (target.tag) { "tr" -> "Turkish"; "de" -> "German"; "fr" -> "French"; "es" -> "Spanish"; "it" -> "Italian"; "pt" -> "Portuguese"
        "ru" -> "Russian"; "ar" -> "Arabic"; "ja" -> "Japanese"; "ko" -> "Korean"; "zh" -> "Simplified Chinese"; else -> target.tag }
    return "You translate video game subtitles into $lang. Keep character names, keep the speaker's tone (slang, jokes, swearing), " +
        "keep it short and natural like real subtitles. If the line starts with a speaker label like 'JOEL:', keep the label. " +
        "Output ONLY the translation, no quotes, no notes." +
        (context?.let { "\nPrevious lines for context (do not translate them): $it" } ?: "")
}

internal fun cleanAi(s: String?): String? = s?.trim()?.removeSurrounding("\"")?.removeSurrounding("“", "”")?.trim()?.ifEmpty { null }

/** Groq (Llama vb. açık modeller): ücretsiz anahtar, kart istemez. Uygun model hesapta listelenenlerden seçilir. */
class GroqEngine(private val key: () -> String) : OnlineEngine("Groq") {
    @Volatile private var model: String? = null
    override fun available() = key().isNotBlank() && super.available()
    @Volatile private var forKey = ""
    private fun pickModel(k: String): String {
        if (k != forKey) { forKey = k; model = null }
        model?.let { return it }
        val prefs = listOf("llama-3.3-70b", "gpt-oss-120b", "llama-4-maverick", "llama-4-scout", "qwen", "llama-3.1-8b")
        val ids = runCatching {
            val (code, body) = http("https://api.groq.com/openai/v1/models", headers = mapOf("Authorization" to "Bearer $k"))
            if (code != 200) emptyList() else JSONObject(body).getJSONArray("data").let { a -> (0 until a.length()).map { a.getJSONObject(it).getString("id") } }
        }.getOrDefault(emptyList()).filter { id -> listOf("whisper", "guard", "tts", "embed", "compound").none { id.contains(it) } }
        val m = prefs.firstNotNullOfOrNull { p -> ids.firstOrNull { it.contains(p) } } ?: ids.firstOrNull() ?: "llama-3.3-70b-versatile"
        model = m; return m
    }
    override fun translate(text: String, src: String, target: Target, context: String?): String? {
        val k = key().trim(); if (k.isEmpty()) return null
        return try {
            val m = pickModel(k)
            val body = JSONObject().put("model", m).put("temperature", 0.2).put("max_tokens", 400)
                .put("messages", JSONArray().put(JSONObject().put("role", "system").put("content", aiPrompt(target, context)))
                    .put(JSONObject().put("role", "user").put("content", text)))
            val (code, resp) = http("https://api.groq.com/openai/v1/chat/completions", "POST", body.toString(),
                mapOf("Authorization" to "Bearer $k", "Content-Type" to "application/json"))
            when (code) {
                200 -> cleanAi(JSONObject(resp).getJSONArray("choices").getJSONObject(0).getJSONObject("message").optString("content"))
                401 -> { cool(60_000, "Groq anahtarı hatalı"); null }
                404, 400 -> { model = null; cool(5_000, "Groq modeli değişti, yeniden seçilecek"); null }
                429 -> { cool(30_000, "Groq ücretsiz sınırına ulaşıldı (bekleniyor)"); null }
                else -> { cool(20_000, "Groq HTTP $code"); null }
            }
        } catch (e: IOException) { cool(8_000, "bağlantı yok (${e.javaClass.simpleName})"); null }
        catch (e: Exception) { cool(15_000, "Groq yanıtı okunamadı"); null }
    }
}

/** Google Gemini (AI Studio): ücretsiz anahtar, kart istemez. Hızlı "flash" modellerinden en yenisi seçilir. */
class GeminiEngine(private val key: () -> String) : OnlineEngine("Gemini") {
    @Volatile private var models: List<String>? = null
    @Volatile private var modelIdx = 0
    @Volatile private var noThinkingCfg = false
    override fun available() = key().isNotBlank() && super.available()

    /** Hesapta generateContent destekleyen flash modelleri, tercih sırasıyla (kararlı flash-lite → flash). */
    @Volatile private var forKey = ""
    private fun candidates(k: String): List<String> {
        if (k != forKey) { forKey = k; models = null; noThinkingCfg = false; modelIdx = 0 }   // anahtar değişti: baştan seç
        models?.let { return it }
        val names = runCatching {
            var (code, body) = http("https://generativelanguage.googleapis.com/v1beta/models?pageSize=200", headers = mapOf("x-goog-api-key" to k))
            if (code != 200) { val r2 = http("https://generativelanguage.googleapis.com/v1beta/models?pageSize=200&key=" + URLEncoder.encode(k, "UTF-8")); code = r2.first; body = r2.second }
            if (code != 200) emptyList() else JSONObject(body).getJSONArray("models").let { a ->
                (0 until a.length()).map { a.getJSONObject(it) }.filter { o ->
                    o.optJSONArray("supportedGenerationMethods")?.let { m -> (0 until m.length()).any { m.getString(it) == "generateContent" } } == true
                }.map { it.getString("name").removePrefix("models/") }
            }
        }.getOrDefault(emptyList()).filter { n -> n.contains("flash") && listOf("tts", "image", "live", "audio", "exp", "thinking", "embedding", "native").none { n.contains(it) } }
        fun ver(n: String) = Regex("(\\d+(?:\\.\\d+)?)").find(n)?.value?.toDoubleOrNull() ?: 0.0
        val stable = names.filter { !it.contains("preview") }
        val ordered = (stable.filter { it.contains("flash-lite") }.sortedByDescending { ver(it) } +
            stable.filter { !it.contains("lite") }.sortedByDescending { ver(it) } +
            names.filter { it.contains("preview") }.sortedByDescending { ver(it) }).distinct()
        val list = ordered.ifEmpty { listOf("gemini-flash-lite-latest", "gemini-flash-latest", "gemini-2.5-flash-lite", "gemini-2.5-flash") }
        models = list; modelIdx = 0
        return list
    }

    /** Google'ın hata mesajını kullanıcıya anlaşılır Türkçeyle verir. */
    private fun why(code: Int, resp: String): String {
        val e = runCatching { JSONObject(resp).optJSONObject("error") }.getOrNull()
        val msg = e?.optString("message").orEmpty(); val st = e?.optString("status").orEmpty()
        val all = (msg + " " + st + " " + resp.take(400))
        return when {
            all.contains("ACCESS_TOKEN_TYPE_UNSUPPORTED", true) -> "Gemini bu anahtar türünü kabul etmedi (AQ. anahtarı, hesap kısıtı)"
            all.contains("API key not valid", true) || all.contains("API_KEY_INVALID", true) -> "Gemini anahtarı geçersiz ya da hesap kısıtlı (anahtarı AI Studio'da yeniden oluşturup kopyala)"
            all.contains("location is not supported", true) || all.contains("not available in your country", true) -> "Gemini bu ülkeden/ağdan kullanılamıyor"
            all.contains("has not been used", true) || all.contains("SERVICE_DISABLED", true) -> "Gemini API bu projede kapalı (AI Studio'dan yeni anahtar al)"
            all.contains("expired", true) -> "Gemini anahtarının süresi dolmuş, yenisini al"
            msg.isNotBlank() -> "Gemini: " + msg.take(140)
            else -> "Gemini HTTP $code"
        }
    }

    /** Anahtar gönderim biçimi: 0 başlık (x-goog-api-key), 1 adres (?key=), 2 Bearer. Yeni "AQ." anahtarlarında hangisinin
     *  kabul edileceği hesaba göre değişebildiği için reddedilirse sıradaki biçim denenir; çalışan biçim hatırlanır. */
    @Volatile private var authMode = 0
    private fun send(m: String, k: String, body: String): Pair<Int, String> {
        val base = "https://generativelanguage.googleapis.com/v1beta/models/$m:generateContent"
        return when (authMode) {
            1 -> http(base + "?key=" + URLEncoder.encode(k, "UTF-8"), "POST", body, mapOf("Content-Type" to "application/json"))
            2 -> http(base, "POST", body, mapOf("Authorization" to "Bearer $k", "Content-Type" to "application/json"))
            else -> http(base, "POST", body, mapOf("x-goog-api-key" to k, "Content-Type" to "application/json"))
        }
    }

    override fun translate(text: String, src: String, target: Target, context: String?): String? {
        val k = key().trim().replace(Regex("\\s"), ""); if (k.isEmpty()) return null
        return try {
            val list = candidates(k)
            var authTried = 0
            var lastWhy = "Gemini yanıt vermedi"
            // en çok 5 deneme: anahtar biçimi, düşünme ayarı ya da model reddi olursa kendiliğinden uyarlanır
            repeat(5) {
                val m = list[modelIdx.coerceIn(0, list.lastIndex)]
                val gen = JSONObject().put("temperature", 0.2).put("maxOutputTokens", 400)
                if (!noThinkingCfg) gen.put("thinkingConfig", JSONObject().put("thinkingBudget", 0))
                val body = JSONObject()
                    .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", aiPrompt(target, context)))))
                    .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", text)))))
                    .put("generationConfig", gen)
                val (code, resp) = send(m, k, body.toString())
                val authProblem = (code == 400 || code == 401 || code == 403) &&
                    (resp.contains("API key not valid", true) || resp.contains("API_KEY_INVALID", true) || resp.contains("ACCESS_TOKEN_TYPE_UNSUPPORTED", true) ||
                        resp.contains("UNAUTHENTICATED", true) || resp.contains("Multiple authentication", true))
                if (authProblem && authTried < 2) { authTried++; authMode = (authMode + 1) % 3; lastWhy = why(code, resp); return@repeat }
                when (code) {
                    200 -> {
                        val parts = JSONObject(resp).optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")
                        val out = cleanAi(parts?.let { p -> (0 until p.length()).map { p.getJSONObject(it) }.filter { !it.optBoolean("thought") }.joinToString("") { it.optString("text") } })
                        if (out != null) return out
                        lastWhy = "Gemini boş yanıt verdi (güvenlik filtresi olabilir)"; noThinkingCfg = true
                    }
                    400 -> {
                        lastWhy = why(code, resp)
                        if (lastWhy.contains("anahtar") || lastWhy.contains("kullanılamıyor")) { cool(60_000, lastWhy); return null }
                        if (!noThinkingCfg) noThinkingCfg = true          // önce düşünme ayarı olmadan dene
                        else modelIdx = (modelIdx + 1) % list.size         // sonra sıradaki modeli dene
                    }
                    401, 403 -> { lastWhy = why(code, resp); cool(60_000, lastWhy); return null }
                    404 -> { lastWhy = "Gemini modeli bulunamadı ($m), sıradaki deneniyor"; modelIdx = (modelIdx + 1) % list.size }
                    429 -> { cool(45_000, "Gemini ücretsiz sınırına ulaşıldı (bekleniyor)"); return null }
                    else -> { cool(20_000, why(code, resp)); return null }
                }
            }
            cool(10_000, lastWhy); null
        } catch (e: IOException) { cool(8_000, "bağlantı yok (${e.javaClass.simpleName})"); null }
        catch (e: Exception) { cool(15_000, "Gemini yanıtı okunamadı"); null }
    }
}

/** LibreTranslate: açık kaynak; kullanıcının kendi (ya da izin verilen) sunucusu, isteğe bağlı anahtar. */
class LibreEngine(private val url: () -> String, private val key: () -> String) : OnlineEngine("LibreTranslate") {
    override fun available() = url().isNotBlank() && super.available()
    override fun translate(text: String, src: String, target: Target, context: String?): String? {
        var base = url().trim().trimEnd('/'); if (base.isEmpty()) return null
        if (!base.startsWith("http")) base = "https://$base"
        val body = JSONObject().put("q", text).put("source", if (src == "auto") "auto" else src).put("target", target.tag).put("format", "text")
        key().trim().takeIf { it.isNotEmpty() }?.let { body.put("api_key", it) }
        return try {
            val (code, resp) = http("$base/translate", "POST", body.toString(), mapOf("Content-Type" to "application/json"))
            when (code) {
                200 -> JSONObject(resp).optString("translatedText").trim().ifEmpty { null }
                400, 403 -> { cool(60_000, "LibreTranslate anahtar ya da dil hatası"); null }
                429 -> { cool(30_000, "LibreTranslate çok fazla istek"); null }
                else -> { cool(20_000, "LibreTranslate HTTP $code"); null }
            }
        } catch (e: IOException) { cool(8_000, "LibreTranslate sunucusuna ulaşılamadı"); null }
        catch (e: Exception) { cool(15_000, "LibreTranslate yanıtı okunamadı"); null }
    }
}

class DeepLEngine(private val key: () -> String) : OnlineEngine("DeepL") {
    override fun available() = key().isNotBlank() && super.available()
    override fun translate(text: String, src: String, target: Target, context: String?): String? {
        val k = key().trim(); if (k.isEmpty()) return null
        val host = if (k.endsWith(":fx")) "api-free.deepl.com" else "api.deepl.com"
        val body = JSONObject().put("text", JSONArray().put(text)).put("target_lang", target.deepl)
        if (!context.isNullOrBlank()) body.put("context", context)
        return try {
            val (code, resp) = http("https://$host/v2/translate", "POST", body.toString(),
                mapOf("Authorization" to "DeepL-Auth-Key $k", "Content-Type" to "application/json"))
            when (code) {
                200 -> JSONObject(resp).getJSONArray("translations").getJSONObject(0).getString("text")
                401, 403 -> { disabledReason = "DeepL anahtarı geçersiz"; null }
                456 -> { disabledReason = "Aylık DeepL hakkı bitti"; null }
                429 -> { cool(30_000, "DeepL çok fazla istek"); null }
                else -> { cool(15_000, "DeepL HTTP $code"); Log.w("KonsolCeviri/DeepL", "HTTP $code"); null }
            }
        } catch (e: IOException) { cool(15_000, "internet yok"); null }
        catch (e: Exception) { cool(15_000, "DeepL yanıtı okunamadı"); null }
    }
}

/** ML Kit cihaz içi çeviri (çevrimdışı). İlk kullanımda dil modeli (~30 MB) indirilir. */
class MlKitEngine {
    private val languageId: LanguageIdentifier = LanguageIdentification.getClient(
        LanguageIdentificationOptions.Builder().setConfidenceThreshold(0.4f).build())
    private val translators = HashMap<String, Translator>()
    private val ready = HashSet<String>()

    private fun translator(src: String, tl: String): Translator = synchronized(translators) {
        translators.getOrPut("$src>$tl") {
            Translation.getClient(TranslatorOptions.Builder().setSourceLanguage(src).setTargetLanguage(tl).build())
        }
    }

    fun isReady(src: String, target: String) = "$src>$target" in ready

    /** Modeli indirir (gerekirse); başarıyla biterse true. */
    fun ensureModel(srcTag: String, targetTag: String, timeoutSec: Long = 240): Boolean {
        val src = TranslateLanguage.fromLanguageTag(srcTag) ?: TranslateLanguage.ENGLISH
        val tl = TranslateLanguage.fromLanguageTag(targetTag) ?: return false
        if (src == tl) return true
        val key = "$src>$tl"
        if (key in ready) return true
        Tasks.await(translator(src, tl).downloadModelIfNeeded(DownloadConditions.Builder().build()), timeoutSec, TimeUnit.SECONDS)
        ready.add(key)
        return true
    }

    fun translate(text: String, srcTag: String, targetTag: String): String? {
        var s = srcTag
        if (s == "auto") {
            val id = Tasks.await(languageId.identifyLanguage(text), 5, TimeUnit.SECONDS)
            s = if (id == LanguageIdentifier.UNDETERMINED_LANGUAGE_TAG) "en" else id.substringBefore('-')
        }
        val src = TranslateLanguage.fromLanguageTag(s) ?: TranslateLanguage.ENGLISH
        val tl = TranslateLanguage.fromLanguageTag(targetTag) ?: return null
        if (src == tl) return text
        ensureModel(src, tl)
        return Tasks.await(translator(src, tl).translate(text), 10, TimeUnit.SECONDS)
    }

    fun close() {
        synchronized(translators) { translators.values.forEach { it.close() }; translators.clear() }
        languageId.close()
    }
}
