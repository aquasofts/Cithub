package edu.ccit.webvpn.core.runtime

import android.content.Context
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class RuntimeLogTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val runtimeLog = RuntimeLog.get(context)

    @Before
    fun clearBefore() = runBlocking { runtimeLog.clear() }

    @After
    fun clearAfter() = runBlocking { runtimeLog.clear() }

    @Test
    fun interceptorKeepsSensitiveTextAndTruncatesLargeBodies() = runBlocking {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json; charset=utf-8")
                .setBody("{\"stoken\":\"plain-response-secret\"}"),
        )
        server.start()
        try {
            val largeSecretBody = "password=plain-request-secret&" + "x".repeat(80 * 1024)
            loggedClient().newCall(
                Request.Builder()
                    .url(server.url("/runtime"))
                    .header("Cookie", "BDUSS=plain-cookie-secret")
                    .post(largeSecretBody.toRequestBody("text/plain".toMediaType()))
                    .build(),
            ).execute().use { response -> assertTrue(response.isSuccessful) }

            val exported = runtimeLog.exportText()
            assertTrue(exported.contains(RuntimeLog.PRIVACY_WARNING))
            assertTrue(exported.contains("plain-request-secret"))
            assertTrue(exported.contains("plain-response-secret"))
            assertTrue(exported.contains("plain-cookie-secret"))
            assertTrue(exported.contains("\"captured_bytes\":65536"))
            assertTrue(exported.contains("\"truncated\":true"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun interceptorDoesNotStoreImagePayloads() = runBlocking {
        val server = MockWebServer()
        val imageBytes = ByteArray(128 * 1024) { (it and 0xff).toByte() }
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "image/jpeg")
                .setBody(Buffer().write(imageBytes)),
        )
        server.start()
        try {
            loggedClient().newCall(Request.Builder().url(server.url("/large.jpg")).build())
                .execute()
                .use { response ->
                    assertTrue(response.isSuccessful)
                    response.body?.bytes()
                }

            val exported = runtimeLog.exportText()
            assertTrue(exported.contains("skipped_binary_media"))
            assertTrue(exported.contains("image/jpeg"))
            assertFalse(exported.contains(android.util.Base64.encodeToString(imageBytes, android.util.Base64.NO_WRAP)))
            assertTrue(exported.length < 20_000)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun streamedExportWritesTheCompleteLogWithoutUsingClipboardText() = runBlocking {
        val newestMarker = "latest-runtime-marker"
        runtimeLog.info(
            source = "runtime_test",
            event = "large_export",
            fields = mapOf(
                "payload" to "x".repeat(512 * 1024),
                "marker" to newestMarker,
            ),
        )
        val output = ByteArrayOutputStream()

        runtimeLog.writeTo(output)

        val exported = output.toString(Charsets.UTF_8.name())
        assertTrue(exported.startsWith("Cithub 运行日志"))
        assertTrue(exported.contains(RuntimeLog.PRIVACY_WARNING))
        assertTrue(exported.contains(newestMarker))
        assertTrue(output.size() > 512 * 1024)
    }

    private fun loggedClient(): OkHttpClient = OkHttpClient.Builder()
        .readTimeout(5, TimeUnit.SECONDS)
        .addInterceptor(RuntimeLogInterceptor(context, "runtime_test"))
        .build()
}
