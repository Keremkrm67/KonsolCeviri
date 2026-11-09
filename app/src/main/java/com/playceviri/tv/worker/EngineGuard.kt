package com.playceviri.tv.worker

import android.content.Context
import java.io.File

/**
 * İşçi süreci bir motoru çalıştırırken çökerse hangi motorun çökerttiğini bilmek için
 * çalışmadan önce aşama dosyaya yazılır. Çöken motor bu kurulum için kapatılır (güncellemede sıfırlanır).
 */
object EngineGuard {
    const val OCR_MLKIT = "ocr:mlkit"
    const val TR_MLKIT = "ceviri:mlkit"

    private fun stageFile(c: Context) = File(c.applicationContext.cacheDir, "isci_asama")
    private fun disabledFile(c: Context) = File(c.applicationContext.filesDir, "kapali_motorlar")

    fun mark(c: Context, stage: String) { runCatching { stageFile(c).writeText(stage) } }
    fun clear(c: Context) { runCatching { stageFile(c).writeText("") } }
    fun stage(c: Context): String = runCatching { stageFile(c).readText().trim() }.getOrDefault("")

    private fun installStamp(c: Context) = runCatching {
        c.packageManager.getPackageInfo(c.packageName, 0).lastUpdateTime.toString()
    }.getOrDefault("0")

    fun disabled(c: Context): Set<String> {
        val lines = runCatching { disabledFile(c).readLines() }.getOrDefault(emptyList())
        if (lines.isEmpty() || lines[0] != installStamp(c)) return emptySet()
        return lines.drop(1).filter { it.isNotBlank() }.toSet()
    }

    fun disable(c: Context, stage: String) {
        val all = disabled(c) + stage
        runCatching { disabledFile(c).writeText((listOf(installStamp(c)) + all).joinToString("\n")) }
    }

    fun reset(c: Context) { runCatching { disabledFile(c).delete() } }

    fun label(stage: String) = when (stage) {
        OCR_MLKIT -> "ML Kit yazı okuma"
        TR_MLKIT -> "ML Kit cihaz içi çeviri"
        else -> stage
    }
}
