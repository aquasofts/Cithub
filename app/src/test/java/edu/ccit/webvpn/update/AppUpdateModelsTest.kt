package edu.ccit.webvpn.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer

class AppUpdateModelsTest {
    @Test
    fun semanticVersion_acceptsReleaseTagsAndFlavorSuffixes() {
        assertEquals(SemanticVersion(2, 1, 35), SemanticVersion.parse("V2.1.35"))
        assertEquals(SemanticVersion(2, 1, 35), SemanticVersion.parse("2.1.35-auto-captcha"))
        assertTrue(SemanticVersion(2, 1, 35) > SemanticVersion(2, 1, 34))
        assertNull(SemanticVersion.parse("prerelease"))
        assertEquals(SemanticVersion(2, 1, 36), SemanticVersion.find("Cithub Pre-release 2.1.36"))
    }

    @Test
    fun selectsCurrentAndHistoricalAutoCaptchaAssetNames() {
        val assets = listOf(
            asset("Cithub-2.1.35-manual-captcha-performance.apk"),
            asset("Cithub-2.1.35-auto-captcha-performance.apk"),
            asset("Cithub-2.1.35-autoCaptcha-performance.apk"),
        )

        assertEquals(
            "Cithub-2.1.35-auto-captcha-performance.apk",
            selectAsset(assets, UpdateFlavor.AutoCaptcha, SemanticVersion(2, 1, 35))?.name,
        )
    }

    @Test
    fun selectsManualCaptchaWithoutCrossInstallingFullApk() {
        val assets = listOf(
            asset("Full-Cithub-V2.1.35.apk"),
            asset("Lite-Cithub-V2.1.35.apk"),
        )

        assertEquals(
            "Lite-Cithub-V2.1.35.apk",
            selectAsset(assets, UpdateFlavor.ManualCaptcha, SemanticVersion(2, 1, 35))?.name,
        )
        assertNull(
            selectAsset(
                listOf(asset("Full-Cithub-V2.1.35.apk")),
                UpdateFlavor.ManualCaptcha,
                SemanticVersion(2, 1, 35),
            ),
        )
    }

    @Test
    fun parsesPrereleaseVersionFromReleaseName() {
        val release = GitHubReleaseDto(
            tagName = "prerelease",
            name = "Cithub Pre-release 2.1.36",
            htmlUrl = "https://github.com/aquasofts/Cithub/releases/tag/prerelease",
            prerelease = true,
            assets = listOf(asset("Lite-Cithub-2.1.36-PreRelease.apk")),
        )

        assertEquals(SemanticVersion(2, 1, 36), release.toAppRelease(UpdateFlavor.ManualCaptcha)?.version)
        assertTrue(requireNotNull(release.toAppRelease(UpdateFlavor.ManualCaptcha)).prerelease)
    }

    @Test
    fun githubClientFiltersPrereleasesUnlessPreviewIsEnabled() = runBlocking {
        val server = MockWebServer()
        val response = """
                [
                {
                  "tag_name": "prerelease",
                  "name": "Cithub Pre-release 2.1.36",
                  "body": "Preview",
                  "html_url": "https://github.com/aquasofts/Cithub/releases/tag/prerelease",
                  "draft": false,
                  "prerelease": true,
                  "assets": [
                    {
                      "name": "Lite-Cithub-2.1.36-PreRelease.apk",
                      "size": 4096,
                      "browser_download_url": "https://github.com/aquasofts/Cithub/releases/download/prerelease/lite.apk"
                    }
                  ]
                },
                {
                  "tag_name": "V2.1.35",
                  "name": "Release 2.1.35",
                  "body": "Safe update",
                  "html_url": "https://github.com/aquasofts/Cithub/releases/tag/V2.1.35",
                  "draft": false,
                  "prerelease": false,
                  "assets": [
                    {
                      "name": "Cithub-2.1.35-manual-captcha-performance.apk",
                      "size": 4096,
                      "browser_download_url": "https://github.com/aquasofts/Cithub/releases/download/V2.1.35/lite.apk"
                    }
                  ]
                }
                ]
                """.trimIndent()
        server.enqueue(MockResponse().setResponseCode(200).setBody(response))
        server.enqueue(MockResponse().setResponseCode(200).setBody(response))
        try {
            val client = GitHubReleaseClient(
                userAgent = "test",
                apiUrl = server.url("/releases").toString(),
            )
            val formal = client.latest(
                flavor = UpdateFlavor.ManualCaptcha,
                includePrereleases = false,
                accelerators = emptyList(),
            )
            val preview = client.latest(
                flavor = UpdateFlavor.ManualCaptcha,
                includePrereleases = true,
                accelerators = emptyList(),
            )

            assertEquals(SemanticVersion(2, 1, 35), formal?.version)
            assertFalse(requireNotNull(formal).prerelease)
            assertEquals(SemanticVersion(2, 1, 36), preview?.version)
            assertTrue(requireNotNull(preview).prerelease)
            assertEquals("/releases", server.takeRequest().path)
        } finally {
            server.close()
        }
    }

    @Test
    fun acceleratorsKeepPriorityAndDirectGithubLast() {
        assertEquals(
            listOf(
                "https://a.example/https://github.com/aquasofts/Cithub/releases/download/V2.1.36/app.apk",
                "https://b.example/proxy/https://github.com/aquasofts/Cithub/releases/download/V2.1.36/app.apk",
                "https://github.com/aquasofts/Cithub/releases/download/V2.1.36/app.apk",
            ),
            githubUrlCandidates(
                originalUrl = "https://github.com/aquasofts/Cithub/releases/download/V2.1.36/app.apk",
                accelerators = listOf("https://a.example", "https://b.example/proxy"),
            ),
        )
    }

    @Test
    fun acceleratorCheckerRequiresAValidApkResponse() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(206).setBody("PK\u0003\u0004payload"))
        server.enqueue(MockResponse().setResponseCode(200).setBody("<html><title>Proxy home</title></html>"))
        try {
            val checker = GitHubAcceleratorChecker(
                userAgent = "test",
                probeUrl = CITHUB_ACCELERATOR_PROBE_APK,
            )
            val accelerator = server.url("/proxy").toString().trimEnd('/')

            assertTrue(checker.check(accelerator) is AcceleratorAvailability.Available)
            assertEquals(AcceleratorAvailability.Unavailable, checker.check(accelerator))
            assertEquals(
                "/proxy/https://github.com/aquasofts/Cithub/releases/download/V2.2.0/" +
                    "Cithub-2.2.0-auto-captcha-performance.apk",
                server.takeRequest().path,
            )
            assertEquals("bytes=0-3", server.takeRequest().getHeader("Range"))
        } finally {
            server.close()
        }
    }

    @Test
    fun upgradeHousekeepingDetectsExistingInstallAndVersionIncrease() {
        assertTrue(
            shouldCleanUpgradeCaches(
                previousVersionCode = null,
                currentVersionCode = 41,
                firstInstallTime = 1_000,
                lastUpdateTime = 2_000,
            ),
        )
        assertTrue(
            shouldCleanUpgradeCaches(
                previousVersionCode = 40,
                currentVersionCode = 41,
                firstInstallTime = 1_000,
                lastUpdateTime = 2_000,
            ),
        )
        assertFalse(
            shouldCleanUpgradeCaches(
                previousVersionCode = null,
                currentVersionCode = 41,
                firstInstallTime = 2_000,
                lastUpdateTime = 2_000,
            ),
        )
    }

    private fun asset(name: String) = GitHubAssetDto(
        name = name,
        size = 1_024,
        downloadUrl = "https://github.com/aquasofts/Cithub/releases/download/V2.1.35/$name",
    )
}
