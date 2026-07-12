package edu.ccit.webvpn

import android.content.Context
import edu.ccit.webvpn.core.captcha.CaptchaAutomation
import edu.ccit.webvpn.core.captcha.DisabledCaptchaAutomation

object CaptchaAutomationProvider {
    fun get(@Suppress("UNUSED_PARAMETER") context: Context): CaptchaAutomation =
        DisabledCaptchaAutomation
}
