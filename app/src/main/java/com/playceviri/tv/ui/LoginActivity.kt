package com.playceviri.tv.ui

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Color
import android.net.http.SslError
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.TextView
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature
import com.playceviri.tv.data.SecretStore
import com.playceviri.tv.net.DohProxy
import com.playceviri.tv.psn.PsnAccount
import com.playceviri.tv.util.DiagLog
import kotlin.concurrent.thread

/** Sony girişi (ikinci PSN hesabıyla). Giriş bitince dönen adresten hesap kimliği alınır. */
class LoginActivity : BaseActivity() {
    private var done = false
    private lateinit var info: TextView
    private lateinit var web: WebView
    private var proxy: DohProxy? = null
    private val onTv by lazy { isTv() }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        info = TextView(this).apply {
            text = "İKİNCİ PSN hesabınla giriş yap (ana hesabınla değil)." +
                if (onTv) " Sayfa hata verirse Geri tuşuna bas ve ana ekrandan \"Telefonla kurulum\" yöntemini kullan." else ""
            setTextColor(Color.parseColor(BG)); setBackgroundColor(Color.parseColor(ACCENT))
            textSize = 16f; setPadding(32, 18, 32, 18)
        }
        web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            settings.userAgentString = settings.userAgentString.replace("; wv", "")
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            isFocusable = true; isFocusableInTouchMode = true
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = capture(request.url.toString())
                override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) { capture(url) }
                override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, r: WebResourceResponse) {
                    DiagLog.add("sayfa HTTP ${r.statusCode}: ${request.url.host}${request.url.path?.take(40)}")
                }
                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                    DiagLog.add("sayfa HATA ${error.errorCode} ${error.description}: ${request.url.host}")
                    if (request.isForMainFrame && !done) problem(
                        "Sony'nin giriş sayfası açılamadı.\n\n• Cihazın internete bağlı olduğundan ve saat/tarihin doğru olduğundan emin ol.\n" +
                            "• Ağ ayarlarından DNS'i 8.8.8.8 ve 1.1.1.1 yapıp tekrar dene.\n" +
                            if (onTv) "• Olmazsa telefon yöntemini kullan: ana ekranda \"Telefonla kurulum\"." else "• Ana ekrandaki \"Bağlantı testi\" sonucunu tanılama kaydıyla paylaş.")
                }
                @SuppressLint("WebViewClientOnReceivedSslError")
                override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                    handler.cancel()
                    problem("Güvenli bağlantı kurulamadı. Cihazın tarih ve saati yanlış olabilir (otomatik tarih/saat açık olmalı). Düzeltince tekrar dene.")
                }
            }
        }
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.WHITE) }
        root.addView(info, ViewGroup.LayoutParams(-1, -2))
        root.addView(web, LinearLayout.LayoutParams(-1, 0, 1f))
        root.setOnApplyWindowInsetsListener { v, ins ->
            @Suppress("DEPRECATION") v.setPadding(ins.systemWindowInsetLeft, ins.systemWindowInsetTop, ins.systemWindowInsetRight, ins.systemWindowInsetBottom); ins
        }
        setContentView(root)
        dialog("Önce bunu oku").setMessage(
            "Bu girişi Konsol Çeviri için açtığın İKİNCİ PSN hesabıyla yap.\n\n" +
                "• Ana hesabınla girersen kumandan PS5 ile Konsol Çeviri arasında karışabilir.\n" +
                "• Henüz ikinci hesabın yoksa şimdi vazgeç; önce playstation.com'dan yeni hesap aç ve PS5'e kullanıcı olarak ekle." +
                if (onTv) "\n• Yazmak yavaşsa vazgeç ve telefon formundaki yöntemi kullan." else "")
            .setPositiveButton("İkinci hesabım hazır, devam") { _, _ -> start() }
            .setNegativeButton("Vazgeç") { _, _ -> finish() }
            .setCancelable(false).show()
    }

    private fun start() {
        val cm = CookieManager.getInstance()
        cm.removeAllCookies {
            cm.flush()
            WebStorage.getInstance().deleteAllData()
            web.clearCache(true)
            useSecureDns { web.loadUrl(PsnAccount.LOGIN_URL); web.requestFocus() }
        }
    }

    /** Ev ağının DNS'i Sony adreslerini çözemiyorsa giriş sayfası güvenli DNS üzerinden açılır. */
    private fun useSecureDns(then: () -> Unit) {
        val p = if (WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) DohProxy().takeIf { it.start() } else null
        if (p == null) { DiagLog.add("giriş: güvenli DNS vekili KAPALI (destek yok ya da başlatılamadı)"); then(); return }
        DiagLog.add("giriş: güvenli DNS vekili açık, port ${p.port}")
        proxy = p
        try {
            ProxyController.getInstance().setProxyOverride(
                ProxyConfig.Builder().addProxyRule("127.0.0.1:${p.port}").build(), { it.run() }
            ) { DiagLog.add("giriş: vekil ayarı uygulandı"); then() }
        } catch (e: Exception) { DiagLog.add("giriş: vekil ayarı HATA ${e.javaClass.simpleName}"); then() }
    }

    private fun problem(message: String) {
        if (isFinishing) return
        dialog("Giriş sayfası açılamadı").setMessage(message)
            .setPositiveButton("Tekrar dene") { _, _ -> web.loadUrl(PsnAccount.LOGIN_URL) }
            .setNegativeButton("Kapat") { _, _ -> finish() }.show()
    }

    private fun capture(url: String): Boolean {
        if (!url.startsWith(PsnAccount.REDIRECT)) return false
        if (done) return true
        done = true
        info.text = "Hesap kaydediliyor…"
        thread {
            val r = PsnAccount.resolveFull(url)
            runOnUiThread {
                r.onSuccess { res ->
                    val store = SecretStore(this)
                    store.psnAccountId = res.id; store.psnName = res.name ?: ""
                    DiagLog.add("PSN hesabı kaydedildi${res.name?.let { ": $it" } ?: ""}")
                    val who = if (res.name != null) "Kaydedilen PSN hesabı: ${res.name}" else "Hesap adı Sony'den alınamadı (kimlik sonu: …${res.id.takeLast(4)})."
                    dialog("Bu ikinci hesabın mı?").setMessage("$who\n\nKonsol Çeviri için açtığın İKİNCİ hesap bu olmalı. Ana hesabınla girdiysen \"Hayır\"ı seç.")
                        .setPositiveButton("Evet, doğru") { _, _ -> finish() }
                        .setNegativeButton("Hayır, sil ve yeniden gir") { _, _ -> store.forgetPsn(); finish() }
                        .setCancelable(false).show()
                }.onFailure { e ->
                    done = false
                    dialog("Giriş tamamlanamadı").setMessage("${e.message ?: "bilinmeyen hata"}" + if (onTv) "\nTelefon formundaki yöntemi deneyebilirsin." else "")
                        .setPositiveButton("Tamam") { _, _ -> finish() }.show()
                }
            }
        }
        return true
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() { if (web.canGoBack()) web.goBack() else super.onBackPressed() }

    override fun onDestroy() {
        try { if (proxy != null) ProxyController.getInstance().clearProxyOverride({ it.run() }) {} } catch (_: Exception) {}
        proxy?.stop()
        web.destroy()
        super.onDestroy()
    }
}
