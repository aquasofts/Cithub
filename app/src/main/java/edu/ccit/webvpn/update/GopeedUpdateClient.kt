package edu.ccit.webvpn.update

import android.content.Context
import com.gopeed.libgopeed.Libgopeed
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.ConnectionSpec
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

internal object GopeedUpdateEngine {
    private val mutex = Mutex()
    private var client: GopeedUpdateClient? = null

    suspend fun start(context: Context): GopeedUpdateClient = mutex.withLock {
        client?.let { return it }
        withContext(Dispatchers.IO) {
            val appContext = context.applicationContext
            val storageDirectory = storageDirectory(appContext).apply { mkdirs() }
            val downloadDirectory = UpdateInstaller.updateDirectory(appContext)
            val apiToken = UpdateDownloadStore.apiToken(appContext)
            val config = buildJsonObject {
                put("network", "tcp")
                put("address", "127.0.0.1:0")
                put("storage", "bolt")
                put("storageDir", storageDirectory.absolutePath)
                put("refreshInterval", 500)
                put("apiToken", apiToken)
                put("whiteDownloadDirs", buildJsonArray { add(JsonPrimitive(downloadDirectory.absolutePath)) })
            }
            val port = Libgopeed.start(config.toString()).toInt()
            check(port in 1..65_535) { "Gopeed 未返回有效的本地端口" }
            GopeedUpdateClient(
                baseUrl = "http://localhost:$port/",
                apiToken = apiToken,
            ).also { client = it }
        }
    }

    suspend fun stop() {
        mutex.withLock {
            if (client == null) return@withLock
            withContext(Dispatchers.IO) { Libgopeed.stop() }
            client = null
        }
    }

    fun storageDirectory(context: Context): File = File(context.noBackupFilesDir, "gopeed-update")
}

internal class GopeedUpdateClient(
    private val baseUrl: String,
    private val apiToken: String,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectionSpecs(listOf(ConnectionSpec.CLEARTEXT))
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS)
        .build(),
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    fun createTask(
        url: String,
        destinationDirectory: File,
        fileName: String,
        connections: Int,
        userAgent: String,
    ): String {
        val payload = buildJsonObject {
            put("req", buildJsonObject {
                put("url", url)
                put("extra", buildJsonObject {
                    put("method", "GET")
                    put("header", buildJsonObject { put("User-Agent", userAgent) })
                    put("body", "")
                })
                put("skipVerifyCert", false)
            })
            put("opts", buildJsonObject {
                put("name", fileName)
                put("path", destinationDirectory.absolutePath)
                put("selectFiles", buildJsonArray {})
                put("extra", buildJsonObject {
                    put("connections", connections)
                    put("autoExtract", false)
                    put("deleteAfterExtract", false)
                })
            })
        }
        return requireData<String>(execute("api/v1/tasks", "POST", payload.toString()))
    }

    fun getTask(taskId: String): GopeedTask =
        requireData<GopeedTask>(execute("api/v1/tasks/$taskId", "GET"))

    fun continueTask(taskId: String) {
        requireSuccess(execute("api/v1/tasks/$taskId/continue", "PUT"))
    }

    fun deleteTask(taskId: String, force: Boolean) {
        requireSuccess(execute("api/v1/tasks/$taskId?force=$force", "DELETE"))
    }

    private fun execute(path: String, method: String, body: String? = null): String {
        val requestBody = when {
            body != null -> body.toRequestBody(JsonMediaType)
            method == "POST" || method == "PUT" || method == "PATCH" ->
                ByteArray(0).toRequestBody(null)
            else -> null
        }
        val request = Request.Builder()
            .url(baseUrl.trimEnd('/') + "/" + path.trimStart('/'))
            .header("X-Api-Token", apiToken)
            .method(method, requestBody)
            .build()
        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Gopeed HTTP ${response.code}")
            response.body.string()
        }
    }

    private inline fun <reified T> requireData(responseBody: String): T {
        val result = json.decodeFromString<GopeedResponse<T>>(responseBody)
        if (result.code != 0) throw IOException(result.msg ?: "Gopeed 请求失败")
        return requireNotNull(result.data) { "Gopeed 响应缺少数据" }
    }

    private fun requireSuccess(responseBody: String) {
        val result = json.decodeFromString<GopeedResponse<JsonElement>>(responseBody)
        if (result.code != 0) throw IOException(result.msg ?: "Gopeed 请求失败")
    }

    private companion object {
        val JsonMediaType = "application/json; charset=utf-8".toMediaType()
    }
}

@Serializable
internal data class GopeedResponse<T>(
    val code: Int,
    val msg: String? = null,
    val data: T? = null,
)

@Serializable
internal data class GopeedTask(
    val id: String,
    val status: String,
    val progress: GopeedProgress = GopeedProgress(),
)

@Serializable
internal data class GopeedProgress(
    val speed: Long = 0L,
    val downloaded: Long = 0L,
)
