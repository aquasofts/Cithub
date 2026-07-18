package edu.ccit.webvpn.feature.tieba

import android.content.Context
import android.net.Uri
import android.webkit.CookieManager
import androidx.room.Room
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.request.crossfade
import edu.ccit.webvpn.feature.tieba.data.AccountEntity
import edu.ccit.webvpn.feature.tieba.data.TiebaDatabase
import edu.ccit.webvpn.feature.tieba.data.TiebaSettingsRepository
import edu.ccit.webvpn.feature.tieba.network.LoginCookies
import edu.ccit.webvpn.feature.tieba.network.SignResponse
import edu.ccit.webvpn.feature.tieba.network.TiebaNetworkRepository
import edu.ccit.webvpn.feature.tieba.network.TiebaSignDiagnostics
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class TiebaRuntime private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val database = Room.databaseBuilder(appContext, TiebaDatabase::class.java, "tieba_lite.db").build()
    internal val accountDao = database.accountDao()
    val settings = TiebaSettingsRepository(appContext)
    val network = TiebaNetworkRepository.create(appContext, settings)
    private val signDiagnostics = TiebaSignDiagnostics.get(appContext)
    private val signMutex = Mutex()
    private val _signState = MutableStateFlow<TiebaSignState>(TiebaSignState.Idle)
    val signState: StateFlow<TiebaSignState> = _signState.asStateFlow()

    val account = accountDao.observe().map { entity -> entity?.toPublic() }
        .stateIn(scope, SharingStarted.Eagerly, null)

    init {
        SingletonImageLoader.setSafe(TiebaImageLoaderFactory)
    }

    suspend fun login(cookies: LoginCookies) {
        val account = network.loadAccount(cookies)
        accountDao.replace(account)
        onAppForegrounded()
    }

    suspend fun refreshAccount(): Result<Unit> = runCatching {
        val current = accountDao.get() ?: error("尚未登录贴吧")
        val refreshed = network.loadAccount(
            LoginCookies(current.bduss, current.sToken, null, current.cookie),
        )
        accountDao.replace(refreshed)
    }

    suspend fun logout() {
        settings.disableSign()
        accountDao.deleteAll()
        clearTiebaCookies()
        _signState.value = TiebaSignState.Idle
    }

    fun onAppForegrounded() {
        scope.launch {
            settings.refreshClientActiveTimestamp()
            val current = accountDao.get()
            runCatching { network.syncClientConfig(current) }
            autoSignForToday()
        }
    }

    private suspend fun autoSignForToday() {
        signMutex.withLock {
            val preferences = settings.preferences.first()
            val current = accountDao.get()
            val homeForumName = normalizeForumName(preferences.homeForumName).ifBlank { TARGET_FORUM_NAME }
            val lastSignWasForHomeForum = normalizeForumName(preferences.sign.lastForumName) == homeForumName
            if (
                !preferences.sign.enabled || current == null ||
                (lastSignWasForHomeForum && !shouldAutoSign(preferences.sign.lastRunAt))
            ) return

            val forum = runCatching {
                network.loadForum(
                    page = 1,
                    sort = preferences.reading.forumSort,
                    goodOnly = false,
                    account = current,
                    forumName = homeForumName,
                ).forum
            }.getOrElse { error ->
                finishAutomaticSignFailure(homeForumName, error.message ?: "主页贴吧加载失败")
                return
            }
            if (!forum.isFollowed) {
                finishAutomaticSignFailure(homeForumName, "请先关注${forumDisplayName(homeForumName)}，再进行签到")
                return
            }
            performSign(current, forum, automatic = true)
        }
    }

    suspend fun signNow(forum: ForumSummary): SignResponse = signMutex.withLock {
        val current = accountDao.get()
        if (current == null) {
            return@withLock SignResponse(SignOutcome.FAILED, "请先登录贴吧账号")
        }
        if (!forum.isFollowed) {
            return@withLock SignResponse(SignOutcome.FAILED, "请先关注${forumDisplayName(forum.name)}，再进行签到")
        }
        performSign(current, forum, automatic = false)
    }

    suspend fun followForum(forum: ForumSummary): String = signMutex.withLock {
        val current = accountDao.get() ?: error("请先登录贴吧账号")
        val attemptId = signDiagnostics.startAttempt(
            source = "manual_follow_button",
            account = current,
            forum = forum,
        )
        signDiagnostics.recordStage(attemptId, "tieba_lite_follow_started")
        try {
            network.likeForum(
                account = current,
                forumId = forum.id.toLongOrNull()?.takeIf { it > 0 } ?: error("贴吧关注失败：吧信息无效"),
                forumName = forum.name.ifBlank { TARGET_FORUM_NAME },
                tbs = forum.tbs,
                diagnosticAttempt = attemptId,
            )
            signDiagnostics.recordStage(attemptId, "attempt_finished", mapOf("outcome" to "FOLLOWED"))
            "关注成功，请再次点击签到"
        } catch (error: Throwable) {
            signDiagnostics.recordStage(
                attemptId,
                "attempt_finished",
                mapOf("outcome" to "FAILED", "message" to error.message),
            )
            throw error
        }
    }

    suspend fun saveRuntimeLog(uri: Uri) = signDiagnostics.saveTo(uri)

    suspend fun clearRuntimeLog() = signDiagnostics.clear()

    private suspend fun finishAutomaticSignFailure(forumName: String, message: String) {
        val response = SignResponse(SignOutcome.FAILED, message)
        settings.recordSign(response.outcome, response.message, forumName)
        _signState.value = TiebaSignState.Finished(response.outcome, response.message)
    }

    private suspend fun performSign(
        current: AccountEntity,
        forum: ForumSummary,
        automatic: Boolean,
    ): SignResponse {
        _signState.value = TiebaSignState.Running
        val forumId = forum.id.toLongOrNull()?.takeIf { it > 0 } ?: run {
            val response = SignResponse(SignOutcome.FAILED, "贴吧签到失败：吧信息无效")
            settings.recordSign(response.outcome, response.message, forum.name)
            _signState.value = TiebaSignState.Finished(response.outcome, response.message)
            return response
        }
        val attemptId = signDiagnostics.startAttempt(
            source = if (automatic) "automatic" else "manual_forum_button",
            account = current,
            forum = forum,
        )
        val response = runCatching {
            signDiagnostics.recordStage(attemptId, "tieba_lite_flow_started")
            executeTiebaLiteForumSign(
                current = current,
                forumTbs = forum.tbs.takeUnless { automatic },
                refreshOfficialAccount = { account -> network.refreshOfficialAccount(account) },
                persistAccount = accountDao::replace,
                submitSign = { account, tbs ->
                    network.sign(
                        account = account,
                        forumId = forumId,
                        forumName = forum.name.ifBlank { TARGET_FORUM_NAME },
                        tbs = tbs,
                        diagnosticAttempt = attemptId,
                    )
                },
            )
        }
            .getOrElse { error -> SignResponse(SignOutcome.FAILED, error.message ?: "签到失败") }
        signDiagnostics.recordStage(
            attemptId,
            "attempt_finished",
            mapOf("outcome" to response.outcome.name, "message" to response.message),
        )
        settings.recordSign(response.outcome, response.message, forum.name)
        _signState.value = TiebaSignState.Finished(response.outcome, response.message)
        return response
    }

    companion object {
        @Volatile private var instance: TiebaRuntime? = null

        fun get(context: Context): TiebaRuntime = instance ?: synchronized(this) {
            instance ?: TiebaRuntime(context).also { instance = it }
        }
    }
}

/**
 * Mirrors TiebaLite's two sign-in entry points:
 * - the forum button submits the anti.tbs already returned by the displayed FRS page;
 * - automatic sign-in refreshes the account and submits the anti.tbs returned by /c/s/login.
 */
internal suspend fun executeTiebaLiteForumSign(
    current: AccountEntity,
    forumTbs: String?,
    refreshOfficialAccount: suspend (AccountEntity) -> AccountEntity,
    persistAccount: suspend (AccountEntity) -> Unit,
    submitSign: suspend (AccountEntity, String) -> SignResponse,
): SignResponse {
    if (forumTbs != null) return submitSign(current, forumTbs)
    val refreshedAccount = refreshOfficialAccount(current)
    persistAccount(refreshedAccount)
    return submitSign(refreshedAccount, refreshedAccount.tbs)
}

private object TiebaImageLoaderFactory : SingletonImageLoader.Factory {
    override fun newImageLoader(context: PlatformContext): ImageLoader = ImageLoader.Builder(context)
        .diskCache {
            DiskCache.Builder()
                .directory(context.cacheDir.resolve("tieba_image_cache"))
                .maxSizePercent(0.02)
                .build()
        }
        .crossfade(true)
        .build()
}

private fun AccountEntity.toPublic() = TiebaAccount(
    uid = uid,
    username = name,
    nickname = nickname,
    avatarUrl = portrait,
    intro = intro,
    fans = fans,
    posts = posts,
    concerned = concerned,
    lastUpdatedAt = lastUpdate,
)

private fun clearTiebaCookies() {
    val manager = CookieManager.getInstance()
    val urls = listOf(
        "https://tieba.baidu.com",
        "https://tiebac.baidu.com",
        "https://passport.baidu.com",
        "https://wappass.baidu.com",
    )
    urls.forEach { url ->
        manager.getCookie(url)?.split(';')?.mapNotNull { cookie ->
            cookie.substringBefore('=').trim().takeIf(String::isNotBlank)
        }?.distinct()?.forEach { name ->
            manager.setCookie(url, "$name=; Max-Age=0; Path=/; Secure; SameSite=Lax")
            manager.setCookie(url, "$name=; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Path=/")
        }
    }
    manager.flush()
}
