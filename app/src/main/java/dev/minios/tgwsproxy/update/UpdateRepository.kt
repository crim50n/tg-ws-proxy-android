package dev.minios.tgwsproxy.update

import android.content.Context
import dev.minios.tgwsproxy.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import java.io.IOException
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resumeWithException

private const val RELEASE_API_URL =
    "https://api.github.com/repos/crim50n/tg-ws-proxy-android/releases/latest"
private const val MANIFEST_URL =
    "https://raw.githubusercontent.com/crim50n/tg-ws-proxy-android/master/update.json"
private const val RELEASE_TAG_URL =
    "https://github.com/crim50n/tg-ws-proxy-android/releases/tag/"
private const val RELEASE_DOWNLOAD_URL =
    "https://github.com/crim50n/tg-ws-proxy-android/releases/download/"
private const val CHECK_INTERVAL_MS = 24 * 60 * 60_000L
private const val MAX_API_CHARS = 64 * 1024
private const val MAX_MANIFEST_CHARS = 8 * 1024
private const val MAX_CHECKSUM_CHARS = 1024
private const val MAX_APK_BYTES = 100L * 1024 * 1024

data class UpdateInfo(
    val versionName: String,
    val releaseUrl: String,
    val versionCode: Int? = null,
    val apkUrl: String? = null,
    val checksumUrl: String? = null,
)

@Serializable
data class UpdateManifest(
    val versionCode: Int,
    val versionName: String,
    val releaseUrl: String,
    val apkUrl: String? = null,
    val checksumUrl: String? = null,
)

@Serializable
private data class GitHubAsset(
    val name: String,
    @SerialName("browser_download_url") val downloadUrl: String,
)

@Serializable
private data class GitHubRelease(
    @SerialName("tag_name") val tagName: String,
    @SerialName("html_url") val htmlUrl: String,
    val draft: Boolean = false,
    val prerelease: Boolean = false,
    val assets: List<GitHubAsset> = emptyList(),
)

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data object Error : UpdateState
    data class Available(val release: UpdateInfo) : UpdateState
    data class Downloading(val release: UpdateInfo) : UpdateState
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
        if (info.releaseUrl != "${RELEASE_TAG_URL}v${info.versionName}") return false
        if ((info.apkUrl == null) != (info.checksumUrl == null)) return false
        if (info.apkUrl != null) {
            val apkName = "tg-ws-proxy-${info.versionName}.apk"
            val baseUrl = "${RELEASE_DOWNLOAD_URL}v${info.versionName}/"
            if (info.apkUrl != baseUrl + apkName) return false
            if (info.checksumUrl != baseUrl + "$apkName.sha256") return false
        }
        return true
    }
}

object UpdatePolicy {
    internal fun checkDelayMs(now: Long, lastAttempt: Long): Long {
        if (lastAttempt <= 0 || now < lastAttempt) return 0
        return (CHECK_INTERVAL_MS - (now - lastAttempt)).coerceAtLeast(0)
    }

    internal fun shouldNotify(
        serviceRunning: Boolean,
        currentVersionCode: Int,
        currentVersionName: String,
        release: UpdateInfo,
        lastNotifiedVersion: String?,
    ): Boolean = serviceRunning && release.versionName != lastNotifiedVersion &&
        isAvailable(currentVersionCode, currentVersionName, release)

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
    companion object {
        private val _changes = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        internal val changes = _changes.asSharedFlow()
        private val checkMutex = Mutex()
    }

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
            apkUrl = preferences.getString("apk_url", null),
            checksumUrl = preferences.getString("checksum_url", null),
        )
        return if (UpdatePolicy.isAvailable(BuildConfig.VERSION_CODE, BuildConfig.VERSION_NAME, release)) {
            UpdateState.Available(release)
        } else {
            UpdateState.Idle
        }
    }

    fun shouldCheck(): Boolean {
        return timeUntilNextCheckMs() == 0L
    }

    fun timeUntilNextCheckMs(): Long = UpdatePolicy.checkDelayMs(
        System.currentTimeMillis(), preferences.getLong("last_attempt", 0L),
    )

    fun lastNotifiedVersion(): String? = preferences.getString("last_notified_version", null)

    fun markNotified(release: UpdateInfo) {
        preferences.edit().putString("last_notified_version", release.versionName).apply()
    }

    suspend fun check(): Result<UpdateInfo> = checkMutex.withLock { checkLocked() }

    suspend fun checkIfDue(): Result<UpdateInfo>? = checkMutex.withLock {
        if (shouldCheck()) checkLocked() else null
    }

    private suspend fun checkLocked(): Result<UpdateInfo> = withContext(Dispatchers.IO) {
        preferences.edit().putLong("last_attempt", System.currentTimeMillis()).apply()
        try {
            val release = try {
                fetchLatestRelease()
            } catch (e: CancellationException) {
                throw e
            } catch (apiError: Exception) {
                currentCoroutineContext().ensureActive()
                try {
                    fetchManifest()
                } catch (e: CancellationException) {
                    throw e
                } catch (manifestError: Exception) {
                    manifestError.addSuppressed(apiError)
                    throw manifestError
                }
            }
            currentCoroutineContext().ensureActive()
            preferences.edit()
                .putInt("version_code", release.versionCode ?: 0)
                .putString("version_name", release.versionName)
                .putString("release_url", release.releaseUrl)
                .putString("apk_url", release.apkUrl)
                .putString("checksum_url", release.checksumUrl)
                .apply()
            _changes.tryEmit(Unit)
            Result.success(release)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            Result.failure(e)
        }
    }

    private suspend fun fetchLatestRelease(): UpdateInfo {
        return createRequest(RELEASE_API_URL, "application/vnd.github+json", githubApi = true).useResponse { response ->
            if (!response.isSuccessful) error("GitHub API HTTP ${response.code}")
            val githubRelease = json.decodeFromString<GitHubRelease>(readResponse(response, MAX_API_CHARS))
            require(!githubRelease.draft && !githubRelease.prerelease) { "Invalid GitHub release" }
            require(githubRelease.tagName.startsWith('v')) { "Invalid release tag" }
            val versionName = githubRelease.tagName.removePrefix("v")
            val apkName = "tg-ws-proxy-$versionName.apk"
            val apkUrl = githubRelease.assets.singleOrNull { it.name == apkName }?.downloadUrl
            val checksumUrl = githubRelease.assets.singleOrNull { it.name == "$apkName.sha256" }?.downloadUrl
            val release = UpdateInfo(
                versionName = versionName,
                releaseUrl = githubRelease.htmlUrl,
                apkUrl = apkUrl,
                checksumUrl = checksumUrl,
            )
            require(UpdateValidator.isValid(release)) { "Invalid GitHub release metadata" }
            release
        }
    }

    private suspend fun fetchManifest(): UpdateInfo {
        return createRequest(MANIFEST_URL, "application/json").useResponse { response ->
            if (!response.isSuccessful) error("Update manifest HTTP ${response.code}")
            val manifest = json.decodeFromString<UpdateManifest>(readResponse(response, MAX_MANIFEST_CHARS))
            val release = UpdateInfo(
                manifest.versionName,
                manifest.releaseUrl,
                manifest.versionCode,
                manifest.apkUrl,
                manifest.checksumUrl,
            )
            require(UpdateValidator.isValid(release)) { "Invalid update manifest" }
            release
        }
    }

    private suspend fun executeRequest(url: String, accept: String, githubApi: Boolean = false): Response =
        createRequest(url, accept, githubApi).awaitResponse()

    private fun createRequest(url: String, accept: String, githubApi: Boolean = false): Call =
        client.newCall(Request.Builder()
            .url(url)
            .header("Accept", accept)
            .header("User-Agent", "TG-WS-Proxy/${BuildConfig.VERSION_NAME}")
            .apply {
                if (githubApi) header("X-GitHub-Api-Version", "2022-11-28")
            }
            .build())

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

    suspend fun downloadApk(release: UpdateInfo): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            require(UpdateValidator.isValid(release)) { "Invalid update metadata" }
            val apkUrl = requireNotNull(release.apkUrl) { "Release APK is unavailable" }
            val checksumUrl = requireNotNull(release.checksumUrl) { "Release checksum is unavailable" }
            val expectedChecksum = executeRequest(checksumUrl, "text/plain").use { response ->
                if (!response.isSuccessful) error("Checksum HTTP ${response.code}")
                parseSha256(readResponse(response, MAX_CHECKSUM_CHARS))
            }

            val updateDir = File(contextCacheDir, "updates").apply { mkdirs() }
            val finalFile = File(updateDir, "tg-ws-proxy-${release.versionName}.apk")
            val temporaryFile = File(updateDir, "${finalFile.name}.download")
            try {
                executeRequest(apkUrl, "application/vnd.android.package-archive").use { response ->
                    if (!response.isSuccessful) error("APK HTTP ${response.code}")
                    val body = response.body ?: error("Empty APK response")
                    if (body.contentLength() > MAX_APK_BYTES) error("APK is too large")
                    val digest = MessageDigest.getInstance("SHA-256")
                    var total = 0L
                    body.byteStream().use { input ->
                        FileOutputStream(temporaryFile).use { output ->
                            val buffer = ByteArray(32 * 1024)
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                total += count
                                if (total > MAX_APK_BYTES) error("APK is too large")
                                digest.update(buffer, 0, count)
                                output.write(buffer, 0, count)
                            }
                        }
                    }
                    val actualChecksum = digest.digest().joinToString("") { "%02x".format(it) }
                    require(actualChecksum == expectedChecksum) { "APK checksum mismatch" }
                }
                if (finalFile.exists() && !finalFile.delete()) error("Unable to replace cached APK")
                if (!temporaryFile.renameTo(finalFile)) error("Unable to store downloaded APK")
                finalFile
            } finally {
                temporaryFile.delete()
            }
        }
    }

    private val contextCacheDir: File = context.applicationContext.cacheDir
}

internal suspend fun Call.awaitResponse(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (continuation.isActive) continuation.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            continuation.resume(response) { _, discarded, _ -> discarded.close() }
        }
    })
}

internal suspend fun <T> Call.useResponse(block: (Response) -> T): T = coroutineScope {
    // Keep cancellation attached while the bounded metadata body is being read on IO.
    val monitor = launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
        try { awaitCancellation() } finally { this@useResponse.cancel() }
    }
    try {
        awaitResponse().use(block)
    } finally {
        monitor.cancel()
    }
}

internal fun parseSha256(value: String): String {
    return value.trim()
        .substringBefore(' ')
        .lowercase()
        .also { require(it.matches(Regex("^[0-9a-f]{64}$"))) { "Invalid checksum" } }
}
