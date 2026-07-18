package edu.ccit.webvpn.update

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File
import java.security.MessageDigest

internal data class VerifiedUpdateApk(
    val path: String,
    val versionCode: Long,
    val version: SemanticVersion,
)

internal class UpdateVerificationException(message: String) : Exception(message)

internal object UpdateInstaller {
    private const val ApkMimeType = "application/vnd.android.package-archive"

    fun updateDirectory(context: Context): File =
        File(
            requireNotNull(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)) {
                "外部应用存储当前不可用"
            },
            "updates",
        ).apply { mkdirs() }

    fun destinationFile(context: Context, assetName: String): File {
        val safeName = assetName
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
            .take(160)
            .ifBlank { "Cithub-update.apk" }
        return File(updateDirectory(context), safeName)
    }

    fun verify(
        context: Context,
        apk: File,
        release: AppRelease,
        flavor: UpdateFlavor,
        verifyReleaseVersion: Boolean = true,
    ): VerifiedUpdateApk {
        if (!apk.isFile || apk.length() <= 0L) {
            throw UpdateVerificationException("下载的安装包不存在或为空")
        }

        val packageManager = context.packageManager
        val archive = packageManager.archivePackageInfo(apk)
            ?: throw UpdateVerificationException("下载的文件不是有效的 Android 安装包")
        val installed = packageManager.installedPackageInfo(context.packageName)

        if (archive.packageName != context.packageName) {
            throw UpdateVerificationException("安装包应用标识不匹配，已阻止安装")
        }

        val archiveVersionName = archive.versionName.orEmpty()
        val archiveVersion = SemanticVersion.parse(archiveVersionName)
            ?: throw UpdateVerificationException("安装包版本格式无效")
        if (verifyReleaseVersion && archiveVersion != release.version) {
            throw UpdateVerificationException("安装包版本与 Release ${release.tagName} 不匹配")
        }
        val flavorMatches = when (flavor) {
            UpdateFlavor.AutoCaptcha -> archiveVersionName.endsWith("-auto-captcha")
            UpdateFlavor.ManualCaptcha -> archiveVersionName.endsWith("-manual-captcha")
        }
        if (!flavorMatches) {
            throw UpdateVerificationException("安装包类型与当前 Full/Lite 版本不匹配")
        }

        val archiveVersionCode = archive.longVersionCodeCompat()
        if (archiveVersionCode <= installed.longVersionCodeCompat()) {
            throw UpdateVerificationException("安装包版本号没有高于当前版本，已阻止降级或重复安装")
        }

        if (!packageManager.signaturesCanUpdate(installed, archive)) {
            throw UpdateVerificationException("安装包签名不兼容，需卸载后安装")
        }

        return VerifiedUpdateApk(apk.absolutePath, archiveVersionCode, archiveVersion)
    }

    fun canRequestInstalls(context: Context): Boolean =
        context.packageManager.canRequestPackageInstalls()

    fun unknownSourcesSettingsIntent(context: Context): Intent = Intent(
        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
        Uri.parse("package:${context.packageName}"),
    )

    fun installIntent(context: Context, apkPath: String): Intent {
        val apk = File(apkPath)
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.update-files",
            apk,
        )
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, ApkMimeType)
            clipData = ClipData.newRawUri("Cithub update", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }
}

@Suppress("DEPRECATION")
private fun PackageManager.archivePackageInfo(apk: File): PackageInfo? =
    getPackageArchiveInfo(
        apk.absolutePath,
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            PackageManager.GET_SIGNATURES
        },
    )

@Suppress("DEPRECATION")
private fun PackageManager.installedPackageInfo(packageName: String): PackageInfo =
    getPackageInfo(
        packageName,
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            PackageManager.GET_SIGNATURES
        },
    )

@Suppress("DEPRECATION")
private fun PackageInfo.longVersionCodeCompat(): Long =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) longVersionCode else versionCode.toLong()

@Suppress("DEPRECATION")
private fun PackageManager.signaturesCanUpdate(installed: PackageInfo, archive: PackageInfo): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
        return installed.signatures.orEmpty().digestSet() == archive.signatures.orEmpty().digestSet()
    }

    val installedInfo = installed.signingInfo ?: return false
    val archiveInfo = archive.signingInfo ?: return false
    val installedCurrent = installedInfo.apkContentsSigners.orEmpty().digestSet()
    val archiveCurrent = archiveInfo.apkContentsSigners.orEmpty().digestSet()
    if (installedCurrent == archiveCurrent) return true

    val archiveHistory = archiveInfo.signingCertificateHistory.orEmpty().digestSet()
    return installedCurrent.isNotEmpty() && archiveHistory.containsAll(installedCurrent)
}

private fun Array<out android.content.pm.Signature>.digestSet(): Set<String> = mapTo(linkedSetOf()) { signature ->
    MessageDigest.getInstance("SHA-256")
        .digest(signature.toByteArray())
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
