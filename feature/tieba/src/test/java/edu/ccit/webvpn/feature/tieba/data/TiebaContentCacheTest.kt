package edu.ccit.webvpn.feature.tieba.data

import edu.ccit.webvpn.feature.tieba.FloorReply
import edu.ccit.webvpn.feature.tieba.FloorSort
import edu.ccit.webvpn.feature.tieba.ForumPage
import edu.ccit.webvpn.feature.tieba.ForumSort
import edu.ccit.webvpn.feature.tieba.ForumSummary
import edu.ccit.webvpn.feature.tieba.ForumThread
import edu.ccit.webvpn.feature.tieba.ThreadFloor
import edu.ccit.webvpn.feature.tieba.ThreadPage
import edu.ccit.webvpn.feature.tieba.TiebaContent
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TiebaContentCacheTest {
    private lateinit var directory: File
    private lateinit var cache: TiebaContentCache

    @Before
    fun setUp() {
        directory = createTempDirectory("tieba-content-cache-test").toFile()
        cache = TiebaContentCache(directory)
    }

    @After
    fun tearDown() {
        directory.deleteRecursively()
    }

    @Test
    fun forumAndThreadContentRoundTripUntilExpiry() = runBlocking {
        val savedAt = 10_000L
        val forum = ForumPage(
            forum = ForumSummary(id = "64554", name = "campus", slogan = "hello"),
            threads = listOf(
                ForumThread(
                    id = "123",
                    title = "Cached thread",
                    excerpt = "preview",
                    authorName = "author",
                    authorNickname = "nickname",
                    authorPortrait = "https://example.com/avatar.jpg",
                    replyCount = "2",
                    viewCount = "10",
                    lastReplyTime = "now",
                    isTop = false,
                    isGood = true,
                    imageUrls = listOf("https://example.com/preview.jpg"),
                    richExcerpt = listOf(TiebaContent.Text("rich preview")),
                ),
            ),
            page = 1,
            hasMore = true,
        )
        val floor = ThreadFloor(
            postId = "1",
            floor = 1,
            authorName = "author",
            authorNickname = "nickname",
            authorPortrait = "https://example.com/avatar.jpg",
            content = "body",
            time = "now",
            imageUrls = listOf("https://example.com/image.jpg"),
            videoUrls = emptyList(),
            replies = listOf(
                FloorReply(
                    authorName = "reply-author",
                    content = "reply",
                    time = "later",
                    richContent = listOf(TiebaContent.Emoticon("image_emoticon1", "smile")),
                ),
            ),
            richContent = listOf(
                TiebaContent.Image(
                    previewUrl = "https://example.com/preview.jpg",
                    originalUrl = "https://example.com/original.jpg",
                    width = 640,
                    height = 480,
                ),
            ),
        )
        val thread = ThreadPage(
            threadId = "123",
            title = "Cached thread",
            floors = emptyList(),
            page = 1,
            totalPages = 2,
            replyCount = 2,
            body = floor,
            forumId = 64554L,
            forumName = "campus",
        )

        cache.writeForum("campus", ForumSort.BY_REPLY, false, forum, savedAt)
        cache.writeThread("123", FloorSort.ASCENDING, false, 64554L, "campus", null, thread, savedAt)

        val cachedForum = cache.readForum("campus", ForumSort.BY_REPLY, false, savedAt + 1)
        val cachedThread = cache.readThread(
            "123",
            FloorSort.ASCENDING,
            false,
            64554L,
            "campus",
            null,
            savedAt + 1,
        )
        assertEquals("Cached thread", cachedForum?.value?.threads?.single()?.title)
        assertEquals(TiebaContent.Text("rich preview"), cachedForum?.value?.threads?.single()?.richExcerpt?.single())
        assertEquals("body", cachedThread?.value?.body?.content)
        assertEquals(640, (cachedThread?.value?.body?.richContent?.single() as TiebaContent.Image).width)

        assertNull(
            cache.readForum(
                "campus",
                ForumSort.BY_REPLY,
                false,
                savedAt + TIEBA_CACHE_EXPIRY_MILLIS,
            ),
        )
    }

    @Test
    fun refreshIntervalHasAStableBoundary() {
        val refreshedAt = 20_000L

        assertFalse(shouldRefreshTiebaContent(refreshedAt, refreshedAt + TIEBA_MIN_REFRESH_INTERVAL_MILLIS - 1))
        assertTrue(shouldRefreshTiebaContent(refreshedAt, refreshedAt + TIEBA_MIN_REFRESH_INTERVAL_MILLIS))
        assertTrue(shouldRefreshTiebaContent(0L, refreshedAt))
        assertTrue(shouldRefreshTiebaContent(refreshedAt, refreshedAt - 1))
    }

    @Test
    fun corruptOrUnavailableCacheFailsClosed() = runBlocking {
        val forum = ForumPage(ForumSummary(id = "1", name = "campus"), emptyList(), 1, false)
        cache.writeForum("campus", ForumSort.BY_REPLY, false, forum, savedAtMillis = 10_000L)
        directory.listFiles().orEmpty().single { it.extension == "json" }
            .writeText("{\"version\":1,\"savedAtMillis\":10000,\"value\":null}")

        assertNull(cache.readForum("campus", ForumSort.BY_REPLY, false, nowMillis = 10_001L))

        val blockedPath = File(directory, "not-a-directory").apply { writeText("blocked") }
        TiebaContentCache(blockedPath).writeForum(
            "campus",
            ForumSort.BY_REPLY,
            false,
            forum,
            savedAtMillis = 10_000L,
        )
        assertTrue(blockedPath.isFile)
    }
}
