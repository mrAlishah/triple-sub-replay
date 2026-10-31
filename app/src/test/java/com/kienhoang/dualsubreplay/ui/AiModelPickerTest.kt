package com.kienhoang.dualsubreplay.ui

import com.kienhoang.dualsubreplay.assistant.AiAssistantSettings
import com.kienhoang.dualsubreplay.assistant.AiProvider
import org.junit.Assert.assertEquals
import org.junit.Test

class AiModelPickerTest {
    private val models =
        listOf(AiModelChoice("gemini-flash-latest", "Gemini Flash"), AiModelChoice("gemini-pro-latest", "gemini-pro-latest"))

    @Test
    fun theItemIsDerivedFromTheTypedModel() {
        assertEquals(AiModelPickerValue.Listed(models[0]), aiModelPickerValue("gemini-flash-latest", models))
        assertEquals(AiModelPickerValue.Listed(models[0]), aiModelPickerValue("  gemini-flash-latest ", models))
    }

    @Test
    fun aTypedModelThatIsNotListedIsCustomAndStaysTyped() {
        assertEquals(AiModelPickerValue.Custom, aiModelPickerValue("claude-some-future-model", models))
        // Even before the list arrives, or when it never does, a typed id is Custom, not replaced.
        assertEquals(AiModelPickerValue.Custom, aiModelPickerValue("custom-provider-model-v2", emptyList()))
    }

    @Test
    fun aBlankModelShowsNoItem() {
        assertEquals(AiModelPickerValue.Empty, aiModelPickerValue("", models))
        assertEquals(AiModelPickerValue.Empty, aiModelPickerValue("   ", models))
    }

    @Test
    fun theTextBoxValueIsTheOnlyModelTheRequestUses() {
        val typed = AiAssistantSettings(models = mapOf(AiProvider.GEMINI to "gemini-flash-lite-latest"), provider = AiProvider.GEMINI)
        assertEquals("gemini-flash-lite-latest", typed.modelFor(AiProvider.GEMINI))
        // A blank box falls back to the provider default instead of sending an empty model.
        val blank = AiAssistantSettings(models = mapOf(AiProvider.GEMINI to "  "), provider = AiProvider.GEMINI)
        assertEquals(AiProvider.GEMINI.defaultModel, blank.modelFor(AiProvider.GEMINI))
    }

    @Test
    fun eachServiceKeepsItsOwnModel() {
        val both = AiAssistantSettings(models = mapOf(AiProvider.GEMINI to "g-model", AiProvider.OPENAI to "o-model"))
        assertEquals("g-model", both.modelFor(AiProvider.GEMINI))
        assertEquals("o-model", both.modelFor(AiProvider.OPENAI))
    }
}
