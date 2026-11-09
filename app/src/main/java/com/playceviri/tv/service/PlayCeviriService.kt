package com.playceviri.tv.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.Rect
import android.media.ImageReader
import android.net.wifi.WifiManager
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import com.metallic.chiaki.lib.ConnectInfo
import com.metallic.chiaki.lib.ConnectedEvent
import com.metallic.chiaki.lib.CreateError
import com.metallic.chiaki.lib.LoginPinRequestEvent
import com.metallic.chiaki.lib.QuitEvent
import com.metallic.chiaki.lib.Session
import com.playceviri.tv.browser.BrowserHub
import com.playceviri.tv.data.SecretStore
import com.playceviri.tv.data.Settings
import com.playceviri.tv.display.DisplayClient
import com.playceviri.tv.link.ConsoleLink
import com.playceviri.tv.overlay.SubtitleOverlay
import com.playceviri.tv.overlay.SubtitleView
import com.playceviri.tv.ui.MainActivity
import com.playceviri.tv.util.DiagLog
import com.playceviri.tv.util.Qr
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import com.playceviri.tv.vision.ChangeGate
import com.playceviri.tv.vision.Gray
import com.playceviri.tv.vision.GrayImage
import com.playceviri.tv.vision.SubtitleLocator
import com.playceviri.tv.vision.TextFilter
import com.playceviri.tv.worker.EngineGuard
import com.playceviri.tv.worker.WorkerClient
import com.playceviri.tv.worker.WorkerProtocol
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * PS5'e ikinci bir Remote Play oturumu olarak bağlanır, görüntüyü ekrana çizmeden alır,
 * seçili bölgedeki altyazıyı okur, çevirir ve sonucu
 *  - "Telefon" görevinde: TV ekranına (ve telefondaki uygulamaya),
 *  - "Tek başına TV" görevinde: TV'deki katmana gönderir.
 */
class PlayCeviriService : Service() {
    companion object {
        const val ACTION_START = "com.playceviri.tv.START"
        const val ACTION_STOP = "com.playceviri.tv.STOP"
        const val ACTION_RECONNECT = "com.playceviri.tv.RECONNECT"
        const val ACTION_SNAPSHOT = "com.playceviri.tv.SNAPSHOT"
        const val ACTION_STYLE = "com.playceviri.tv.STYLE"
        private const val CHANNEL = "playceviri_servis"
        private const val NOTE_ID = 7

        @Volatile var running = false; private set
        @Volatile var currentGameId: String = ""; private set
        @Volatile var currentGameName: String = ""; private set
        /** TV + tarayıcı görevinde telefonun okutacağı adres ve bağlantı durumu. */
        @Volatile var browserUrl: String = ""; private set
        @Volatile var browserState: String = ""; private set

        fun start(c: Context) = c.startForegroundService(Intent(c, PlayCeviriService::class.java).setAction(ACTION_START))
        fun stop(c: Context) = c.startService(Intent(c, PlayCeviriService::class.java).setAction(ACTION_STOP))
        /** Ayar değişti: TV'ye yeni stili gönder, istenirse örnek altyazıyla önizlet. */
        fun pushStyle(c: Context, preview: Boolean) {
            if (running) c.startService(Intent(c, PlayCeviriService::class.java).setAction(ACTION_STYLE).putExtra("preview", preview))
        }
        fun requestSnapshot(c: Context) {
            if (running) c.startService(Intent(c, PlayCeviriService::class.java).setAction(ACTION_SNAPSHOT))
        }
        fun snapshotFile(c: Context) = File(c.cacheDir, "son_kare.jpg")
    }

    private class Outcome(val reason: String, val connected: Boolean, val fatal: Boolean = false)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val main = Handler(Looper.getMainLooper())
    private lateinit var settings: Settings
    private lateinit var secrets: SecretStore
    private val modelStore by lazy { com.playceviri.tv.translate.ModelStore(this) }
    private lateinit var worker: WorkerClient
    private var overlay: SubtitleOverlay? = null
    private var display: DisplayClient? = null
    private var hub: BrowserHub? = null
    @Volatile private var hubReady = false
    private val ocrWaits = ConcurrentHashMap<Int, CompletableDeferred<JSONObject>>()
    private var role = Settings.Role.PHONE
    private var loopJob: Job? = null
    private var trJob: Job? = null
    private var gameJob: Job? = null
    private var warmJob: Job? = null
    @Volatile private var emptyReads = 0
    private var wake: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    // kare işleme durumu (kare iş parçacığı)
    private val gate = ChangeGate()
    private val busy = AtomicBoolean(false)
    private var lastSample = 0L
    private var lastSnapshot = 0L
    @Volatile private var snapshotWanted = true
    private var frameCount = 0L
    private var lastStat = 0L
    private val reads = ArrayDeque<Long>()
    private var region = floatArrayOf(0.03f, 0.55f, 0.97f, 0.99f)
    private var regionReload = 0L

    // metin durumu
    private data class Job2(val seq: Long, val text: String, val anchor: FloatArray?, val since: Long, val ocrMs: Long, val clearAfter: Boolean = false)

    // ───── okuma düzeltme + yarım cümle birleştirme ─────
    private val fixer by lazy { runCatching { com.playceviri.tv.vision.OcrFixer.load(assets.open("sozluk-en.txt").bufferedReader().readText()) }.getOrNull() }
    private var fixLogs = 0
    private class Held(val text: String, val anchor: FloatArray?, val since: Long, val ocrMs: Long)
    @Volatile private var held: Held? = null
    private var holdJob: kotlinx.coroutines.Job? = null

    /** Cümle bitmemiş mi? (virgül, üç nokta ya da noktalama yok) — oyun cümleyi iki parçada gösteriyor olabilir. */
    private fun unfinished(t: String): Boolean {
        val e = t.trimEnd().trimEnd('"', '\'', '”', '’', ')')
        if (e.endsWith("...") || e.endsWith("…")) return true
        val last = e.lastOrNull() ?: return false
        return last == ',' || last == ';' || last == ':' || last == '-' || last == '—' || last == '–' || last.isLetterOrDigit()
    }

    /** Okunan cümleyi çeviri sırasına verir; yarım cümleyi en çok 2,2 sn bekletip devamıyla birleştirir. */
    @Synchronized
    private fun submitLine(text0: String, anchor: FloatArray?, since: Long, ocrMs: Long) {
        var text = text0
        held?.let { h ->
            holdJob?.cancel(); held = null
            // oyun yazıyı uzatarak gösteriyorsa (daktilo), yeni metin eskisini içerir
            text = if (text.startsWith(h.text.trimEnd('.', '…', ' ')) || TextFilter.similar(text, h.text)) text else h.text + " " + text
        }
        if (unfinished(text) && text.length < 160 && role == Settings.Role.TV_BROWSER) {
            held = Held(text, anchor, since, ocrMs)
            holdJob = scope.launch { delay(2200); flushHold(false) }
            return
        }
        trChannel.trySend(Job2(seq.incrementAndGet(), text, anchor, since, ocrMs))
    }

    @Synchronized
    private fun flushHold(afterClear: Boolean) {
        val h = held ?: return
        held = null; holdJob?.cancel()
        trChannel.trySend(Job2(seq.incrementAndGet(), h.text, h.anchor, h.since, h.ocrMs, clearAfter = afterClear))
    }
    private val trChannel = Channel<Job2>(Channel.CONFLATED)
    private val seq = AtomicLong(0)
    @Volatile private var clearedAt = 0L
    @Volatile private var shownSeq = 0L
    @Volatile private var lastAccepted = ""
    @Volatile private var rejected = 0L
    @Volatile private var latencyLogs = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        DiagLog.attach(this)
        settings = Settings(this)
        secrets = SecretStore(this)
        worker = WorkerClient(this) { msg -> publish(Phase.STREAMING, msg) }
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { shutdown(); return START_NOT_STICKY }
            ACTION_RECONNECT -> { restartLoop(); return START_NOT_STICKY }
            ACTION_SNAPSHOT -> { snapshotWanted = true; return START_NOT_STICKY }
            "com.playceviri.tv.TVLINK" -> {
                display?.shutdown(); display = null
                if (running && role == Settings.Role.PHONE) startDisplayLink()
                return START_NOT_STICKY
            }
            ACTION_STYLE -> {
                overlay?.let { o -> main.post { o.refreshStyle(); if (intent.getBooleanExtra("preview", false)) o.show("Örnek altyazı burada görünür.", "This is how the subtitle will look.", fixedMs = 3000) } }
                display?.sendStyle(settings.styleJson(), intent.getBooleanExtra("preview", false))
                return START_NOT_STICKY
            }
        }
        promote("Başlatılıyor…")
        running = true
        restartLoop()
        return START_NOT_STICKY
    }

    private fun restartLoop() {
        loopJob?.cancel(); trJob?.cancel(); gameJob?.cancel()
        loopJob = scope.launch { connectLoop() }
    }

    private fun shutdown() {
        loopJob?.cancel(); trJob?.cancel(); gameJob?.cancel(); warmJob?.cancel()
        running = false
        scope.launch {
            withContext(NonCancellable) {
                display?.shutdown(); display = null
                hub?.stop(); hub = null; hubReady = false; browserUrl = ""; browserState = ""
                withContext(Dispatchers.Main) { overlay?.detach(); overlay = null }
                publish(Phase.IDLE, "Durduruldu")
                releaseLocks()
                withContext(Dispatchers.Main) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
            }
        }
    }

    override fun onDestroy() {
        running = false
        scope.cancel()
        main.post { overlay?.detach() }
        display?.shutdown()
        hub?.stop(); browserUrl = ""; browserState = ""
        worker.unbind()
        releaseLocks()
        super.onDestroy()
    }

    // ───────────────────────── bağlantı döngüsü ─────────────────────────

    private suspend fun connectLoop() {
        role = settings.role
        val host = settings.consoleHost
        if (host.isBlank()) { fail("PS5 adresi yok. Önce PS5 ile eşleştir."); return }
        val info = ConsoleLink.connectInfo(host, secrets, settings.quality == 1)
            ?: run { fail("PS5 ile eşleşmemiş. Önce \"PS5 ile eşleştir\"."); return }

        when (role) {
            Settings.Role.TV_SOLO, Settings.Role.TV_BROWSER -> {
                val ok = withContext(Dispatchers.Main) {
                    val o = overlay ?: SubtitleOverlay(this@PlayCeviriService, settings).also { overlay = it }
                    o.attach()
                }
                if (!ok) { fail("Üstte gösterme izni yok. Ana ekrandan izni aç."); return }
                if (role == Settings.Role.TV_BROWSER && !startHub()) { fail("Telefon sayfası açılamadı (ağ bağlantısı yok mu?)"); return }
            }
            Settings.Role.PHONE -> startDisplayLink()
            Settings.Role.TV_DISPLAY -> { fail("Bu cihazın görevi \"TV ekranı\". Çeviriyi telefondan başlat."); return }
        }
        acquireLocks()
        trJob = scope.launch { translateLoop() }
        warmupOffline()
        gameJob = scope.launch { gameWatch(host) }

        var since: Long? = null
        var tries = 0
        while (scope.isActive) {
            publish(Phase.WAKING, "PS5 uyandırılıyor…")
            ConsoleLink.wake(host, secrets)
            delay(if (tries == 0) 1500 else 4000)
            publish(if (tries == 0) Phase.CONNECTING else Phase.RECONNECTING, "PS5'e bağlanılıyor…")
            val out = runSession(info)
            if (out.fatal) { fail(out.reason); return }
            if (out.connected) { since = null; tries = 0 }
            val start = since ?: SystemClock.elapsedRealtime().also { since = it }
            val elapsed = SystemClock.elapsedRealtime() - start
            if (elapsed >= 30 * 60_000L) { fail("PS5'e 30 dakikadır bağlanılamadı, denemeyi bıraktım."); return }
            tries++
            val wait = if (elapsed < 5 * 60_000L) minOf(tries * 3000L, 10_000L) else 30_000L
            publish(Phase.RECONNECTING, "${out.reason} · ${wait / 1000} sn sonra yeniden denenecek")
            output(null)
            delay(wait)
        }
    }

    private suspend fun runSession(info: ConnectInfo): Outcome {
        val done = CompletableDeferred<Outcome>()
        var connected = false
        val (w, h) = if (settings.quality == 1) 1920 to 1080 else 1280 to 720
        val ft = HandlerThread("playceviri-kare").apply { start() }
        val reader = ImageReader.newInstance(w, h, ImageFormat.YUV_420_888, 3)
        resetFrameState()
        reader.setOnImageAvailableListener({ r -> onFrame(r) }, Handler(ft.looper))
        runCatching { DiagLog.sessionLogFile(this).delete() }
        val session = try {
            Session(info, DiagLog.sessionLogFile(this).path, false)
        } catch (e: CreateError) {
            reader.close(); ft.quitSafely()
            return Outcome("Bağlantı kurulamadı (${e.errorCode})", false)
        }
        session.eventCallback = { e ->
            when (e) {
                is ConnectedEvent -> {
                    connected = true
                    DiagLog.add("PS5 oturumu bağlandı")
                    publish(Phase.STREAMING, "Çeviri çalışıyor")
                }
                is LoginPinRequestEvent -> done.complete(Outcome(
                    "PS5, Remote Play hesabı için profil şifresi istiyor. PS5'te ikinci hesabın oturum açma şifresini kapat.", connected, true))
                is QuitEvent -> {
                    DiagLog.add("OTURUM KAPANDI: neden=${e.reason} metin=${e.reasonString} bağlıydı=$connected")
                    done.complete(Outcome(e.reasonString?.takeIf { it.isNotBlank() } ?: e.reason.toString(), connected))
                }
                else -> {}
            }
        }
        val err = session.start()
        if (!err.isSuccess) {
            withContext(Dispatchers.IO) { runCatching { session.dispose() } }
            reader.close(); ft.quitSafely()
            return Outcome("Bağlantı başlatılamadı ($err)", false)
        }
        session.setSurface(reader.surface)
        try {
            return done.await()
        } finally {
            withContext(NonCancellable + Dispatchers.IO) {
                runCatching { session.stop() }
                runCatching { session.dispose() }
                ft.quitSafely()
                runCatching { reader.close() }
            }
        }
    }

    // ───────────────────────── kare işleme ─────────────────────────

    private fun resetFrameState() {
        busy.set(false); lastSample = 0; gate.reset(); lastAccepted = ""
    }

    private val suppressor = com.playceviri.tv.vision.SceneSuppressor()
    @Volatile private var rawFallback: GrayImage? = null

    private fun onFrame(reader: ImageReader) {
        val img = try { reader.acquireLatestImage() } catch (e: Exception) { null } ?: return
        try {
            frameCount++
            val now = SystemClock.elapsedRealtime()
            if (now - lastStat >= 15_000) {
                val rt = Runtime.getRuntime()
                DiagLog.add("kare: $frameCount toplam, bellek ${(rt.totalMemory() - rt.freeMemory()) shr 20}/${rt.maxMemory() shr 20} MB")
                lastStat = now
            }
            val snapEvery = if (role == Settings.Role.TV_BROWSER && hubReady) 1000 else 5000
            if (snapshotWanted || now - lastSnapshot > snapEvery) {
                saveSnapshot(img); lastSnapshot = now; snapshotWanted = false
                if (role == Settings.Role.TV_BROWSER && hubReady) scope.launch { sendPreview() }
            }
            if (now - lastSample < 1000L / settings.rate.coerceAtLeast(1)) return
            if (busy.get()) return
            lastSample = now
            if (now - regionReload > 2000) { region = settings.region(currentGameId); regionReload = now }
            val rect = Gray.regionRect(region, img.width, img.height)
            val crop = Gray.crop(img, rect)
            suppressor.push(crop, now)
            when (gate.decide(crop, settings.settle)) {
                ChangeGate.Decision.SKIP -> {}
                ChangeGate.Decision.CLEAR -> { clearedAt = seq.incrementAndGet(); lastAccepted = ""; output(null); flushHold(true) }
                ChangeGate.Decision.READ -> {
                    // hareketli sahneyi karart (yerinde duran altyazı parlak kalır)
                    var g: GrayImage = if (role == Settings.Role.TV_BROWSER) suppressor.clean(crop, now) else crop
                    var raw: GrayImage? = if (g !== crop) crop else null
                    var r = rect
                    if (settings.autoLocate) SubtitleLocator.locate(crop)?.let { loc ->
                        g = Gray.sub(g, loc); raw = raw?.let { Gray.sub(it, loc) }
                        r = Rect(rect.left + loc.left, rect.top + loc.top, rect.left + loc.right, rect.top + loc.bottom)
                    }
                    busy.set(true)
                    try {
                        WorkerProtocol.imageFile(this).writeBytes(g.data)
                    } catch (e: Exception) { busy.set(false); return }
                    val anchor = floatArrayOf(r.top / img.height.toFloat(), r.left / img.width.toFloat(), r.width() / img.width.toFloat())
                    rawFallback = raw
                    scope.launch { readText(g.width, g.height, anchor, now) }
                }
            }
            Live.update { it.copy(skippedSame = gate.skippedSame) }
        } finally {
            img.close()
        }
    }

    private suspend fun readText(w: Int, h: Int, anchor: FloatArray, since: Long) {
        try {
            val res = if (role == Settings.Role.TV_BROWSER) readViaBrowser(w, h) else readViaWorker(w, h)
            countRead()
            if (res == null) { gate.forgetProcessed(); return }
            val (pairs, ocrMs) = res
            val lines = pairs.map { it.first }
            val v = TextFilter.clean(pairs, if (role == Settings.Role.TV_BROWSER) maxOf(settings.minConf, 55) else settings.minConf)
            Live.update { it.copy(ocrMs = ocrMs) }
            val text = v.text
            if (text == null) {
                // Bölge değişti ama okunabilir yazı yok: oyundaki altyazı kaybolmuş demektir.
                emptyReads = if (lines.isEmpty()) emptyReads + 2 else emptyReads + 1
                if (emptyReads >= 2 && (lastAccepted.isNotEmpty() || held != null)) {
                    clearedAt = seq.incrementAndGet(); lastAccepted = ""; output(null); flushHold(true)
                }
                if (lines.isNotEmpty()) {
                    rejected++
                    val raw = lines.joinToString(" ").take(80)
                    Live.update { it.copy(rejected = rejected, lastRejected = "$raw (${v.reason})") }
                }
                return
            }
            emptyReads = 0
            if (lastAccepted.isNotEmpty() && TextFilter.similar(text, lastAccepted)) return
            lastAccepted = text
            // okuma hatası düzeltme: düşen ince harfler ("wit" → "with", "do ars" → "dollars")
            val fx = fixer?.fix(text)
            val fixed = fx?.text ?: text
            if (fx != null && fx.changes.isNotEmpty()) {
                if (fixLogs < 20) { fixLogs++; DiagLog.add("okuma düzeltildi: ${fx.changes.joinToString(", ")}") }
                hub?.send(JSONObject().put("t", "fix").put("text", fixed).put("changes", fx.changes.joinToString(", ")))
            }
            submitLine(fixed, anchor, since, ocrMs)
        } finally {
            busy.set(false)
        }
    }

    private suspend fun readViaWorker(w: Int, h: Int): Pair<List<Pair<String, Float>>, Long>? {
        if (EngineGuard.OCR_MLKIT in EngineGuard.disabled(this)) {
            publish(Phase.STREAMING, "Yazı okuma bu cihazda çöküyor. \"TV + telefonda tarayıcı\" görevini kullan.")
            delay(3000); return null
        }
        val b = Bundle().apply { putInt(WorkerProtocol.K_W, w); putInt(WorkerProtocol.K_H, h); putInt(WorkerProtocol.K_PREP, settings.prep) }
        val r = worker.call(WorkerProtocol.MSG_OCR, b, 9000) ?: return null
        if (!r.getBoolean(WorkerProtocol.K_OK)) { reportError(r.getString(WorkerProtocol.K_ERROR)); return null }
        val lines = r.getStringArrayList(WorkerProtocol.K_LINES) ?: arrayListOf()
        val confs = r.getFloatArray(WorkerProtocol.K_CONFS) ?: FloatArray(lines.size) { -1f }
        return lines.mapIndexed { i, s -> s to (confs.getOrNull(i) ?: -1f) } to r.getLong(WorkerProtocol.K_OCR_MS)
    }

    /** TV + tarayıcı: kırpıntı telefondaki sayfaya gider, sayfa okunan satırları geri yollar. TV okumaz. */
    private suspend fun readViaBrowser(w: Int, h: Int): Pair<List<Pair<String, Float>>, Long>? {
        val hb = hub ?: return null
        if (!hb.connected || !hubReady) { delay(400); return null }
        val raw = runCatching { WorkerProtocol.imageFile(this).readBytes() }.getOrNull() ?: return null
        // Altyazıyı oyun sahnesinden ayır; altyazı yoksa telefona hiçbir şey gönderme (saçma okuma olmasın).
        // Önce sahnesi karartılmış kare; orada yazı yoksa (altyazı yeni belirmiş olabilir) ham kare.
        val iso = com.playceviri.tv.vision.SubtitleIsolator.isolate(GrayImage(raw, w, h))
            ?: rawFallback?.takeIf { it.width == w && it.height == h }?.let { com.playceviri.tv.vision.SubtitleIsolator.isolate(it) }
            ?: return emptyList<Pair<String, Float>>() to 0L
        val img = iso.image; val packed = true; val data = com.playceviri.tv.vision.SubtitleIsolator.pack(img)
        val id = seq.incrementAndGet().toInt()
        val wait = CompletableDeferred<JSONObject>()
        ocrWaits[id] = wait
        val t0 = SystemClock.elapsedRealtime()
        if (!hb.sendImage(id, img.width, img.height, packed, data)) { ocrWaits.remove(id); return null }
        val m = kotlinx.coroutines.withTimeoutOrNull(6000) { wait.await() }
        ocrWaits.remove(id)
        if (m == null) { reportError("telefon 6 sn içinde okumadı (sayfa arka planda mı?)"); return null }
        val arr = m.optJSONArray("lines")
        val out = ArrayList<Pair<String, Float>>()
        if (arr != null) for (i in 0 until arr.length()) arr.optJSONArray(i)?.let { out += it.optString(0) to it.optDouble(1, -1.0).toFloat() }
        val ms = m.optLong("ms", SystemClock.elapsedRealtime() - t0)
        Live.update { it.copy(engine = "telefon tarayıcısı (Tesseract)") }
        return out to ms
    }

    /** Telefondaki "PS5'ten gelen görüntü" ekranı için küçük kare (yakalamanın çalıştığını görmek için). */
    private fun sendPreview() {
        val f = snapshotFile(this)
        val b = runCatching { f.readBytes() }.getOrNull() ?: return
        hub?.send(JSONObject().put("t", "frame").put("jpg", android.util.Base64.encodeToString(b, android.util.Base64.NO_WRAP))
            .put("region", org.json.JSONArray(region.toList().map { it.toDouble() })))
    }

    private fun startHub(): Boolean {
        if (hub != null) return true
        val h = BrowserHub({ n -> assets.open("web/$n").use { it.readBytes() } }, settings.browserToken, { m -> onBrowserMessage(m) }, { connected, ua ->
            if (!connected) { hubReady = false; ocrWaits.values.forEach { it.cancel() }; trWaits.values.forEach { it.cancel() } }
            else phoneTrFails = 0
            browserState = if (connected) "$ua bağlı" else "telefon bağlı değil"
            Live.update { it.copy(tvLink = if (connected) "📱 $ua bağlandı" else "📱 Telefon bağlı değil · QR'ı okut") }
            updateQr()
        }, { part -> modelStore.part(part) })
        if (!h.start()) return false
        hub = h
        browserUrl = h.url() ?: ""
        browserState = "telefon bağlı değil"
        DiagLog.add("telefon sayfası: $browserUrl")
        Live.update { it.copy(tvLink = "📱 Telefon bağlı değil · QR'ı okut") }
        updateQr()
        return true
    }

    private fun onBrowserMessage(m: JSONObject) {
        when (m.optString("t")) {
            "ready" -> {
                if (hubReady) return
                hubReady = true
                gate.forgetProcessed()
                Live.update { it.copy(tvLink = "📱 ${browserState.removeSuffix(" bağlı")} hazır, okuyor") }
                main.post { overlay?.status(SubtitleView.DOT_GREEN, "Telefon hazır") }
                updateQr()
                val s = Live.state.value
                hub?.send(JSONObject().put("t", "status").put("phase", s.phase.name).put("msg", s.message))
                hub?.send(JSONObject().put("t", "hold").put("ms", settings.holdExtraMs))
                sendTrModes()
            }
            "hello" -> {
                val st = m.optBoolean("started", true)
                if (!hubReady) {
                    Live.update { it.copy(tvLink = "📱 ${browserState.removeSuffix(" bağlı")} bağlandı · " + if (st) "okuma motoru yükleniyor" else "telefonda Başlat'a bas") }
                    main.post { overlay?.status(SubtitleView.DOT_YELLOW, if (st) "Telefon bağlandı, motor yükleniyor" else "Telefon bağlandı: Başlat'a bas") }
                }
                hub?.send(JSONObject().put("t", "hold").put("ms", settings.holdExtraMs))
                sendTrModes()
            }
            "log" -> {
                val msg = m.optString("msg").take(200)
                DiagLog.add("telefon: $msg")
                if (!hubReady) Live.update { it.copy(tvLink = "📱 ${msg.take(90)}") }
            }
            "ocr" -> ocrWaits[m.optInt("seq")]?.complete(m)
            "tr" -> trWaits[m.optInt("seq")]?.complete(m)
            "keys" -> {
                // Telefondaki "Çeviri anahtarları" formu: boş gelen alan değiştirilmez, "-" gelen alan silinir.
                fun upd(name: String, set: (String) -> Unit) { if (m.has(name)) { val v = m.optString(name).trim(); if (v == "-") set("") else if (v.isNotEmpty()) set(v) } }
                upd("mm") { secrets.myMemoryEmail = it }; upd("groq") { secrets.groqKey = it }; upd("gem") { secrets.geminiKey = it }
                upd("deepl") { secrets.deeplKey = it }; upd("ms") { secrets.msKey = it }; upd("msr") { secrets.msRegion = it }
                upd("lu") { secrets.libreUrl = it }; upd("lk") { secrets.libreKey = it }; upd("gc") { secrets.googleCloudKey = it }
                DiagLog.add("çeviri anahtarları telefondan güncellendi")
                sendKeyState()
            }
            "trmode" -> {
                settings.translateMode = m.optInt("mode", settings.translateMode).let { if (it in Settings.ALLOWED_MODES) it else settings.translateMode }
                DiagLog.add("çevirici telefondan değişti: ${Settings.TR_MODES[settings.translateMode]}")
                sendTrModes()
            }
            "trtest" -> scope.launch {
                // TV'deki her çeviriciyi örnek cümleyle dener, sonucu tek tek telefona yollar.
                // only=keys: yalnız anahtarı/adresi girilmiş olanlar (anahtar bölümündeki "Kaydet ve sına").
                val onlyKeys = m.optString("only") == "keys"
                val k = secrets.translatorKeys()
                val has = mapOf("tr:groq" to k.groq, "tr:gemini" to k.gemini, "tr:deepl" to k.deepl, "tr:mskey" to k.msKey,
                    "tr:gcloud" to k.googleCloud, "tr:libre" to k.libreUrl, "tr:mymemory" to k.myMemoryEmail)
                val dest = if (onlyKeys) "keys" else "list"
                var n = 0
                for ((step, mode) in listOf("tr:groq" to 10, "tr:gemini" to 11, "tr:deepl" to 4, "tr:mskey" to 9, "tr:google" to 2, "tr:microsoft" to 5, "tr:google2" to 7, "tr:lingva" to 6,
                        "tr:mymemory" to 3, "tr:libre" to 12, "tr:gcloud" to 15).filter { it.second in Settings.ALLOWED_MODES }
                        .filter { !onlyKeys || has[it.first]?.isNotBlank() == true }) {
                    n++
                    val t0 = SystemClock.elapsedRealtime()
                    val r = worker.call(WorkerProtocol.MSG_TEST, Bundle().apply {
                        putString(WorkerProtocol.K_STEP, step); putString(WorkerProtocol.K_TARGET, settings.targetLang)
                        putBundle("anahtarlar", secrets.translatorKeys().toBundle()) }, 20_000)
                    val o = JSONObject().put("t", "trtest").put("dest", dest).put("mode", mode).put("name", Settings.TR_MODES[mode].removePrefix("Yalnız ").substringBefore(" ·").substringBefore(" ("))
                        .put("ms", SystemClock.elapsedRealtime() - t0)
                    if (r != null && r.getBoolean(WorkerProtocol.K_OK)) o.put("ok", true).put("text", r.getString(WorkerProtocol.K_TRANSLATED))
                    else o.put("ok", false).put("why", r?.getString(WorkerProtocol.K_ERROR) ?: "yanıt yok")
                    hub?.send(o)
                }
                hub?.send(JSONObject().put("t", "trtest").put("dest", dest).put("done", true).put("none", n == 0))
            }
            "trq" -> {
                // Telefon çeviremeyince (örnek düğmesi) TV kendi motorlarıyla çevirir ve sonucu geri yollar.
                val text = m.optString("text").take(300)
                scope.launch {
                    val t0 = SystemClock.elapsedRealtime()
                    val r = workerTranslate(text, false, 12_000, if (settings.translateMode == 8) 13 else settings.translateMode)
                    val o = JSONObject().put("t", "trr").put("ms", SystemClock.elapsedRealtime() - t0)
                    if (r != null && r.getBoolean(WorkerProtocol.K_OK)) o.put("ok", true).put("text", r.getString(WorkerProtocol.K_TRANSLATED)).put("engine", r.getString(WorkerProtocol.K_ENGINE))
                    else o.put("ok", false).put("why", r?.getString(WorkerProtocol.K_ERROR) ?: "TV yanıt vermedi").put("status", r?.getString(WorkerProtocol.K_STATUS) ?: "")
                    DiagLog.add("TV çeviri denemesi: ${o.optString("text", o.optString("why"))} · ${r?.getString(WorkerProtocol.K_STATUS) ?: ""}")
                    hub?.send(o)
                }
            }
            "hold" -> {
                settings.holdExtraMs = m.optInt("ms", settings.holdExtraMs)
                DiagLog.add("ekranda kalma ek süresi: ${settings.holdExtraMs} ms")
                hub?.send(JSONObject().put("t", "hold").put("ms", settings.holdExtraMs))
            }
            "region" -> {
                // Telefonda sarı kutu sürüklendi: yeni okuma bölgesi hemen geçerli.
                val a = m.optJSONArray("r") ?: return
                if (a.length() != 4) return
                val r = FloatArray(4) { a.optDouble(it).toFloat().coerceIn(0f, 1f) }
                if (r[2] - r[0] < 0.05f || r[3] - r[1] < 0.04f) return
                settings.setRegion(r, currentGameId.takeIf { settings.hasGameRegion(it) })
                region = r; regionReload = SystemClock.elapsedRealtime(); gate.reset(); lastAccepted = ""
                DiagLog.add("bölge telefondan değişti: ${r.joinToString { "%.2f".format(it) }}")
                snapshotWanted = true
            }
        }
    }

    /** Telefon hazır değilken TV'nin köşesinde QR kod gösterilir. */
    private var qrShownOnce = false
    private fun updateQr() {
        val url = browserUrl
        val show = role == Settings.Role.TV_BROWSER && url.isNotBlank() && !(hub?.connected == true && hubReady)
        main.removeCallbacks(qrLater)
        if (!show) { main.post { overlay?.qr(null) }; qrShownOnce = true; return }
        // Telefon daha önce bağlandıysa kısa kopmalarda (sekme değişimi) QR hemen çıkmasın: 10 sn bekle.
        if (qrShownOnce) main.postDelayed(qrLater, 10_000) else main.post { overlay?.qr(Qr.bitmap(url, 300), "Telefonla okut: Konsol Çeviri") }
    }
    private val qrLater = Runnable {
        val url = browserUrl
        if (role == Settings.Role.TV_BROWSER && url.isNotBlank() && !(hub?.connected == true && hubReady)) overlay?.qr(Qr.bitmap(url, 300), "Telefonla okut: Konsol Çeviri")
    }

    private val trWaits = ConcurrentHashMap<Int, CompletableDeferred<JSONObject>>()
    private var phoneTrFails = 0

    /** TV + tarayıcı: çeviriyi telefondaki sayfa yapar. Olmazsa (internet, sınır) null döner, TV yedek olarak çevirir. */
    private suspend fun translateViaBrowser(text: String, timeout: Long = 8000, modeOverride: Int? = null): Bundle? {
        val hb = hub ?: return null
        if (!hb.connected || !hubReady) return null
        val id = seq.incrementAndGet().toInt()
        val wait = CompletableDeferred<JSONObject>()
        trWaits[id] = wait
        val ok = hb.send(JSONObject().put("t", "translate").put("seq", id).put("text", text)
            .put("src", settings.sourceLang).put("target", settings.targetLang).put("mode", modeOverride ?: settings.translateMode)
            .put("terms", org.json.JSONArray(settings.glossaryTerms)))
        val m = if (ok) kotlinx.coroutines.withTimeoutOrNull(timeout) { wait.await() } else null
        trWaits.remove(id)
        val t = m?.optString("text")?.takeIf { it.isNotBlank() && m.optBoolean("ok", true) }
            ?: return Bundle().apply {
                putBoolean(WorkerProtocol.K_OK, false)
                putString(WorkerProtocol.K_ERROR, "telefon çeviremedi: ${m?.optString("why")?.take(120) ?: "yanıt yok"}")
            }
        return Bundle().apply {
            putBoolean(WorkerProtocol.K_OK, true)
            putString(WorkerProtocol.K_TRANSLATED, t)
            putString(WorkerProtocol.K_ENGINE, "${m.optString("engine", "telefon")} (telefonda)")
            putLong(WorkerProtocol.K_TR_MS, m.optLong("ms"))
        }
    }

    private fun trBundle(text: String, offline: Boolean, mode: Int) = Bundle().apply {
        putString(WorkerProtocol.K_TEXT, text)
        putString(WorkerProtocol.K_TARGET, settings.targetLang)
        putString(WorkerProtocol.K_SRC, settings.sourceLang)
        putInt(WorkerProtocol.K_TR_MODE, mode)
        putStringArrayList(WorkerProtocol.K_TERMS, ArrayList(settings.glossaryTerms))
        putBoolean("mlkit_kapali", !offline)
        putBundle("anahtarlar", secrets.translatorKeys().toBundle())
    }

    private suspend fun workerTranslate(text: String, offline: Boolean, timeout: Long, mode: Int = settings.translateMode) =
        worker.call(WorkerProtocol.MSG_TRANSLATE, trBundle(text, offline, mode), timeout)

    /**
     * TV + tarayıcı çeviri sırası.
     * Otomatik: 1) TV'de anahtarlı motorlar (Groq, Gemini, DeepL, Microsoft; tam sürümde ücretsiz uçlar da)
     *           2) telefonda Firefox modeli (internetsiz)  3) TV'de MyMemory / LibreTranslate  4) TV'de cihaz içi.
     * Anahtar yoksa 1. adım beklemeden geçer. Seçili tek motor varsa yalnız o kullanılır.
     */
    private suspend fun translateTvBrowser(text: String, onQuick: (String) -> Unit = {}): Bundle? {
        val ok = { b: Bundle? -> b != null && b.getBoolean(WorkerProtocol.K_OK) }
        val mode = settings.translateMode
        // Kaliteli (anahtarlı) motor varsa: 1,2 sn'de gelmezse telefondaki Firefox modeliyle ön çeviri gösterilir,
        // kaliteli çeviri gelince yerine konur.
        if (mode == 0 && secrets.translatorKeys().hasQuality && hubReady) {
            val res = kotlinx.coroutines.coroutineScope {
                val tv = async { workerTranslate(text, false, 12_000, 0) }
                val fast = kotlinx.coroutines.withTimeoutOrNull(1200) { tv.await() }
                if (ok(fast)) return@coroutineScope fast
                val ph = translateViaBrowser(text, 3500, modeOverride = 8)
                if (ok(ph)) ph!!.getString(WorkerProtocol.K_TRANSLATED)?.let(onQuick)
                tv.await()
            }
            if (ok(res)) return res
        }
        val errs = ArrayList<String>()
        fun note(b: Bundle?) { b?.getString(WorkerProtocol.K_ERROR)?.let { if (it.isNotBlank()) errs.add(it) } }
        when (mode) {
            1 -> return workerTranslate(text, true, 30_000)
            8 -> return translateViaBrowser(text, 15_000)
            0 -> {
                val tv = workerTranslate(text, false, 12_000, 0); if (ok(tv)) return tv; note(tv)
                val ph = translateViaBrowser(text, 15_000); if (ok(ph)) return ph; note(ph)
                val fb = workerTranslate(text, false, 12_000, 13); if (ok(fb)) return fb; note(fb)
                val off = workerTranslate(text, true, 30_000, 13); if (ok(off)) return off; note(off)
            }
            else -> {
                val tv = workerTranslate(text, false, 12_000); if (ok(tv)) return tv; note(tv)
                // tam sürümde tarayıcıda da çalışan ücretsiz motorlar için telefon yedeği
                if (mode in listOf(2, 3, 6, 7)) { val ph = translateViaBrowser(text, 8_000); if (ok(ph)) return ph; note(ph) }
            }
        }
        return Bundle().apply { putBoolean(WorkerProtocol.K_OK, false); putString(WorkerProtocol.K_ERROR, "çeviri olmadı: " + errs.distinct().joinToString(" · ").ifBlank { "yanıt yok" }) }
    }

    @Volatile private var lastEngine = ""

    /** Hangi anahtarların girili olduğu (değerleri değil, yalnız son 4 karakter) telefona bildirilir. */
    private fun sendKeyState() {
        fun mask(v: String) = if (v.isBlank()) "" else "…" + v.takeLast(4)
        hub?.send(JSONObject().put("t", "keys").put("mm", secrets.myMemoryEmail.let { if (it.isBlank()) "" else it.take(2) + "…@" + it.substringAfter('@', "") })
            .put("groq", mask(secrets.groqKey)).put("gem", mask(secrets.geminiKey)).put("deepl", mask(secrets.deeplKey))
            .put("ms", mask(secrets.msKey)).put("msr", secrets.msRegion).put("lu", secrets.libreUrl).put("lk", mask(secrets.libreKey)).put("gc", mask(secrets.googleCloudKey))
            .put("model", modelStore.status))
    }

    private fun sendTrModes() {
        sendKeyState()
        hub?.send(JSONObject().put("t", "trmodes").put("cur", settings.translateMode).put("web", com.playceviri.tv.BuildConfig.GAYRIRESMI)
            .put("items", org.json.JSONArray(Settings.ALLOWED_MODES.map { org.json.JSONArray().put(it).put(Settings.TR_MODES[it]) })))
    }

    private suspend fun translateLoop() {
        for (job in trChannel) {
            var quick: String? = null
            val r = if (role == Settings.Role.TV_BROWSER) translateTvBrowser(job.text) { q ->
                // hızlı ön çeviri (telefonda Firefox modeli): hemen göster, kaliteli çeviri gelince yerine konur
                if (job.seq > clearedAt) { quick = q; lastEngine = "Firefox modeli (ön çeviri)"; output(q to job.text, job.anchor) }
            } else workerTranslate(job.text, offline = true, 15_000)
            if (r == null || !r.getBoolean(WorkerProtocol.K_OK)) {
                if (quick == null) reportError(r?.getString(WorkerProtocol.K_ERROR) ?: "çeviri yanıt vermedi")
                if (job.clearAfter) output(null)
                continue
            }
            if (quick == null && (job.seq <= clearedAt || job.seq < shownSeq)) continue
            shownSeq = maxOf(shownSeq, job.seq)
            val tr = r.getString(WorkerProtocol.K_TRANSLATED) ?: continue
            val trMs = r.getLong(WorkerProtocol.K_TR_MS)
            val total = SystemClock.elapsedRealtime() - job.since
            val engine = r.getString(WorkerProtocol.K_ENGINE) ?: ""
            Live.update {
                it.copy(phase = Phase.STREAMING, message = "Çeviri çalışıyor", translateMs = trMs, totalMs = total,
                    engine = engine, lastOriginal = job.text, lastTranslated = tr)
            }
            if (latencyLogs < 8) { latencyLogs++; DiagLog.add("GECİKME: toplam $total ms (okuma ${job.ocrMs} + çeviri $trMs [$engine])") }
            lastEngine = engine
            val q = quick
            if (q != null) { if (q != tr) upgrade(q, tr, job.text) }
            else output(tr to job.text, job.anchor)
            if (job.clearAfter) output(null)
        }
    }

    /** Ekrandaki (ya da sıradaki) ön çeviriyi kaliteli çeviriyle değiştirir. */
    private fun upgrade(old: String, new: String, orig: String) {
        hub?.send(JSONObject().put("t", "sub").put("text", new).put("orig", orig).put("engine", lastEngine).put("upgraded", true))
        main.post { overlay?.upgrade(old, new, orig) }
    }

    private fun countRead() {
        val now = SystemClock.elapsedRealtime()
        synchronized(reads) {
            reads.addLast(now)
            while (reads.isNotEmpty() && now - reads.first() > 5000) reads.removeFirst()
            val rps = reads.size / 5f
            Live.update { it.copy(readsPerSec = rps) }
        }
    }

    /** null → altyazıyı kaldır. */
    private fun output(sub: Pair<String, String>?, anchor: FloatArray? = null) {
        if (role == Settings.Role.PHONE) {
            val d = display
            if (sub == null) d?.sendClear() else d?.sendSub(sub.first, sub.second, anchor?.get(0), anchor?.get(1), anchor?.get(2))
            if (sub == null) Live.update { it.copy(lastTranslated = "", lastOriginal = "") }
        } else {
            hub?.let { h ->
                if (sub == null) h.send(JSONObject().put("t", "clear"))
                else h.send(JSONObject().put("t", "sub").put("text", sub.first).put("orig", sub.second))
            }
            main.post {
                val o = overlay ?: return@post
                if (sub == null) o.requestClear() else o.enqueue(sub.first, sub.second, anchor)
            }
        }
    }

    private fun saveSnapshot(img: android.media.Image) {
        try {
            val p = img.planes[0]; val buf = p.buffer; val rs = p.rowStride; val ps = p.pixelStride
            val ow = 640; val oh = 360
            val px = IntArray(ow * oh)
            for (y in 0 until oh) {
                val sy = y * img.height / oh
                for (x in 0 until ow) {
                    val v = buf.get(sy * rs + (x * img.width / ow) * ps).toInt() and 255
                    px[y * ow + x] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
                }
            }
            buf.rewind()
            val bmp = Bitmap.createBitmap(px, ow, oh, Bitmap.Config.ARGB_8888)
            val tmp = File(cacheDir, "son_kare.tmp")
            FileOutputStream(tmp).use { bmp.compress(Bitmap.CompressFormat.JPEG, 80, it) }
            bmp.recycle()
            tmp.renameTo(snapshotFile(this))
        } catch (_: Exception) {}
    }

    // ───────────────────────── yardımcılar ─────────────────────────

    private fun startDisplayLink() {
        if (!settings.tvPaired) {
            Live.update { it.copy(tvLink = "TV eşleşmemiş · çeviriler yalnız bu telefonda görünür") }
            return
        }
        val d = display ?: DisplayClient(this, { settings.tvHost }, { settings.tvPort }, { settings.tvName }, { settings.tvCode }) { st, msg ->
            Live.update { it.copy(tvLink = msg) }
            if (st == DisplayClient.State.CONNECTED) DiagLog.add(msg)
        }.also { display = it }
        d.sendStyle(settings.styleJson(), false)
        d.start()
    }

    private fun warmupOffline() {
        val m = settings.translateMode
        if (m != 0 && m != 1) return
        if (warmJob?.isActive == true) return
        warmJob = scope.launch {
            val r = worker.call(WorkerProtocol.MSG_WARMUP, Bundle().apply {
                putString(WorkerProtocol.K_SRC, settings.sourceLang.let { if (it == "auto") "en" else it })
                putString(WorkerProtocol.K_TARGET, settings.targetLang)
            }, 300_000)
            DiagLog.add("çevrimdışı çeviri modeli: ${if (r?.getBoolean(WorkerProtocol.K_OK) == true) "hazır" else "hazır değil"}")
        }
    }

    /** PS5'te açık oyunu öğrenir; oyuna özel okuma bölgesi bu kimlikle saklanır. */
    private suspend fun gameWatch(host: String) {
        while (scope.isActive) {
            val h = withContext(Dispatchers.IO) { runCatching { ConsoleLink.findOnce(host, 3000) }.getOrNull() }
            val id = h?.runningAppTitleid.orEmpty(); val name = h?.runningAppName.orEmpty()
            if (h != null && id != currentGameId) {
                currentGameId = id; currentGameName = name
                regionReload = 0
                if (name.isNotBlank()) DiagLog.add("oyun: $name ($id)${if (settings.hasGameRegion(id)) " · oyuna özel bölge kullanılıyor" else ""}")
                Live.update { it.copy(game = if (name.isBlank()) "" else name) }
            }
            delay(90_000)
        }
    }

    private fun reportError(text: String?) {
        val t = text ?: "bilinmeyen hata"
        DiagLog.add("işlem hatası: $t")
        Live.update { it.copy(message = "Uyarı: ${t.take(90)}") }
    }

    private fun fail(message: String) {
        publish(Phase.ERROR, message)
        main.post { overlay?.detach(); overlay = null }
        promote(message)
    }

    private fun publish(phase: Phase, message: String) {
        val prev = Live.state.value.phase
        Live.update { it.copy(phase = phase, message = message) }
        DiagLog.add("$phase: $message")
        if (phase != Phase.STREAMING || prev != Phase.STREAMING) promote(message)
        display?.sendStatus(phase.name, message)
        hub?.send(JSONObject().put("t", "status").put("phase", phase.name).put("msg", message))
        val color = when (phase) { Phase.STREAMING -> SubtitleView.DOT_GREEN; Phase.ERROR -> SubtitleView.DOT_RED; else -> SubtitleView.DOT_YELLOW }
        main.post { overlay?.status(color, message.take(60)) }
    }

    private fun acquireLocks() {
        runCatching {
            if (wake == null) wake = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "playceviri:akis").apply { setReferenceCounted(false); acquire(6 * 3600_000L) }
            @Suppress("DEPRECATION")
            if (wifiLock == null) wifiLock = applicationContext.getSystemService(WifiManager::class.java)
                .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "playceviri:wifi").apply { setReferenceCounted(false); acquire() }
        }
    }

    private fun releaseLocks() {
        runCatching { wake?.release() }; wake = null
        runCatching { wifiLock?.release() }; wifiLock = null
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Konsol Çeviri çeviri", NotificationManager.IMPORTANCE_LOW))
    }

    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, PlayCeviriService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setContentTitle("Konsol Çeviri")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Durdur", stop).build())
            .setOngoing(true)
            .build()
    }

    private fun promote(text: String) {
        val n = notification(text)
        try {
            if (android.os.Build.VERSION.SDK_INT >= 34)
                startForeground(NOTE_ID, n, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            else startForeground(NOTE_ID, n)
        } catch (e: Exception) {
            getSystemService(NotificationManager::class.java).notify(NOTE_ID, n)
        }
    }
}
