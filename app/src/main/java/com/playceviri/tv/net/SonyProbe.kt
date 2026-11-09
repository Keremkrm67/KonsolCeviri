package com.playceviri.tv.net

import android.os.SystemClock
import androidx.webkit.WebViewFeature
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.util.Date
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/** "Bağlantı testi": Sony giriş adreslerine DNS / TCP / TLS / HTTPS denemesi. */
object SonyProbe {
    private val hosts = listOf("auth.api.sonyentertainmentnetwork.com", "ca.account.sony.com", "remoteplay.dl.playstation.net",
        "translate.googleapis.com", "api.mymemory.translated.net")
    private val ipRe = Regex("\\d+\\.\\d+\\.\\d+\\.\\d+")

    fun run(): String {
        val sb = StringBuilder("Tarih/saat: ").append(Date()).append("\nVekil desteği (WebView): ")
        sb.append(runCatching { WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE) }.getOrDefault(false))
        sb.append("\nİnternet (1.1.1.1:443 TCP): ").append(tcp("1.1.1.1", 443)).append("\n\n")
        for (h in hosts) {
            sb.append("■ ").append(h).append("\n")
            val local = try { InetAddress.getAllByName(h).mapNotNull { it.hostAddress } } catch (e: Exception) { listOf("HATA: ${e.javaClass.simpleName}") }
            sb.append("  Cihaz DNS: ").append(local.joinToString()).append("\n")
            val secure = try { doh(h) } catch (e: Exception) { listOf("HATA: ${e.javaClass.simpleName}") }
            sb.append("  Güvenli DNS: ").append(secure.joinToString()).append("\n")
            val ip = secure.firstOrNull { ipRe.matches(it) } ?: local.firstOrNull { ipRe.matches(it) }
            if (ip == null) sb.append("  IPv4 adres yok, bağlantı denenmedi\n\n")
            else sb.append("  TCP $ip:443: ").append(tcp(ip, 443)).append("\n  TLS (adla): ").append(tls(ip, h))
                .append("\n  HTTPS GET /: ").append(https(h)).append("\n\n")
        }
        return sb.toString()
    }

    private fun tcp(ip: String, port: Int): String = try {
        val t = SystemClock.elapsedRealtime()
        Socket().use { it.connect(InetSocketAddress(ip, port), 6000) }
        "tamam (${SystemClock.elapsedRealtime() - t} ms)"
    } catch (e: Exception) { "BAŞARISIZ (${e.javaClass.simpleName})" }

    private fun tls(ip: String, host: String): String = try {
        val t = SystemClock.elapsedRealtime()
        val raw = Socket(); raw.connect(InetSocketAddress(ip, 443), 6000); raw.soTimeout = 6000
        val s = (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(raw, host, 443, true) as SSLSocket
        s.startHandshake(); s.close()
        "tamam (${SystemClock.elapsedRealtime() - t} ms)"
    } catch (e: Exception) { "BAŞARISIZ (${e.javaClass.simpleName}: ${e.message?.take(60)})" }

    private fun https(host: String): String = try {
        val t = SystemClock.elapsedRealtime()
        val c = URL("https://$host/").openConnection() as HttpURLConnection
        c.connectTimeout = 6000; c.readTimeout = 6000; c.instanceFollowRedirects = false
        val code = c.responseCode; c.disconnect()
        "HTTP $code (${SystemClock.elapsedRealtime() - t} ms)"
    } catch (e: Exception) { "BAŞARISIZ (${e.javaClass.simpleName}: ${e.message?.take(60)})" }

    private fun doh(host: String): List<String> {
        val c = URL("https://1.1.1.1/dns-query?name=$host&type=A").openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 4000; c.readTimeout = 4000
            c.setRequestProperty("accept", "application/dns-json")
            val a = JSONObject(c.inputStream.bufferedReader().readText()).optJSONArray("Answer") ?: return listOf("(yanıt yok)")
            return (0 until a.length()).map { a.getJSONObject(it) }.filter { it.optInt("type") == 1 }.map { it.getString("data") }
        } finally { c.disconnect() }
    }
}
