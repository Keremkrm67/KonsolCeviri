package com.playceviri.tv.display

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.SystemClock
import com.playceviri.tv.util.DiagLog
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * Telefon ↔ TV ekranı bağlantısı: yerel ağda TCP üzerinden satır satır JSON.
 * Mesajlar: hello{code}, welcome{name}, denied, sub{text,orig,ms,top,left,width}, clear, style{...},
 * status{phase,msg}, ping, pong. TV kendini NSD ile "_playceviri._tcp" olarak duyurur.
 */
object DisplayProtocol {
    const val SERVICE_TYPE = "_playceviri._tcp."
    const val DEFAULT_PORT = 47999
    const val VERSION = 2

    fun localIp(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }.filterIsInstance<Inet4Address>()
            .firstOrNull { it.isSiteLocalAddress }?.hostAddress
    }.getOrNull()

    fun deviceName(): String = "${Build.MANUFACTURER} ${Build.MODEL}".trim().replaceFirstChar { it.uppercase() }
}

/** NSD ile ağdaki Konsol Çeviri ekranlarını bulur. */
object DisplayFinder {
    data class Found(val name: String, val host: String, val port: Int)

    fun find(context: Context, timeoutMs: Long = 4000, wanted: String? = null): List<Found> {
        val nsd = context.getSystemService(NsdManager::class.java) ?: return emptyList()
        val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
        val lock = runCatching { wifi?.createMulticastLock("playceviri-bul")?.apply { setReferenceCounted(false); acquire() } }.getOrNull()
        val infos = mutableListOf<NsdServiceInfo>()
        val done = CountDownLatch(1)
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) { done.countDown() }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onServiceFound(info: NsdServiceInfo) {
                synchronized(infos) { infos.add(info) }
                if (wanted != null && info.serviceName == wanted) done.countDown()
            }
            override fun onServiceLost(info: NsdServiceInfo) {}
        }
        try {
            nsd.discoverServices(DisplayProtocol.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
            done.await(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: Exception) { DiagLog.add("TV arama hatası: ${e.javaClass.simpleName}") }
        runCatching { nsd.stopServiceDiscovery(listener) }
        val out = mutableListOf<Found>()
        val list = synchronized(infos) { infos.distinctBy { it.serviceName } }
        for (info in list) {
            if (wanted != null && info.serviceName != wanted) continue
            val latch = CountDownLatch(1)
            val ref = AtomicReference<NsdServiceInfo?>()
            @Suppress("DEPRECATION")
            runCatching {
                nsd.resolveService(info, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(si: NsdServiceInfo, errorCode: Int) { latch.countDown() }
                    override fun onServiceResolved(si: NsdServiceInfo) { ref.set(si); latch.countDown() }
                })
            }
            latch.await(2500, TimeUnit.MILLISECONDS)
            ref.get()?.let { si ->
                @Suppress("DEPRECATION")
                val host = si.host?.hostAddress
                if (host != null) out.add(Found(si.serviceName, host, si.port))
            }
        }
        runCatching { lock?.release() }
        return out
    }
}

/** TV tarafı: telefonun bağlandığı sunucu. Aynı anda tek telefon; yenisi gelirse eskisi kapanır. */
class DisplayServer(
    private val context: Context,
    private val code: () -> String,
    private val onMessage: (JSONObject) -> Unit,
    private val onClient: (connected: Boolean, name: String) -> Unit,
) {
    private var server: ServerSocket? = null
    @Volatile private var running = false
    @Volatile private var current: Socket? = null
    private var nsdListener: NsdManager.RegistrationListener? = null
    val port: Int get() = server?.localPort ?: 0
    @Volatile var registeredName: String = ""; private set

    fun start(): Boolean {
        if (running) return true
        val s = runCatching { ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(DisplayProtocol.DEFAULT_PORT)) } }.getOrNull()
            ?: runCatching { ServerSocket(0) }.getOrNull() ?: return false
        server = s; running = true
        thread(isDaemon = true, name = "playceviri-ekran") {
            while (running) {
                val c = try { s.accept() } catch (e: Exception) { break }
                thread(isDaemon = true, name = "playceviri-ekran-istemci") { serve(c) }
            }
        }
        register()
        return true
    }

    private fun register() {
        val nsd = context.getSystemService(NsdManager::class.java) ?: return
        val info = NsdServiceInfo().apply {
            serviceName = "Konsol Çeviri · ${DisplayProtocol.deviceName()}"
            serviceType = DisplayProtocol.SERVICE_TYPE
            port = this@DisplayServer.port
        }
        val l = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(si: NsdServiceInfo) { registeredName = si.serviceName; DiagLog.add("ekran ağda duyuruldu: ${si.serviceName}") }
            override fun onRegistrationFailed(si: NsdServiceInfo, errorCode: Int) { DiagLog.add("ekran duyurulamadı ($errorCode)") }
            override fun onServiceUnregistered(si: NsdServiceInfo) {}
            override fun onUnregistrationFailed(si: NsdServiceInfo, errorCode: Int) {}
        }
        runCatching { nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, l); nsdListener = l }
    }

    fun stop() {
        running = false
        runCatching { current?.close() }
        runCatching { server?.close() }
        nsdListener?.let { l -> runCatching { context.getSystemService(NsdManager::class.java)?.unregisterService(l) } }
        nsdListener = null
    }

    private fun serve(sock: Socket) {
        var name = "telefon"
        try {
            sock.soTimeout = 9000
            sock.tcpNoDelay = true
            val input = BufferedReader(InputStreamReader(sock.getInputStream(), Charsets.UTF_8))
            val out = OutputStreamWriter(sock.getOutputStream(), Charsets.UTF_8)
            val hello = JSONObject(input.readLine() ?: return)
            if (hello.optString("t") != "hello" || hello.optString("code") != code()) {
                out.write(JSONObject().put("t", "denied").put("why", "kod yanlış").toString() + "\n"); out.flush()
                DiagLog.add("ekran: yanlış kodla bağlanma denemesi")
                return
            }
            name = hello.optString("name", "telefon")
            out.write(JSONObject().put("t", "welcome").put("name", DisplayProtocol.deviceName()).put("v", DisplayProtocol.VERSION).toString() + "\n"); out.flush()
            val old = current
            current = sock
            runCatching { old?.close() }
            onClient(true, name)
            DiagLog.add("ekran: telefon bağlandı ($name)")
            while (running) {
                val line = input.readLine() ?: break
                val m = runCatching { JSONObject(line) }.getOrNull() ?: continue
                if (m.optString("t") == "ping") { out.write("{\"t\":\"pong\"}\n"); out.flush(); continue }
                onMessage(m)
            }
        } catch (e: Exception) {
            if (running) DiagLog.add("ekran: bağlantı koptu (${e.javaClass.simpleName})")
        } finally {
            runCatching { sock.close() }
            if (current === sock) { current = null; onClient(false, name) }
        }
    }
}

/**
 * Telefon tarafı: TV ekranına bağlanır, kopunca kendiliğinden yeniden bağlanır.
 * Gönderimler tek iş parçacığında; bağlantı yokken yalnız son altyazı ve son stil saklanır.
 */
class DisplayClient(
    private val context: Context,
    private val host: () -> String,
    private val port: () -> Int,
    private val wantedName: () -> String,
    private val code: () -> String,
    private val onState: (State, String) -> Unit,
) {
    enum class State { OFF, SEARCHING, CONNECTING, CONNECTED, DENIED }

    private val exec = Executors.newSingleThreadExecutor()
    @Volatile private var sock: Socket? = null
    @Volatile private var writer: OutputStreamWriter? = null
    @Volatile private var running = false
    @Volatile private var lastSub: JSONObject? = null
    @Volatile private var lastStyle: JSONObject? = null
    @Volatile var state = State.OFF; private set
    @Volatile private var lastPong = 0L
    private var loop: Thread? = null

    private fun set(s: State, msg: String) { state = s; onState(s, msg) }

    fun start() {
        if (running) return
        running = true
        loop = thread(isDaemon = true, name = "playceviri-tv-baglanti") { runLoop() }
    }

    fun stop() {
        running = false
        runCatching { sock?.close() }
        loop?.interrupt()
        set(State.OFF, "kapalı")
    }

    fun shutdown() { stop(); exec.shutdownNow() }

    private fun runLoop() {
        var attempt = 0
        while (running) {
            var h = host(); var p = port().takeIf { it > 0 } ?: DisplayProtocol.DEFAULT_PORT
            if (h.isBlank() || attempt % 3 == 2) {
                set(State.SEARCHING, "TV ekranı aranıyor…")
                val found = DisplayFinder.find(context, 4000, wantedName().ifBlank { null })
                    .ifEmpty { if (wantedName().isNotBlank()) DisplayFinder.find(context, 3000) else emptyList() }
                found.firstOrNull()?.let { h = it.host; p = it.port }
            }
            if (h.isBlank()) { attempt++; sleep(3000); continue }
            set(State.CONNECTING, "TV'ye bağlanılıyor ($h)…")
            try {
                val s = Socket()
                s.connect(InetSocketAddress(h, p), 4000)
                s.tcpNoDelay = true
                s.soTimeout = 8000
                val w = OutputStreamWriter(s.getOutputStream(), Charsets.UTF_8)
                val r = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8))
                w.write(JSONObject().put("t", "hello").put("code", code()).put("name", DisplayProtocol.deviceName()).put("v", DisplayProtocol.VERSION).toString() + "\n"); w.flush()
                val resp = JSONObject(r.readLine() ?: throw java.io.IOException("yanıt yok"))
                if (resp.optString("t") == "denied") {
                    set(State.DENIED, "TV kodu kabul etmedi. TV ekranındaki 4 haneli kodu yeniden gir.")
                    runCatching { s.close() }
                    sleep(8000); continue
                }
                sock = s; writer = w; attempt = 0
                lastPong = SystemClock.elapsedRealtime()
                set(State.CONNECTED, "TV bağlı: ${resp.optString("name", "TV")}")
                lastStyle?.let { send(it) }
                lastSub?.let { send(it) }
                val pinger = thread(isDaemon = true) {
                    while (running && sock === s) {
                        sleep(2000)
                        send(JSONObject().put("t", "ping"))
                        if (SystemClock.elapsedRealtime() - lastPong > 9000) { runCatching { s.close() }; break }
                    }
                }
                while (running) {
                    val line = r.readLine() ?: break
                    if (line.contains("pong")) lastPong = SystemClock.elapsedRealtime()
                }
                pinger.interrupt()
            } catch (e: Exception) {
                if (running) DiagLog.add("TV bağlantısı: ${e.javaClass.simpleName}")
            }
            sock = null; writer = null
            if (!running) break
            attempt++
            set(State.CONNECTING, "TV bağlantısı koptu, yeniden deneniyor…")
            sleep((1000L * attempt).coerceAtMost(5000))
        }
    }

    private fun sleep(ms: Long) = try { Thread.sleep(ms) } catch (e: InterruptedException) {}

    fun send(o: JSONObject) {
        when (o.optString("t")) { "sub", "clear" -> lastSub = o; "style" -> lastStyle = o }
        if (exec.isShutdown) return
        exec.execute {
            val w = writer ?: return@execute
            try { w.write(o.toString() + "\n"); w.flush() } catch (e: Exception) { runCatching { sock?.close() } }
        }
    }

    fun sendSub(text: String, orig: String, top: Float? = null, left: Float? = null, width: Float? = null) {
        val o = JSONObject().put("t", "sub").put("text", text).put("orig", orig)
        if (top != null) o.put("top", top.toDouble()).put("left", (left ?: 0f).toDouble()).put("width", (width ?: 1f).toDouble())
        send(o)
    }
    fun sendClear() = send(JSONObject().put("t", "clear"))
    fun sendStyle(style: JSONObject, preview: Boolean) = send(JSONObject().put("t", "style").put("s", style).put("preview", preview))
    fun sendStatus(phase: String, msg: String) {
        if (exec.isShutdown) return
        val o = JSONObject().put("t", "status").put("phase", phase).put("msg", msg)
        exec.execute { val w = writer ?: return@execute; runCatching { w.write(o.toString() + "\n"); w.flush() } }
    }
}
