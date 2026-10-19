package dev.minios.tgwsproxy.data

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

internal val PROXY_ENABLED = booleanPreferencesKey("proxy_enabled")
internal val LEGACY_START_ON_BOOT = booleanPreferencesKey("start_on_boot")

internal fun MutablePreferences.resolveProxyEnabled(explicit: Boolean? = null): Boolean {
    val enabled = explicit ?: this[PROXY_ENABLED] ?: this[LEGACY_START_ON_BOOT] ?: false
    this[PROXY_ENABLED] = enabled
    remove(LEGACY_START_ON_BOOT)
    return enabled
}

/** FIFO ownership belongs to the process, never to a screen, tile, or service instance. */
internal class ProxyCommands(scope: CoroutineScope, private val onFailure: (Exception) -> Unit) {
    private class Command(val block: suspend () -> Unit, val completion: CompletableDeferred<Unit>)
    private val commands = Channel<Command>(Channel.UNLIMITED)

    init {
        scope.launch {
            for (command in commands) {
                try {
                    command.block()
                    command.completion.complete(Unit)
                } catch (error: Exception) {
                    onFailure(error)
                    command.completion.completeExceptionally(error)
                }
            }
        }
    }

    fun submit(block: suspend () -> Unit): CompletableDeferred<Unit> {
        val completion = CompletableDeferred<Unit>()
        check(commands.trySend(Command(block, completion)).isSuccess)
        return completion
    }
}
