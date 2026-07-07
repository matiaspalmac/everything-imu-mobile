package cl.matiaspalma.everythingimu.core.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Queries the GitHub Releases API for the mobile repository's latest release
 * tag and reports whether it is newer than the running build. The mobile app
 * lives in its own repository (`everything-imu-mobile`), separate from the
 * desktop app, so the update check must target it — not the desktop repo.
 * Network-only — no install is
 * performed; the caller hands the user an `Intent.ACTION_VIEW` deep-link to
 * the release's HTML page so the OS package installer takes it from there.
 *
 * Lives in `core` so the phone and Wear OS apps can share the network call
 * and the version-comparison logic; only the UI surface differs.
 */
object UpdateChecker {

    /** Resolved comparison result between the running build and GitHub's latest tag. */
    data class UpdateInfo(
        val currentVersion: String,
        val latestVersion: String,
        val releaseUrl: String,
        val updateAvailable: Boolean,
    )

    private const val LATEST_RELEASE_URL =
        "https://api.github.com/repos/matiaspalmac/everything-imu-mobile/releases/latest"

    /** Fallback when the response-supplied `html_url` fails validation. */
    private const val RELEASES_PAGE_URL =
        "https://github.com/matiaspalmac/everything-imu-mobile/releases/latest"

    /**
     * Fetches `releases/latest` and returns a populated [UpdateInfo]. Returns
     * `null` only on transport failure — when the request succeeds but no
     * newer version is published, the result carries `updateAvailable = false`
     * so the UI can show "you're on the latest" without a second flag.
     */
    suspend fun check(currentVersion: String): UpdateInfo? = withContext(Dispatchers.IO) {
        runCatching {
            val conn = (URL(LATEST_RELEASE_URL).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 5_000
                readTimeout = 5_000
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "everything-imu-mobile")
            }
            try {
                if (conn.responseCode !in 200..299) return@runCatching null
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                val root = JSONObject(body)
                val tag = root.optString("tag_name").trimStart('v').ifEmpty { return@runCatching null }
                val htmlUrl = root.optString("html_url")
                UpdateInfo(
                    currentVersion = currentVersion,
                    latestVersion = tag,
                    releaseUrl = safeReleaseUrl(htmlUrl),
                    updateAvailable = isNewer(tag, currentVersion),
                )
            } finally {
                conn.disconnect()
            }
        }.getOrNull()
    }

    /**
     * SemVer-ish comparison. Strips any pre-release/build suffix
     * (`-beta.6`, `+build`) before tokenizing the release core, compares the
     * integer components, and — when the cores are equal — treats a
     * pre-release as older than the corresponding release (so a running
     * `1.0.0-beta.6` still sees stable `1.0.0` as an update).
     */
    private fun isNewer(candidate: String, current: String): Boolean {
        val a = parseVersion(candidate)
        val b = parseVersion(current)
        val max = maxOf(a.core.size, b.core.size)
        for (i in 0 until max) {
            val ai = a.core.getOrElse(i) { 0 }
            val bi = b.core.getOrElse(i) { 0 }
            if (ai > bi) return true
            if (ai < bi) return false
        }
        // Cores equal: a release supersedes a pre-release of the same core.
        return b.preRelease && !a.preRelease
    }

    private data class Version(val core: List<Int>, val preRelease: Boolean)

    private fun parseVersion(s: String): Version {
        val trimmed = s.trim()
        // SemVer pre-release ('-') and build ('+') metadata begin the suffix.
        val core = trimmed.substringBefore('-').substringBefore('+')
        val preRelease = core.length < trimmed.length
        val parts = core.split(Regex("[^0-9]+")).mapNotNull { it.toIntOrNull() }
        return Version(parts, preRelease)
    }

    /** Only launch response-supplied URLs that are https on a GitHub host. */
    private fun safeReleaseUrl(url: String): String {
        return try {
            val parsed = URL(url)
            val host = parsed.host.orEmpty().lowercase()
            if (parsed.protocol.equals("https", ignoreCase = true) &&
                (host == "github.com" || host.endsWith(".github.com"))
            ) {
                url
            } else {
                RELEASES_PAGE_URL
            }
        } catch (_: Throwable) {
            RELEASES_PAGE_URL
        }
    }
}
