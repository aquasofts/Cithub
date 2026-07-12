package edu.ccit.webvpn.core.captcha

/** Optional local captcha recognition used for form filling and session recovery. */
interface CaptchaAutomation : AutoCloseable {
    val isEnabled: Boolean

    suspend fun recognize(imageBytes: ByteArray): String

    override fun close() = Unit
}

object DisabledCaptchaAutomation : CaptchaAutomation {
    override val isEnabled: Boolean = false

    override suspend fun recognize(imageBytes: ByteArray): String = ""
}
