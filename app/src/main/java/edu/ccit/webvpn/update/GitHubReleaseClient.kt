package edu.ccit.webvpn.update

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request

internal class GitHubReleaseClient(
    private val userAgent: String,
    private val apiUrl: String = CITHUB_RELEASES_API,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS)
        .build(),
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    suspend fun latest(
        flavor: UpdateFlavor,
        includePrereleases: Boolean,
        accelerators: List<String>,
    ): AppRelease? = withContext(Dispatchers.IO) {
        var lastError: Throwable? = null
        for (candidate in githubUrlCandidates(apiUrl, accelerators)) {
            val result = runCatching { request(candidate, flavor, includePrereleases) }
            result.onSuccess { return@withContext it }
            lastError = result.exceptionOrNull()
        }
        throw IOException("无法访问 GitHub", lastError)
    }

    private fun request(
        url: String,
        flavor: UpdateFlavor,
        includePrereleases: Boolean,
    ): AppRelease? {
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("User-Agent", userAgent)
            .build()
        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("HTTP ${response.code}")
            }
            val responseBody = response.body.string()
            json.decodeFromString<List<GitHubReleaseDto>>(responseBody)
                .asSequence()
                .filterNot(GitHubReleaseDto::draft)
                .filter { includePrereleases || !it.prerelease }
                .mapNotNull { it.toAppRelease(flavor) }
                .maxWithOrNull(
                    compareBy<AppRelease> { it.version }
                        .thenBy { if (it.prerelease) 0 else 1 },
                )
        }
    }
}
