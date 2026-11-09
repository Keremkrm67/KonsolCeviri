package com.playceviri.tv.util

import android.app.Application
import android.content.Context
import android.os.Build
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Native çökmeleri (SIGSEGV vb.) cache/cokme.log dosyasına yazan küçük yerel kütüphane. */
object CrashCatcher {
    @JvmStatic
    private external fun nativeInstall(path: String, process: String)

    fun file(context: Context) = File(context.applicationContext.cacheDir, "cokme.log")

    fun processName(): String = runCatching {
        if (Build.VERSION.SDK_INT >= 28) Application.getProcessName()
        else File("/proc/self/cmdline").readText().trimEnd('\u0000')
    }.getOrDefault("?")

    @Synchronized
    fun install(context: Context) {
        runCatching {
            System.loadLibrary("playceviri-crash")
            val f = file(context)
            if (f.length() > 64_000) f.writeText(f.readText().takeLast(16_000))
            nativeInstall(f.path, processName().substringAfterLast(':', "ana"))
        }.exceptionOrNull()?.let { DiagLog.add("çökme yakalayıcı kurulamadı: ${it.javaClass.simpleName}") }
    }

    fun count(context: Context): Int = runCatching {
        file(context).readText().split("=== CRASH ").count { it.isNotBlank() }
    }.getOrDefault(0)

    fun lastCrashes(context: Context): String {
        val s = runCatching { file(context).readText() }.getOrDefault("")
        if (s.isBlank()) return ""
        val f = SimpleDateFormat("dd.MM HH:mm:ss", Locale.US)
        return s.split("=== CRASH ").filter { it.isNotBlank() }.takeLast(2).joinToString("") { b ->
            val sp = b.indexOf(' ').coerceAtLeast(0)
            val t = b.substring(0, sp).toLongOrNull()?.let { f.format(Date(it * 1000)) } ?: "?"
            "ÇÖKME $t ${b.substring(sp + 1).replace("=== SON\n", "")}"
        }
    }
}
