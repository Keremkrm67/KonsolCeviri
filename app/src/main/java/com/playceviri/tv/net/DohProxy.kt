package com.playceviri.tv.net

import com.playceviri.tv.util.DiagLog
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

/**
 * Giriş sayfası için yerel HTTPS (CONNECT) vekili. Adları güvenli DNS (DoH) ile çözer;
 * böylece ev ağının DNS'i Sony adreslerini engellese de giriş sayfası açılır.
 */
class DohProxy {
    private var server: ServerSocket? = null
    @Volatile private var running = false
    private val cache = ConcurrentHashMap<String, Pair<Long, List<String>>>()
    private val seen = ConcurrentHashMap.newKeySet<String>()
    val port: Int get() = server?.localPort ?: 0

    fun start(): Boolean = try {
        val s = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        server = s; running = true
        thread(isDaemon = true, name = "playceviri-doh") {
            while (running) {
                val c = try { s.accept() } catch (e: Exception) { break }
                thread(isDaemon = true) { runCatching { handle(c) }; runCatching { c.close() } }
            }
        }
        true
    } catch (e: Exception) { false }

    fun stop() { running = false; runCatching { server?.close() } }

    private fun handle(client: Socket) {
        client.soTimeout = 30_000
        val head = readHead(client.getInputStream()) ?: return
        val parts = head.lineSequence().first().split(" ")
        if (parts.size < 2 || !parts[0].equals("CONNECT", true)) {
            client.getOutputStream().write("HTTP/1.1 405 Method Not Allowed\r\nConnection: close\r\n\r\n".toByteArray()); return
        }
        val host = parts[1].substringBeforeLast(':')
        val port = parts[1].substringAfterLast(':').toIntOrNull() ?: 443
        val up = connect(host, port)
        if (up == null) {
            client.getOutputStream().write("HTTP/1.1 502 Bad Gateway\r\nConnection: close\r\n\r\n".toByteArray()); return
        }
        client.getOutputStream().write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray())
        client.soTimeout = 0; up.soTimeout = 0
        val t = thread(isDaemon = true) { pipe(up.getInputStream(), client.getOutputStream()); runCatching { client.shutdownOutput() } }
        pipe(client.getInputStream(), up.getOutputStream())
        runCatching { up.shutdownOutput() }
        t.join(30_000)
        runCatching { up.close() }
    }

    private fun connect(host: String, port: Int): Socket? {
        for (ip in resolve(host)) {
            try {
                val s = Socket(); s.connect(InetSocketAddress(ip, port), 8000)
                if (seen.add("ok:$host")) DiagLog.add("vekil: $host → $ip tamam")
                return s
            } catch (_: Exception) {}
        }
        if (seen.add("fail:$host")) DiagLog.add("vekil: $host → BAŞARISIZ (adresler: ${resolve(host).joinToString().ifEmpty { "yok" }})")
        return null
    }

    private fun resolve(host: String): List<String> {
        if (Regex("\\d{1,3}(\\.\\d{1,3}){3}").matches(host)) return listOf(host)
        cache[host]?.let { if (System.currentTimeMillis() < it.first) return it.second }
        val out = LinkedHashSet<String>()
        for (u in listOf("https://1.1.1.1/dns-query?name=%s&type=A", "https://8.8.8.8/resolve?name=%s&type=A")) {
            runCatching { out.addAll(doh(String.format(u, host))) }
            if (out.isNotEmpty()) break
        }
        if (out.isEmpty()) runCatching { InetAddress.getAllByName(host).mapNotNull { it.hostAddress } }.getOrNull()?.let { out.addAll(it) }
        val list = out.toList()
        if (list.isNotEmpty()) cache[host] = (System.currentTimeMillis() + 300_000) to list
        return list
    }

    private fun doh(url: String): List<String> {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 4000; c.readTimeout = 4000
            c.setRequestProperty("accept", "application/dns-json")
            val a = JSONObject(c.inputStream.bufferedReader().readText()).optJSONArray("Answer") ?: return emptyList()
            return (0 until a.length()).map { a.getJSONObject(it) }.filter { it.optInt("type") == 1 }.map { it.getString("data") }
        } finally { c.disconnect() }
    }

    private fun pipe(from: InputStream, to: OutputStream) {
        val buf = ByteArray(16 * 1024)
        try { while (true) { val n = from.read(buf); if (n < 0) break; to.write(buf, 0, n); to.flush() } } catch (_: Exception) {}
    }

    private fun readHead(input: InputStream): String? {
        val b = ByteArrayOutputStream()
        while (b.size() < 8192) {
            val r = input.read(); if (r < 0) return null
            b.write(r)
            val s = b.size()
            if (s >= 4) {
                val a = b.toByteArray()
                if (a[s - 4] == '\r'.code.toByte() && a[s - 3] == '\n'.code.toByte() && a[s - 2] == '\r'.code.toByte() && a[s - 1] == '\n'.code.toByte())
                    return b.toString("UTF-8")
            }
        }
        return null
    }
}
