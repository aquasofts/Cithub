package edu.ccit.webvpn.feature.tieba.data

import android.util.AtomicFile
import com.google.gson.Gson
import edu.ccit.webvpn.feature.tieba.FloorReply
import edu.ccit.webvpn.feature.tieba.FloorSort
import edu.ccit.webvpn.feature.tieba.ForumPage
import edu.ccit.webvpn.feature.tieba.ForumSort
import edu.ccit.webvpn.feature.tieba.ForumSummary
import edu.ccit.webvpn.feature.tieba.ForumThread
import edu.ccit.webvpn.feature.tieba.ThreadFloor
import edu.ccit.webvpn.feature.tieba.ThreadPage
import edu.ccit.webvpn.feature.tieba.TiebaContent
import edu.ccit.webvpn.feature.tieba.TiebaModeratorRole
import edu.ccit.webvpn.feature.tieba.normalizeForumName
import java.io.File
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal const val TIEBA_MIN_REFRESH_INTERVAL_MILLIS = 5L * 60L * 1000L
internal const val TIEBA_CACHE_EXPIRY_MILLIS = 7L * 24L * 60L * 60L * 1000L

internal data class CachedTiebaContent<T>(
    val value: T,
    val savedAtMillis: Long,
)

internal fun shouldRefreshTiebaContent(
    lastRefreshedAtMillis: Long,
    nowMillis: Long = System.currentTimeMillis(),
): Boolean {
    if (lastRefreshedAtMillis <= 0L) return true
    val age = nowMillis - lastRefreshedAtMillis
    return age < 0L || age >= TIEBA_MIN_REFRESH_INTERVAL_MILLIS
}

internal class TiebaContentCache(
    private val directory: File,
    private val gson: Gson = Gson(),
) {
    suspend fun readForum(
        forumName: String,
        sort: ForumSort,
        goodOnly: Boolean,
        nowMillis: Long = System.currentTimeMillis(),
    ): CachedTiebaContent<ForumPage>? = withContext(Dispatchers.IO) {
        val target = cacheFile("forum", forumKey(forumName, sort, goodOnly))
        val envelope = read(target, ForumCacheEnvelope::class.java) ?: return@withContext null
        runCatching {
            envelope.takeIfFresh(target, nowMillis)?.let {
                CachedTiebaContent(it.value.toModel(), it.savedAtMillis)
            }
        }.getOrNull()
    }

    suspend fun writeForum(
        forumName: String,
        sort: ForumSort,
        goodOnly: Boolean,
        page: ForumPage,
        savedAtMillis: Long = System.currentTimeMillis(),
    ) = withContext(Dispatchers.IO) {
        write(
            cacheFile("forum", forumKey(forumName, sort, goodOnly)),
            ForumCacheEnvelope(savedAtMillis = savedAtMillis, value = page.toCache()),
        )
    }

    suspend fun readThread(
        threadId: String,
        sort: FloorSort,
        onlyOriginalPoster: Boolean,
        forumId: Long,
        forumName: String,
        focusPostId: String?,
        nowMillis: Long = System.currentTimeMillis(),
    ): CachedTiebaContent<ThreadPage>? = withContext(Dispatchers.IO) {
        val target = cacheFile(
            "thread",
            threadKey(threadId, sort, onlyOriginalPoster, forumId, forumName, focusPostId),
        )
        val envelope = read(target, ThreadCacheEnvelope::class.java) ?: return@withContext null
        runCatching {
            envelope.takeIfFresh(target, nowMillis)?.let {
                CachedTiebaContent(it.value.toModel(), it.savedAtMillis)
            }
        }.getOrNull()
    }

    suspend fun writeThread(
        threadId: String,
        sort: FloorSort,
        onlyOriginalPoster: Boolean,
        forumId: Long,
        forumName: String,
        focusPostId: String?,
        page: ThreadPage,
        savedAtMillis: Long = System.currentTimeMillis(),
    ) = withContext(Dispatchers.IO) {
        write(
            cacheFile(
                "thread",
                threadKey(threadId, sort, onlyOriginalPoster, forumId, forumName, focusPostId),
            ),
            ThreadCacheEnvelope(savedAtMillis = savedAtMillis, value = page.toCache()),
        )
    }

    private fun forumKey(forumName: String, sort: ForumSort, goodOnly: Boolean): String =
        "${normalizeForumName(forumName)}:${sort.name}:$goodOnly"

    private fun threadKey(
        threadId: String,
        sort: FloorSort,
        onlyOriginalPoster: Boolean,
        forumId: Long,
        forumName: String,
        focusPostId: String?,
    ): String = listOf(
        threadId,
        sort.name,
        onlyOriginalPoster.toString(),
        forumId.toString(),
        normalizeForumName(forumName),
        focusPostId.orEmpty(),
    ).joinToString(":")

    private fun cacheFile(prefix: String, key: String): File = File(directory, "${prefix}_${key.sha256()}.json")

    private fun <T> read(target: File, type: Class<T>): T? = runCatching {
        val atomic = AtomicFile(target)
        if (!atomic.baseFile.isFile || atomic.baseFile.length() !in 1..MAX_CACHE_FILE_BYTES) return@runCatching null
        atomic.openRead().bufferedReader(StandardCharsets.UTF_8).use { gson.fromJson(it, type) }
    }.getOrNull()

    private fun write(target: File, value: Any) {
        val directoryReady = runCatching { directory.exists() || directory.mkdirs() }.getOrDefault(false)
        if (!directoryReady) return
        val bytes = runCatching { gson.toJson(value).toByteArray(StandardCharsets.UTF_8) }.getOrNull() ?: return
        if (bytes.isEmpty() || bytes.size > MAX_CACHE_FILE_BYTES) return
        val atomic = AtomicFile(target)
        val stream = runCatching { atomic.startWrite() }.getOrNull() ?: return
        try {
            stream.write(bytes)
            stream.flush()
            atomic.finishWrite(stream)
        } catch (_: Exception) {
            runCatching { atomic.failWrite(stream) }
            return
        }
        runCatching { trimCache() }
    }

    private fun trimCache(nowMillis: Long = System.currentTimeMillis()) {
        val files = directory.listFiles()?.filter(File::isFile).orEmpty()
        files.filter { file ->
            val modifiedAt = file.lastModified()
            modifiedAt <= 0L || modifiedAt > nowMillis || nowMillis - modifiedAt >= TIEBA_CACHE_EXPIRY_MILLIS
        }.forEach(File::delete)
        directory.listFiles()
            ?.filter(File::isFile)
            ?.sortedByDescending(File::lastModified)
            ?.drop(MAX_CACHE_FILES)
            ?.forEach(File::delete)
    }

    private fun <T : CacheEnvelope> T.takeIfFresh(target: File, nowMillis: Long): T? {
        val age = nowMillis - savedAtMillis
        if (version != CACHE_VERSION || savedAtMillis <= 0L || age < 0L || age >= TIEBA_CACHE_EXPIRY_MILLIS) {
            runCatching { AtomicFile(target).delete() }
            return null
        }
        return this
    }

    private companion object {
        const val CACHE_VERSION = 1
        const val MAX_CACHE_FILE_BYTES = 8L * 1024L * 1024L
        const val MAX_CACHE_FILES = 64
    }

    private interface CacheEnvelope {
        val version: Int
        val savedAtMillis: Long
    }

    private data class ForumCacheEnvelope(
        override val version: Int = CACHE_VERSION,
        override val savedAtMillis: Long = 0L,
        val value: ForumPageCache = ForumPageCache(),
    ) : CacheEnvelope

    private data class ThreadCacheEnvelope(
        override val version: Int = CACHE_VERSION,
        override val savedAtMillis: Long = 0L,
        val value: ThreadPageCache = ThreadPageCache(),
    ) : CacheEnvelope
}

private data class ForumPageCache(
    val forum: ForumSummaryCache = ForumSummaryCache(),
    val threads: List<ForumThreadCache> = emptyList(),
    val page: Int = 1,
    val hasMore: Boolean = false,
)

private data class ForumSummaryCache(
    val id: String = "",
    val name: String = "",
    val slogan: String = "",
    val avatarUrl: String = "",
    val memberCount: String = "",
    val threadCount: String = "",
    val postCount: String = "",
    val forumRuleTitle: String = "",
)

private data class ForumThreadCache(
    val id: String = "",
    val title: String = "",
    val excerpt: String = "",
    val authorName: String = "",
    val authorNickname: String = "",
    val authorPortrait: String = "",
    val replyCount: String = "",
    val viewCount: String = "",
    val lastReplyTime: String = "",
    val isTop: Boolean = false,
    val isGood: Boolean = false,
    val imageUrls: List<String> = emptyList(),
    val videoUrl: String? = null,
    val authorId: Long = 0L,
    val forumId: Long = 0L,
    val forumName: String = "",
    val richExcerpt: List<TiebaContentEntryCache> = emptyList(),
    val authorModeratorRole: String? = null,
)

private data class ThreadPageCache(
    val threadId: String = "",
    val title: String = "",
    val floors: List<ThreadFloorCache> = emptyList(),
    val page: Int = 1,
    val totalPages: Int = 1,
    val replyCount: Int = 0,
    val body: ThreadFloorCache? = null,
    val forumId: Long = 0L,
    val forumName: String = "",
)

private data class ThreadFloorCache(
    val postId: String = "",
    val floor: Int = 0,
    val authorName: String = "",
    val authorNickname: String = "",
    val authorPortrait: String = "",
    val content: String = "",
    val time: String = "",
    val imageUrls: List<String> = emptyList(),
    val videoUrls: List<String> = emptyList(),
    val replyCount: Int = 0,
    val replies: List<FloorReplyCache> = emptyList(),
    val richContent: List<TiebaContentEntryCache> = emptyList(),
    val authorId: Long = 0L,
    val authorLevel: Int = 0,
    val authorTitle: String = "",
    val authorIp: String = "",
    val authorModeratorRole: String? = null,
    val isTopAgree: Boolean = false,
)

private data class FloorReplyCache(
    val id: String = "",
    val authorName: String = "",
    val authorNickname: String = "",
    val authorPortrait: String = "",
    val content: String = "",
    val time: String = "",
    val richContent: List<TiebaContentEntryCache> = emptyList(),
    val authorId: Long = 0L,
    val authorLevel: Int = 0,
    val authorTitle: String = "",
    val authorIp: String = "",
    val authorModeratorRole: String? = null,
)

private data class TiebaContentEntryCache(
    val kind: String = "",
    val value: String = "",
    val label: String = "",
    val url: String = "",
    val id: String = "",
    val name: String = "",
    val previewUrl: String = "",
    val originalUrl: String = "",
    val picId: String = "",
    val width: Int? = null,
    val height: Int? = null,
    val thumbnailUrl: String = "",
)

private fun ForumPage.toCache() = ForumPageCache(
    forum = forum.toCache(),
    threads = threads.map(ForumThread::toCache),
    page = page,
    hasMore = hasMore,
)

private fun ForumPageCache.toModel() = ForumPage(
    forum = forum.toModel(),
    threads = threads.map(ForumThreadCache::toModel),
    page = page,
    hasMore = hasMore,
)

private fun ForumSummary.toCache() = ForumSummaryCache(
    id = id,
    name = name,
    slogan = slogan,
    avatarUrl = avatarUrl,
    memberCount = memberCount,
    threadCount = threadCount,
    postCount = postCount,
    forumRuleTitle = forumRuleTitle,
)

private fun ForumSummaryCache.toModel() = ForumSummary(
    id = id,
    name = name,
    slogan = slogan,
    avatarUrl = avatarUrl,
    memberCount = memberCount,
    threadCount = threadCount,
    postCount = postCount,
    forumRuleTitle = forumRuleTitle,
)

private fun ForumThread.toCache() = ForumThreadCache(
    id = id,
    title = title,
    excerpt = excerpt,
    authorName = authorName,
    authorNickname = authorNickname,
    authorPortrait = authorPortrait,
    replyCount = replyCount,
    viewCount = viewCount,
    lastReplyTime = lastReplyTime,
    isTop = isTop,
    isGood = isGood,
    imageUrls = imageUrls,
    videoUrl = videoUrl,
    authorId = authorId,
    forumId = forumId,
    forumName = forumName,
    richExcerpt = richExcerpt.map(TiebaContent::toCache),
    authorModeratorRole = authorModeratorRole?.name,
)

private fun ForumThreadCache.toModel() = ForumThread(
    id = id,
    title = title,
    excerpt = excerpt,
    authorName = authorName,
    authorNickname = authorNickname,
    authorPortrait = authorPortrait,
    replyCount = replyCount,
    viewCount = viewCount,
    lastReplyTime = lastReplyTime,
    isTop = isTop,
    isGood = isGood,
    imageUrls = imageUrls,
    videoUrl = videoUrl,
    authorId = authorId,
    forumId = forumId,
    forumName = forumName,
    richExcerpt = richExcerpt.mapNotNull(TiebaContentEntryCache::toModel),
    authorModeratorRole = authorModeratorRole.toModeratorRole(),
)

private fun ThreadPage.toCache() = ThreadPageCache(
    threadId = threadId,
    title = title,
    floors = floors.map(ThreadFloor::toCache),
    page = page,
    totalPages = totalPages,
    replyCount = replyCount,
    body = body?.toCache(),
    forumId = forumId,
    forumName = forumName,
)

private fun ThreadPageCache.toModel() = ThreadPage(
    threadId = threadId,
    title = title,
    floors = floors.map(ThreadFloorCache::toModel),
    page = page,
    totalPages = totalPages,
    replyCount = replyCount,
    body = body?.toModel(),
    forumId = forumId,
    forumName = forumName,
)

private fun ThreadFloor.toCache() = ThreadFloorCache(
    postId = postId,
    floor = floor,
    authorName = authorName,
    authorNickname = authorNickname,
    authorPortrait = authorPortrait,
    content = content,
    time = time,
    imageUrls = imageUrls,
    videoUrls = videoUrls,
    replyCount = replyCount,
    replies = replies.map(FloorReply::toCache),
    richContent = richContent.map(TiebaContent::toCache),
    authorId = authorId,
    authorLevel = authorLevel,
    authorTitle = authorTitle,
    authorIp = authorIp,
    authorModeratorRole = authorModeratorRole?.name,
    isTopAgree = isTopAgree,
)

private fun ThreadFloorCache.toModel() = ThreadFloor(
    postId = postId,
    floor = floor,
    authorName = authorName,
    authorNickname = authorNickname,
    authorPortrait = authorPortrait,
    content = content,
    time = time,
    imageUrls = imageUrls,
    videoUrls = videoUrls,
    replyCount = replyCount,
    replies = replies.map(FloorReplyCache::toModel),
    richContent = richContent.mapNotNull(TiebaContentEntryCache::toModel),
    authorId = authorId,
    authorLevel = authorLevel,
    authorTitle = authorTitle,
    authorIp = authorIp,
    authorModeratorRole = authorModeratorRole.toModeratorRole(),
    isTopAgree = isTopAgree,
)

private fun FloorReply.toCache() = FloorReplyCache(
    id = id,
    authorName = authorName,
    authorNickname = authorNickname,
    authorPortrait = authorPortrait,
    content = content,
    time = time,
    richContent = richContent.map(TiebaContent::toCache),
    authorId = authorId,
    authorLevel = authorLevel,
    authorTitle = authorTitle,
    authorIp = authorIp,
    authorModeratorRole = authorModeratorRole?.name,
)

private fun FloorReplyCache.toModel() = FloorReply(
    id = id,
    authorName = authorName,
    authorNickname = authorNickname,
    authorPortrait = authorPortrait,
    content = content,
    time = time,
    richContent = richContent.mapNotNull(TiebaContentEntryCache::toModel),
    authorId = authorId,
    authorLevel = authorLevel,
    authorTitle = authorTitle,
    authorIp = authorIp,
    authorModeratorRole = authorModeratorRole.toModeratorRole(),
)

private fun TiebaContent.toCache(): TiebaContentEntryCache = when (this) {
    is TiebaContent.Text -> TiebaContentEntryCache(kind = "text", value = value)
    is TiebaContent.Link -> TiebaContentEntryCache(kind = "link", label = label, url = url)
    is TiebaContent.Emoticon -> TiebaContentEntryCache(kind = "emoticon", id = id, name = name)
    is TiebaContent.Image -> TiebaContentEntryCache(
        kind = "image",
        previewUrl = previewUrl,
        originalUrl = originalUrl,
        picId = picId,
        width = width,
        height = height,
    )
    is TiebaContent.Video -> TiebaContentEntryCache(
        kind = "video",
        url = url,
        thumbnailUrl = thumbnailUrl,
        width = width,
        height = height,
    )
}

private fun TiebaContentEntryCache.toModel(): TiebaContent? = when (kind) {
    "text" -> TiebaContent.Text(value)
    "link" -> TiebaContent.Link(label, url)
    "emoticon" -> TiebaContent.Emoticon(id, name)
    "image" -> TiebaContent.Image(previewUrl, originalUrl, picId, width, height)
    "video" -> TiebaContent.Video(url, thumbnailUrl, width, height)
    else -> null
}

private fun String?.toModeratorRole(): TiebaModeratorRole? =
    TiebaModeratorRole.entries.firstOrNull { it.name == this }

private fun String.sha256(): String = MessageDigest.getInstance("SHA-256")
    .digest(toByteArray(StandardCharsets.UTF_8))
    .joinToString("") { "%02x".format(it) }
