package edu.ccit.webvpn.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateDownloadStoreTest {
    @Test
    fun failedMultiConnectionAttemptRetriesSameUrlWithOneConnection() {
        val initial = record(connections = 16)

        val fallback = requireNotNull(initial.nextDownloadAttempt())

        assertEquals(0, fallback.urlIndex)
        assertTrue(fallback.singleConnectionFallback)
        assertEquals(1, fallback.activeConnections)
    }

    @Test
    fun failedSingleConnectionFallbackAdvancesAndRestoresConfiguredConnections() {
        val initial = record(connections = 16, singleConnectionFallback = true)

        val nextRoute = requireNotNull(initial.nextDownloadAttempt())

        assertEquals(1, nextRoute.urlIndex)
        assertFalse(nextRoute.singleConnectionFallback)
        assertEquals(16, nextRoute.activeConnections)
    }

    @Test
    fun finalSingleConnectionFailureExhaustsCandidates() {
        val finalAttempt = record(
            connections = 16,
            urlIndex = 1,
            singleConnectionFallback = true,
        )

        assertNull(finalAttempt.nextDownloadAttempt())
    }

    @Test
    fun configuredSingleConnectionMovesDirectlyToNextCandidate() {
        val nextRoute = requireNotNull(record(connections = 1).nextDownloadAttempt())

        assertEquals(1, nextRoute.urlIndex)
        assertFalse(nextRoute.singleConnectionFallback)
        assertEquals(1, nextRoute.activeConnections)
    }

    private fun record(
        connections: Int,
        urlIndex: Int = 0,
        singleConnectionFallback: Boolean = false,
    ) = UpdateDownloadRecord(
        status = UpdateDownloadStatus.Downloading,
        destinationPath = "C:/tmp/Cithub.apk",
        version = "2.2.2",
        tagName = "V2.2.2",
        title = "Cithub 2.2.2",
        notes = "",
        pageUrl = "https://github.com/aquasofts/Cithub/releases/tag/V2.2.2",
        assetName = "Cithub-2.2.2.apk",
        assetSize = 1_024L,
        assetUrl = "https://github.com/aquasofts/Cithub/releases/download/V2.2.2/Cithub.apk",
        prerelease = false,
        downloadUrls = listOf(
            "https://mirror.example/Cithub.apk",
            "https://github.com/aquasofts/Cithub/releases/download/V2.2.2/Cithub.apk",
        ),
        urlIndex = urlIndex,
        connections = connections,
        singleConnectionFallback = singleConnectionFallback,
    )
}
