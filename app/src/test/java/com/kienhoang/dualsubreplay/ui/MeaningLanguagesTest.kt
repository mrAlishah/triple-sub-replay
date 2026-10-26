package com.kienhoang.dualsubreplay.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class MeaningLanguagesTest {
    @Test fun germanWordGetsPersianBesideItsEnglishPrimary() {
        assertEquals(listOf("fa"), extraMeaningLanguages("de", "en", "de", "en", true, "fa"))
    }

    @Test fun secondLineWordGetsSourceAsPrimaryAndThirdAsExtra() {
        assertEquals(listOf("fa"), extraMeaningLanguages("en", "de", "de", "en", true, "fa"))
    }

    @Test fun thirdLineWordGetsSecondAsExtra() {
        assertEquals(listOf("en"), extraMeaningLanguages("fa", "de", "de", "en", true, "fa"))
    }

    @Test fun offThirdSlotAddsNothing() {
        assertEquals(emptyList<String>(), extraMeaningLanguages("de", "en", "de", "en", true, null))
    }

    @Test fun hiddenSecondLineIsNotAnExtraMeaning() {
        assertEquals(emptyList<String>(), extraMeaningLanguages("fa", "de", "de", "en", false, "fa"))
    }

    @Test fun duplicatesAndRegionTagsCollapse() {
        assertEquals(listOf("fa"), extraMeaningLanguages("de", "en", "de", "en", true, "fa"))
        assertEquals(emptyList<String>(), extraMeaningLanguages("de", "en", "de", "en", true, "en"))
    }
}
