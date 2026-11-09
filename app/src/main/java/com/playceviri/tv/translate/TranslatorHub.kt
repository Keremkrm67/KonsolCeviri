package com.playceviri.tv.translate

import android.content.Context
import com.playceviri.tv.data.Settings

/**
 * Çeviri motorlarını sırayla dener.
 * Mod 0 (Otomatik): DeepL (anahtar varsa) → Google → Microsoft → Google (2) → Lingva → MyMemory → ML Kit (çevrimdışı).
 * İnternet koparsa çevrimiçi motorlar kısa süre beklemeye alınır ve ML Kit devreye girer.
 */
class TranslatorHub(context: Context) {
    /** Her çağrıda ana süreçten gelen güncel anahtarlar. */
    @Volatile var keys = Keys()
    data class Translated(val text: String, val engine: String)

    private val cache = TranslationCache(context)
    private val deepl = DeepLEngine { keys.deepl }
    private val google = GoogleFreeEngine()
    private val myMemory = MyMemoryEngine { keys.myMemoryEmail }
    private val microsoft = MicrosoftEngine({ keys.msKey }, { keys.msRegion })
    private val groq = GroqEngine { keys.groq }
    private val gemini = GeminiEngine { keys.gemini }
    private val libre = LibreEngine({ keys.libreUrl }, { keys.libreKey })
    private val gcloud = GoogleCloudEngine { keys.googleCloud }
    private val google2 = GoogleDictEngine()
    private val edge = EdgeEngine()
    private val lingva = LingvaEngine()
    private val keyed get() = listOf(gemini, groq, deepl, gcloud, microsoft)
    private val all get() = keyed + (if (com.playceviri.tv.BuildConfig.GAYRIRESMI) listOf(google, edge, google2, lingva) else emptyList()) + listOf(myMemory, libre)
    private val mlKit by lazy { MlKitEngine() }
    private var mlKitUsed = false
    private val history = ArrayDeque<String>()
    var lastError: String? = null; private set

    fun status(): String = all.joinToString(" · ") { e ->
        "${e.name}: " + when {
            !e.available() && e.disabledReason == null && e.lastError == null -> "anahtar yok"
            e.disabledReason != null -> e.disabledReason!!
            !e.available() -> "beklemede (${e.lastError})"
            else -> "hazır"
        }
    }

    fun resetEngines() { all.forEach { it.reset() } }

    fun mlKitEngine(): MlKitEngine { mlKitUsed = true; return mlKit }

    fun translate(text: String, lang: Settings.Language, terms: List<String>, src: String, mode: Int, allowMlKit: Boolean,
                  beforeMlKit: () -> Unit = {}, useCache: Boolean = true): Translated? {
        if (useCache) cache.get(lang.tag, text)?.let { remember(text); return Translated(it, "önbellek") }
        val (protectedText, restore) = protect(text, terms)
        val ctx = history.takeLast(2).joinToString(" ").ifBlank { null }
        val target = OnlineEngine.Target(lang.tag, lang.deeplCode)
        val ok = com.playceviri.tv.BuildConfig.GAYRIRESMI
        // 0: anahtarlı motorlar (+ tam sürümde gayriresmî uçlar); telefon çevirisi ve yedekler bundan sonra denenir.
        // 13: yedek (MyMemory, LibreTranslate) · 14: eski davranış, hepsi sırayla
        val order: List<OnlineEngine> = when (mode) {
            0 -> keyed + (if (ok) listOf(google, edge, google2, lingva) else emptyList())
            13 -> listOf(myMemory, libre)
            14 -> all
            2 -> if (ok) listOf(google) else emptyList()
            3 -> listOf(myMemory)
            4 -> listOf(deepl)
            5 -> if (ok) listOf(edge) else emptyList()
            6 -> if (ok) listOf(lingva) else emptyList()
            7 -> if (ok) listOf(google2) else emptyList()
            9 -> listOf(microsoft)
            10 -> listOf(groq)
            11 -> listOf(gemini)
            12 -> listOf(libre)
            15 -> listOf(gcloud)
            else -> emptyList()
        }
        var out: String? = null; var engine = ""
        for (e in order) {
            if (!e.available()) { if (out == null) lastError = e.disabledReason ?: e.lastError ?: "${e.name}: anahtar girilmemiş"; continue }
            out = e.translate(protectedText, src, target, ctx)
            if (out != null) { engine = e.name; break } else lastError = e.disabledReason ?: e.lastError
        }
        if (out == null && (mode == 0 || mode == 1 || mode == 13 || mode == 14) && allowMlKit) {
            try {
                beforeMlKit()
                out = mlKitEngine().translate(protectedText, src, lang.tag)
                engine = "ML Kit (cihaz içi)"
            } catch (e: Exception) { lastError = "ML Kit: ${e.message ?: e.javaClass.simpleName}" }
        }
        val final = out?.let { restore(it).trim() }?.takeIf { it.isNotEmpty() } ?: return null
        if (useCache) { cache.put(lang.tag, text, final); remember(text) }
        return Translated(final, engine)
    }

    private fun remember(t: String) {
        if (history.lastOrNull() == t) return
        history.addLast(t)
        while (history.size > 4) history.removeFirst()
    }

    /** Sözlükteki terimleri (karakter adları vb.) çeviriden korur. */
    private fun protect(text: String, terms: List<String>): Pair<String, (String) -> String> {
        if (terms.isEmpty()) return text to { it }
        val map = LinkedHashMap<String, String>()
        var s = text
        terms.forEachIndexed { i, term ->
            val token = "Xq${i}z"
            s = Regex("\\b" + Regex.escape(term) + "\\b", RegexOption.IGNORE_CASE).replace(s) { m -> map[token] = m.value; token }
        }
        if (map.isEmpty()) return text to { it }
        return s to { t -> var r = t; map.forEach { (k, v) -> r = r.replace(k, v, true) }; r }
    }

    fun close() {
        if (mlKitUsed) runCatching { mlKit.close() }
        runCatching { cache.close() }
    }
}
