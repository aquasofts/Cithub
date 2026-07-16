package edu.ccit.webvpn.feature.tieba.network

import android.content.Context
import edu.ccit.webvpn.feature.tieba.FloorSort
import edu.ccit.webvpn.feature.tieba.LoadPicPageData
import edu.ccit.webvpn.feature.tieba.TiebaContent
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/** One-shot live verification; removed after its result has been recorded. */
@RunWith(RobolectricTestRunner::class)
class LivePicPageFormalVerificationTest {
    private val context: Context = RuntimeEnvironment.getApplication()

    @Test
    fun productionResolverReturnsRealImageInsteadOfTiebaPlaceholder() = runBlocking {
        val repository = TiebaNetworkRepository.create(context)
        val threadId = 10779378421L
        val thread = repository.loadThread(threadId.toString(), 1, FloorSort.ASCENDING, false)
        val floor = (listOfNotNull(thread.body) + thread.floors)
            .first { post -> post.richContent.any { it is TiebaContent.Image } }
        val image = floor.richContent.filterIsInstance<TiebaContent.Image>().first()
        val resolved = repository.resolveOriginalImage(
            LoadPicPageData(
                forumId = thread.forumId,
                forumName = thread.forumName,
                seeLz = false,
                objType = "pb",
                picId = image.picId,
                picIndex = 1,
                threadId = threadId,
                postId = floor.postId.toLong(),
                originUrl = image.originalUrl,
            ),
        )

        assertTrue(resolved.contains("tbpicau="))
        val realBytes = download(resolved)
        val unsigned = "https://tiebapic.baidu.com/forum/pic/item/${image.picId}.jpg"
        val placeholderBytes = download(unsigned)
        val real = ImageIO.read(ByteArrayInputStream(realBytes))
        val placeholder = ImageIO.read(ByteArrayInputStream(placeholderBytes))

        assertTrue(realBytes.size > 1_000)
        assertFalse(realBytes.contentEquals(placeholderBytes))
        assertNotEquals(238 to 238, real.width to real.height)
        assertTrue(placeholder.width == 238 && placeholder.height == 238)
        println(
            "FORMAL_REAL_IMAGE resolved=$resolved bytes=${realBytes.size} " +
                "dimensions=${real.width}x${real.height}; placeholder=${placeholder.width}x${placeholder.height}",
        )
    }

    private fun download(url: String): ByteArray = OkHttpClient().newCall(
        Request.Builder().url(url).header("Referer", "https://tieba.baidu.com/").build(),
    ).execute().use { response ->
        assertTrue("HTTP ${response.code} for $url", response.isSuccessful)
        response.body!!.bytes()
    }
}
