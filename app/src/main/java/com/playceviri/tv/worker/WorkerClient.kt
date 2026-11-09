package com.playceviri.tv.worker

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Message
import android.os.Messenger
import com.playceviri.tv.util.DiagLog
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * İşçi sürecine bağlanır, istek gönderir, yanıt bekler. İşçi çökerse bekleyen istekler null döner,
 * çökme anındaki motor [EngineGuard] ile kapatılır ve [onNotice] ile kullanıcıya anlatılır.
 */
class WorkerClient(private val context: Context, private val onNotice: (String) -> Unit) {
    private val app = context.applicationContext
    private val ids = AtomicInteger(1)
    private val pending = ConcurrentHashMap<Int, CompletableDeferred<Bundle?>>()
    private val replyThread = HandlerThread("playceviri-isci-yanit").apply { start() }
    private val replies = Messenger(object : Handler(replyThread.looper) {
        override fun handleMessage(msg: Message) {
            val d = msg.data ?: return
            pending.remove(d.getInt(WorkerProtocol.K_ID))?.complete(d)
        }
    })
    @Volatile private var service: Messenger? = null
    @Volatile private var bound = false
    @Volatile private var ready = CompletableDeferred<Boolean>()

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = Messenger(binder)
            runCatching { binder?.linkToDeath({ died() }, 0) }
            ready.complete(true)
        }
        override fun onServiceDisconnected(name: ComponentName?) { died() }
        override fun onBindingDied(name: ComponentName?) { died(); rebind() }
    }

    private fun died() {
        if (service == null && pending.isEmpty()) return
        service = null
        ready = CompletableDeferred()
        val stage = EngineGuard.stage(app)
        if (stage.isNotEmpty()) {
            EngineGuard.disable(app, stage)
            EngineGuard.clear(app)
            val msg = "${EngineGuard.label(stage)} bu cihazda çöktü; kapatıldı, yedeğe geçiliyor"
            DiagLog.add("İŞÇİ ÇÖKTÜ: $msg")
            onNotice(msg)
        } else DiagLog.add("işçi süreci kapandı (aşama yok)")
        pending.values.forEach { it.complete(null) }
        pending.clear()
    }

    fun bind() {
        if (bound) return
        bound = app.bindService(Intent(app, WorkerService::class.java), conn,
            Context.BIND_AUTO_CREATE or Context.BIND_IMPORTANT)
    }

    private fun rebind() { runCatching { app.unbindService(conn) }; bound = false; bind() }

    fun unbind() {
        if (bound) runCatching { app.unbindService(conn) }
        bound = false; service = null
        pending.values.forEach { it.complete(null) }; pending.clear()
        replyThread.quitSafely()
    }

    suspend fun call(what: Int, data: Bundle, timeoutMs: Long): Bundle? {
        bind()
        val ok = withTimeoutOrNull(6000) { ready.await() } ?: return null
        if (!ok) return null
        val s = service ?: return null
        val id = ids.getAndIncrement()
        val d = CompletableDeferred<Bundle?>()
        pending[id] = d
        data.putInt(WorkerProtocol.K_ID, id)
        try {
            s.send(Message.obtain(null, what).apply { this.data = data; replyTo = replies })
        } catch (e: Exception) { pending.remove(id); return null }
        val r = withTimeoutOrNull(timeoutMs) { d.await() }
        if (r == null) pending.remove(id)
        return r
    }
}
