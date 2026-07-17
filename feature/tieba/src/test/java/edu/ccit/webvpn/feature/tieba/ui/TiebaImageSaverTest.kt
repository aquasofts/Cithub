package edu.ccit.webvpn.feature.tieba.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class TiebaImageSaverTest {
    @Test
    fun `mime type takes priority over signed url suffix`() {
        assertEquals(
            "png",
            imageExtension("image/png; charset=binary", "https://example.com/photo.jpg?sign=abc"),
        )
    }

    @Test
    fun `jpeg extension is normalized to jpg`() {
        assertEquals("jpg", imageExtension("image/jpeg", "https://example.com/photo"))
    }

    @Test
    fun `url suffix is used for generic response type`() {
        assertEquals("webp", imageExtension("application/octet-stream", "https://example.com/photo.WEBP?x=1"))
    }

    @Test
    fun `unknown image format falls back to jpg`() {
        assertEquals("jpg", imageExtension(null, "https://example.com/photo"))
    }
}
