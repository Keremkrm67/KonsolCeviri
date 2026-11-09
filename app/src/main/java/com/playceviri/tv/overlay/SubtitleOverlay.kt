package com.playceviri.tv.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import com.playceviri.tv.data.Settings

/** Diğer uygulamaların (HDMI kaynağı dahil) üstünde çizilen pencere. Tüm çağrılar ana iş parçacığından. */
class SubtitleOverlay(private val context: Context, private val settings: Settings) {
    private val wm = context.getSystemService(WindowManager::class.java)
    private val view = SubtitleView(context)
    private val main = Handler(Looper.getMainLooper())
    private var attached = false
    private val hideTask = Runnable { view.clearSubtitle() }

    fun canDraw() = android.provider.Settings.canDrawOverlays(context)

    fun attach(): Boolean {
        if (attached) return true
        if (!canDraw()) return false
        return try {
            @Suppress("DEPRECATION")
            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT)
            wm.addView(view, lp)
            attached = true
            true
        } catch (e: Exception) { false }
    }

    fun detach() {
        main.removeCallbacks(hideTask)
        if (attached) runCatching { wm.removeView(view) }
        attached = false
    }

    fun refreshStyle() { view.style = SubStyle.from(settings) }

    /** anchor: "orijinalin üstüne" modu için [top, left, width] (0..1). */
    fun show(text: String, orig: String = "", anchor: FloatArray? = null, fixedMs: Long? = null) {
        if (!attached) return
        view.style = SubStyle.from(settings)
        view.setSubtitle(text, orig, anchor)
        main.removeCallbacks(hideTask)
        val sec = settings.durationSec
        val ms = fixedMs ?: if (sec > 0) sec * 1000L else null
        if (ms != null) main.postDelayed(hideTask, ms)
    }

    fun hide() { main.removeCallbacks(hideTask); main.removeCallbacks(tickTask); queue.reset(); if (attached) view.clearSubtitle() }

    // ───── okuma sırası ─────
    private val queue = ReadingQueue({ settings.holdExtraMs.toLong() }, { settings.durationSec.takeIf { it > 0 }?.let { it * 1000L } })
    private val tickTask = Runnable { apply(queue.tick(android.os.SystemClock.uptimeMillis())) }

    private fun apply(step: ReadingQueue.Step) {
        when (step) {
            is ReadingQueue.Step.Show -> {
                if (attached) { view.style = SubStyle.from(settings); view.setSubtitle(step.item.text, step.item.orig, step.item.anchor) }
                main.removeCallbacks(hideTask); main.removeCallbacks(tickTask)
                main.postAtTime(tickTask, step.untilMs)
            }
            ReadingQueue.Step.Hide -> { main.removeCallbacks(tickTask); if (attached) view.clearSubtitle() }
            ReadingQueue.Step.Keep -> {}
        }
    }

    /** Çeviriyi okuma sırasına ekler (hemen ya da ekrandakinin süresi dolunca gösterilir). */
    fun enqueue(text: String, orig: String = "", anchor: FloatArray? = null) =
        apply(queue.push(ReadingQueue.Item(text, orig, anchor), android.os.SystemClock.uptimeMillis()))

    /** Ön çeviriyi kaliteli çeviriyle değiştirir (ekrandaysa hemen yeniden çizilir). */
    fun upgrade(oldText: String, newText: String, orig: String = "") {
        val n = queue.replace(oldText, newText, orig) ?: return
        if (attached) { view.style = SubStyle.from(settings); view.setSubtitle(n.text, n.orig, n.anchor) }
        main.removeCallbacks(tickTask); main.postAtTime(tickTask, queue.currentUntil)
    }

    /** Oyundaki altyazı kayboldu: ekrandaki çeviri süresini doldurduktan sonra silinir. */
    fun requestClear() = apply(queue.clear(android.os.SystemClock.uptimeMillis()))

    fun status(color: Int, label: String) { if (attached) view.setDot(color, label) }

    fun qr(b: android.graphics.Bitmap?, label: String = "") { view.setQr(b, label) }
}
