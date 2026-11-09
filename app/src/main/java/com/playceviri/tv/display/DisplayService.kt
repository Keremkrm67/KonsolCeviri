package com.playceviri.tv.display

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import com.playceviri.tv.data.Settings
import com.playceviri.tv.overlay.SubtitleOverlay
import com.playceviri.tv.overlay.SubtitleView
import com.playceviri.tv.ui.MainActivity
import com.playceviri.tv.util.DiagLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/**
 * "TV ekranı" görevi: ağır iş yapmaz. Telefondan gelen çeviriyi HDMI görüntüsünün üstüne yazar,
 * köşedeki noktayla durumu gösterir (yeşil: çeviri akıyor, sarı: bekliyor, kırmızı: kopuk).
 */
class DisplayService : Service() {
    data class Info(
        val running: Boolean = false, val ip: String = "", val port: Int = 0, val code: String = "",
        val phone: String = "", val phoneConnected: Boolean = false, val phonePhase: String = "", val phoneMsg: String = "",
        val lastText: String = "", val overlayOk: Boolean = true,
    )

    companion object {
        private const val CHANNEL = "playceviri_ekran"
        private const val NOTE_ID = 8
        private val _info = MutableStateFlow(Info())
        val info: StateFlow<Info> = _info.asStateFlow()
        fun start(c: Context) = c.startForegroundService(Intent(c, DisplayService::class.java))
        fun stop(c: Context) = c.stopService(Intent(c, DisplayService::class.java))
        fun sample(c: Context) = c.startService(Intent(c, DisplayService::class.java).setAction("ornek"))
        fun restyle(c: Context) { if (info.value.running) c.startService(Intent(c, DisplayService::class.java).setAction("stil")) }
    }

    private val main = Handler(Looper.getMainLooper())
    private lateinit var settings: Settings
    private lateinit var overlay: SubtitleOverlay
    private var server: DisplayServer? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        DiagLog.attach(this)
        settings = Settings(this)
        overlay = SubtitleOverlay(this, settings)
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(CHANNEL, "Konsol Çeviri ekranı", NotificationManager.IMPORTANCE_LOW))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        promote("Telefon bekleniyor · kod ${settings.displayCode}")
        when (intent?.action) {
            "ornek" -> { if (overlay.attach()) overlay.show("Örnek altyazı burada görünür.", "This is how the subtitle will look.", fixedMs = 4000); return START_STICKY }
            "stil" -> { overlay.refreshStyle(); return START_STICKY }
        }
        if (server == null) {
            val ok = overlay.attach()
            val s = DisplayServer(this, { settings.displayCode }, { m -> main.post { onMessage(m) } }) { connected, name ->
                main.post { onClient(connected, name) }
            }
            if (!s.start()) {
                DiagLog.add("ekran sunucusu açılamadı")
                _info.value = Info(running = false, overlayOk = ok)
                stopSelf(); return START_NOT_STICKY
            }
            server = s
            _info.value = Info(running = true, ip = DisplayProtocol.localIp() ?: "", port = s.port, code = settings.displayCode, overlayOk = ok)
            overlay.status(SubtitleView.DOT_YELLOW, "Konsol Çeviri: telefon bekleniyor")
            DiagLog.add("TV ekranı açık: ${_info.value.ip}:${s.port}, kod ${settings.displayCode}")
        }
        return START_STICKY
    }

    private fun onClient(connected: Boolean, name: String) {
        _info.value = _info.value.copy(phone = name, phoneConnected = connected, phonePhase = if (connected) _info.value.phonePhase else "")
        if (connected) {
            overlay.status(SubtitleView.DOT_YELLOW, "Telefon bağlandı: $name")
            promote("Telefon bağlı: $name")
        } else {
            overlay.hide()
            overlay.status(SubtitleView.DOT_RED, "Telefon bağlantısı koptu")
            promote("Telefon bekleniyor · kod ${settings.displayCode}")
        }
    }

    private fun onMessage(m: JSONObject) {
        when (m.optString("t")) {
            "sub" -> {
                val anchor = if (m.has("top")) floatArrayOf(m.optDouble("top").toFloat(), m.optDouble("left").toFloat(), m.optDouble("width").toFloat()) else null
                overlay.enqueue(m.optString("text"), m.optString("orig"), anchor)
                _info.value = _info.value.copy(lastText = m.optString("text"))
            }
            "clear" -> overlay.requestClear()
            "style" -> {
                m.optJSONObject("s")?.let { settings.applyStyleJson(it) }
                overlay.refreshStyle()
                if (m.optBoolean("preview")) overlay.show("Örnek altyazı burada görünür.", "This is how the subtitle will look.", fixedMs = 3000)
            }
            "status" -> {
                val phase = m.optString("phase"); val msg = m.optString("msg")
                _info.value = _info.value.copy(phonePhase = phase, phoneMsg = msg)
                val color = when (phase) { "STREAMING" -> SubtitleView.DOT_GREEN; "ERROR", "IDLE" -> SubtitleView.DOT_RED; else -> SubtitleView.DOT_YELLOW }
                overlay.status(color, msg.take(60))
                if (phase == "IDLE" || phase == "ERROR") overlay.hide()
            }
        }
    }

    override fun onDestroy() {
        server?.stop(); server = null
        overlay.detach()
        _info.value = Info(running = false)
        super.onDestroy()
    }

    private fun promote(text: String) {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n: Notification = Notification.Builder(this, CHANNEL)
            .setContentTitle("Konsol Çeviri ekranı").setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_view).setContentIntent(open).setOngoing(true).build()
        try {
            if (Build.VERSION.SDK_INT >= 34) startForeground(NOTE_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            else startForeground(NOTE_ID, n)
        } catch (e: Exception) { DiagLog.add("ekran ön plan hatası: ${e.javaClass.simpleName}") }
    }
}
