package com.playceviri.tv.browser

import com.playceviri.tv.display.DisplayProtocol
import com.playceviri.tv.util.DiagLog
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import kotlin.concurrent.thread

/**
 * TV'deki küçük web sunucusu. Telefon QR kodu okutunca tarayıcıda okuma sayfası açılır;
 * sayfa ve okuma motoru (Tesseract, WebAssembly) TV'den yüklenir, internet gerekmez.
 * Sayfa WebSocket ile bağlanır: TV kırpılmış altyazı görüntüsünü gönderir, sayfa okunan satırları geri yollar.
 */
class BrowserHub(
    private val assets: (String) -> ByteArray,
    private val token: String,
    private val onMessage: (JSONObject) -> Unit,
    private val onClient: (connected: Boolean, ua: String) -> Unit,
    /** Telefondaki çevrimdışı çeviri modeli dosyaları (model/lex/vocab); yoksa null. */
    private val model: ((String) -> ByteArray?)? = null,
) {
    companion object {
        const val PORT = 48080
        private const val GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
    }

    private var server: ServerSocket? = null
    @Volatile private var running = false
    @Volatile private var client: Socket? = null
    private val writeLock = Any()
    val port: Int get() = server?.localPort ?: 0
    val connected: Boolean get() = client != null

    fun url(): String? = DisplayProtocol.localIp()?.let { "http://$it:$port/$token" }

    fun start(): Boolean {
        if (running) return true
        val s = runCatching { ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(PORT)) } }.getOrNull()
            ?: runCatching { ServerSocket(0) }.getOrNull() ?: return false
        server = s; running = true
        thread(isDaemon = true, name = "playceviri-web") {
            while (running) {
                val c = try { s.accept() } catch (e: Exception) { break }
                thread(isDaemon = true, name = "playceviri-web-istek") { runCatching { handle(c) }; if (client !== c) gracefulClose(c) }
            }
        }
        return true
    }

    fun stop() {
        running = false
        runCatching { client?.close() }
        runCatching { server?.close() }
    }

    // ───────────── HTTP ─────────────

    private fun handle(sock: Socket) {
        sock.soTimeout = 15_000
        val input = sock.getInputStream()
        val head = readHead(input) ?: return
        val lines = head.split("\r\n")
        val parts = lines[0].split(" ")
        if (parts.size < 2) return
        val path = parts[1].substringBefore('?')
        val headers = lines.drop(1).mapNotNull { l -> l.indexOf(':').takeIf { it > 0 }?.let { l.substring(0, it).trim().lowercase() to l.substring(it + 1).trim() } }.toMap()
        val out = sock.getOutputStream()
        val base = "/$token"
        when {
            path == "/favicon.ico" -> respond(out, 404, "text/plain", ByteArray(0))
            !path.startsWith(base) -> respond(out, 404, "text/html; charset=utf-8",
                "<meta name=viewport content='width=device-width'><body style='background:#12161D;color:#F1F3F5;font:16px system-ui;padding:24px'>Adres eski. TV'deki QR kodu yeniden okut.</body>".toByteArray())
            path == "$base/ws" && headers["upgrade"].equals("websocket", true) -> websocket(sock, input, out, headers)
            path == base || path == "$base/" -> respond(out, 200, "text/html; charset=utf-8", asset("sayfa.html").toString(Charsets.UTF_8).replace("{{TOKEN}}", token).toByteArray())
            path.startsWith("$base/m/") -> {
                val name = path.substringAfterLast('/')
                val data = model?.invoke(name) ?: run { respond(out, 404, "text/plain", "model yok".toByteArray()); return }
                respond(out, 200, "application/octet-stream", data, cache = true)
            }
            path.startsWith("$base/t/") -> {
                val name = path.substringAfterLast('/')
                if (!Regex("[A-Za-z0-9._-]+").matches(name)) { respond(out, 404, "text/plain", ByteArray(0)); return }
                val gz = headers["accept-encoding"]?.contains("gzip") == true
                val packed = if (gz) runCatching { asset("$name.gzenc") }.getOrNull() else null
                val data = packed ?: runCatching { asset(name) }.getOrNull() ?: run { respond(out, 404, "text/plain", ByteArray(0)); return }
                val type = when {
                    name.endsWith(".js") -> "application/javascript"
                    name.endsWith(".mp4") -> "video/mp4"
                    name.endsWith(".wasm") -> "application/wasm"
                    else -> "application/octet-stream"
                }
                respond(out, 200, type, data, cache = true, gzip = packed != null)
            }
            else -> respond(out, 404, "text/plain", ByteArray(0))
        }
    }

    private fun asset(name: String): ByteArray = assets(name)

    /**
     * Yanıt bittikten sonra bağlantıyı sert kapatmak (RST) yavaş Wi-Fi'da telefonun dosyayı yarım almasına yol açar
     * (Safari: "Load failed"). Önce gönderim tarafı kapatılır, telefon bağlantıyı kapatana kadar beklenir.
     */
    private fun gracefulClose(c: Socket) {
        runCatching {
            c.getOutputStream().flush()
            c.shutdownOutput()
            c.soTimeout = 3000
            val inp = c.getInputStream(); val buf = ByteArray(1024)
            while (inp.read(buf) >= 0) {}
        }
        runCatching { c.close() }
    }

    private fun respond(out: OutputStream, code: Int, type: String, body: ByteArray, cache: Boolean = false, gzip: Boolean = false) {
        val status = when (code) { 200 -> "OK"; 404 -> "Not Found"; else -> "Error" }
        val h = "HTTP/1.1 $code $status\r\nContent-Type: $type\r\nContent-Length: ${body.size}\r\n" +
            (if (cache) "Cache-Control: max-age=86400\r\n" else "Cache-Control: no-store\r\n") +
            (if (gzip) "Content-Encoding: gzip\r\nVary: Accept-Encoding\r\n" else "") +
            "Access-Control-Allow-Origin: *\r\nConnection: close\r\n\r\n"
        out.write(h.toByteArray())
        var off = 0
        while (off < body.size) { val n = minOf(64 * 1024, body.size - off); out.write(body, off, n); off += n }
        out.flush()
    }

    private fun readHead(input: InputStream): String? {
        val b = ByteArrayOutputStream(); var last = 0
        while (b.size() < 16384) {
            val r = input.read(); if (r < 0) return null
            b.write(r); last = (last shl 8) or r
            if (last == 0x0D0A0D0A) return b.toString("UTF-8")
        }
        return null
    }

    // ───────────── WebSocket ─────────────

    private fun websocket(sock: Socket, input: InputStream, out: OutputStream, headers: Map<String, String>) {
        val key = headers["sec-websocket-key"] ?: return
        val accept = java.util.Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1").digest((key + GUID).toByteArray()))
        out.write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: $accept\r\n\r\n").toByteArray())
        out.flush()
        sock.soTimeout = 12_000
        sock.tcpNoDelay = true
        val old = client
        client = sock
        runCatching { old?.close() }
        val ua = headers["user-agent"]?.let { uaName(it) } ?: "telefon"
        onClient(true, ua)
        DiagLog.add("tarayıcı bağlandı ($ua)")
        val din = DataInputStream(input)
        try {
            while (running && client === sock) {
                val b0 = din.readUnsignedByte(); val b1 = din.readUnsignedByte()
                val op = b0 and 0x0F
                var len = (b1 and 0x7F).toLong()
                if (len == 126L) len = din.readUnsignedShort().toLong() else if (len == 127L) len = din.readLong()
                if (len > 4_000_000) break
                val mask = if (b1 and 0x80 != 0) ByteArray(4).also { din.readFully(it) } else null
                val data = ByteArray(len.toInt()); din.readFully(data)
                if (mask != null) for (i in data.indices) data[i] = (data[i].toInt() xor mask[i and 3].toInt()).toByte()
                when (op) {
                    0x1 -> runCatching { JSONObject(String(data, Charsets.UTF_8)) }.getOrNull()?.let { m ->
                        if (m.optString("t") == "ping") sendText("{\"t\":\"pong\"}") else onMessage(m)
                    }
                    0x8 -> break
                    0x9 -> frame(0xA, data)
                }
            }
        } catch (e: Exception) {
            if (running) DiagLog.add("tarayıcı bağlantısı koptu (${e.javaClass.simpleName})")
        } finally {
            runCatching { sock.close() }
            if (client === sock) { client = null; onClient(false, ua) }
        }
    }

    private fun uaName(ua: String) = when {
        "iPhone" in ua -> "iPhone"; "iPad" in ua -> "iPad"; "Android" in ua -> "Android telefon"; else -> "tarayıcı"
    }

    private fun frame(op: Int, payload: ByteArray): Boolean {
        val s = client ?: return false
        return try {
            synchronized(writeLock) {
                val o = s.getOutputStream()
                val hdr = ByteArrayOutputStream()
                hdr.write(0x80 or op)
                val n = payload.size
                when {
                    n < 126 -> hdr.write(n)
                    n < 65536 -> { hdr.write(126); hdr.write(n ushr 8); hdr.write(n and 0xFF) }
                    else -> { hdr.write(127); for (i in 7 downTo 0) hdr.write(((n.toLong() ushr (8 * i)) and 0xFF).toInt()) }
                }
                o.write(hdr.toByteArray()); o.write(payload); o.flush()
            }
            true
        } catch (e: Exception) { runCatching { s.close() }; false }
    }

    fun sendText(s: String) = frame(0x1, s.toByteArray(Charsets.UTF_8))
    fun send(o: JSONObject) = sendText(o.toString())

    /** Görüntü: [sıra int][en int][boy int][biçim bayt: 1 = 1 bit siyah-beyaz, 0 = gri] + veri. */
    fun sendImage(seq: Int, w: Int, h: Int, packed: Boolean, data: ByteArray): Boolean {
        val b = java.nio.ByteBuffer.allocate(13 + data.size)
        b.putInt(seq).putInt(w).putInt(h).put(if (packed) 1 else 0).put(data)
        return frame(0x2, b.array())
    }
}
