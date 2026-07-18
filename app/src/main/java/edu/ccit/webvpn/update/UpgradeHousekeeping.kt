package edu.ccit.webvpn.update

import android.content.Context
import android.os.Build
import java.io.File
import java.util.concurrent.Executors

object UpgradeHousekeeping {
    private const val PreferencesName = "app_upgrade_state"
    private const val LastVersionCodeKey = "last_version_code"
    private val executor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "cithub-upgrade-housekeeping").apply { isDaemon = true }
    }

    fun run(context: Context) {
        val appContext = context.applicationContext
        val packageInfo = runCatching {
            @Suppress("DEPRECATION")
            appContext.packageManager.getPackageInfo(appContext.packageName, 0)
        }.getOrNull() ?: return
        val currentVersionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            packageInfo.versionCode.toLong()
        }
        val preferences = appContext.getSharedPreferences(PreferencesName, Context.MODE_PRIVATE)
        val previousVersionCode = preferences.getLong(LastVersionCodeKey, 0L).takeIf { it > 0L }
        val upgraded = shouldCleanUpgradeCaches(
            previousVersionCode = previousVersionCode,
            currentVersionCode = currentVersionCode,
            firstInstallTime = packageInfo.firstInstallTime,
            lastUpdateTime = packageInfo.lastUpdateTime,
        )
        preferences.edit().putLong(LastVersionCodeKey, currentVersionCode).apply()

        if (upgraded) {
            appContext.getSharedPreferences("app_update_download", Context.MODE_PRIVATE)
                .edit()
                .clear()
                .apply()
            val suffix = "$currentVersionCode-${System.currentTimeMillis()}"
            val stalePaths = buildList {
                addAll(detachChildren(appContext.cacheDir, suffix))
                detachDirectory(File(appContext.noBackupFilesDir, "home_feed_cache"), suffix)?.let(::add)
                runCatching { detachDirectory(UpdateInstaller.updateDirectory(appContext), suffix) }
                    .getOrNull()
                    ?.let(::add)
            }
            executor.execute {
                stalePaths.forEach { stale -> runCatching { stale.deleteRecursively() } }
            }
        }
    }

    private fun detachChildren(root: File, suffix: String): List<File> {
        if (!root.isDirectory) return emptyList()
        val canonicalRoot = root.canonicalFile
        return root.listFiles().orEmpty().mapIndexedNotNull { index, child ->
            val canonicalChild = runCatching { child.canonicalFile }.getOrNull()
                ?: return@mapIndexedNotNull null
            if (canonicalChild.path.startsWith(canonicalRoot.path + File.separator)) {
                val stale = File(canonicalRoot, ".cithub-stale-$suffix-$index")
                if (canonicalChild.renameTo(stale)) stale else null
            } else null
        }
    }

    private fun detachDirectory(directory: File, suffix: String): File? {
        if (!directory.exists()) return null
        val parent = directory.parentFile?.canonicalFile ?: return null
        val canonicalDirectory = directory.canonicalFile
        if (!canonicalDirectory.path.startsWith(parent.path + File.separator)) return null
        val stale = File(parent, ".${directory.name}.cithub-stale-$suffix")
        return stale.takeIf { canonicalDirectory.renameTo(it) }
    }
}

internal fun shouldCleanUpgradeCaches(
    previousVersionCode: Long?,
    currentVersionCode: Long,
    firstInstallTime: Long,
    lastUpdateTime: Long,
): Boolean = when {
    previousVersionCode != null -> currentVersionCode > previousVersionCode
    else -> lastUpdateTime > firstInstallTime
}
