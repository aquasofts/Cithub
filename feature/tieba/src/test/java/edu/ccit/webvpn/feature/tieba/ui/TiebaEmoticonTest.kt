package edu.ccit.webvpn.feature.tieba.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class TiebaEmoticonTest {
    @Test
    fun bundledClassicEmoticonsUseAssets() {
        assertEquals(
            "file:///android_asset/emoticon/image_emoticon25.webp",
            tiebaEmoticonModel("image_emoticon25"),
        )
    }

    @Test
    fun dynamicEmoticonsUseTiebaLiteHttpEndpoint() {
        assertEquals(
            "http://static.tieba.baidu.com/tb/editor/images/client/image_emoticon130.png",
            tiebaEmoticonModel("image_emoticon130"),
        )
        assertEquals(
            "http://static.tieba.baidu.com/tb/editor/images/client/shoubai_emoji12.png",
            tiebaEmoticonModel("shoubai_emoji12"),
        )
    }
}
