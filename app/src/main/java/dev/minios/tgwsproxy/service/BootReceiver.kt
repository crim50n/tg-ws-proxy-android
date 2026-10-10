package dev.minios.tgwsproxy.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.minios.tgwsproxy.data.ConfigRepository
import dev.minios.tgwsproxy.diagnostics.DiagnosticLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

internal const val BOOT_COMPLETED_ACTION = "android.intent.action.BOOT_COMPLETED"

internal fun shouldStartProxyOnBoot(action: String?, enabled: Boolean): Boolean {
    return action == BOOT_COMPLETED_ACTION && enabled
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != BOOT_COMPLETED_ACTION) return
        val pendingResult = goAsync()
        val appContext = context.applicationContext
        CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
            try {
                withTimeout(8_000L) {
                    val enabled = ConfigRepository(appContext).getConfig().startOnBoot
                    DiagnosticLogger.event("boot_completed", "startEnabled" to enabled)
                    if (shouldStartProxyOnBoot(intent.action, enabled)) {
                        ProxyService.start(appContext)
                    }
                }
            } catch (error: Exception) {
                DiagnosticLogger.failure("boot_start_failed", error)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
