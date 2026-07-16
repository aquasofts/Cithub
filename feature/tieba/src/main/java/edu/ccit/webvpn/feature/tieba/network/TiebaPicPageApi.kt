package edu.ccit.webvpn.feature.tieba.network

import android.content.Context
import android.os.Build
import com.google.gson.annotations.SerializedName
import edu.ccit.webvpn.feature.tieba.LoadPicPageData
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.ThreadLocalRandom
import kotlin.math.roundToInt
import retrofit2.http.FieldMap
import retrofit2.http.FormUrlEncoded
import retrofit2.http.POST

/** TiebaLite's Mini Tieba endpoint used to refresh signed original-image URLs. */
internal interface TiebaPicPageApi {
    @FormUrlEncoded
    @POST("c/f/pb/picpage")
    suspend fun picPage(@FieldMap fields: Map<String, String>): PicPageResponse
}

internal data class PicPageResponse(
    @SerializedName("error_code") val errorCode: String = "-1",
    @SerializedName("pic_amount") val picAmount: Int? = null,
    @SerializedName("pic_list") val picList: List<PicPagePicture> = emptyList(),
)

internal data class PicPagePicture(
    @SerializedName("overall_index") val overallIndex: String = "",
    @SerializedName("post_id") val postId: String? = null,
    val img: PicPageImage = PicPageImage(),
)

internal data class PicPageImage(
    val original: PicPageImageInfo = PicPageImageInfo(),
    val medium: PicPageImageInfo? = null,
    val screen: PicPageImageInfo? = null,
) {
    /** Exact equivalent of TiebaLite's PicPageBean.ImgBean.bestQualitySrc. */
    fun bestQualitySrc(): String = with(original) {
        val byteSize = size.toLongOrNull() ?: 0L
        val chosen = when {
            format == "2" -> waterUrl
            byteSize >= 2L * 1024L * 1024L && isLongImage() -> bigCdnSrc
            else -> waterUrl
        }.ifBlank { originalSrc.ifBlank { url.ifBlank { bigCdnSrc } } }
        normalizePicPageUrl(chosen)
    }
}

internal data class PicPageImageInfo(
    val id: String = "",
    val width: String? = null,
    val height: String? = null,
    val size: String = "0",
    val format: String = "",
    @SerializedName("waterurl") val waterUrl: String = "",
    @SerializedName("big_cdn_src") val bigCdnSrc: String = "",
    val url: String = "",
    @SerializedName("original_src") val originalSrc: String = "",
) {
    fun isLongImage(): Boolean {
        val width = width?.toIntOrNull() ?: return false
        val height = height?.toIntOrNull() ?: return false
        return height > width * 3
    }
}

/**
 * Copies TiebaLite's Mini API common fields, st fields, sorting and
 * `MD5(sorted(name=value) + "tiebaclient!!!")` signing algorithm.
 */
internal class TiebaPicPageRequestFactory(
    context: Context,
    private val identity: TiebaClientIdentity,
) {
    private val appContext = context.applicationContext

    fun picPage(data: LoadPicPageData, credentials: TiebaReadCredentials?): Map<String, String> {
        val metrics = appContext.resources.displayMetrics
        val fields = linkedMapOf(
            "forum_id" to data.forumId.toString(),
            "kw" to data.forumName,
            "tid" to data.threadId.toString(),
            "pic_id" to data.picId,
            "pic_index" to data.picIndex.toString(),
            "obj_type" to data.objType,
            "page_name" to "PB",
            "next" to "10",
            "scr_h" to metrics.heightPixels.toString(),
            "scr_w" to metrics.widthPixels.toString(),
            "q_type" to "2",
            "prev" to "0",
            "not_see_lz" to if (data.seeLz) "0" else "1",
            "_client_id" to identity.clientId,
            "_client_type" to "2",
            "_client_version" to MINI_TIEBA_VERSION,
            "_os_version" to Build.VERSION.SDK_INT.toString(),
            "_model" to Build.MODEL,
            "_net_type" to "1",
            "_phone_imei" to "",
            "_timestamp" to System.currentTimeMillis().toString(),
            "cuid" to identity.cuid,
            "cuid_galaxy2" to identity.cuid,
            "from" to "1021636m",
            "subapp_type" to "mini",
        )
        credentials?.let {
            fields["BDUSS"] = it.bduss
            fields["user_id"] = it.uid.toString()
        }
        addStParams(fields)
        fields["sign"] = miniTiebaSign(fields)
        return fields.toSortedMap()
    }

    private fun addStParams(fields: MutableMap<String, String>) {
        val number = ThreadLocalRandom.current().nextInt(100, 850)
        if (number in 100..120) {
            fields["stErrorNums"] = "0"
            return
        }
        fields["stErrorNums"] = "1"
        fields["stMethod"] = "1"
        fields["stMode"] = "1"
        fields["stTimesNum"] = "1"
        fields["stTime"] = number.toString()
        fields["stSize"] = ((Math.random() * 8 + 0.4) * number).roundToInt().toString()
    }
}

internal fun miniTiebaSign(fields: Map<String, String>): String {
    val sortedRaw = fields.asSequence()
        .filter { it.key != "sign" }
        .map { "${it.key}=${it.value}" }
        .sorted()
        .joinToString(separator = "")
    return MessageDigest.getInstance("MD5")
        .digest((sortedRaw + MINI_TIEBA_SECRET).toByteArray(StandardCharsets.UTF_8))
        .joinToString(separator = "") { byte -> "%02x".format(Locale.ROOT, byte) }
}

internal fun isAuthorizedTiebaImageUrl(raw: String): Boolean {
    val normalized = normalizePicPageUrl(raw)
    if (!normalized.startsWith("https://")) return false
    return !normalized.contains("/forum/pic/item/") || normalized.contains("tbpicau=")
}

private fun normalizePicPageUrl(raw: String): String = when {
    raw.startsWith("//") -> "https:$raw"
    raw.startsWith("http://") -> "https://${raw.removePrefix("http://")}"
    else -> raw
}

private const val MINI_TIEBA_VERSION = "7.2.0.0"
private const val MINI_TIEBA_SECRET = "tiebaclient!!!"
