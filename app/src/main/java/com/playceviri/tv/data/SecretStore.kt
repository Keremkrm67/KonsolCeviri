package com.playceviri.tv.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** PSN kimliği, PS5 kayıt anahtarları ve çeviri anahtarları; Android Keystore ile şifreli tutulur. */
class SecretStore(context: Context) {
    private val sp = context.applicationContext.getSharedPreferences("playceviri_gizli", Context.MODE_PRIVATE)

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build()
        )
        return gen.generateKey()
    }

    private fun put(name: String, value: ByteArray?) {
        if (value == null) { sp.edit().remove(name).apply(); return }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        sp.edit().putString(name, Base64.encodeToString(c.iv + c.doFinal(value), Base64.NO_WRAP)).apply()
    }

    private fun get(name: String): ByteArray? {
        val s = sp.getString(name, null) ?: return null
        return try {
            val d = Base64.decode(s, Base64.NO_WRAP)
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, d, 0, 12))
            c.doFinal(d, 12, d.size - 12)
        } catch (e: Exception) { null }
    }

    private fun str(name: String) = get(name)?.toString(Charsets.UTF_8) ?: ""
    private fun setStr(name: String, v: String) = put(name, v.trim().ifEmpty { null }?.toByteArray(Charsets.UTF_8))

    var deeplKey: String
        get() = str(DEEPL)
        set(v) = setStr(DEEPL, v)
    var myMemoryEmail: String
        get() = str(MM_EMAIL)
        set(v) = setStr(MM_EMAIL, v)
    var msKey: String
        get() = str(MS_KEY)
        set(v) = setStr(MS_KEY, v)
    var msRegion: String
        get() = str(MS_REGION)
        set(v) = setStr(MS_REGION, v)
    var groqKey: String
        get() = str(GROQ)
        set(v) = setStr(GROQ, v)
    var geminiKey: String
        get() = str(GEMINI)
        set(v) = setStr(GEMINI, v)
    var libreUrl: String
        get() = str(LIBRE_URL)
        set(v) = setStr(LIBRE_URL, v)
    var googleCloudKey: String
        get() = str(GCLOUD)
        set(v) = setStr(GCLOUD, v)
    var libreKey: String
        get() = str(LIBRE_KEY)
        set(v) = setStr(LIBRE_KEY, v)

    /** Çeviri anahtarları tek pakette (işçi sürece her çağrıda taşınır; ayrı süreç ayar önbelleğine güvenilmez). */
    fun translatorKeys() = com.playceviri.tv.translate.Keys(deeplKey, myMemoryEmail, msKey, msRegion, groqKey, geminiKey, libreUrl, libreKey, googleCloudKey)

    var psnAccountId: String
        get() = str(PSN)
        set(v) = setStr(PSN, v)
    var psnName: String
        get() = str(PSN_NAME)
        set(v) = setStr(PSN_NAME, v)
    var consoleRegistKey: ByteArray?
        get() = get(REG_KEY)
        set(v) = put(REG_KEY, v)
    var consoleMorning: ByteArray?
        get() = get(MORNING)
        set(v) = put(MORNING, v)

    val isConsolePaired: Boolean get() = consoleRegistKey != null && consoleMorning != null

    fun forgetPsn() { put(PSN, null); put(PSN_NAME, null); forgetConsole() }
    fun forgetConsole() { put(REG_KEY, null); put(MORNING, null) }

    companion object {
        private const val ALIAS = "playceviri_gizli_anahtar"
        private const val DEEPL = "deepl"
        private const val MM_EMAIL = "mymemory_eposta"
        private const val MS_KEY = "ms_anahtar"
        private const val MS_REGION = "ms_bolge"
        private const val GROQ = "groq"
        private const val GEMINI = "gemini"
        private const val LIBRE_URL = "libre_adres"
        private const val LIBRE_KEY = "libre_anahtar"
        private const val GCLOUD = "google_cloud"
        private const val PSN = "psn_hesap"
        private const val PSN_NAME = "psn_ad"
        private const val REG_KEY = "ps5_kayit"
        private const val MORNING = "ps5_morning"
    }
}
