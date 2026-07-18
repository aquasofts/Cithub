package edu.ccit.webvpn

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import edu.ccit.webvpn.core.runtime.RuntimeLog
import edu.ccit.webvpn.feature.tieba.TiebaRuntime
import edu.ccit.webvpn.update.UpgradeHousekeeping

@HiltAndroidApp
class CcitAcademicApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        RuntimeLog.get(this).install(this)
        UpgradeHousekeeping.run(this)
        TiebaRuntime.get(this)
    }
}
