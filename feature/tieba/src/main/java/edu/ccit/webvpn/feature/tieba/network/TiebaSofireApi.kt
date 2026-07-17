package edu.ccit.webvpn.feature.tieba.network

import android.util.Base64
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.GZIPOutputStream
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.experimental.xor
import kotlin.random.Random
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** TiebaLite's Sofire x6 handshake used to obtain the account z_id. */
internal class TiebaSofireClient(
    private val client: OkHttpClient = OkHttpClient(),
    private val gson: Gson = Gson(),
    private val endpoint: String = "https://sofire.baidu.com",
    private val clock: () -> Long = System::currentTimeMillis,
    private val randomKey: () -> String = { generateRandomString(16) },
) {
    suspend fun fetchZid(uuid: String): String = kotlinx.coroutines.Dispatchers.IO.run {
        kotlinx.coroutines.withContext(this) { fetchZidBlocking(uuid) }
    }

    private fun fetchZidBlocking(uuid: String): String {
        val appKey = DEFAULT_APP_KEY
        val secretKey = DEFAULT_SECRET_KEY
        val cuid = "${md5Upper(uuid)}|0"
        val cuidMd5 = md5Upper(cuid).lowercase(Locale.ROOT)
        val currentTime = (clock() / 1000).toString()
        val requestBytes = gson.toJson(
            SofireRequestBody(listOf(mapOf("zid" to cuid))),
        ).gzipCompress()
        val aesKey = randomKey()
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        val iv = IvParameterSpec(ByteArray(16))
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(aesKey.toByteArray(), "AES"), iv)
        val encryptedBody = cipher.doFinal(requestBytes)
        val bodyDigest = MessageDigest.getInstance("MD5").apply { update(requestBytes) }.digest()
        val body = (encryptedBody + bodyDigest)
            .toRequestBody("application/x-www-form-urlencoded".toMediaType())
        val pathMd5 = md5Upper("$appKey$currentTime$secretKey").lowercase(Locale.ROOT)
        val skey = Base64.encodeToString(
            rc442Crypt(aesKey.encodeToByteArray(), cuidMd5.encodeToByteArray(), 16, 32),
            Base64.DEFAULT,
        )
        val url = "$endpoint/c/11/z/100/$appKey/$currentTime/$pathMd5"
            .toHttpUrl()
            .newBuilder()
            .addQueryParameter("skey", skey)
            .build()
        val request = Request.Builder()
            .url(url)
            .post(body)
            .header("Pragma", "no-cache")
            .header("Accept", "*/*")
            .header("Accept-Language", Locale.getDefault().language)
            .header("x-device-id", cuidMd5)
            .header("x-client-src", "src")
            .header("User-Agent", "x6/$appKey/12.35.1.0/4.4.1.3")
            .header("x-sdk-ver", "sofire/3.5.9.6")
            .header("x-plu-ver", "x6/4.4.1.3")
            .header("x-app-ver", "com.baidu.tieba/12.35.1.0")
            .header("x-api-ver", "33")
            .build()
        val envelope = client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Sofire HTTP ${response.code}")
            gson.fromJson(response.body?.charStream(), SofireResponse::class.java)
                ?: throw IOException("Sofire response is empty")
        }
        val responseKey = rc442Crypt(
            Base64.decode(envelope.skey, Base64.DEFAULT),
            cuidMd5.encodeToByteArray(),
            16,
            32,
        )
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(responseKey, "AES"), iv)
        val encryptedData = Base64.decode(envelope.data, Base64.DEFAULT)
        if (encryptedData.size <= 16) throw IOException("Sofire response data is invalid")
        val decoded = cipher.doFinal(encryptedData.dropLast(16).toByteArray()).decodeToString()
        return gson.fromJson(decoded, SofireResponseData::class.java)?.token
            ?.takeIf(String::isNotBlank)
            ?: throw IOException("Sofire response is missing z_id")
    }

    private companion object {
        const val DEFAULT_APP_KEY = "200033"
        const val DEFAULT_SECRET_KEY = "ea737e4f435b53786043369d2e5ace4f"
    }
}

private data class SofireRequestBody(
    @SerializedName("module_section") val moduleSection: List<Map<String, String>>,
)

private data class SofireResponse(
    val data: String,
    @SerializedName("request_id") val requestId: Long,
    val skey: String,
)

private data class SofireResponseData(val token: String)

private fun generateRandomString(length: Int): String {
    val charPool: List<Char> = ('a'..'z') + ('A'..'Z') + ('0'..'9')
    return (1..length)
        .map { Random.nextInt(0, charPool.size) }
        .map(charPool::get)
        .joinToString("")
}

private fun String.gzipCompress(): ByteArray {
    val output = ByteArrayOutputStream()
    GZIPOutputStream(output).bufferedWriter(Charsets.UTF_8).use { it.write(this) }
    return output.toByteArray()
}

private fun md5Upper(value: String): String = MessageDigest.getInstance("MD5")
    .digest(value.toByteArray(Charsets.UTF_8))
    .joinToString("") { "%02X".format(Locale.ROOT, it.toInt() and 0xFF) }

private class RC442 {
    private var x: Int = 0
    private var y: Int = 0
    private var state: ByteArray = ByteArray(256)

    fun setup(key: ByteArray, keyLength: Int = key.size) {
        for (i in 0 until 256) state[i] = i.toByte()
        var j = 0
        var k = 0
        for (i in 0 until 256) {
            if (k >= keyLength) k = 0
            val value = state[i].toInt()
            j = (j + value + key[k].toInt()) and 0xFF
            state[i] = state[j]
            state[j] = value.toByte()
            k++
        }
    }

    fun crypt(source: ByteArray, sourceLength: Int = source.size): ByteArray {
        val result = ByteArray(source.size)
        var currentX = x
        var currentY = y
        for (i in 0 until sourceLength) {
            currentX = (currentX + 1) and 0xFF
            val a = state[currentX].toInt()
            currentY = (currentY + a) and 0xFF
            val b = state[currentY].toInt()
            state[currentX] = b.toByte()
            state[currentY] = a.toByte()
            result[i] = (source[i] xor state[(a + b) and 0xFF]) xor 42.toByte()
        }
        x = currentX
        y = currentY
        return result
    }
}

private fun rc442Crypt(
    source: ByteArray,
    key: ByteArray,
    sourceLength: Int = source.size,
    keyLength: Int = key.size,
): ByteArray = RC442().apply { setup(key, keyLength) }.crypt(source, sourceLength)
