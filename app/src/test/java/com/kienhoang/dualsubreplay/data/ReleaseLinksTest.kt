package com.kienhoang.dualsubreplay.data

import com.kienhoang.dualsubreplay.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.util.Properties

class ReleaseLinksTest {
    @Test
    fun theBuildEmbedsTheTwoReleasePages() {
        assertEquals("https://github.com/hoangkien1703/dual-sub-replay/releases", BuildConfig.UPSTREAM_RELEASES_URL)
        assertEquals("https://github.com/mrAlishah/triple-sub-replay/releases", BuildConfig.FORK_RELEASES_URL)
    }

    @Test
    fun theEmbeddedPagesComeFromTheCommittedConfigFile() {
        val file =
            generateSequence(
                File("").absoluteFile,
            ) { it.parentFile }.map { File(it, "release-links.properties") }.first { it.exists() }
        val properties = Properties().apply { file.inputStream().use(::load) }

        fun page(prefix: String) =
            "https://github.com/${properties.getProperty("$prefix.owner")}/${properties.getProperty("$prefix.repo")}/releases"
        assertEquals(page("upstream"), BuildConfig.UPSTREAM_RELEASES_URL)
        assertEquals(page("fork"), BuildConfig.FORK_RELEASES_URL)
    }

    @Test
    fun theAutomaticUpdaterAsksTheForkRepository() {
        assertEquals("mrAlishah/triple-sub-replay", UPDATE_REPOSITORY)
        assertEquals("com.kienhoang.dualsubreplay", OFFICIAL_APPLICATION_ID)
        assertEquals(BuildConfig.FORK_RELEASES_URL, UPDATE_RELEASES_PAGE_URL)
        assertEquals("TripleSub-Replay.apk", OFFICIAL_APK_NAME)
    }

    @Test
    fun remoteSwitchesAndProblemReportsStayWithTheOriginalRepository() {
        assertEquals("hoangkien1703/dual-sub-replay", UPSTREAM_REPOSITORY)
        assertEquals(
            "https://raw.githubusercontent.com/hoangkien1703/dual-sub-replay/main/remote-config/switches.json",
            REMOTE_SWITCHES_URL,
        )
    }
}
