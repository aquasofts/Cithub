package edu.ccit.webvpn.settings

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AppIconController @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    fun setThemed(enabled: Boolean) {
        val packageManager = context.packageManager
        val enabledComponent = component(themed = enabled)
        val disabledComponent = component(themed = !enabled)
        packageManager.setComponentEnabledSetting(
            enabledComponent,
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
            PackageManager.DONT_KILL_APP,
        )
        packageManager.setComponentEnabledSetting(
            disabledComponent,
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP,
        )
    }

    private fun component(themed: Boolean) = ComponentName(
        context,
        "${context.packageName}.${if (themed) "LauncherThemed" else "LauncherPlain"}",
    )
}
