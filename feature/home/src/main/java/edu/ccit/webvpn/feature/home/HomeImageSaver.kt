package edu.ccit.webvpn.feature.home

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val HomeAlbumName = "CCIT Academic"

internal suspend fun saveHomeImage(context: Context, url: String): String = withContext(Dispatchers.IO) {
    val cached = HomeImageCache.get(context).getOrFetchBlocking(url) ?: error("图片下载失败")
    val extension = homeImageExtension(cached.mimeType, url)
    val mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
        ?: cached.mimeType.takeIf { it.startsWith("image/") }
        ?: "image/jpeg"
    val sourceName = url.substringBefore('?').substringAfterLast('/').substringBeforeLast('.')
        .filter { it.isLetterOrDigit() || it == '-' || it == '_' }
        .take(36)
        .ifBlank { "image" }
    val displayName = "News_${sourceName}_${System.currentTimeMillis()}.$extension"

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        saveHomeImageWithMediaStore(context, displayName, mimeType, cached.file)
    } else {
        saveHomeImageToLegacyPictures(context, displayName, mimeType, cached.file)
    }
    "Pictures/$HomeAlbumName/$displayName"
}

internal fun homeImageExtension(mimeType: String?, url: String): String {
    val mimeExtension = when (mimeType?.substringBefore(';')?.lowercase()) {
        "image/jpeg", "image/jpg" -> "jpg"
        "image/png" -> "png"
        "image/webp" -> "webp"
        "image/gif" -> "gif"
        "image/heic" -> "heic"
        "image/heif" -> "heif"
        "image/avif" -> "avif"
        else -> null
    }
    val urlExtension = url.substringBefore('?').substringAfterLast('.').lowercase()
        .takeIf { it in setOf("jpg", "jpeg", "png", "webp", "gif", "heic", "heif", "avif") }
    return when (val extension = mimeExtension ?: urlExtension ?: "jpg") {
        "jpeg" -> "jpg"
        else -> extension
    }
}

private fun saveHomeImageWithMediaStore(
    context: Context,
    displayName: String,
    mimeType: String,
    source: File,
) {
    val resolver = context.contentResolver
    val values = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
        put(MediaStore.Images.Media.MIME_TYPE, mimeType)
        put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/$HomeAlbumName")
        put(MediaStore.Images.Media.IS_PENDING, 1)
    }
    val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        ?: error("无法创建相册文件")
    try {
        resolver.openOutputStream(uri, "w")?.use { output ->
            source.inputStream().buffered().use { input -> input.copyTo(output) }
        } ?: error("无法写入相册文件")
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
private fun saveHomeImageToLegacyPictures(
    context: Context,
    displayName: String,
    mimeType: String,
    source: File,
) {
    val directory = File(
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
        HomeAlbumName,
    )
    check(directory.exists() || directory.mkdirs()) { "无法创建相册目录" }
    val target = File(directory, displayName)
    try {
        source.inputStream().buffered().use { input ->
            FileOutputStream(target).buffered().use { output -> input.copyTo(output) }
        }
        MediaScannerConnection.scanFile(
            context,
            arrayOf(target.absolutePath),
            arrayOf(mimeType),
            null,
        )
    } catch (error: Throwable) {
        target.delete()
        throw error
    }
}
