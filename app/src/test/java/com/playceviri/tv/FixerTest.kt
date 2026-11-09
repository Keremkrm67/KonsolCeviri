package com.playceviri.tv

import com.playceviri.tv.vision.OcrFixer
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class FixerTest {
    private val fx by lazy { OcrFixer.load(File("src/main/assets/sozluk-en.txt").readText()) }
    private fun chk(inp: String, exp: String) { val r = fx.fix(inp); println("$inp\n  → ${r.text}  ${r.changes}"); assertEquals(exp, r.text) }

    @Test fun droppedThinLetters() {
        chk("the criminals are believed to have escaped wit millions o do ars wort of", "the criminals are believed to have escaped with millions of dollars worth of")
        chk("Whatever the hell it is you're ta in' about.", "Whatever the hell it is you're talkin' about.")
        chk("host, and I've ot to hear your crap?", "host, and I've got to hear your crap?")
    }

    @Test fun correctTextUntouched() {
        for (s in listOf("If that fruitcake realizes, no, no, finds out you're alive,", "JOEL: We need to move. Now!", "I can't believe you did this to me.",
                "think I'm gonna need a bigger car, homie", "You call this or what?", "Really?",
                "Get in the car.", "Let's go, Arthur!", "We ain't got time for this.", "Michael, you there?", "FRANKLIN: Yo, what up, homie?",
                "I'm gonna kill you, Trevor!", "Keep your head down.", "Ellie, stay close.", "Hold on, hold on.", "Shit, the cops!",
                "Lamar, you dumb ass.", "Dutch has a plan.", "We gotta find Tommy and Abby.", "Kratos, the boy is ready.", "Boy, eat your food.",
                "Ain't nobody gonna stop us now.", "Y'all hear that?", "Stay here, Atreus.", "It's the feds!", "He's down, he's down!"))
            chk(s, s)
    }
}
