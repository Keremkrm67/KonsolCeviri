package com.playceviri.tv

import com.playceviri.tv.browser.BrowserHub
import com.playceviri.tv.vision.GrayImage
import com.playceviri.tv.vision.SubtitleIsolator
import org.junit.Assume
import org.junit.Test
import java.io.File

/** Elle çalıştırılır (BENCH=1): GTA benzeri örnekleri ayıklayıp tarayıcıdaki Tesseract'a okutur. */
class OcrBenchTest {
    @Test fun bench() {
        Assume.assumeTrue(System.getenv("BENCH") == "1")
        val web = File(System.getenv("HUB_WEB") ?: "src/main/assets/web")
        val dir = File("/tmp/claude-0/gta")
        val files = dir.listFiles { f -> f.name.endsWith(".raw") }!!.sortedBy { it.name }
        val out = File("/tmp/claude-0/bench.txt"); out.writeText("")
        lateinit var hub: BrowserHub
        var i = 0
        fun next() {
            while (i < files.size) {
                val f = files[i]; i++
                val t0 = System.nanoTime()
                val iso = SubtitleIsolator.isolate(GrayImage(f.readBytes(), if (f.name.startsWith("g")) 1690 else 1840, when { f.name.startsWith("g") -> 227; f.name.startsWith("ince") -> 300; else -> 540 }))
                val ms = (System.nanoTime() - t0) / 1_000_000
                if (iso == null) { out.appendText("${f.name}\tAYIKLANAMADI\n"); continue }
                File(dir, f.name.replace(".raw", ".iso.pgm")).outputStream().use { it.write("P5\n${iso.image.width} ${iso.image.height}\n255\n".toByteArray()); it.write(iso.image.data) }
                out.appendText("${f.name}\t${ms}ms\t")
                hub.sendImage(i, iso.image.width, iso.image.height, true, SubtitleIsolator.pack(iso.image))
                return
            }
            out.appendText("BITTI\n")
        }
        hub = BrowserHub({ n -> File(web, n).readBytes() }, "deneme123", { m ->
            when (m.optString("t")) {
                "ready" -> if (i == 0) next()
                "ocr" -> { val a = m.optJSONArray("lines"); val t = (0 until (a?.length() ?: 0)).joinToString(" | ") { a!!.getJSONArray(it).getString(0) }; out.appendText("$t\n"); next() }
            }
        }, { _, _ -> })
        check(hub.start())
        File("/tmp/claude-0/hub_port.txt").writeText(hub.port.toString())
        Thread.sleep(80_000)
        hub.stop()
    }
}
