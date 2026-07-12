package edu.ccit.webvpn.feature.captcha.autofill

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import edu.ccit.webvpn.core.captcha.CaptchaAutomation
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.abs
import kotlin.math.max
import kotlinx.coroutines.suspendCancellableCoroutine

class MlKitCaptchaAutomation : CaptchaAutomation {
    override val isEnabled: Boolean = true

    private val recognizer: TextRecognizer =
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    override suspend fun recognize(imageBytes: ByteArray): String {
        val source = imageBytes.decodeBitmap()
        val scaled = source.scaledForRecognition()
        return try {
            recognizer.process(InputImage.fromBitmap(scaled, 0))
                .await()
                .captchaCandidates()
                .bestCaptchaCandidate()
        } finally {
            if (scaled !== source) scaled.recycle()
            source.recycle()
        }
    }

    override fun close() = recognizer.close()
}

private fun ByteArray.decodeBitmap(): Bitmap =
    requireNotNull(BitmapFactory.decodeByteArray(this, 0, size)) { "验证码图片无法解析" }

private fun Bitmap.scaledForRecognition(): Bitmap {
    if (width >= TargetCaptchaWidth) return this
    val targetHeight = max(MinimumCaptchaHeight, height * TargetCaptchaWidth / width)
    return Bitmap.createScaledBitmap(this, TargetCaptchaWidth, targetHeight, true)
}

private fun Text.captchaCandidates(): List<String> = buildList {
    add(text)
    textBlocks.forEach { block ->
        add(block.text)
        block.lines.forEach { line ->
            add(line.text)
            line.elements.forEach { element -> add(element.text) }
        }
    }
}

private fun List<String>.bestCaptchaCandidate(): String = asSequence()
    .map(String::captchaCharacters)
    .filter { it.length in MinCaptchaLength..MaxCaptchaLength }
    .distinct()
    .minWithOrNull(
        compareBy<String> { abs(it.length - ExpectedCaptchaLength) }
            .thenByDescending(String::length),
    )
    .orEmpty()

private fun String.captchaCharacters(): String =
    filter { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' }

private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { continuation ->
    addOnCompleteListener { task ->
        if (!continuation.isActive) return@addOnCompleteListener
        val error = task.exception
        if (error != null) continuation.resumeWithException(error)
        else continuation.resume(task.result)
    }
}

private const val TargetCaptchaWidth = 600
private const val MinimumCaptchaHeight = 160
private const val ExpectedCaptchaLength = 4
private const val MinCaptchaLength = 3
private const val MaxCaptchaLength = 8
