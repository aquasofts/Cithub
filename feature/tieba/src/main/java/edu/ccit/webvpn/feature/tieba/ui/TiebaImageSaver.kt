package edu.ccit.webvpn.feature.tieba.ui

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import edu.ccit.webvpn.feature.tieba.TiebaRuntime
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val TiebaAlbumName = "CCIT Academic"

internal suspend fun saveTiebaImage(
    context: Context,
    runtime: TiebaRuntime,
    url: String,
    picId: String,
): String = withContext(Dispatchers.IO) {
    val downloaded = runtime.network.downloadImage(url)
    val extension = imageExtension(downloaded.mimeType, url)
    val mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
        ?: "image/jpeg"
    val safeId = picId.filter { it.isLetterOrDigit() || it == '-' || it == '_' }
        .take(40)
        .ifBlank { "image" }
    val displayName = "Tieba_${safeId}_${System.currentTimeMillis()}.$extension"

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        saveWithMediaStore(context, displayName, mimeType, downloaded.bytes)
    } else {
        saveToLegacyPictures(context, displayName, mimeType, downloaded.bytes)
    }
    "Pictures/$TiebaAlbumName/$displayName"
}

internal fun imageExtension(mimeType: String?, url: String): String {
    val normalizedMime = mimeType?.substringBefore(';')?.lowercase()
    val mimeExtension = when (normalizedMime) {
        "image/jpeg", "image/jpg" -> "jpg"
        "image/png" -> "png"
        "image/webp" -> "webp"
        "image/gif" -> "gif"
        "image/heic" -> "heic"
        "image/heif" -> "heif"
        "image/avif" -> "avif"
        else -> null
    }
    val urlExtension = url.substringBefore('?')
        .substringAfterLast('/')
        .substringAfterLast('.', "")
        .lowercase()
        .takeIf { it in setOf("jpg", "jpeg", "png", "webp", "gif", "heic", "heif", "avif") }
    return when (val extension = mimeExtension ?: urlExtension ?: "jpg") {
        "jpeg" -> "jpg"
        else -> extension
    }
}

private fun saveWithMediaStore(
    context: Context,
    displayName: String,
    mimeType: String,
    bytes: ByteArray,
) {
    val resolver = context.contentResolver
    val values = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
        put(MediaStore.Images.Media.MIME_TYPE, mimeType)
        put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/$TiebaAlbumName")
        put(MediaStore.Images.Media.IS_PENDING, 1)
    }
    val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        ?: error("无法创建相册文件")
    try {
        resolver.openOutputStream(uri, "w")?.use { it.write(bytes) }
            ?: error("无法写入相册文件")
        resolver.update(
            uri,
            ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) },
            null,
            null,
        )
    } catch (error: Throwable) {
        resolver.delete(uri, null, null)
        throw error
    }
}

@Suppress("DEPRECATION")
private fun saveToLegacyPictures(
    context: Context,
    displayName: String,
    mimeType: String,
    bytes: ByteArray,
) {
    val directory = File(
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
        TiebaAlbumName,
    )
    check(directory.exists() || directory.mkdirs()) { "无法创建相册目录" }
    val file = File(directory, displayName)
    try {
        FileOutputStream(file).use { it.write(bytes) }
        MediaScannerConnection.scanFile(
            context,
            arrayOf(file.absolutePath),
            arrayOf(mimeType),
            null,
        )
    } catch (error: Throwable) {
        file.delete()
        throw error
    }
}
