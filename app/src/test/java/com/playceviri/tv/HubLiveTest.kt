package com.playceviri.tv

import com.playceviri.tv.browser.BrowserHub
import com.playceviri.tv.vision.Gray
import com.playceviri.tv.vision.GrayImage
import org.json.JSONObject
import org.junit.Assume
import org.junit.Test
import java.io.File

/** Elle çalıştırılır (HUB_LIVE=1): tarayıcı sayfası ile uçtan uca okuma denemesi. */
class HubLiveTest {
    @Test fun live() {
        Assume.assumeTrue(System.getenv("HUB_LIVE") == "1")
        val web = File(System.getenv("HUB_WEB") ?: File(System.getProperty("user.dir"), "src/main/assets/web").path)
        val result = File("/tmp/claude-0/hub_result.txt"); result.delete()
        lateinit var hub: BrowserHub
        val texts = listOf("I can't believe you did this to me.", "JOEL: We need to move. Now!")
        var i = 0
        fun sendNext() {
            if (i >= texts.size) return
            val g = if (i == 0) GrayImage(File("/tmp/claude-0/zor.raw").readBytes(), 1840, 540) else render(texts[i])
            val iso = com.playceviri.tv.vision.SubtitleIsolator.isolate(g)!!
            hub.sendImage(100 + i, iso.image.width, iso.image.height, true, com.playceviri.tv.vision.SubtitleIsolator.pack(iso.image))
            i++
        }
        hub = BrowserHub({ n -> File(web, n).readBytes() }, "deneme123", { m ->
            when (m.optString("t")) {
                "ready" -> {
                    hub.send(JSONObject().put("t","trmodes").put("cur", 0).put("list", org.json.JSONArray(listOf("Otomatik · 6 ücretsiz çevrimiçi motor","Yalnız cihaz içi","Yalnız Google","Yalnız MyMemory","Yalnız DeepL","Yalnız Microsoft","Yalnız Lingva"))))
                    val jpg = java.util.Base64.getEncoder().encodeToString(File("/tmp/claude-0/kare.jpg").readBytes())
                    hub.send(JSONObject().put("t", "frame").put("jpg", jpg).put("region", org.json.JSONArray(listOf(0.03, 0.55, 0.97, 0.99))))
                    sendNext()
                }
                "ocr" -> { result.appendText(m.toString() + "\n"); sendNext()
                    if (i >= texts.size) hub.send(JSONObject().put("t", "translate").put("seq", 7).put("text", "We need to move.").put("src", "en").put("target", "tr").put("mode", 0).put("terms", org.json.JSONArray())) }
                "trtest" -> { val d = if (m.optString("only") == "keys") "keys" else "list"; result.appendText(m.toString() + "\n"); hub.send(JSONObject().put("t","trtest").put("dest", d).put("name","Gemini").put("ok",true).put("text","Bunu bana yaptığına inanamıyorum.").put("ms",320)); hub.send(JSONObject().put("t","trtest").put("dest", d).put("name","Groq").put("ok",false).put("why","Groq anahtarı hatalı").put("ms",210)); hub.send(JSONObject().put("t","trtest").put("dest", d).put("done",true)) }
                "keys" -> result.appendText(m.toString() + "\n")
                "trmode" -> { result.appendText(m.toString() + "\n"); hub.send(JSONObject().put("t","trmodes").put("cur", m.optInt("mode")).put("list", org.json.JSONArray(listOf("Otomatik · 6 ücretsiz çevrimiçi motor","Yalnız cihaz içi","Yalnız Google","Yalnız MyMemory","Yalnız DeepL","Yalnız Microsoft","Yalnız Lingva")))) }
                "hold" -> { result.appendText(m.toString() + "\n"); hub.send(JSONObject().put("t","hold").put("ms", m.optInt("ms"))) }
                "tr", "log", "hello", "region" -> result.appendText(m.toString().take(160) + "\n")
            }
        }, { c, ua -> result.appendText("client=$c $ua\n") })
        check(hub.start())
        File("/tmp/claude-0/hub_port.txt").writeText(hub.port.toString())
        Thread.sleep(150_000)
        hub.stop()
    }

    private fun render(s: String): GrayImage {
        val idx = if (s.startsWith("I can")) 0 else 1
        return GrayImage(File("/tmp/claude-0/sub$idx.raw").readBytes(), 1500, 120)
    }
}
