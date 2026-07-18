package edu.ccit.webvpn.update

import java.io.File
import java.io.IOException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GopeedUpdateClientTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun createTaskSendsCoreOptionsAndApiToken() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""{"code":0,"data":"task-123"}"""))
        try {
            val client = GopeedUpdateClient(
                baseUrl = server.url("/").toString(),
                apiToken = "local-secret",
            )
            val destination = File(System.getProperty("java.io.tmpdir"), "cithub-gopeed-test")

            val id = client.createTask(
                url = "https://ghproxy.net/https://github.com/aquasofts/Cithub/releases/download/V2.2.1/app.apk",
                destinationDirectory = destination,
                fileName = "Cithub-2.2.1.apk",
                connections = 8,
                userAgent = "Cithub/test (Android)",
            )

            assertEquals("task-123", id)
            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertEquals("/api/v1/tasks", request.path)
            assertEquals("local-secret", request.getHeader("X-Api-Token"))
            val body = json.parseToJsonElement(request.body.readUtf8()).jsonObject
            val req = requireNotNull(body["req"]).jsonObject
            val opts = requireNotNull(body["opts"]).jsonObject
            assertEquals(
                "https://ghproxy.net/https://github.com/aquasofts/Cithub/releases/download/V2.2.1/app.apk",
                requireNotNull(req["url"]).jsonPrimitive.content,
            )
            assertEquals(
                "Cithub/test (Android)",
                requireNotNull(req["extra"])
                    .jsonObject["header"]
                    ?.jsonObject
                    ?.get("User-Agent")
                    ?.jsonPrimitive
                    ?.content,
            )
            assertEquals(destination.absolutePath, requireNotNull(opts["path"]).jsonPrimitive.content)
            assertEquals("Cithub-2.2.1.apk", requireNotNull(opts["name"]).jsonPrimitive.content)
            assertTrue(requireNotNull(opts["selectFiles"]).jsonArray.isEmpty())
            assertEquals(
                8,
                requireNotNull(opts["extra"])
                    .jsonObject["connections"]
                    ?.jsonPrimitive
                    ?.content
                    ?.toInt(),
            )
        } finally {
            server.close()
        }
    }

    @Test
    fun parsesTaskStatusProgressAndUsesToken() {
        val server = MockWebServer()
        server.enqueue(
            MockResponse().setBody(
                """
                {
                  "code": 0,
                  "data": {
                    "id": "task-123",
                    "status": "running",
                    "progress": {"speed": 2048, "downloaded": 4096}
                  }
                }
                """.trimIndent(),
            ),
        )
        try {
            val client = GopeedUpdateClient(server.url("/").toString(), "local-secret")

            val task = client.getTask("task-123")

            assertEquals("task-123", task.id)
            assertEquals("running", task.status)
            assertEquals(2_048L, task.progress.speed)
            assertEquals(4_096L, task.progress.downloaded)
            val request = server.takeRequest()
            assertEquals("GET", request.method)
            assertEquals("/api/v1/tasks/task-123", request.path)
            assertEquals("local-secret", request.getHeader("X-Api-Token"))
        } finally {
            server.close()
        }
    }

    @Test
    fun continueTaskUsesPutWithAnEmptyBody() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""{"code":0,"data":null}"""))
        try {
            val client = GopeedUpdateClient(server.url("/").toString(), "local-secret")

            client.continueTask("task-123")

            val request = server.takeRequest()
            assertEquals("PUT", request.method)
            assertEquals("/api/v1/tasks/task-123/continue", request.path)
            assertEquals(0L, request.body.size)
        } finally {
            server.close()
        }
    }

    @Test
    fun nonzeroGopeedResultThrows() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""{"code":2001,"msg":"task not found"}"""))
        try {
            val client = GopeedUpdateClient(server.url("/").toString(), "local-secret")

            val error = runCatching { client.getTask("missing") }.exceptionOrNull()

            assertTrue(error is IOException)
            assertEquals("task not found", error?.message)
        } finally {
            server.close()
        }
    }
}
