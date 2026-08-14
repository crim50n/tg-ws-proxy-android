package dev.minios.tgwsproxy.diagnostics

import android.content.Context
import android.os.Build
import android.util.Log
import dev.minios.tgwsproxy.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

enum class DiagnosticLevel { DEBUG, INFO, WARN, ERROR, FATAL }

data class DiagnosticState(
    val sizeBytes: Long = 0L,
) {
    val hasLog: Boolean get() = sizeBytes > 0
}

data class DiagnosticEntry(
    val timestamp: String,
    val level: DiagnosticLevel,
    val event: String,
    val details: String,
)

internal fun parseDiagnosticLine(line: String): DiagnosticEntry? {
    val parts = line.split('\t', limit = 3)
    if (parts.size < 2 || parts[0].isBlank() || parts[1].isBlank()) return null
    val hasLevel = parts.size == 3 && runCatching { DiagnosticLevel.valueOf(parts[1]) }.isSuccess
    val safeEvent = DiagnosticRedactor.redact(if (hasLevel) parts[2] else parts[1])
    return DiagnosticEntry(
        timestamp = parts[0],
        level = if (hasLevel) DiagnosticLevel.valueOf(parts[1]) else DiagnosticLevel.INFO,
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

    fun redact(value: String, maxLength: Int = 500): String {
        return value
            .replace(proxyLink, "[proxy-link-redacted]")
            .replace(secretParameter, "$1[redacted]")
            .replace(secretHex, "[secret-redacted]")
            .replace(ipv4Address, "[ip-redacted]")
            .replace(ipv6Address, "[ip-redacted]")
            .replace('\n', ' ')
            .replace('\r', ' ')
            .take(maxLength)
    }

    fun field(key: String, value: Any?): String {
        if (forbiddenKeys.any { key.contains(it, ignoreCase = true) }) return "[redacted]"
        val maxLength = if (key.equals("stack", ignoreCase = true)) 4_000 else 500
        return redact(value?.toString() ?: "null", maxLength)
    }
}

object DiagnosticLogger {
    private const val PREFS_NAME = "diagnostic_logging"
    private const val LEGACY_KEY_RECORDING = "recording"
    private const val LEGACY_KEY_EXPIRES_AT = "expires_at"
    private const val RETENTION_MS = 7 * 24 * 60 * 60_000L
    private const val MAX_FILE_BYTES = 2 * 1024 * 1024L
    private const val LOG_DIR = "diagnostics"
    private const val CURRENT_LOG = "diagnostic.log"
    private const val PREVIOUS_LOG = "diagnostic.old.log"
    private const val MAX_LIVE_ENTRIES = 300
    private const val MAX_PENDING_ENTRIES = 500
    private const val MAX_BATCH_ENTRIES = 50
    private const val BATCH_DELAY_MS = 150L
    private const val RETENTION_CHECK_INTERVAL_HOURS = 24L
    private const val TAG = "DiagnosticLogger"

    private val fileLock = Any()
    private val queueLock = Any()
    private val liveLock = Any()
    private val timestampLock = Any()
    private val operationLock = Any()
    private val pendingLines = ArrayDeque<String>()
    private val initialized = AtomicBoolean(false)
    private var writerScheduled = false
    private val timestampFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US)
    private val writer = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "proxy-diagnostic-writer").apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
        }
    }
    private val _state = MutableStateFlow(DiagnosticState())
    val state: StateFlow<DiagnosticState> = _state.asStateFlow()
    private val _entries = MutableStateFlow<List<DiagnosticEntry>>(emptyList())
    val entries: StateFlow<List<DiagnosticEntry>> = _entries.asStateFlow()

    @Volatile
    private var appContext: Context? = null

    fun initialize(context: Context) {
        val applicationContext = context.applicationContext
        appContext = applicationContext
        if (!initialized.compareAndSet(false, true)) return
        runCatching {
            synchronized(fileLock) {
                clearLegacyRecordingState(applicationContext)
                deleteExpiredFiles(applicationContext)
                refreshState(applicationContext)
                loadEntries(applicationContext)
            }
        }.onFailure { Log.w(TAG, "Unable to restore diagnostic history", it) }
        writer.scheduleWithFixedDelay(
            {
                runCatching {
                    synchronized(fileLock) {
                        deleteExpiredFiles(applicationContext)
                        refreshState(applicationContext)
                    }
                }.onFailure { Log.w(TAG, "Unable to enforce diagnostic retention", it) }
            },
            RETENTION_CHECK_INTERVAL_HOURS,
            RETENTION_CHECK_INTERVAL_HOURS,
            TimeUnit.HOURS,
        )
    }

    fun event(name: String, vararg fields: Pair<String, Any?>) {
        write(DiagnosticLevel.INFO, name, fields)
    }

    fun debug(name: String, vararg fields: Pair<String, Any?>) {
        write(DiagnosticLevel.DEBUG, name, fields)
    }

    fun warning(name: String, vararg fields: Pair<String, Any?>) {
        write(DiagnosticLevel.WARN, name, fields)
    }

    fun failure(name: String, error: Throwable, vararg fields: Pair<String, Any?>) {
        write(
            DiagnosticLevel.ERROR,
            name,
            arrayOf(
                *fields,
                "errorType" to error.javaClass.simpleName,
                "message" to (error.message ?: ""),
            ),
        )
    }

    fun recordCrash(thread: Thread, error: Throwable) {
        write(
            DiagnosticLevel.FATAL,
            "uncaught_exception",
            arrayOf(
                "thread" to thread.name,
                "errorType" to error.javaClass.name,
                "message" to (error.message ?: ""),
                "stack" to error.stackTrace.take(12).joinToString(" | "),
            ),
        )
        flush(timeoutMs = 1_500L)
    }

    fun clear() {
        val context = appContext ?: return
        synchronized(operationLock) {
            flush()
            runOnWriter {
                synchronized(fileLock) {
                    currentFile(context).delete()
                    previousFile(context).delete()
                    exportFile(context).delete()
                    refreshState(context)
                }
            }
            synchronized(liveLock) { _entries.value = emptyList() }
        }
    }

    fun flush(timeoutMs: Long = 2_000L) {
        runCatching { writer.submit {}.get(timeoutMs, TimeUnit.MILLISECONDS) }
    }

    fun createExportFile(): File? {
        val context = appContext ?: return null
        return runOnWriter {
            synchronized(fileLock) {
                deleteExpiredFiles(context)
                val files = listOf(previousFile(context), currentFile(context))
                    .filter { it.isFile && it.length() > 0 }
                if (files.isEmpty()) return@runOnWriter null
                val output = exportFile(context)
                output.parentFile?.mkdirs()
                output.bufferedWriter(Charsets.UTF_8).use { export ->
                    export.appendLine("TG WS Proxy diagnostics")
                    export.appendLine("App: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                    export.appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
                    export.appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
                    export.appendLine("Exported: ${formatTimestamp()}")
                    export.appendLine("Logging: always on, INFO and above plus selected transport events")
                    export.appendLine("Sensitive proxy links, secrets, and IP addresses are redacted.")
                    export.appendLine()
                    files.forEach { file ->
                        file.forEachLine(Charsets.UTF_8) { line ->
                            parseDiagnosticLine(line)?.let { entry ->
                                val details = entry.details.takeIf(String::isNotEmpty)?.let { " $it" }.orEmpty()
                                export.appendLine(
                                    "${entry.timestamp}\t${entry.level}\t${entry.event}$details",
                                )
                            }
                        }
                    }
                }
                output
            }
        }
    }

    private fun write(
        level: DiagnosticLevel,
        name: String,
        fields: Array<out Pair<String, Any?>>,
    ) {
        synchronized(operationLock) {
            if (level == DiagnosticLevel.DEBUG && !BuildConfig.DEBUG) return
            if (appContext == null) return
            val safeName = name.replace(Regex("[^a-zA-Z0-9_.-]"), "_").take(80)
            val details = fields.joinToString(" ") { (key, value) ->
                val safeKey = key.replace(Regex("[^a-zA-Z0-9_.-]"), "_").take(40)
                "$safeKey=${DiagnosticRedactor.field(key, value)}"
            }
            val timestamp = formatTimestamp()
            val eventText = if (details.isEmpty()) safeName else "$safeName $details"
            val line = "$timestamp\t$level\t$eventText\n"
            parseDiagnosticLine(line.trimEnd())?.let { entry ->
                synchronized(liveLock) {
                    _entries.value = (_entries.value + entry).takeLast(MAX_LIVE_ENTRIES)
                }
            }
            enqueue(line)
        }
    }

    private fun enqueue(line: String) {
        synchronized(queueLock) {
            if (pendingLines.size >= MAX_PENDING_ENTRIES) pendingLines.removeFirst()
            pendingLines.addLast(line)
            if (writerScheduled) return
            writerScheduled = true
            writer.execute(::drainPendingLines)
        }
    }

    private fun drainPendingLines() {
        runCatching { Thread.sleep(BATCH_DELAY_MS) }
        while (true) {
            val batch = synchronized(queueLock) {
                if (pendingLines.isEmpty()) {
                    writerScheduled = false
                    return
                }
                buildList {
                    repeat(minOf(MAX_BATCH_ENTRIES, pendingLines.size)) {
                        add(pendingLines.removeFirst())
                    }
                }
            }
            runCatching { persistBatch(batch) }
                .onFailure { Log.e(TAG, "Unable to persist diagnostic batch", it) }
        }
    }

    private fun persistBatch(lines: List<String>) {
        val context = appContext ?: return
        synchronized(fileLock) {
            val encoded = lines.joinToString(separator = "").toByteArray(Charsets.UTF_8)
            rotateIfNeeded(context, encoded.size.toLong())
            currentFile(context).apply { parentFile?.mkdirs() }.appendBytes(encoded)
            refreshState(context)
        }
    }

    private fun rotateIfNeeded(context: Context, incomingBytes: Long) {
        val current = currentFile(context)
        current.parentFile?.mkdirs()
        if (current.length() + incomingBytes < MAX_FILE_BYTES) return
        previousFile(context).delete()
        if (!current.renameTo(previousFile(context))) current.delete()
    }

    private fun deleteExpiredFiles(context: Context) {
        val cutoff = System.currentTimeMillis() - RETENTION_MS
        listOf(currentFile(context), previousFile(context), exportFile(context)).forEach { file ->
            if (file.isFile && file.lastModified() < cutoff) file.delete()
        }
    }

    private fun refreshState(context: Context) {
        _state.value = DiagnosticState(
            sizeBytes = currentFile(context).length() + previousFile(context).length(),
        )
    }

    private fun clearLegacyRecordingState(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(LEGACY_KEY_RECORDING)
            .remove(LEGACY_KEY_EXPIRES_AT)
            .apply()
    }

    private fun loadEntries(context: Context) {
        synchronized(liveLock) {
            _entries.value = listOf(previousFile(context), currentFile(context))
                .filter(File::isFile)
                .flatMap { it.readLines(Charsets.UTF_8) }
                .takeLast(MAX_LIVE_ENTRIES)
                .mapNotNull(::parseDiagnosticLine)
        }
    }

    private fun formatTimestamp(): String = synchronized(timestampLock) {
        timestampFormat.format(Date())
    }

    private fun <T> runOnWriter(block: () -> T): T? {
        return runCatching { writer.submit<T> { block() }.get(3, TimeUnit.SECONDS) }.getOrNull()
    }

    private fun logDirectory(context: Context) = File(context.filesDir, LOG_DIR)
    private fun currentFile(context: Context) = File(logDirectory(context), CURRENT_LOG)
    private fun previousFile(context: Context) = File(logDirectory(context), PREVIOUS_LOG)
    private fun exportFile(context: Context) =
        File(File(context.cacheDir, LOG_DIR), "tg-ws-proxy-diagnostics.txt")
}
