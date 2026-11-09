package com.playceviri.tv.psn

import android.net.Uri
import android.util.Base64
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Sony girişi sonrası dönen adresteki koddan PSN hesap kimliğini (8 bayt, base64) çıkarır. */
object PsnAccount {
    private const val CLIENT_ID = "ba495a24-818c-472b-b12d-ff231c1b5745"
    private const val CLIENT_SECRET = "mvaiZkRsAsI1IBkY"
    const val REDIRECT = "https://remoteplay.dl.playstation.net/remoteplay/redirect"
    private const val TOKEN_URL = "https://auth.api.sonyentertainmentnetwork.com/2.0/oauth/token"
    const val LOGIN_URL = "https://auth.api.sonyentertainmentnetwork.com/2.0/oauth/authorize?service_entity=urn:service-entity:psn&response_type=code&client_id=$CLIENT_ID&redirect_uri=$REDIRECT&scope=psn:clientapp&request_locale=en_US&ui=pr&service_logo=ps&layout_type=popup&smcid=remoteplay&prompt=always&PlatformPrivacyWs1=minimal&"

    data class Resolved(val id: String, val name: String?)

    private fun basic() = "Basic " + Base64.encodeToString("$CLIENT_ID:$CLIENT_SECRET".toByteArray(), Base64.NO_WRAP)

    fun looksLikeAccountId(s: String): Boolean = try {
        val t = s.trim()
        Base64.decode(t, Base64.DEFAULT).size == 8 && t.length in 11..12
    } catch (e: IllegalArgumentException) { false }

    fun resolveFull(input: String): Result<Resolved> {
        val s = input.trim()
        if (looksLikeAccountId(s)) return Result.success(Resolved(s, null))
        if (s.isEmpty()) return Result.failure(IllegalArgumentException("Kutu boş."))
        val uri = runCatching { Uri.parse(s) }.getOrNull()
        val host = uri?.host
        if (host != null && "playstation.net" in host) {
            val code = uri.getQueryParameter("code")
            if (code.isNullOrEmpty()) return Result.failure(IllegalArgumentException(
                "Adreste giriş kodu yok. Adres çubuğuna dokunup tamamını kopyaladığından emin ol; giriş tamamlanmamış olabilir."))
            return runCatching {
                val token = open(TOKEN_URL, "POST",
                    "grant_type=authorization_code&code=${URLEncoder.encode(code, "UTF-8")}&redirect_uri=$REDIRECT&")
                    .getString("access_token")
                val uid = open("$TOKEN_URL/${URLEncoder.encode(token, "UTF-8")}", "GET", null).get("user_id").toString().toLong()
                val b = ByteArray(8) { i -> ((uid ushr (i * 8)) and 0xFF).toByte() }
                Resolved(Base64.encodeToString(b, Base64.NO_WRAP), onlineId(token))
            }
        }
        return Result.failure(IllegalArgumentException(
            "Bu, giriş sonrası açılan adres değil. Giriş bitince adres çubuğundaki tam adresi (…redirect?code=… ile başlayan) kopyala."))
    }

    private fun onlineId(token: String): String? = try {
        val c = URL("https://us-prof.np.community.playstation.net/userProfile/v1/users/me/profile2?fields=onlineId")
            .openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 8000; c.readTimeout = 8000
            c.setRequestProperty("Authorization", "Bearer $token")
            if (c.responseCode == 200) {
                JSONObject(c.inputStream.bufferedReader().readText()).optJSONObject("profile")
                    ?.optString("onlineId")?.takeIf { it.isNotBlank() }
            } else null
        } finally { c.disconnect() }
    } catch (e: Exception) { null }

    private fun open(url: String, method: String, body: String?): JSONObject {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = method
            c.connectTimeout = 10_000; c.readTimeout = 15_000
            c.setRequestProperty("Authorization", basic())
            if (body != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                c.outputStream.use { it.write(body.toByteArray(Charsets.US_ASCII)) }
            }
            val code = c.responseCode
            if (code != 200) throw IllegalStateException(
                "Sony isteği reddetti (HTTP $code). Giriş kodunun süresi dolmuş olabilir; girişi baştan yap.")
            return JSONObject(c.inputStream.bufferedReader().readText())
        } finally { c.disconnect() }
    }
}
