package dev.minios.tgwsproxy.diagnostics

import android.content.Context
import android.os.Build
import dev.minios.tgwsproxy.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class DiagnosticState(
    val isRecording: Boolean = false,
    val sizeBytes: Long = 0L,
) {
    val hasLog: Boolean get() = sizeBytes > 0
}

data class DiagnosticEntry(
    val timestamp: String,
    val event: String,
    val details: String,
)

internal fun parseDiagnosticLine(line: String): DiagnosticEntry? {
    val parts = line.split('\t', limit = 2)
    if (parts.size != 2 || parts[0].isBlank() || parts[1].isBlank()) return null
    val safeEvent = DiagnosticRedactor.redact(parts[1])
    return DiagnosticEntry(
        timestamp = parts[0],
        event = safeEvent.substringBefore(' '),
        details = safeEvent.substringAfter(' ', ""),
    )
}

object DiagnosticRedactor {
    private val proxyLink = Regex("(?i)tg://\\S+")
    private val secretParameter = Regex("(?i)(secret=)[^\\s&]+")
    private val secretHex = Regex("(?i)\\b(?:dd)?[0-9a-f]{32}\\b")
    private val ipv4Address = Regex("(?<![0-9])(?:[0-9]{1,3}\\.){3}[0-9]{1,3}(?![0-9])")
    private val ipv6Address = Regex("(?i)(?<![0-9a-f])(?:[0-9a-f]{0,4}:){2,7}[0-9a-f]{0,4}(?![0-9a-f])")
    private val forbiddenKeys = setOf("secret", "link", "payload", "header", "data")

    fun redact(value: String): String {
        return value
            .replace(proxyLink, "[proxy-link-redacted]")
            .replace(secretParameter, "$1[redacted]")
            .replace(secretHex, "[secret-redacted]")
            .replace(ipv4Address, "[ip-redacted]")
            .replace(ipv6Address, "[ip-redacted]")
            .replace('\n', ' ')
            .replace('\r', ' ')
            .take(500)
    }

    fun field(key: String, value: Any?): String {
        if (forbiddenKeys.any { key.contains(it, ignoreCase = true) }) return "[redacted]"
        return redact(value?.toString() ?: "null")
    }
}

object DiagnosticLogger {
    private const val PREFS_NAME = "diagnostic_logging"
    private const val KEY_RECORDING = "recording"
    private const val LEGACY_KEY_EXPIRES_AT = "expires_at"
    private const val RETENTION_MS = 7 * 24 * 60 * 60_000L
    private const val MAX_FILE_BYTES = 2 * 1024 * 1024L
    private const val LOG_DIR = "diagnostics"
    private const val CURRENT_LOG = "diagnostic.log"
    private const val PREVIOUS_LOG = "diagnostic.old.log"
    private const val MAX_LIVE_ENTRIES = 300

    private val lock = Any()
    private val timestampFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US)
    private val _state = MutableStateFlow(DiagnosticState())
    val state: StateFlow<DiagnosticState> = _state.asStateFlow()
    private val _entries = MutableStateFlow<List<DiagnosticEntry>>(emptyList())
    val entries: StateFlow<List<DiagnosticEntry>> = _entries.asStateFlow()

    @Volatile
    private var appContext: Context? = null

    fun initialize(context: Context) {
        appContext = context.applicationContext
        synchronized(lock) {
            migrateLegacyRecording(context.applicationContext)
            deleteExpiredFiles()
            refreshState()
            loadEntries(context.applicationContext)
        }
    }

    fun start() {
        val context = appContext ?: return
        synchronized(lock) {
            currentFile(context).delete()
            previousFile(context).delete()
            preferences(context).edit()
                .putBoolean(KEY_RECORDING, true)
                .remove(LEGACY_KEY_EXPIRES_AT)
                .apply()
            refreshState()
        }
        event(
            "diagnostics_started",
            "appVersion" to "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
            "androidApi" to Build.VERSION.SDK_INT,
            "maxStoredMb" to 4,
        )
    }

    fun stop() {
        val context = appContext ?: return
        event("diagnostics_stopped")
        synchronized(lock) {
            preferences(context).edit().putBoolean(KEY_RECORDING, false).apply()
            refreshState()
        }
    }

    fun clear() {
        val context = appContext ?: return
        synchronized(lock) {
            currentFile(context).delete()
            previousFile(context).delete()
            exportFile(context).delete()
            refreshState()
        }
    }

    fun refresh() {
        synchronized(lock) {
            deleteExpiredFiles()
            refreshState()
        }
    }

    fun event(name: String, vararg fields: Pair<String, Any?>) {
        val context = appContext ?: return
        synchronized(lock) {
            val safeName = name.replace(Regex("[^a-zA-Z0-9_.-]"), "_").take(80)
            val details = fields.joinToString(" ") { (key, value) ->
                val safeKey = key.replace(Regex("[^a-zA-Z0-9_.-]"), "_").take(40)
                "$safeKey=${DiagnosticRedactor.field(key, value)}"
            }
            val timestamp = timestampFormat.format(Date())
            val line = if (details.isEmpty()) "$timestamp\t$safeName\n" else "$timestamp\t$safeName $details\n"
            parseDiagnosticLine(line.trimEnd())?.let { entry ->
                _entries.value = (_entries.value + entry).takeLast(MAX_LIVE_ENTRIES)
            }
            if (isRecording(context)) {
                rotateIfNeeded(context)
                currentFile(context).appendText(line, Charsets.UTF_8)
                refreshState()
            }
        }
    }

    fun failure(name: String, error: Throwable, vararg fields: Pair<String, Any?>) {
        event(
            name,
            *fields,
            "errorType" to error.javaClass.simpleName,
            "message" to (error.message ?: ""),
        )
    }

    fun createExportFile(): File? {
        val context = appContext ?: return null
        synchronized(lock) {
            deleteExpiredFiles()
            val files = listOf(previousFile(context), currentFile(context)).filter { it.isFile && it.length() > 0 }
            if (files.isEmpty()) return null
            val output = exportFile(context)
            output.parentFile?.mkdirs()
            output.bufferedWriter(Charsets.UTF_8).use { writer ->
                writer.appendLine("TG WS Proxy diagnostics")
                writer.appendLine("App: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                writer.appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
                writer.appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
                writer.appendLine("Exported: ${timestampFormat.format(Date())}")
                writer.appendLine("Sensitive proxy links, secrets, and IP addresses are redacted.")
                writer.appendLine()
                files.forEach { file ->
                    file.forEachLine(Charsets.UTF_8) { writer.appendLine(it) }
                }
            }
            return output
        }
    }

    private fun isRecording(context: Context): Boolean {
        return preferences(context).getBoolean(KEY_RECORDING, false)
    }

    private fun rotateIfNeeded(context: Context) {
        val current = currentFile(context)
        current.parentFile?.mkdirs()
        if (current.length() < MAX_FILE_BYTES) return
        previousFile(context).delete()
        current.renameTo(previousFile(context))
    }

    private fun deleteExpiredFiles() {
        val context = appContext ?: return
        val cutoff = System.currentTimeMillis() - RETENTION_MS
        listOf(currentFile(context), previousFile(context), exportFile(context)).forEach { file ->
            if (file.isFile && file.lastModified() < cutoff) file.delete()
        }
    }

    private fun refreshState() {
        val context = appContext ?: return
        _state.value = DiagnosticState(
            isRecording = isRecording(context),
            sizeBytes = currentFile(context).length() + previousFile(context).length(),
        )
    }

    private fun migrateLegacyRecording(context: Context) {
        val prefs = preferences(context)
        if (!prefs.contains(LEGACY_KEY_EXPIRES_AT)) return
        val wasRecording = prefs.getLong(LEGACY_KEY_EXPIRES_AT, 0L) > System.currentTimeMillis()
        prefs.edit()
            .remove(LEGACY_KEY_EXPIRES_AT)
            .putBoolean(KEY_RECORDING, wasRecording)
            .apply()
    }

    private fun loadEntries(context: Context) {
        _entries.value = listOf(previousFile(context), currentFile(context))
            .filter(File::isFile)
            .flatMap { it.readLines(Charsets.UTF_8) }
            .takeLast(MAX_LIVE_ENTRIES)
            .mapNotNull(::parseDiagnosticLine)
    }

    private fun preferences(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun logDirectory(context: Context) = File(context.filesDir, LOG_DIR)
    private fun currentFile(context: Context) = File(logDirectory(context), CURRENT_LOG)
    private fun previousFile(context: Context) = File(logDirectory(context), PREVIOUS_LOG)
    private fun exportFile(context: Context) = File(File(context.cacheDir, LOG_DIR), "tg-ws-proxy-diagnostics.txt")
}
