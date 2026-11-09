package com.playceviri.tv.worker

import android.app.Service
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Message
import android.os.Messenger
import android.os.SystemClock
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.playceviri.tv.data.SecretStore
import com.playceviri.tv.data.Settings
import com.playceviri.tv.translate.TranslatorHub
import com.playceviri.tv.translate.Keys
import com.playceviri.tv.util.DiagLog
import com.playceviri.tv.vision.Gray
import com.playceviri.tv.vision.GrayImage
import java.util.concurrent.TimeUnit

/**
 * Ayrı süreçte (:isci) çalışan yazı okuma + çeviri. Yerel (native) bir motor bu süreçte çökerse
 * ana uygulama ve PS5 bağlantısı ayakta kalır; istemci çöken motoru kapatıp yedeğe geçer.
 */
class WorkerService : Service() {
    private lateinit var thread: HandlerThread
    private lateinit var messenger: Messenger
    private var recognizer: TextRecognizer? = null
    private lateinit var hub: TranslatorHub
    private lateinit var secrets: SecretStore

    override fun onCreate() {
        super.onCreate()
        DiagLog.attach(this)
        secrets = SecretStore(this)
        hub = TranslatorHub(this).also { it.keys = secrets.translatorKeys() }
        thread = HandlerThread("playceviri-isci").apply { start() }
        messenger = Messenger(object : Handler(thread.looper) {
            override fun handleMessage(msg: Message) {
                val reply = msg.replyTo ?: return
                val data = msg.data ?: Bundle()
                val out = Bundle()
                out.putInt(WorkerProtocol.K_ID, data.getInt(WorkerProtocol.K_ID))
                try {
                    when (msg.what) {
                        WorkerProtocol.MSG_OCR -> ocr(data, out)
                        WorkerProtocol.MSG_TRANSLATE -> translate(data, out)
                        WorkerProtocol.MSG_TEST -> test(data, out)
                        WorkerProtocol.MSG_WARMUP -> warmup(data, out)
                    }
                } catch (t: Throwable) {
                    out.putBoolean(WorkerProtocol.K_OK, false)
                    out.putString(WorkerProtocol.K_ERROR, "${t.javaClass.simpleName}: ${t.message?.take(140)}")
                } finally {
                    EngineGuard.clear(this@WorkerService)
                }
                runCatching { reply.send(Message.obtain(null, WorkerProtocol.MSG_RESULT).apply { this.data = out }) }
            }
        })
    }

    override fun onBind(intent: Intent?): IBinder = messenger.binder

    override fun onDestroy() {
        runCatching { recognizer?.close() }
        runCatching { hub.close() }
        thread.quitSafely()
        super.onDestroy()
    }

    private fun recognizer(): TextRecognizer =
        recognizer ?: TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS).also { recognizer = it }

    private fun runOcr(g: GrayImage, out: Bundle) {
        val (nv21, w, h) = Gray.toNv21(g)
        EngineGuard.mark(this, EngineGuard.OCR_MLKIT)
        val t0 = SystemClock.elapsedRealtime()
        val text = Tasks.await(recognizer().process(InputImage.fromByteArray(nv21, w, h, 0, InputImage.IMAGE_FORMAT_NV21)), 8, TimeUnit.SECONDS)
        EngineGuard.clear(this)
        val lines = text.textBlocks.flatMap { it.lines }.sortedBy { it.boundingBox?.top ?: 0 }
        out.putStringArrayList(WorkerProtocol.K_LINES, ArrayList(lines.map { it.text }))
        out.putFloatArray(WorkerProtocol.K_CONFS, FloatArray(lines.size) { i -> runCatching { lines[i].confidence }.getOrDefault(-1f) })
        val boxes = IntArray(lines.size * 4)
        lines.forEachIndexed { i, l -> val b = l.boundingBox ?: Rect(); boxes[i * 4] = b.left; boxes[i * 4 + 1] = b.top; boxes[i * 4 + 2] = b.right; boxes[i * 4 + 3] = b.bottom }
        out.putIntArray(WorkerProtocol.K_BOXES, boxes)
        out.putLong(WorkerProtocol.K_OCR_MS, SystemClock.elapsedRealtime() - t0)
        out.putBoolean(WorkerProtocol.K_OK, true)
    }

    private fun ocr(d: Bundle, out: Bundle) {
        val w = d.getInt(WorkerProtocol.K_W); val h = d.getInt(WorkerProtocol.K_H)
        val raw = WorkerProtocol.imageFile(this).readBytes()
        if (raw.size < w * h) throw IllegalStateException("kırpıntı eksik")
        val g = Gray.prepare(GrayImage(raw, w, h), d.getInt(WorkerProtocol.K_PREP))
        runOcr(g, out)
    }

    private fun translate(d: Bundle, out: Bundle) {
        Keys.from(d.getBundle("anahtarlar"))?.let { hub.keys = it }
        val text = d.getString(WorkerProtocol.K_TEXT) ?: ""
        val lang = Settings.LANGUAGES.firstOrNull { it.tag == d.getString(WorkerProtocol.K_TARGET) } ?: Settings.LANGUAGES[0]
        val disabled = EngineGuard.disabled(this)
        val t0 = SystemClock.elapsedRealtime()
        val r = hub.translate(text, lang, d.getStringArrayList(WorkerProtocol.K_TERMS) ?: emptyList(),
            d.getString(WorkerProtocol.K_SRC) ?: "en", d.getInt(WorkerProtocol.K_TR_MODE),
            allowMlKit = EngineGuard.TR_MLKIT !in disabled && !d.getBoolean("mlkit_kapali"),
            beforeMlKit = { EngineGuard.mark(this, EngineGuard.TR_MLKIT) })
        EngineGuard.clear(this)
        out.putLong(WorkerProtocol.K_TR_MS, SystemClock.elapsedRealtime() - t0)
        out.putString(WorkerProtocol.K_STATUS, hub.status())
        if (r == null) {
            out.putBoolean(WorkerProtocol.K_OK, false)
            out.putString(WorkerProtocol.K_ERROR, hub.lastError ?: "çeviri yapılamadı")
        } else {
            out.putBoolean(WorkerProtocol.K_OK, true)
            out.putString(WorkerProtocol.K_TRANSLATED, r.text)
            out.putString(WorkerProtocol.K_ENGINE, r.engine)
        }
    }

    /** Çevrimdışı modelin önceden inmesi: internet koptuğunda ML Kit hazır olsun. */
    private fun warmup(d: Bundle, out: Bundle) {
        if (EngineGuard.TR_MLKIT in EngineGuard.disabled(this)) { out.putBoolean(WorkerProtocol.K_OK, false); return }
        EngineGuard.mark(this, EngineGuard.TR_MLKIT)
        val ok = hub.mlKitEngine().ensureModel(d.getString(WorkerProtocol.K_SRC) ?: "en", d.getString(WorkerProtocol.K_TARGET) ?: "tr")
        out.putBoolean(WorkerProtocol.K_OK, ok)
    }

    private fun test(d: Bundle, out: Bundle) {
        Keys.from(d.getBundle("anahtarlar"))?.let { hub.keys = it }
        val step = d.getString(WorkerProtocol.K_STEP) ?: ""
        val sample = "I can't believe you did this to me."
        val lang = Settings.LANGUAGES.firstOrNull { it.tag == d.getString(WorkerProtocol.K_TARGET) } ?: Settings.LANGUAGES[0]
        val t0 = SystemClock.elapsedRealtime()
        when (step) {
            "ocr:mlkit" -> {
                runOcr(Gray.prepare(sampleImage(sample), 1), out)
                out.putString(WorkerProtocol.K_ORIGINAL, out.getStringArrayList(WorkerProtocol.K_LINES)?.joinToString(" ") ?: "")
            }
            "tr:mlkit" -> {
                EngineGuard.mark(this, EngineGuard.TR_MLKIT)
                val r = hub.mlKitEngine().translate(sample, "en", lang.tag)
                out.putBoolean(WorkerProtocol.K_OK, r != null); out.putString(WorkerProtocol.K_TRANSLATED, r)
            }
            "tr:google", "tr:mymemory", "tr:deepl", "tr:microsoft", "tr:lingva", "tr:google2", "tr:mskey", "tr:groq", "tr:gemini", "tr:libre", "tr:gcloud" -> {
                hub.resetEngines()
                val mode = when (step) { "tr:google" -> 2; "tr:mymemory" -> 3; "tr:microsoft" -> 5; "tr:lingva" -> 6; "tr:google2" -> 7; "tr:mskey" -> 9; "tr:groq" -> 10; "tr:gemini" -> 11; "tr:libre" -> 12; "tr:gcloud" -> 15; else -> 4 }
                val r = hub.translate(sample, lang, emptyList(), "en", mode, false, useCache = false)
                out.putBoolean(WorkerProtocol.K_OK, r != null)
                out.putString(WorkerProtocol.K_TRANSLATED, r?.text)
                if (r == null) out.putString(WorkerProtocol.K_ERROR, hub.lastError ?: "yanıt yok")
            }
        }
        out.putLong(WorkerProtocol.K_TR_MS, SystemClock.elapsedRealtime() - t0)
    }

    /** Oyun altyazısına benzeyen test görüntüsü: koyu sahne üstünde beyaz, kenarlı yazı. */
    private fun sampleImage(s: String): GrayImage {
        val bmp = Bitmap.createBitmap(1280, 150, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.rgb(52, 60, 58))
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 52f; typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER }
        p.style = Paint.Style.STROKE; p.strokeWidth = 5f; p.color = Color.BLACK
        c.drawText(s, 640f, 95f, p)
        p.style = Paint.Style.FILL; p.color = Color.WHITE
        c.drawText(s, 640f, 95f, p)
        val px = IntArray(1280 * 150); bmp.getPixels(px, 0, 1280, 0, 0, 1280, 150); bmp.recycle()
        val g = ByteArray(px.size) { i -> val v = px[i]; (((v shr 16 and 255) * 299 + (v shr 8 and 255) * 587 + (v and 255) * 114) / 1000).toByte() }
        return GrayImage(g, 1280, 150)
    }
}
