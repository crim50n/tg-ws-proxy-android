package dev.minios.tgwsproxy.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

internal const val BOOT_COMPLETED_ACTION = "android.intent.action.BOOT_COMPLETED"

internal fun isProxyBootAction(action: String?): Boolean = action == BOOT_COMPLETED_ACTION

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!isProxyBootAction(intent.action)) return
        val pendingResult = goAsync()
        val resume = ProxyService.resume(context)
        CoroutineScope(Dispatchers.Default + SupervisorJob()).launch {
            try {
                // Bound receiver lifetime, not the process-owned command's lifetime.
                withTimeoutOrNull(8_000L) { resume.join() }
            } finally {
                pendingResult.finish()
            }
        }
    }
}
