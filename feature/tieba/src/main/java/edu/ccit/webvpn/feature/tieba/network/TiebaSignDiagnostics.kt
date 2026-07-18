package edu.ccit.webvpn.feature.tieba.network

import android.content.Context
import android.net.Uri
import android.os.Build
import edu.ccit.webvpn.core.runtime.RuntimeLog
import edu.ccit.webvpn.feature.tieba.ForumSummary
import edu.ccit.webvpn.feature.tieba.data.AccountEntity
import java.util.UUID
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response

/** Adds detailed Tieba sign stages to the process-wide, deliberately unredacted runtime log. */
internal class TiebaSignDiagnostics private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val runtimeLog = RuntimeLog.get(appContext)

    fun startAttempt(source: String, account: AccountEntity, forum: ForumSummary): String {
        val attemptId = "${System.currentTimeMillis()}-${UUID.randomUUID().toString().take(8)}"
        val packageInfo = runCatching {
            appContext.packageManager.getPackageInfo(appContext.packageName, 0)
        }.getOrNull()
        record(
            attemptId = attemptId,
            event = "attempt_started",
            fields = mapOf(
                "source" to source,
                "app_version" to packageInfo?.versionName.orEmpty(),
                @Suppress("DEPRECATION")
                "app_version_code" to packageInfo?.versionCode?.toLong(),
                "sdk" to Build.VERSION.SDK_INT,
                "device" to "${Build.MANUFACTURER} ${Build.MODEL}".trim(),
                "forum_id" to forum.id,
                "forum_name" to forum.name,
                "forum_tbs" to forum.tbs,
                "account_uid" to account.uid,
                "account_name" to account.name,
                "account_nickname" to account.nickname,
                "account_bduss" to account.bduss,
                "account_stoken" to account.sToken,
                "account_cookie" to account.cookie,
                "account_tbs" to account.tbs,
                "account_zid" to account.zid,
            ),
        )
        return attemptId
    }

    fun recordForumSnapshot(account: AccountEntity?, forum: ForumSummary) {
        record(
            attemptId = null,
            event = "frs_snapshot",
            fields = mapOf(
                "forum_id" to forum.id,
                "forum_name" to forum.name,
                "tbs" to forum.tbs,
                "signed" to forum.signed,
                "signed_days" to forum.signedDays,
                "followed" to forum.isFollowed,
                "account_uid" to account?.uid,
                "account_bduss" to account?.bduss,
                "account_stoken" to account?.sToken,
                "account_cookie" to account?.cookie,
            ),
        )
    }

    fun recordStage(attemptId: String?, event: String, fields: Map<String, Any?> = emptyMap()) {
        record(attemptId, event, fields)
    }

    fun recordHttpRequest(attemptId: String?, channel: String, request: Request) {
        runtimeLog.recordHttpRequest(
            channel = channel,
            request = request,
            fields = mapOf("attempt" to attemptId, "feature" to "tieba_sign"),
        )
    }

    fun recordHttpResponse(
        attemptId: String?,
        channel: String,
        response: Response,
        elapsedMs: Long,
    ) {
        runtimeLog.recordHttpResponse(
            channel = channel,
            response = response,
            elapsedMs = elapsedMs,
            fields = mapOf("attempt" to attemptId, "feature" to "tieba_sign"),
        )
    }

    fun recordHttpFailure(attemptId: String?, channel: String, request: Request, error: Throwable, elapsedMs: Long) {
        runtimeLog.recordHttpFailure(
            channel = channel,
            request = request,
            elapsedMs = elapsedMs,
            error = error,
            fields = mapOf("attempt" to attemptId, "feature" to "tieba_sign"),
        )
    }

    suspend fun exportText(): String = runtimeLog.exportText()

    suspend fun saveTo(uri: Uri) = runtimeLog.saveTo(uri)

    suspend fun clear() = runtimeLog.clear()

    private fun record(attemptId: String?, event: String, fields: Map<String, Any?>) {
        runtimeLog.info(
            source = "tieba_sign",
            event = event,
            fields = mapOf("attempt" to attemptId) + fields,
        )
    }

    companion object {
        @Volatile private var instance: TiebaSignDiagnostics? = null

        fun get(context: Context): TiebaSignDiagnostics = instance ?: synchronized(this) {
            instance ?: TiebaSignDiagnostics(context).also { instance = it }
        }
    }
}

internal class TiebaSignDiagnosticsInterceptor(
    private val diagnostics: TiebaSignDiagnostics,
    private val channel: String,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val taggedRequest = chain.request()
        val attemptId = taggedRequest.header(SIGN_DIAGNOSTIC_ATTEMPT_HEADER)
        val request = taggedRequest.newBuilder()
            .removeHeader(SIGN_DIAGNOSTIC_ATTEMPT_HEADER)
            .build()
        runCatching { diagnostics.recordHttpRequest(attemptId, channel, request) }
        val startedAt = System.nanoTime()
        return try {
            chain.proceed(request).also { response ->
                runCatching {
                    diagnostics.recordHttpResponse(
                        attemptId = attemptId,
                        channel = channel,
                        response = response,
                        elapsedMs = (System.nanoTime() - startedAt) / 1_000_000,
                    )
                }
            }
        } catch (error: Throwable) {
            runCatching {
                diagnostics.recordHttpFailure(
                    attemptId = attemptId,
                    channel = channel,
                    request = request,
                    error = error,
                    elapsedMs = (System.nanoTime() - startedAt) / 1_000_000,
                )
            }
            throw error
        }
    }
}

internal const val SIGN_DIAGNOSTIC_ATTEMPT_HEADER = "X-Cithub-Sign-Attempt"
