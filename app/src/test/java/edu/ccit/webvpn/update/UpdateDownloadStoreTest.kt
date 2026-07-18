package edu.ccit.webvpn.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UpdateDownloadStoreTest {
    @Test
    fun failedAttemptAdvancesToNextCandidate() {
        val nextRoute = requireNotNull(record().nextDownloadAttempt())

        assertEquals(1, nextRoute.urlIndex)
        assertEquals(UpdateDownloadStatus.Queued, nextRoute.status)
        assertEquals(0L, nextRoute.downloadedBytes)
        assertEquals(0L, nextRoute.speedBytesPerSecond)
    }

    @Test
    fun finalFailureExhaustsCandidates() {
        assertNull(record(urlIndex = 1).nextDownloadAttempt())
    }

    private fun record(urlIndex: Int = 0) = UpdateDownloadRecord(
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
    )
}
