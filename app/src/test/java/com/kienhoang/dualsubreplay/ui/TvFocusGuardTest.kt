package com.kienhoang.dualsubreplay.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Compose maps ▲/▼ to the cursor in every text field (single-line too, `KeyCommand.UP/DOWN`) and a
 * Material slider takes them as value steps, so on a remote each is a focus trap unless it hands them
 * on with `tvArrowsLeave()` (spec 2026-10-30-android-tv-layout.md). This guard only checks that the
 * modifier is present; the behaviour is in `TvArrowsLeaveTest` and the device tests.
 */
class TvFocusGuardTest {
    private val sources =
        File("src/main/java/com/kienhoang/dualsubreplay/ui").listFiles { f -> f.extension == "kt" }.orEmpty()

    /** Calls of [call] whose next [window] lines satisfy [trapsWhen] yet lack `tvArrowsLeave`, as `file:line`. */
    private fun unguarded(
        call: String,
        window: Int,
        trapsWhen: (String) -> Boolean,
    ): List<String> =
        sources.flatMap { file ->
            val lines = file.readLines()
            lines.indices
                .filter { Regex("""(^|[^A-Za-z])$call\(""").containsMatchIn(lines[it]) && !lines[it].contains("fun ") }
                .filter { i ->
                    val block = lines.subList(i, minOf(lines.size, i + window)).joinToString("\n")
                    trapsWhen(block) && !block.contains("tvArrowsLeave")
                }.map { "${file.name}:${it + 1}" }
        }

    @Test
    fun everySliderLetsTheArrowsLeave() {
        assertTrue(sources.isNotEmpty())
        // The held-slider copy in SettingsPeek only shows a value (`onValueChange = {}`) and takes no keys.
        val bad = unguarded("Slider", 14) { !it.contains("onValueChange = {}") }
        assertTrue("Sliders without tvArrowsLeave(): $bad", bad.isEmpty())
    }

    @Test
    fun everyTextFieldLetsTheArrowsLeave() {
        val bad = unguarded("OutlinedTextField", 16) { true } + unguarded("TextField", 16) { true }
        assertTrue("Text fields without tvArrowsLeave(): $bad", bad.isEmpty())
    }
}
