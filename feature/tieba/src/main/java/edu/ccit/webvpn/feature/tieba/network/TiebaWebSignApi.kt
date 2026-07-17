package edu.ccit.webvpn.feature.tieba.network

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.annotations.SerializedName
import edu.ccit.webvpn.feature.tieba.SignOutcome
import edu.ccit.webvpn.feature.tieba.data.AccountEntity
import edu.ccit.webvpn.feature.tieba.forumDisplayName
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Field
import retrofit2.http.FormUrlEncoded
import retrofit2.http.Header
import retrofit2.http.POST

/** HTTPS-only fallback for the public web sign endpoint when the TiebaLite mobile API returns 300004. */
internal interface TiebaWebSignApi {
    @POST("sign/add")
    @FormUrlEncoded
    suspend fun sign(
        @Field("ie") encoding: String = "utf-8",
        @Field("kw") forumName: String,
        @Field("tbs") tbs: String,
        @Header("Cookie") cookie: String,
        @Header("Referer") referer: String,
        @Header("User-Agent") userAgent: String = WEB_USER_AGENT,
        @Header(SIGN_DIAGNOSTIC_ATTEMPT_HEADER) diagnosticAttempt: String? = null,
    ): TiebaWebSignBean
}

internal data class TiebaWebSignBean(
    val no: JsonElement? = null,
    val error: JsonElement? = null,
    val data: JsonElement? = null,
) {
    data class UserInfo(
        @SerializedName("cont_sign_num") val consecutiveDays: Int? = null,
        @SerializedName("user_sign_rank") val rank: Int? = null,
        @SerializedName("sign_bonus_point") val bonus: Int? = null,
    )
}

internal class TiebaWebSignClient(
    context: android.content.Context,
    gson: Gson,
    baseUrl: String = "https://tieba.baidu.com/",
    diagnostics: TiebaSignDiagnostics = TiebaSignDiagnostics.get(context),
) {
    private val api = Retrofit.Builder()
        .baseUrl(baseUrl)
        .client(
            OkHttpClient.Builder()
                .readTimeout(60, TimeUnit.SECONDS)
                .connectTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .addInterceptor(TiebaSignDiagnosticsInterceptor(diagnostics, "web_https_fallback"))
                .build(),
        )
        .addConverterFactory(GsonConverterFactory.create(gson))
        .validateEagerly(true)
        .build()
        .create(TiebaWebSignApi::class.java)

    suspend fun sign(
        account: AccountEntity,
        forumName: String,
        tbs: String,
        diagnosticAttempt: String?,
    ): SignResponse {
        val cookie = account.cookie.takeIf(String::isNotBlank)
            ?: "BDUSS=${account.bduss}; STOKEN=${account.sToken}"
        val encodedName = URLEncoder.encode(forumName, StandardCharsets.UTF_8.name())
        val result = api.sign(
            forumName = forumName,
            tbs = tbs,
            cookie = cookie,
            referer = "https://tieba.baidu.com/f?kw=$encodedName&ie=utf-8",
            diagnosticAttempt = diagnosticAttempt,
        )
        val code = result.no.tiebaIntOrNull()
        val message = result.error.tiebaErrorMessage()
        val days = result.userInfo()?.consecutiveDays
        return when {
            code == 0 -> SignResponse(
                SignOutcome.SUCCESS,
                days?.let { "已签${it}天" } ?: "签到成功",
                days,
            )
            code == 1101 || message.contains("已签") -> SignResponse(
                SignOutcome.ALREADY_SIGNED,
                days?.let { "已签${it}天" } ?: "今日已经签到",
                days,
            )
            code == 1004 || code == 1011 || message.contains("未关注") || message.contains("未加入") -> SignResponse(
                SignOutcome.FAILED,
                "账号尚未关注${forumDisplayName(forumName)}或等级不足，请先关注该吧后再签到",
            )
            else -> SignResponse(
                SignOutcome.FAILED,
                buildString {
                    append("贴吧网页签到失败")
                    message.takeIf(String::isNotBlank)?.let { append("：").append(it) }
                    code?.let { append("（错误码 ").append(it).append("）") }
                },
            )
        }
    }
}

private fun TiebaWebSignBean.userInfo(): TiebaWebSignBean.UserInfo? {
    val dataObject = data?.takeIf { it.isJsonObject }?.asJsonObject ?: return null
    val userObject = dataObject.get("uinfo")?.takeIf { it.isJsonObject }?.asJsonObject ?: return null
    return TiebaWebSignBean.UserInfo(
        consecutiveDays = userObject.get("cont_sign_num").tiebaIntOrNull(),
        rank = userObject.get("user_sign_rank").tiebaIntOrNull(),
        bonus = userObject.get("sign_bonus_point").tiebaIntOrNull(),
    )
}

private fun JsonElement?.tiebaIntOrNull(): Int? = this
    ?.takeIf { it.isJsonPrimitive }
    ?.let { element ->
        runCatching { element.asInt }.getOrNull()
            ?: runCatching { element.asString.toIntOrNull() }.getOrNull()
    }

private const val WEB_USER_AGENT =
    "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) " +
        "Chrome/135.0.0.0 Mobile Safari/537.36"
