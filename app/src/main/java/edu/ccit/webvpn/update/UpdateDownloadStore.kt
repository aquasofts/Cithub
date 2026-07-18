package edu.ccit.webvpn.update

import android.content.Context
import java.io.File
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
internal enum class UpdateDownloadStatus {
    Queued,
    Downloading,
    Ready,
    Failed,
    Cancelled,
}

@Serializable
internal data class UpdateDownloadRecord(
    val status: UpdateDownloadStatus,
    val destinationPath: String,
    val version: String,
    val tagName: String,
    val title: String,
    val notes: String,
    val pageUrl: String,
    val assetName: String,
    val assetSize: Long,
    val assetUrl: String,
    val prerelease: Boolean,
    val downloadUrls: List<String>,
    val urlIndex: Int = 0,
    val gopeedTaskId: String? = null,
    val downloadedBytes: Long = 0L,
    val speedBytesPerSecond: Long = 0L,
    val connections: Int = DefaultConnections,
    val singleConnectionFallback: Boolean = false,
    val customDownload: Boolean = false,
    val verifiedVersionCode: Long? = null,
    val errorMessage: String? = null,
) {
    val currentUrl: String
        get() = downloadUrls[urlIndex]

    val activeConnections: Int
        get() = if (singleConnectionFallback) 1 else connections.coerceIn(1, 64)

    fun toRelease(): AppRelease = AppRelease(
        version = requireNotNull(SemanticVersion.parse(version)),
        tagName = tagName,
        title = title,
        notes = notes,
        pageUrl = pageUrl,
        asset = UpdateAsset(assetName, assetSize, assetUrl),
        prerelease = prerelease,
    )

    fun toVerifiedApk(): VerifiedUpdateApk? = verifiedVersionCode?.let { code ->
        SemanticVersion.parse(version)?.let { version -> VerifiedUpdateApk(destinationPath, code, version) }
    }

    companion object {
        const val DefaultConnections = 16

        fun create(
            destination: File,
            release: AppRelease,
            downloadUrls: List<String>,
            connections: Int = DefaultConnections,
            customDownload: Boolean = false,
        ): UpdateDownloadRecord {
            val asset = requireNotNull(release.asset)
            return UpdateDownloadRecord(
                status = UpdateDownloadStatus.Queued,
                destinationPath = destination.absolutePath,
                version = release.version.toString(),
                tagName = release.tagName,
                title = release.title,
                notes = release.notes,
                pageUrl = release.pageUrl,
                assetName = asset.name,
                assetSize = asset.size,
                assetUrl = asset.downloadUrl,
                prerelease = release.prerelease,
                downloadUrls = downloadUrls,
                connections = connections.coerceAtLeast(1),
                customDownload = customDownload,
            )
        }
    }
}

internal fun UpdateDownloadRecord.nextDownloadAttempt(): UpdateDownloadRecord? {
    val retrySingleConnection = connections > 1 && !singleConnectionFallback
    val nextUrlIndex = urlIndex + 1
    if (!retrySingleConnection && nextUrlIndex !in downloadUrls.indices) return null

    return copy(
        status = UpdateDownloadStatus.Queued,
        urlIndex = if (retrySingleConnection) urlIndex else nextUrlIndex,
        gopeedTaskId = null,
        downloadedBytes = 0L,
        speedBytesPerSecond = 0L,
        singleConnectionFallback = retrySingleConnection,
        verifiedVersionCode = null,
        errorMessage = null,
    )
}

internal object UpdateDownloadStore {
    const val PreferencesName = "app_update_download"
    private const val RecordKey = "gopeed_download"
    private const val ApiTokenKey = "gopeed_api_token"
    private val json = Json { ignoreUnknownKeys = true }

    fun read(context: Context): UpdateDownloadRecord? = preferences(context)
        .getString(RecordKey, null)
        ?.let { encoded -> runCatching { json.decodeFromString<UpdateDownloadRecord>(encoded) }.getOrNull() }
        ?.takeIf { record ->
            SemanticVersion.parse(record.version) != null &&
                record.downloadUrls.isNotEmpty() &&
                record.urlIndex in record.downloadUrls.indices
        }

    fun write(context: Context, record: UpdateDownloadRecord) {
        check(preferences(context).edit().putString(RecordKey, json.encodeToString(record)).commit()) {
            "无法保存更新下载状态"
        }
    }

    fun clear(context: Context) {
        preferences(context).edit().remove(RecordKey).commit()
    }

    fun apiToken(context: Context): String {
        val preferences = preferences(context)
        preferences.getString(ApiTokenKey, null)?.takeIf(String::isNotBlank)?.let { return it }
        val token = UUID.randomUUID().toString()
        check(preferences.edit().putString(ApiTokenKey, token).commit()) {
            "无法保存 Gopeed 本地接口令牌"
        }
        return token
    }

    private fun preferences(context: Context) =
        context.applicationContext.getSharedPreferences(PreferencesName, Context.MODE_PRIVATE)
}
