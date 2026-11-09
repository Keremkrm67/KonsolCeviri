package com.playceviri.tv.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import java.util.Locale

/** Eski Konsol Çeviri görünümü: koyu zemin, sarı başlık, odaklanınca sarı çerçeveli büyük düğmeler. */
open class BaseActivity : Activity() {
    companion object {
        const val BG = "#12161D"
        const val CARD = "#1C222C"
        const val CARD_FOCUS = "#2A3340"
        const val BORDER = "#2F3745"
        const val ACCENT = "#FFD166"
        const val TEXT = "#F1F3F5"
        const val MUTED = "#B7BEC9"
        const val FAINT = "#7D8592"
        const val GOOD = "#4ADE80"
        const val BAD = "#FFB3C1"
    }

    val dpf get() = resources.displayMetrics.density
    fun dp(v: Int) = (v * dpf).toInt()

    fun text(s: String, sp: Float, color: String, bold: Boolean = false) = TextView(this).apply {
        text = s; textSize = sp; setTextColor(Color.parseColor(color))
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    fun section(s: String) = text(s.uppercase(Locale.ROOT), 13f, FAINT, true).apply { setPadding(0, dp(26), 0, dp(2)) }

    fun note(s: String) = text(s, 13.5f, MUTED).apply { setPadding(dp(4), dp(4), dp(4), dp(2)) }

    fun rounded(fill: String, stroke: String, strokeDp: Int) = GradientDrawable().apply {
        cornerRadius = dp(12).toFloat(); setColor(Color.parseColor(fill)); setStroke(dp(strokeDp), Color.parseColor(stroke))
    }

    fun styleButton(b: Button, primary: Boolean = false) {
        b.isAllCaps = false; b.textSize = 17f; b.gravity = Gravity.START or Gravity.CENTER_VERTICAL
        b.setTextColor(Color.parseColor(if (primary) BG else TEXT))
        b.setPadding(dp(20), dp(12), dp(20), dp(12))
        b.isFocusable = true
        b.background = StateListDrawable().apply {
            if (primary) {
                addState(intArrayOf(android.R.attr.state_focused), rounded("#FFE08A", "#FFFFFF", 3))
                addState(intArrayOf(android.R.attr.state_pressed), rounded("#FFE08A", ACCENT, 1))
                addState(intArrayOf(), rounded(ACCENT, ACCENT, 1))
            } else {
                addState(intArrayOf(android.R.attr.state_focused), rounded(CARD_FOCUS, ACCENT, 3))
                addState(intArrayOf(android.R.attr.state_pressed), rounded(CARD_FOCUS, ACCENT, 1))
                addState(intArrayOf(), rounded(CARD, BORDER, 1))
            }
        }
        b.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) }
        b.stateListAnimator = null
    }

    fun button(label: String, primary: Boolean = false, onClick: () -> Unit) = Button(this).apply {
        text = label; styleButton(this, primary); setOnClickListener { onClick() }
    }

    fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    /** Kaydırılabilir sayfa; telefonda sistem çubuklarının altına girmesin diye kenar boşluğu eklenir. */
    fun page(content: LinearLayout): ScrollView {
        val side = if (resources.configuration.smallestScreenWidthDp >= 600) 56 else 18
        content.orientation = LinearLayout.VERTICAL
        content.setPadding(dp(side), dp(28), dp(side), dp(40))
        val sv = ScrollView(this)
        sv.setBackgroundColor(Color.parseColor(BG))
        sv.isFillViewport = true
        sv.addView(content, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        sv.setOnApplyWindowInsetsListener { v, insets ->
            @Suppress("DEPRECATION")
            v.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            insets
        }
        window.decorView.setBackgroundColor(Color.parseColor(BG))
        return sv
    }

    fun dialog(title: String) = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert).setTitle(title)

    /** Kaydırıcı (D-pad sol/sağ ve dokunmayla çalışır) ile sayı seçtirir. */
    fun numberDialog(title: String, min: Int, max: Int, value: Int, unit: String, explain: String, onChange: (Int) -> Unit) {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(8), dp(24), 0) }
        val label = text("", 22f, ACCENT, true).apply { gravity = Gravity.CENTER }
        fun show(v: Int) { label.text = "$v$unit" }
        val sb = SeekBar(this).apply {
            this.max = max - min; progress = value - min
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) { show(p + min); onChange(p + min) }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
            keyProgressIncrement = 1
        }
        show(value)
        box.addView(label)
        box.addView(sb, LinearLayout.LayoutParams(-1, dp(56)))
        if (explain.isNotBlank()) box.addView(text(explain, 13f, MUTED))
        dialog(title).setView(box).setPositiveButton("Tamam", null).show()
        sb.requestFocus()
    }

    fun inputDialog(title: String, hint: String, value: String, numeric: Boolean, explain: String = "", onOk: (String) -> Unit) {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(8), dp(24), 0) }
        if (explain.isNotBlank()) box.addView(text(explain, 14f, MUTED))
        val e = EditText(this).apply {
            this.hint = hint; setText(value)
            inputType = if (numeric) InputType.TYPE_CLASS_NUMBER else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        box.addView(e)
        dialog(title).setView(box).setPositiveButton("Tamam") { _, _ -> onOk(e.text.toString().trim()) }.setNegativeButton("Vazgeç", null).show()
    }

    fun <T> choiceDialog(title: String, items: List<T>, label: (T) -> String, current: Int, onPick: (Int) -> Unit) {
        dialog(title).setSingleChoiceItems(items.map(label).toTypedArray(), current) { d, i -> onPick(i); d.dismiss() }
            .setNegativeButton("Vazgeç", null).show()
    }

    /** D-pad'de sol/sağ ile değer değiştirilebilen düğme (odaktayken ◀ ▶). */
    fun stepper(b: Button, onStep: (Int) -> Unit) {
        b.setOnKeyListener { _, code, ev ->
            if (ev.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            when (code) {
                KeyEvent.KEYCODE_DPAD_LEFT -> { onStep(-1); true }
                KeyEvent.KEYCODE_DPAD_RIGHT -> { onStep(1); true }
                else -> false
            }
        }
    }

    fun row(vararg views: View): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        views.forEachIndexed { i, v ->
            addView(v, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { if (i > 0) marginStart = dp(8); topMargin = dp(8) })
        }
    }
}

fun Context.isTv() = com.playceviri.tv.data.Settings.isTvDevice(this)
