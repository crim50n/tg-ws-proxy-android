package dev.minios.tgwsproxy.update

import android.content.Context
import dev.minios.tgwsproxy.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

private const val RELEASE_API_URL =
    "https://api.github.com/repos/crim50n/tg-ws-proxy-android/releases/latest"
private const val MANIFEST_URL =
    "https://raw.githubusercontent.com/crim50n/tg-ws-proxy-android/master/update.json"
private const val RELEASE_TAG_URL =
    "https://github.com/crim50n/tg-ws-proxy-android/releases/tag/"
private const val CHECK_INTERVAL_MS = 24 * 60 * 60_000L
private const val MAX_API_CHARS = 64 * 1024
private const val MAX_MANIFEST_CHARS = 8 * 1024

data class UpdateInfo(
    val versionName: String,
    val releaseUrl: String,
    val versionCode: Int? = null,
)

@Serializable
data class UpdateManifest(
    val versionCode: Int,
    val versionName: String,
    val releaseUrl: String,
)

@Serializable
private data class GitHubRelease(
    @SerialName("tag_name") val tagName: String,
    @SerialName("html_url") val htmlUrl: String,
    val draft: Boolean = false,
    val prerelease: Boolean = false,
)

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data object Error : UpdateState
    data class Available(val release: UpdateInfo) : UpdateState
}

object VersionComparator {
    private val versionPattern = Regex("^[0-9]+(?:\\.[0-9]+){1,3}$")

    fun compare(left: String, right: String): Int? {
        if (!left.matches(versionPattern) || !right.matches(versionPattern)) return null
        val leftParts = left.split('.').map { it.toLongOrNull() ?: return null }
        val rightParts = right.split('.').map { it.toLongOrNull() ?: return null }
        val count = maxOf(leftParts.size, rightParts.size)
        for (index in 0 until count) {
            val leftPart = leftParts.getOrElse(index) { 0L }
            val rightPart = rightParts.getOrElse(index) { 0L }
            if (leftPart != rightPart) return leftPart.compareTo(rightPart)
        }
        return 0
    }
}

object UpdateValidator {
    fun isValid(info: UpdateInfo): Boolean {
        if (VersionComparator.compare(info.versionName, info.versionName) == null) return false
        if (info.versionCode != null && info.versionCode <= 0) return false
        return info.releaseUrl == "${RELEASE_TAG_URL}v${info.versionName}"
    }
}

object UpdatePolicy {
    fun isAvailable(
        currentVersionCode: Int,
        currentVersionName: String,
        release: UpdateInfo,
    ): Boolean {
        if (!UpdateValidator.isValid(release)) return false
        return release.versionCode?.let { it > currentVersionCode }
            ?: (VersionComparator.compare(release.versionName, currentVersionName)?.let { it > 0 } == true)
    }
}

class UpdateRepository(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        "update_check",
        Context.MODE_PRIVATE,
    )
    private val json = Json { ignoreUnknownKeys = true }
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .callTimeout(15, TimeUnit.SECONDS)
        .build()

    fun cachedState(): UpdateState {
        val versionName = preferences.getString("version_name", null) ?: return UpdateState.Idle
        val releaseUrl = preferences.getString("release_url", null) ?: return UpdateState.Idle
        val storedCode = preferences.getInt("version_code", 0)
        val release = UpdateInfo(
            versionName = versionName,
            releaseUrl = releaseUrl,
            versionCode = storedCode.takeIf { it > 0 },
        )
        return if (UpdatePolicy.isAvailable(BuildConfig.VERSION_CODE, BuildConfig.VERSION_NAME, release)) {
            UpdateState.Available(release)
        } else {
            UpdateState.Idle
        }
    }

    fun shouldCheck(): Boolean {
        val lastAttempt = preferences.getLong("last_attempt", 0L)
        return System.currentTimeMillis() - lastAttempt >= CHECK_INTERVAL_MS
    }

    suspend fun check(): Result<UpdateInfo> = withContext(Dispatchers.IO) {
        preferences.edit().putLong("last_attempt", System.currentTimeMillis()).apply()
        runCatching {
            val release = try {
                fetchLatestRelease()
            } catch (apiError: Exception) {
                try {
                    fetchManifest()
                } catch (manifestError: Exception) {
                    manifestError.addSuppressed(apiError)
                    throw manifestError
                }
            }
            preferences.edit()
                .putInt("version_code", release.versionCode ?: 0)
                .putString("version_name", release.versionName)
                .putString("release_url", release.releaseUrl)
                .apply()
            release
        }
    }

    private fun fetchLatestRelease(): UpdateInfo {
        executeRequest(RELEASE_API_URL, "application/vnd.github+json", githubApi = true).use { response ->
            if (!response.isSuccessful) error("GitHub API HTTP ${response.code}")
            val githubRelease = json.decodeFromString<GitHubRelease>(readResponse(response, MAX_API_CHARS))
            require(!githubRelease.draft && !githubRelease.prerelease) { "Invalid GitHub release" }
            require(githubRelease.tagName.startsWith('v')) { "Invalid release tag" }
            val release = UpdateInfo(
                versionName = githubRelease.tagName.removePrefix("v"),
                releaseUrl = githubRelease.htmlUrl,
            )
            require(UpdateValidator.isValid(release)) { "Invalid GitHub release metadata" }
            return release
        }
    }

    private fun fetchManifest(): UpdateInfo {
        executeRequest(MANIFEST_URL, "application/json").use { response ->
            if (!response.isSuccessful) error("Update manifest HTTP ${response.code}")
            val manifest = json.decodeFromString<UpdateManifest>(readResponse(response, MAX_MANIFEST_CHARS))
            val release = UpdateInfo(manifest.versionName, manifest.releaseUrl, manifest.versionCode)
            require(UpdateValidator.isValid(release)) { "Invalid update manifest" }
            return release
        }
    }

    private fun executeRequest(url: String, accept: String, githubApi: Boolean = false) = client.newCall(
        Request.Builder()
            .url(url)
            .header("Accept", accept)
            .header("User-Agent", "TG-WS-Proxy/${BuildConfig.VERSION_NAME}")
            .apply {
                if (githubApi) header("X-GitHub-Api-Version", "2022-11-28")
            }
            .build(),
    ).execute()

    private fun readResponse(response: okhttp3.Response, maxChars: Int): String {
        val body = response.body ?: error("Empty response")
        if (body.contentLength() > maxChars) error("Update metadata is too large")
        body.charStream().use { reader ->
            val result = StringBuilder()
            val buffer = CharArray(1024)
            while (true) {
                val count = reader.read(buffer)
                if (count < 0) break
                result.append(buffer, 0, count)
                if (result.length > maxChars) error("Update metadata is too large")
            }
            return result.toString()
        }
    }
}
