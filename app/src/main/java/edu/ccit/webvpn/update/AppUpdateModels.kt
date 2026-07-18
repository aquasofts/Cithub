package edu.ccit.webvpn.update

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

internal const val CITHUB_RELEASES_API =
    "https://api.github.com/repos/aquasofts/Cithub/releases?per_page=20"
internal const val CITHUB_ACCELERATOR_PROBE_APK =
    "https://github.com/aquasofts/Cithub/releases/download/V2.2.0/" +
        "Cithub-2.2.0-auto-captcha-performance.apk"

@Serializable
internal data class GitHubReleaseDto(
    @SerialName("tag_name") val tagName: String,
    val name: String = "",
    val body: String = "",
    @SerialName("html_url") val htmlUrl: String,
    val draft: Boolean = false,
    val prerelease: Boolean = false,
    val assets: List<GitHubAssetDto> = emptyList(),
)

@Serializable
internal data class GitHubAssetDto(
    val name: String,
    val size: Long = 0,
    @SerialName("browser_download_url") val downloadUrl: String,
)

internal data class SemanticVersion(
    val major: Int,
    val minor: Int,
    val patch: Int,
) : Comparable<SemanticVersion> {
    override fun compareTo(other: SemanticVersion): Int =
        compareValuesBy(this, other, SemanticVersion::major, SemanticVersion::minor, SemanticVersion::patch)

    override fun toString(): String = "$major.$minor.$patch"

    companion object {
        private val Pattern = Regex("^[vV]?(\\d+)\\.(\\d+)\\.(\\d+)(?:[-+].*)?$")
        private val EmbeddedPattern = Regex("(?i)(?:^|[^0-9])v?(\\d+)\\.(\\d+)\\.(\\d+)(?:[^0-9]|$)")

        fun parse(value: String): SemanticVersion? {
            val match = Pattern.matchEntire(value.trim()) ?: return null
            return SemanticVersion(
                major = match.groupValues[1].toIntOrNull() ?: return null,
                minor = match.groupValues[2].toIntOrNull() ?: return null,
                patch = match.groupValues[3].toIntOrNull() ?: return null,
            )
        }

        fun find(value: String): SemanticVersion? {
            val match = EmbeddedPattern.find(value) ?: return null
            return SemanticVersion(
                major = match.groupValues[1].toIntOrNull() ?: return null,
                minor = match.groupValues[2].toIntOrNull() ?: return null,
                patch = match.groupValues[3].toIntOrNull() ?: return null,
            )
        }
    }
}

internal enum class UpdateFlavor {
    AutoCaptcha,
    ManualCaptcha,
}

internal data class UpdateAsset(
    val name: String,
    val size: Long,
    val downloadUrl: String,
)

internal data class AppRelease(
    val version: SemanticVersion,
    val tagName: String,
    val title: String,
    val notes: String,
    val pageUrl: String,
    val asset: UpdateAsset?,
    val prerelease: Boolean,
)

internal fun updateFlavor(buildFlavor: String): UpdateFlavor = when (buildFlavor) {
    "autoCaptcha" -> UpdateFlavor.AutoCaptcha
    "manualCaptcha" -> UpdateFlavor.ManualCaptcha
    else -> error("Unsupported update flavor: $buildFlavor")
}

internal fun GitHubReleaseDto.toAppRelease(flavor: UpdateFlavor): AppRelease? {
    if (draft) return null
    val version = SemanticVersion.parse(tagName)
        ?: SemanticVersion.find(name)
        ?: assets.firstNotNullOfOrNull { SemanticVersion.find(it.name) }
        ?: return null
    return AppRelease(
        version = version,
        tagName = tagName,
        title = name.ifBlank { "Cithub ${version}" },
        notes = body.trim().take(6_000),
        pageUrl = htmlUrl,
        asset = selectAsset(assets, flavor, version),
        prerelease = prerelease,
    )
}

internal fun githubUrlCandidates(originalUrl: String, accelerators: List<String>): List<String> =
    (accelerators.map { accelerator -> "${accelerator.trimEnd('/')}/$originalUrl" } + originalUrl).distinct()

internal fun normalizeCustomUpdateUrl(raw: String): String? {
    val url = raw.trim().toHttpUrlOrNull() ?: return null
    return url.takeIf {
        it.isHttps && it.username.isBlank() && it.password.isBlank()
    }?.toString()
}

internal fun customUpdateFileName(url: String): String = url.toHttpUrlOrNull()
    ?.pathSegments
    ?.lastOrNull()
    ?.takeIf { it.endsWith(".apk", ignoreCase = true) }
    ?: "Cithub-custom-update.apk"

internal fun selectAsset(
    assets: List<GitHubAssetDto>,
    flavor: UpdateFlavor,
    version: SemanticVersion,
): UpdateAsset? = assets
    .asSequence()
    .filter { it.name.endsWith(".apk", ignoreCase = true) }
    .mapNotNull { asset -> assetScore(asset.name, flavor, version)?.let { score -> asset to score } }
    .maxByOrNull { it.second }
    ?.first
    ?.let { UpdateAsset(it.name, it.size, it.downloadUrl) }

private fun assetScore(
    name: String,
    flavor: UpdateFlavor,
    version: SemanticVersion,
): Int? {
    val normalized = name.lowercase().replace("_", "-")
    val flavorMatch = when (flavor) {
        UpdateFlavor.AutoCaptcha ->
            "auto-captcha" in normalized || "autocaptcha" in normalized || normalized.startsWith("full-cithub")
        UpdateFlavor.ManualCaptcha ->
            "manual-captcha" in normalized || "manualcaptcha" in normalized || normalized.startsWith("lite-cithub")
    }
    if (!flavorMatch) return null

    return 100 +
        (if (version.toString() in normalized) 20 else 0) +
        (if ("performance" in normalized) 10 else 0)
}
