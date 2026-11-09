package com.playceviri.tv.link

import android.util.Base64
import android.util.Log
import com.metallic.chiaki.lib.ChiakiLog
import com.metallic.chiaki.lib.Codec
import com.metallic.chiaki.lib.ConnectInfo
import com.metallic.chiaki.lib.ConnectVideoProfile
import com.metallic.chiaki.lib.CreateError
import com.metallic.chiaki.lib.DiscoveryHost
import com.metallic.chiaki.lib.DiscoveryService
import com.metallic.chiaki.lib.DiscoveryServiceOptions
import com.metallic.chiaki.lib.Regist
import com.metallic.chiaki.lib.RegistEventCanceled
import com.metallic.chiaki.lib.RegistEventFailed
import com.metallic.chiaki.lib.RegistEventSuccess
import com.metallic.chiaki.lib.RegistInfo
import com.metallic.chiaki.lib.Target
import com.metallic.chiaki.lib.VideoFPSPreset
import com.metallic.chiaki.lib.VideoResolutionPreset
import com.playceviri.tv.data.SecretStore
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** PS5 keşfi, eşleştirme (Regist), uyandırma ve bağlantı bilgisi. */
object ConsoleLink {
    private const val TAG = "KonsolCeviri/PS5"

    sealed class PairResult {
        object Success : PairResult()
        data class Failure(val reason: String) : PairResult()
    }

    fun discover(onHosts: (List<DiscoveryHost>) -> Unit): DiscoveryService? = try {
        DiscoveryService(
            DiscoveryServiceOptions(16u, 3u, 500u, InetSocketAddress("255.255.255.255", 987))
        ) { hosts -> onHosts(hosts.filter { it.isPS5 && !it.hostAddr.isNullOrBlank() }) }
    } catch (e: CreateError) {
        Log.w(TAG, "Keşif başlatılamadı: $e"); null
    }

    /** Ağdaki PS5'i (varsa verilen adrese öncelik vererek) birkaç saniye içinde bulur. */
    fun findOnce(preferHost: String?, timeoutMs: Long = 3500): DiscoveryHost? {
        val latch = CountDownLatch(1)
        var found: DiscoveryHost? = null
        val d = discover { hosts ->
            val pick = hosts.firstOrNull { it.hostAddr == preferHost } ?: if (preferHost.isNullOrBlank()) hosts.firstOrNull() else null
            if (pick != null && found == null) { found = pick; latch.countDown() }
        }
        latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        runCatching { d?.dispose() }
        return found
    }

    fun pair(host: String, pin: String, secrets: SecretStore, onDone: (PairResult) -> Unit): Regist? {
        val account = runCatching { Base64.decode(secrets.psnAccountId, Base64.DEFAULT) }.getOrNull()
        if (account == null || account.size != 8) {
            onDone(PairResult.Failure("PSN hesabı eksik. Önce PSN girişini yap.")); return null
        }
        val p = pin.trim()
        val n = p.toIntOrNull()
        if (p.length != 8 || n == null) { onDone(PairResult.Failure("Kod 8 haneli olmalı.")); return null }
        return try {
            Regist(
                RegistInfo(Target.PS5_1, host, false, null, account, n),
                ChiakiLog(ChiakiLog.Level.ALL.value) { level, text -> Log.d(TAG, ChiakiLog.formatLog(level, text)) }
            ) { e ->
                when (e) {
                    is RegistEventSuccess -> {
                        secrets.consoleRegistKey = e.host.rpRegistKey
                        secrets.consoleMorning = e.host.rpKey
                        onDone(PairResult.Success)
                    }
                    is RegistEventFailed -> onDone(PairResult.Failure(
                        "Eşleştirme olmadı. Kontrol et: 1) PS5'te ikinci hesaba geçtin mi ve kodu o hesapla mı aldın? " +
                            "2) Kod doğru mu, süresi dolmadı mı? 3) PS5 açık ve bu cihazla aynı ağda mı? " +
                            "Hâlâ olmuyorsa \"PSN hesabını sil\" ile ikinci hesapla baştan gir."))
                    is RegistEventCanceled -> onDone(PairResult.Failure("Eşleştirme iptal edildi."))
                    else -> {}
                }
            }
        } catch (e: CreateError) {
            onDone(PairResult.Failure("Eşleştirme başlatılamadı (${e.errorCode})")); null
        }
    }

    fun connectInfo(host: String, secrets: SecretStore, hd: Boolean): ConnectInfo? {
        val reg = secrets.consoleRegistKey ?: return null
        val morning = secrets.consoleMorning ?: return null
        val res = if (hd) VideoResolutionPreset.RES_1080P else VideoResolutionPreset.RES_720P
        return ConnectInfo(true, host, reg, morning, ConnectVideoProfile.preset(res, VideoFPSPreset.FPS_30, Codec.CODEC_H264))
    }

    fun wake(host: String, secrets: SecretStore): Boolean {
        val reg = secrets.consoleRegistKey ?: return false
        val end = reg.indexOf(0).let { if (it < 0) reg.size else it }
        val cred = String(reg.copyOfRange(0, end), Charsets.UTF_8).toULongOrNull(16) ?: return false
        return try {
            DiscoveryService.wakeup(null, host, cred, true); true
        } catch (t: Throwable) { Log.w(TAG, "Uyandırma gönderilemedi", t); false }
    }
}
