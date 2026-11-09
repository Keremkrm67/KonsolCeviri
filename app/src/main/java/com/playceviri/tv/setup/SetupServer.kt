package com.playceviri.tv.setup

import android.content.Context
import android.os.SystemClock
import com.playceviri.tv.data.SecretStore
import com.playceviri.tv.data.Settings
import com.playceviri.tv.display.DisplayProtocol
import com.playceviri.tv.link.ConsoleLink
import com.playceviri.tv.psn.PsnAccount
import com.playceviri.tv.util.DiagLog
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URLDecoder
import java.security.SecureRandom
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Adım adım kurulum sayfası. "Tek başına TV" görevinde telefon QR kodla TV'deki bu sayfayı açar;
 * "Telefon" görevinde aynı sayfa telefonda, uygulamanın içinde açılır.
 */
class SetupServer(context: Context, private val localOnly: Boolean, private val onChange: (String) -> Unit) {
    private val app = context.applicationContext
    private val settings = Settings(app)
    private val secrets = SecretStore(app)
    val token: String = ByteArray(9).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
    private val deadline = SystemClock.elapsedRealtime() + 20 * 60_000L
    private var server: ServerSocket? = null
    @Volatile private var running = false
    @Volatile var port = 0; private set
    private var badRequests = 0
    @Volatile private var pairStatus = "idle"
    @Volatile private var pairResult: Pair<List<String>, List<String>> = emptyList<String>() to emptyList()
    private val phone get() = settings.role == Settings.Role.PHONE

    fun url(): String? {
        val ip = if (localOnly) "127.0.0.1" else DisplayProtocol.localIp() ?: return null
        return "http://$ip:$port/$token"
    }

    fun start(): Boolean = try {
        val s = if (localOnly) ServerSocket(0, 10, InetAddress.getByName("127.0.0.1")) else ServerSocket(0)
        s.soTimeout = 1000
        server = s; port = s.localPort; running = true
        thread(isDaemon = true, name = "playceviri-kurulum") { loop(s) }
        true
    } catch (e: Exception) { false }

    fun stop() { running = false; runCatching { server?.close() } }

    private fun loop(s: ServerSocket) {
        while (running && SystemClock.elapsedRealtime() < deadline) {
            try {
                val c = s.accept()
                try { handle(c) } catch (_: Exception) {} finally { runCatching { c.close() } }
            } catch (_: SocketTimeoutException) {} catch (_: Exception) {}
        }
        val tooMany = badRequests >= 5
        val expired = SystemClock.elapsedRealtime() >= deadline
        stop()
        if (tooMany) onChange("Çok fazla yanlış adres denendi, kurulum sayfası kapatıldı. Yeniden açmak için düğmeye bas.")
        else if (expired) onChange("Kurulum sayfasının süresi doldu. Yeniden açmak için düğmeye bas.")
    }

    private fun handle(sock: Socket) {
        sock.soTimeout = 5000
        val input = sock.getInputStream()
        val head = readHead(input) ?: return
        val lines = head.split("\r\n")
        val first = lines[0].split(" ")
        if (first.size < 2) return
        val method = first[0]
        val path = first[1].substringBefore('?')
        if (path == "/favicon.ico") { respond(sock, 404, ""); return }
        if (path.trim('/') != token) {
            badRequests++
            respond(sock, 404, page("Adres geçersiz", "<p>Kurulum adresini yeniden aç.</p>"))
            if (badRequests >= 5) stop()
            return
        }
        if (method != "POST") { respond(sock, 200, form(null)); return }
        val len = lines.firstOrNull { it.startsWith("Content-Length:", true) }?.substringAfter(':')?.trim()?.toIntOrNull() ?: 0
        if (len < 0 || len > 16384) { respond(sock, 413, page("Çok büyük", "")); return }
        val body = ByteArray(len); var n = 0
        while (n < len) { val r = input.read(body, n, len - n); if (r < 0) break; n += r }
        respond(sock, 200, apply(parseForm(String(body, 0, n, Charsets.UTF_8))))
    }

    private fun readHead(input: InputStream): String? {
        val b = ByteArrayOutputStream(); var last = 0
        while (b.size() < 8192) {
            val r = input.read(); if (r < 0) return null
            b.write(r); last = (last shl 8) or r
            if (last == 0x0D0A0D0A) return b.toString("UTF-8")
        }
        return null
    }

    private fun parseForm(body: String): Map<String, String> = body.split('&').mapNotNull { kv ->
        val i = kv.indexOf('='); if (i < 0) null else URLDecoder.decode(kv.substring(0, i), "UTF-8") to URLDecoder.decode(kv.substring(i + 1), "UTF-8")
    }.toMap()

    private fun apply(f: Map<String, String>): String {
        val ok = mutableListOf<String>(); val bad = mutableListOf<String>()
        f["kod"]?.trim()?.takeIf { it.isNotEmpty() }?.let { code ->
            if (pairStatus != "running") {
                pairStatus = "running"; pairResult = emptyList<String>() to emptyList()
                thread(isDaemon = true, name = "playceviri-eslestir") {
                    pairResult = try { pairFromPage(code) } catch (e: Exception) { emptyList<String>() to listOf("Eşleştirme hatası: ${e.javaClass.simpleName}") }
                    pairStatus = "done"
                }
            }
            return form(null)
        }
        if (f["sil"] == "1") {
            secrets.forgetPsn()
            onChange("PSN hesabı ve PS5 eşleşmesi silindi.")
            return form(listOf("PSN hesabı ve PS5 eşleşmesi silindi. İkinci hesapla girişi yeniden yapabilirsin.") to emptyList())
        }
        f["psn"]?.trim()?.takeIf { it.isNotEmpty() }?.let { v ->
            PsnAccount.resolveFull(v).onSuccess { r ->
                secrets.psnAccountId = r.id; secrets.psnName = r.name ?: ""
                ok += if (r.name != null) "PSN hesabı kaydedildi: ${r.name}. Bu, Konsol Çeviri için açtığın İKİNCİ hesap mı? Değilse en alttaki \"Kayıtlı PSN hesabını sil\" düğmesine bas."
                else "PSN hesabı kaydedildi (hesap adı alınamadı, kimlik sonu …${r.id.takeLast(4)}). Girişi İKİNCİ hesapla yaptığından emin ol."
            }.onFailure { bad += "PSN: ${it.message ?: "çözülemedi"}" }
        }
        f["deepl"]?.trim()?.takeIf { it.isNotEmpty() }?.let { k ->
            if (k.length < 20) bad += "DeepL anahtarı çok kısa görünüyor." else { secrets.deeplKey = k; ok += "DeepL anahtarı kaydedildi." }
        }
        f["ip"]?.trim()?.takeIf { it.isNotEmpty() }?.let { ip ->
            if (Regex("\\d{1,3}(\\.\\d{1,3}){3}").matches(ip)) { settings.consoleHost = ip; ok += "PS5 adresi kaydedildi." }
            else bad += "PS5 adresi geçersiz. Örnek: 192.168.1.20"
        }
        if (ok.isNotEmpty()) onChange(ok.joinToString(" "))
        return form(ok to bad)
    }

    private fun pairFromPage(code: String): Pair<List<String>, List<String>> {
        if (secrets.psnAccountId.isBlank()) return emptyList<String>() to listOf("Önce 4. Adım'dan PSN hesabını kaydet, sonra eşleştir.")
        if (!Regex("\\d{8}").matches(code)) return emptyList<String>() to listOf("Kod 8 haneli olmalı (yalnızca rakam).")
        var host = settings.consoleHost
        if (host.isBlank()) {
            host = ConsoleLink.findOnce(null, 8000)?.hostAddr
                ?: return emptyList<String>() to listOf("PS5 ağda bulunamadı. PS5 açık mı, bu cihazla aynı ağda mı? Bulunamazsa 5. Adım'a PS5'in IP adresini yazıp Kaydet'e bas, sonra kodu yeniden gir.")
            settings.consoleHost = host
        }
        val latch = CountDownLatch(1)
        var res: ConsoleLink.PairResult? = null
        val reg = ConsoleLink.pair(host, code, secrets) { res = it; latch.countDown() }
        if (reg == null && res == null) latch.countDown()
        latch.await(40, TimeUnit.SECONDS)
        return when (val r = res) {
            is ConsoleLink.PairResult.Success -> {
                onChange("PS5 eşleşti.")
                listOf("PS5 eşleşti ($host). " + if (phone) "Şimdi 8. Adım'da TV'yi bağla." else "Şimdi PS5'te oyunu aç, TV'de Çeviriyi Başlat'a bas.") to emptyList()
            }
            is ConsoleLink.PairResult.Failure -> emptyList<String>() to listOf(r.reason)
            null -> emptyList<String>() to listOf("Eşleştirme zaman aşımına uğradı. PS5'te yeni bir kod alıp tekrar dene.")
        }
    }

    private fun pairBanner(): Pair<List<String>, List<String>>? = when (pairStatus) {
        "running" -> listOf("⏳ Eşleştiriliyor… PS5 aranıyor ve bağlanılıyor (10–40 saniye sürebilir). Sayfa kendiliğinden yenilenir, kapatma.") to emptyList()
        "done" -> { pairStatus = "idle"; pairResult }
        else -> null
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun savedBox(): String {
        if (secrets.psnAccountId.isBlank()) return "<div class='box'><b>Kayıtlı PSN hesabı yok.</b> Aşağıdaki adımları sırayla yap.</div>"
        val n = secrets.psnName
        val who = if (n.isNotBlank()) "<b>${esc(n)}</b>" else "(hesap adı alınamadı, kimlik sonu …${esc(secrets.psnAccountId.takeLast(4))})"
        val paired = if (secrets.isConsolePaired) " · PS5 eşleşmesi <b>✓</b>" else ""
        return "<div class='box'>Kayıtlı PSN hesabı: $who$paired<br><span class='note'>Bu Konsol Çeviri için açtığın İKİNCİ hesap değilse en alttaki \"Kayıtlı PSN hesabını sil\" düğmesine bas ve girişi baştan yap.</span></div>"
    }

    private fun form(given: Pair<List<String>, List<String>>?): String {
        val g = given ?: pairBanner()
        val sb = StringBuilder()
        g?.first?.forEach { sb.append("<div class='ok'>${esc(it)}</div>") }
        g?.second?.forEach { sb.append("<div class='bad'>${esc(it)}</div>") }
        if (pairStatus == "running") sb.append("<script>setTimeout(function(){location.replace(location.pathname)},2000)</script>")
        val dev = if (phone) "bu telefon" else "TV"
        val loginA = if (phone) """
                <b>Yol A (kolay): bu uygulamada</b>
                <ol>
                  <li>Bu sayfayı kapat, Konsol Çeviri ana ekranında <b>"PSN girişi (ikinci hesapla)"</b> düğmesine bas.</li>
                  <li><b>İkinci hesabın</b> e-posta ve şifresini yaz.</li>
                  <li>Çıkan pencerede hesap adını kontrol edip <b>"Evet, doğru"</b> de. Bitti, <b>5. Adım'a geç</b>.</li>
                </ol>""" else """
                <b>Yol A (kolay): TV'de otomatik</b>
                <ol>
                  <li>TV'de Konsol Çeviri ana ekranında <b>"TV'de PSN girişi (otomatik, ikinci hesapla)"</b> düğmesine bas.</li>
                  <li>Kumandayla <b>ikinci hesabın</b> e-posta ve şifresini yaz.</li>
                  <li>Çıkan pencerede hesap adını kontrol edip <b>"Evet, doğru"</b> de. Bitti, <b>5. Adım'a geç</b>.</li>
                </ol>"""
        val loginB = if (phone) "" else """
              <div class="panel">
                <b>Yol B (yedek): bu telefondan</b>
                <p class="note">Telefonda ana hesabınla giriş açık kalmış olabilir. Bu yüzden aşağıdaki bağlantıya <b>uzun bas › Gizli sekmede aç</b> de.</p>
                <a class="btn" target="_blank" rel="noopener noreferrer" href="${esc(PsnAccount.LOGIN_URL)}">Sony'de giriş yap ↗</a>
                <ol>
                  <li>Açılan sayfada <b>ikinci hesabın</b> e-posta ve şifresiyle gir (doğrulama kodu istenirse yaz).</li>
                  <li>Giriş bitince boş ya da hata gibi bir sayfa açılır; <b>bu normal</b>, içeriği önemsiz.</li>
                  <li>Adres çubuğuna dokun, <b>adresin tamamını kopyala</b>. <code>…redirect?code=…</code> diye uzun bir adrestir.</li>
                  <li>Bu sayfaya dön, aşağıdaki kutuya <b>yapıştır</b>. Kod kısa ömürlü; hemen yapıştır, sonra <b>sayfanın altındaki "Kaydet"</b> düğmesine bas.</li>
                </ol>
                <textarea name="psn" rows="3" autocomplete="off" autocapitalize="off" spellcheck="false"
                  placeholder="https://remoteplay.dl.playstation.net/remoteplay/redirect?code=…"></textarea>
                <p class="note">"Geçersiz" derse giriş bağlantısına yeniden basıp girişi baştan yap.</p>
              </div>"""
        val step8 = if (phone) """
            <div class="step s8">
              <div class="ico">📺</div><div class="num">8. Adım</div>
              <h2>TV'yi bağla</h2>
              <p class="where">📍 Nerede: önce TV'de, sonra bu telefonda</p>
              <div class="panel"><ol>
                <li>TV'ye <b>Konsol Çeviri</b>'yi kur ve aç. <b>"Bu cihazın görevi"</b> düğmesiyle <b>"TV ekranı"</b>nı seç.</li>
                <li>TV'de <b>"Üstte gösterme izni"</b> ✓ olmalı. Değilse o düğmeye basıp izni ver.</li>
                <li>TV'de <b>"Ekranı başlat"</b>a bas. Ekranda <b>4 haneli bir kod</b> çıkar.</li>
                <li>Bu telefonda ana ekrandaki <b>"TV'ye bağlan"</b>a bas, TV'yi seç ve kodu yaz.</li>
                <li>Telefonda <b>"Örnek altyazıyı TV'de göster"</b> ile denediğinde yazı TV'de görünmeli. Sonra <b>Çeviriyi Başlat</b>.</li>
              </ol></div>
              <p class="why"><b>Neden böyle?</b> Görüntüyü okumak ve çevirmek ağır iştir; TV'lerin işlemcisi zayıf olduğu için uygulama donar ya da geç kalır. Bu düzende işi telefon yapar, TV yalnızca hazır çeviriyi yazar.</p>
            </div>""" else ""
        val body = """
            $sb
            ${savedBox()}
            <details class="box"><summary><b>🩺 Tanılama kaydı</b> (sorun olursa buraya dokun, tümünü kopyalayıp Claude'a yapıştır)</summary>
              <pre style="white-space:pre-wrap;word-break:break-word;font-size:12px">${esc(DiagLog.report(app))}</pre></details>
            <p class="lead">${if (phone) 8 else 7} kısa adım, yaklaşık 15 dakika. Adımları <b>sırayla</b> yap; her kartta ne yapacağın ve nereden yapacağın yazıyor. Yazdıkların yalnızca $dev içine kaydedilir.</p>

            <div class="step s0">
              <div class="ico">🔌</div><div class="num">Hazırlık</div>
              <h2>Başlamadan önce</h2>
              <div class="panel"><ul>
                <li><b>PS5'i aç</b> ve ana ekranda beklet (uyku modunda olmasın).</li>
                <li><b>TV, PS5 ve bu telefon aynı Wi-Fi'da</b> olsun (aynı modem). Telefonda mobil veriyi kapat.</li>
                <li>Bu sayfa 20 dakika açık kalır. Kapanırsa kurulum düğmesine yeniden bas.</li>
              </ul></div>
              <p class="why"><b>Neden ikinci hesap?</b> Konsol Çeviri, PS5'e Uzaktan Oynatma ile bağlanıp ekranı okur. Ana hesabınla bağlanırsa kumandan PS5 ile Konsol Çeviri arasında karışabilir. Bu yüzden Konsol Çeviri için ayrı bir PSN hesabı kullanılır; oyunu ana hesabınla oynamaya devam edersin.</p>
            </div>

            <div class="step s1">
              <div class="ico">👤</div><div class="num">1. Adım</div>
              <h2>İkinci PSN hesabı aç</h2>
              <p class="where">📍 Nerede: telefonun tarayıcısında</p>
              <div class="panel"><ol>
                <li><b>playstation.com</b> adresine git, <b>Oturum aç › Hesap oluştur</b>'a bas.</li>
                <li>Ana hesabından <b>farklı bir e-posta</b> kullan ve e-postayı doğrula.</li>
                <li>Bir kullanıcı adı (Çevrimiçi Kimlik) seç, hesabı tamamla.</li>
                <li>Bu hesabın <b>e-postasını ve şifresini not et</b>; sonraki adımlarda lazım.</li>
              </ol></div>
            </div>

            <div class="step s2">
              <div class="ico">🎮</div><div class="num">2. Adım</div>
              <h2>Hesabı PS5'e kullanıcı olarak ekle</h2>
              <p class="where">📍 Nerede: PS5'te, kumandayla</p>
              <div class="panel"><ol>
                <li>PS5 ana ekranında sağ üstteki <b>profil simgesine</b> git (ya da açılışta kullanıcı seçme ekranı çıkar).</li>
                <li><b>Kullanıcı ekle</b> (ya da Kullanıcı seç › Kullanıcı ekle) seç.</li>
                <li><b>Oturum aç</b> de ve <b>ikinci hesabın</b> e-posta ve şifresini gir. Sorulanları varsayılanla geç.</li>
                <li>Sonuç: PS5'te iki kullanıcın var, <b>ana hesabın</b> ve <b>ikinci hesap</b>.</li>
              </ol></div>
            </div>

            <div class="step s3">
              <div class="ico">⚙️</div><div class="num">3. Adım</div>
              <h2>PS5'te Uzaktan Oynatmayı aç</h2>
              <p class="where">📍 Nerede: PS5 Ayarlar menüsü (menü adları sürüme göre biraz değişebilir)</p>
              <div class="panel"><ol>
                <li><b>Ayarlar › Sistem › Uzaktan Oynatma</b> yoluna gir ve <b>Uzaktan Oynatmayı Etkinleştir</b>'i aç.</li>
                <li><b>Ayarlar › Sistem › Güç Tasarrufu › Hazır Modda Kullanılabilir Özellikler</b> yoluna gir. <b>İnternete Bağlı Kal</b> ve <b>PS5'i Ağdan Açmayı Etkinleştir</b>'i aç. Böylece PS5 uykudayken Konsol Çeviri onu uyandırabilir.</li>
              </ol></div>
            </div>

            <form method="post" accept-charset="utf-8">
            <div class="step s4">
              <div class="ico">🔑</div><div class="num">4. Adım</div>
              <h2>İkinci hesapla Sony'ye giriş yap</h2>
              <p class="warn">⚠️ Girişi <b>ikinci hesabınla</b> yap. Ana hesabınla girersen kumanda karışabilir.</p>
              <div class="panel">$loginA
              </div>
              $loginB
            </div>

            <div class="step s5">
              <div class="ico">📡</div><div class="num">5. Adım</div>
              <h2>PS5 adresi <span class="pill">boş bırakabilirsin</span></h2>
              <p class="where">📍 Nerede: bu kutu (isteğe bağlı)</p>
              <p class="note">Konsol Çeviri PS5'i ağda kendisi bulur; 7. Adım'da adres otomatik dolar. Bulamazsa buraya yaz: PS5'te <b>Ayarlar › Ağ › Bağlantı Durumunu Görüntüle</b> içindeki IP adresi (örnek: 192.168.1.20).</p>
              <input name="ip" inputmode="decimal" autocomplete="off" placeholder="192.168.1.20" value="${esc(settings.consoleHost)}">
            </div>

            <div class="step s6">
              <div class="ico">🌐</div><div class="num">6. Adım</div>
              <h2>DeepL anahtarı <span class="pill">isteğe bağlı</span></h2>
              <p class="where">📍 Nerede: bu kutu (isteğe bağlı)</p>
              <p class="note">Yoksa hiçbir şey yapma. Çeviri ücretsiz ve üyeliksiz motorlarla yapılır (Google ücretsiz uç, MyMemory; internet yoksa cihaz içi ML Kit). Anahtarın varsa çeviri daha doğal olur.</p>
              <input name="deepl" autocomplete="off" autocapitalize="off" spellcheck="false" placeholder="xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx:fx">
            </div>

            <button type="submit" class="save">💾 Kaydet</button>
            ${if (secrets.psnAccountId.isNotBlank()) "<button type=\"submit\" name=\"sil\" value=\"1\" class=\"danger\">Kayıtlı PSN hesabını sil</button>" else ""}
            </form>

            <div class="step s7">
              <div class="ico">🔗</div><div class="num">7. Adım</div>
              <h2>PS5 ile eşleş</h2>
              <p class="where">📍 Nerede: önce PS5'te, sonra bu sayfada</p>
              <div class="panel"><ol>
                <li><b>PS5'te ikinci hesaba geç:</b> kumandada <b>PS</b> tuşuna bas, sağ üstteki profil simgesinden <b>ikinci hesabını</b> seç. Ekranda onun adı görünmeli. Kod, giriş yapılmış kullanıcıya bağlanır; bu adım şart.</li>
                <li>Aynı kullanıcıyla <b>Ayarlar › Sistem › Uzaktan Oynatma › Cihazı Bağla</b> yoluna gir. Ekranda <b>8 haneli bir kod</b> çıkar. Birkaç dakika geçerli; ekranı kapatma.</li>
                <li>Aşağıdaki kutuya PS5 ekranındaki 8 haneli kodu yaz ve <b>Eşleştir</b>'e bas. ${if (phone) "Telefon" else "Telefon sayfası TV'ye iletir, TV"} PS5'i kendisi bulup eşleşir (10–30 saniye sürebilir, sayfayı kapatma).</li>
                <li>Yeşil kutuda <b>"PS5 eşleşti"</b> yazısını görünce Konsol Çeviri ana ekranındaki listede PS5 adresi, PSN hesabı ve PS5 eşleşmesi ✓ olmalı.</li>
                <li>İstersen PS5'te <b>ana hesabına</b> geri dön ve oyunu aç.</li>
              </ol></div>
              <form method="post" accept-charset="utf-8">
                <input name="kod" inputmode="numeric" pattern="[0-9]{8}" maxlength="8" autocomplete="off" placeholder="8 haneli kod">
                <button type="submit" class="btn" style="width:100%">🔗 Eşleştir</button>
              </form>
              <p class="why"><b>Olmazsa:</b> kodun süresi dolmuş olabilir (yeni kod al), $dev ile PS5 farklı ağda olabilir ya da PS5'te Uzaktan Oynatma kapalı olabilir. PSN adımını tekrarlamana gerek yok.</p>
            </div>
            $step8
            """
        return page(if (phone) "Konsol Çeviri kurulumu" else "Konsol Çeviri kurulumu", body.trimIndent())
    }

    private fun page(title: String, body: String) = """<!doctype html><html lang="tr"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1"><meta name="referrer" content="no-referrer">
<title>${esc(title)}</title><style>
body{margin:0;background:#12161D;color:#F1F3F5;font:16px/1.5 system-ui,sans-serif}
main{max-width:600px;margin:0 auto;padding:20px 16px 48px}
h1{font-size:22px;margin:4px 0 8px}h2{font-size:17px;margin:26px 0 4px}
.lead,.note{color:#B7BEC9;font-size:14px;margin:4px 0}
textarea,input{box-sizing:border-box;width:100%;background:#0B0E13;color:#F1F3F5;border:1px solid #38404D;border-radius:10px;padding:12px;font:15px ui-monospace,monospace;margin-top:8px}
textarea:focus,input:focus{outline:2px solid #FFD166}
button,.btn{display:block;box-sizing:border-box;width:100%;text-align:center;padding:14px;border-radius:12px;border:0;background:#FFD166;color:#12161D;font:700 16px system-ui,sans-serif;margin-top:18px;text-decoration:none}
.ok,.bad{border-radius:10px;padding:10px 12px;margin:10px 0;font-weight:600}
.ok{background:rgba(74,222,128,.12);color:#4ADE80}.bad{background:rgba(255,99,132,.14);color:#FFB3C1}
.box{background:#181E27;border:1px solid #2B3340;border-radius:12px;padding:10px 14px;margin:14px 0}
.step{position:relative;overflow:hidden;border-radius:18px;padding:16px 16px 18px;margin:16px 0;color:#fff;box-shadow:0 6px 18px rgba(0,0,0,.35)}
.step::after{content:"";position:absolute;top:-26px;right:-26px;width:104px;height:104px;border-radius:50%;background:rgba(255,255,255,.15);pointer-events:none}
.step h2{margin:2px 0 6px;font-size:21px;color:inherit}
.step .ico{font-size:30px;line-height:1}
.step .num{font-size:13px;font-weight:800;letter-spacing:.1em;text-transform:uppercase;opacity:.9;margin-top:6px}
.step .where{margin:2px 0 8px;font-size:13.5px;font-weight:700;opacity:.95}
.step .note,.step .why{color:inherit;opacity:.95;font-size:14.5px}
.step .why{margin-top:12px;background:rgba(0,0,0,.18);border-radius:10px;padding:9px 12px}
.panel{background:rgba(0,0,0,.2);border-radius:12px;padding:10px 12px;margin-top:10px;font-size:15px}
.panel ol,.panel ul{padding-left:22px;margin:8px 0 2px}.panel li{margin:7px 0}
.warn{background:rgba(255,255,255,.22);border-left:5px solid #FFD166;border-radius:8px;padding:9px 12px;margin:8px 0;font-size:15px}
.s0{background:linear-gradient(135deg,#475569,#1E293B)}
.s1{background:linear-gradient(135deg,#4361EE,#3A0CA3)}
.s2{background:linear-gradient(135deg,#059669,#065F46)}
.s3{background:linear-gradient(135deg,#FDD063,#F5A30B);color:#2B1D00}
.s3 .panel{background:rgba(255,255,255,.4)}.s3::after{background:rgba(255,255,255,.3)}
.s4{background:linear-gradient(135deg,#F43F5E,#BE123C)}
.s5{background:linear-gradient(135deg,#0891B2,#155E75)}
.s6{background:linear-gradient(135deg,#9333EA,#6B21A8)}
.s7{background:linear-gradient(135deg,#2563EB,#1E3A8A)}
.s8{background:linear-gradient(135deg,#0D9488,#134E4A)}
.step input,.step textarea{background:#fff;color:#111;border:0}
.step input:focus,.step textarea:focus{outline:3px solid #FFD166}
.step .btn{background:#fff;color:#111;border:0;margin-top:10px}
.step code{background:rgba(0,0,0,.28);color:inherit}.s3 code{background:rgba(0,0,0,.12)}
button.save{font-size:18px;padding:16px;margin-top:8px}
button.danger{background:#3A1F28;color:#FFB3C1;border:1px solid #6B2D3C}
.step .pill{background:rgba(0,0,0,.25);color:inherit}
.pill{font-size:12px;background:#232A35;border-radius:99px;padding:2px 9px;color:#B7BEC9;font-weight:600}
</style></head><body><main><h1>${esc(title)}</h1>$body</main></body></html>"""

    private fun respond(sock: Socket, code: Int, html: String) {
        val b = html.toByteArray(Charsets.UTF_8)
        val status = when (code) { 200 -> "OK"; 404 -> "Not Found"; else -> "Error" }
        val head = "HTTP/1.1 $code $status\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${b.size}\r\nCache-Control: no-store\r\nReferrer-Policy: no-referrer\r\nConnection: close\r\n\r\n"
        sock.getOutputStream().apply { write(head.toByteArray()); write(b); flush() }
    }
}
