package com.playceviri.tv.translate

import android.os.Bundle

/** Kullanıcının kendi (ücretsiz) çeviri anahtarları. Boş alan = o motor kapalı. */
data class Keys(
    val deepl: String = "", val myMemoryEmail: String = "", val msKey: String = "", val msRegion: String = "",
    val groq: String = "", val gemini: String = "", val libreUrl: String = "", val libreKey: String = "",
    val googleCloud: String = "",
) {
    /** Kaliteli (anahtarlı) bir motor tanımlı mı? */
    val hasQuality get() = listOf(deepl, msKey, groq, gemini, googleCloud).any { it.isNotBlank() }
    fun toBundle() = Bundle().apply {
        putString("deepl", deepl); putString("mm", myMemoryEmail); putString("ms", msKey); putString("msr", msRegion)
        putString("groq", groq); putString("gem", gemini); putString("lu", libreUrl); putString("lk", libreKey); putString("gc", googleCloud)
    }
    companion object {
        fun from(b: Bundle?): Keys? = b?.let {
            Keys(it.getString("deepl") ?: "", it.getString("mm") ?: "", it.getString("ms") ?: "", it.getString("msr") ?: "",
                it.getString("groq") ?: "", it.getString("gem") ?: "", it.getString("lu") ?: "", it.getString("lk") ?: "", it.getString("gc") ?: "")
        }
    }
}
