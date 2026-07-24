package edu.ccit.webvpn.feature.tieba.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TiebaPagingTest {
    @Test
    fun separateThreadVisitsNeverReuseTransientScrollState() {
        val firstVisit = threadScreenStateKey(threadId = "123", postId = 0, visitId = 1)
        val secondVisit = threadScreenStateKey(threadId = "123", postId = 0, visitId = 2)

        assertNotEquals(firstVisit, secondVisit)
    }

    @Test
    fun initialPageContinuesOnlyWhenItContainsVisibleThreads() {
        assertTrue(
            shouldContinueForumPaging(
                serverHasMore = true,
                receivedThreadCount = 20,
                append = false,
                previousPage = 1,
                loadedPage = 1,
                previousThreadCount = 0,
                mergedThreadCount = 20,
            ),
        )
        assertFalse(
            shouldContinueForumPaging(
                serverHasMore = true,
                receivedThreadCount = 0,
                append = false,
                previousPage = 1,
                loadedPage = 1,
                previousThreadCount = 0,
                mergedThreadCount = 0,
            ),
        )
    }

    @Test
    fun appendStopsWhenTheServerRepeatsAPageOrAddsNoThreads() {
        assertFalse(
            shouldContinueForumPaging(
                serverHasMore = true,
                receivedThreadCount = 20,
                append = true,
                previousPage = 2,
                loadedPage = 2,
                previousThreadCount = 40,
                mergedThreadCount = 60,
            ),
        )
        assertFalse(
            shouldContinueForumPaging(
                serverHasMore = true,
                receivedThreadCount = 20,
                append = true,
                previousPage = 2,
                loadedPage = 3,
                previousThreadCount = 40,
                mergedThreadCount = 40,
            ),
        )
    }
}
