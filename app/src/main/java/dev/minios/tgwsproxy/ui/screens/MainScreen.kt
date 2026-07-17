package dev.minios.tgwsproxy.ui.screens

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.minios.tgwsproxy.R
import dev.minios.tgwsproxy.diagnostics.ConnectionAdvice
import dev.minios.tgwsproxy.proxy.MtProtoConstants
import dev.minios.tgwsproxy.proxy.ProxyConfig
import dev.minios.tgwsproxy.proxy.StatsSnapshot
import dev.minios.tgwsproxy.proxy.RuntimeRouteMode
import dev.minios.tgwsproxy.service.ProxyStartFailure
import dev.minios.tgwsproxy.service.ProxyServiceState
import dev.minios.tgwsproxy.ui.theme.*
import dev.minios.tgwsproxy.update.UpdateState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    config: ProxyConfig,
    serviceState: ProxyServiceState,
    runtimeRouteMode: RuntimeRouteMode?,
    startFailure: ProxyStartFailure?,
    stats: StatsSnapshot,
    connectionAdvice: ConnectionAdvice?,
    updateState: UpdateState,
    diagnosticRecording: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onOpenInTelegram: () -> Unit,
    onCopyLink: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenHelp: () -> Unit,
    onOpenAbout: () -> Unit,
    onOpenUpdate: () -> Unit,
) {
    val isRunning = serviceState == ProxyServiceState.RUNNING
    val isActive = serviceState != ProxyServiceState.STOPPED
    val proxyLink = remember(config) { config.proxyLink() }
    var menuExpanded by remember { mutableStateOf(false) }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    actionIconContentColor = MaterialTheme.colorScheme.onPrimary,
                ),
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.settings))
                    }
                    Box {
                        IconButton(onClick = { menuExpanded = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.more_options))
                        }
                        DropdownMenu(
                            expanded = menuExpanded,
                            onDismissRequest = { menuExpanded = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.help_title)) },
                                leadingIcon = { Icon(Icons.AutoMirrored.Filled.HelpOutline, contentDescription = null) },
                                onClick = {
                                    menuExpanded = false
                                    onOpenHelp()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.about_title)) },
                                leadingIcon = { Icon(Icons.Default.Info, contentDescription = null) },
                                onClick = {
                                    menuExpanded = false
                                    onOpenAbout()
                                },
                            )
                        }
                    }
                },
            )
        },
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val availableUpdate = when (updateState) {
                is UpdateState.Available -> updateState.release
                is UpdateState.Downloading -> updateState.release
                else -> null
            }
            AnimatedVisibility(
                visible = availableUpdate != null,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
            ) {
                if (availableUpdate != null) {
                    Column {
                        UpdateCard(
                            versionName = availableUpdate.versionName,
                            downloading = updateState is UpdateState.Downloading,
                            installable = availableUpdate.apkUrl != null && availableUpdate.checksumUrl != null,
                            onOpenUpdate = onOpenUpdate,
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                    }
                }
            }

            StatusCard(
                serviceState = serviceState,
                config = config,
                uptimeSeconds = stats.uptimeSeconds,
                runtimeRouteMode = runtimeRouteMode,
                diagnosticRecording = diagnosticRecording,
            )

            AnimatedVisibility(
                visible = startFailure is ProxyStartFailure.PortInUse && startFailure.port == config.port,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
            ) {
                val failure = startFailure as? ProxyStartFailure.PortInUse
                if (failure != null) {
                    Column {
                        Spacer(modifier = Modifier.height(16.dp))
                        PortInUseCard(
                            port = failure.port,
                            onOpenSettings = onOpenSettings,
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            AnimatedVisibility(
                visible = isRunning && connectionAdvice != null,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
            ) {
                if (connectionAdvice != null) {
                    Column {
                        ConnectionAdviceCard(connectionAdvice)
                        Spacer(modifier = Modifier.height(16.dp))
                    }
                }
            }

            ControlsCard(
                serviceState = serviceState,
                onStart = onStart,
                onStop = onStop,
                onOpenInTelegram = onOpenInTelegram,
                onCopyLink = onCopyLink,
                proxyLink = proxyLink,
            )

            AnimatedVisibility(
                visible = shouldWarnRejectedConnections(
                    total = stats.connectionsTotal,
                    rejected = stats.connectionsBad,
                ),
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
            ) {
                Column {
                    Spacer(modifier = Modifier.height(16.dp))
                    RejectedConnectionsWarning()
                }
            }

            AnimatedVisibility(
                visible = isActive && config.showDetailedStats,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
            ) {
                Column {
                    Spacer(modifier = Modifier.height(16.dp))
                    StatsCard(stats = stats)
                }
            }
        }
    }
}

internal fun shouldWarnRejectedConnections(total: Int, rejected: Int): Boolean {
    return total >= 20 && rejected >= 10 && rejected.toLong() * 5 >= total.toLong()
}

@Composable
private fun RejectedConnectionsWarning() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.WarningAmber,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = stringResource(R.string.rejected_warning_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.rejected_warning_message),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
    }
}

@Composable
private fun PortInUseCard(port: Int, onOpenSettings: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.ErrorOutline,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = stringResource(R.string.port_in_use_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.port_in_use_message, port),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            TextButton(
                onClick = onOpenSettings,
                modifier = Modifier.align(Alignment.End),
                colors = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                ),
            ) {
                Text(stringResource(R.string.port_in_use_open_settings))
            }
        }
    }
}

@Composable
private fun ConnectionAdviceCard(advice: ConnectionAdvice) {
    val message = when (advice) {
        ConnectionAdvice.ENABLE_CLOUDFLARE -> R.string.connection_advice_enable_cf
        ConnectionAdvice.USE_CLOUDFLARE_FIRST -> R.string.connection_advice_cf_first
        ConnectionAdvice.USE_DIRECT_FIRST -> R.string.connection_advice_direct_first
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.NetworkCheck,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onTertiaryContainer,
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = stringResource(R.string.connection_advice_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(message),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
        }
    }
}

@Composable
private fun UpdateCard(
    versionName: String,
    downloading: Boolean,
    installable: Boolean,
    onOpenUpdate: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Default.SystemUpdate,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(28.dp),
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.update_available_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = stringResource(R.string.update_available_version, versionName),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onOpenUpdate, enabled = !downloading) {
                if (downloading) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    Text(
                        stringResource(
                            if (installable) R.string.update_install else R.string.update_open_release,
                        ),
                    )
                }
            }
        }
    }
}

@Composable
private fun StatusCard(
    serviceState: ProxyServiceState,
    config: ProxyConfig,
    uptimeSeconds: Long,
    runtimeRouteMode: RuntimeRouteMode?,
    diagnosticRecording: Boolean,
) {
    val isRunning = serviceState == ProxyServiceState.RUNNING
    val isTransitioning = serviceState == ProxyServiceState.STARTING ||
            serviceState == ProxyServiceState.RESTARTING ||
            serviceState == ProxyServiceState.STOPPING
    val statusColor = when {
        isRunning -> StatusGreen
        isTransitioning -> MaterialTheme.colorScheme.primary
        else -> StatusRed
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = statusColor.copy(alpha = 0.1f),
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(16.dp)
                    .clip(CircleShape)
                    .background(statusColor),
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = when (serviceState) {
                        ProxyServiceState.STOPPED -> stringResource(R.string.proxy_stopped)
                        ProxyServiceState.STARTING -> stringResource(R.string.proxy_starting)
                        ProxyServiceState.RUNNING -> stringResource(R.string.proxy_running)
                        ProxyServiceState.RESTARTING -> stringResource(R.string.proxy_restarting)
                        ProxyServiceState.STOPPING -> stringResource(R.string.proxy_stopping)
                    },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "${config.host}:${config.port}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = FontFamily.Monospace,
                )
                if (isRunning && runtimeRouteMode != null) {
                    Text(
                        text = stringResource(
                            R.string.runtime_route,
                            when (runtimeRouteMode) {
                                RuntimeRouteMode.WS_CF_TCP -> "WS → CF → TCP"
                                RuntimeRouteMode.WS_TCP_CF -> "WS → TCP → CF"
                                RuntimeRouteMode.CF_WS_TCP -> "CF → WS → TCP"
                                RuntimeRouteMode.WS_TCP -> "WS → TCP"
                            },
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (diagnosticRecording) {
                    val recordingAlpha = recordingIndicatorAlpha(isRecording = true)
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            modifier = Modifier
                                .size(9.dp)
                                .alpha(recordingAlpha),
                            shape = CircleShape,
                            color = StatusRed,
                        ) {}
                        Spacer(modifier = Modifier.width(7.dp))
                        Text(
                            text = stringResource(R.string.diagnostics_recording_main),
                            style = MaterialTheme.typography.labelMedium,
                            color = StatusRed,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
            if (isRunning && uptimeSeconds > 0) {
                Text(
                    text = formatUptime(uptimeSeconds),
                    style = MaterialTheme.typography.labelLarge.copy(
                        fontFamily = FontFamily.Monospace,
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ControlsCard(
    serviceState: ProxyServiceState,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onOpenInTelegram: () -> Unit,
    onCopyLink: () -> Unit,
    proxyLink: String,
) {
    val isActive = serviceState != ProxyServiceState.STOPPED
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            ActionButtons(
                serviceState = serviceState,
                onStart = onStart,
                onStop = onStop,
            )
            AnimatedVisibility(
                visible = isActive,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
            ) {
                Column {
                    Spacer(modifier = Modifier.height(12.dp))
                    LinkActions(
                        proxyLink = proxyLink,
                        onOpenInTelegram = onOpenInTelegram,
                        onCopyLink = onCopyLink,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalAnimationApi::class)
@Composable
private fun ActionButtons(
    serviceState: ProxyServiceState,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    AnimatedContent(
        targetState = serviceState,
        transitionSpec = {
            fadeIn() togetherWith fadeOut()
        },
        label = "action_buttons",
    ) { state ->
        if (state == ProxyServiceState.STOPPED) {
            Button(
                onClick = onStart,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                shape = RoundedCornerShape(12.dp),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            ) {
                Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.btn_start))
            }
        } else {
            val stopping = state == ProxyServiceState.STOPPING
            Button(
                onClick = onStop,
                enabled = !stopping,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = StatusRed),
                shape = RoundedCornerShape(12.dp),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            ) {
                if (!stopping) {
                    Icon(Icons.Default.Stop, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text(stringResource(if (stopping) R.string.btn_stopping else R.string.btn_stop))
            }
        }
    }
}

@Composable
private fun LinkActions(
    proxyLink: String,
    onOpenInTelegram: () -> Unit,
    onCopyLink: () -> Unit,
) {
    Button(
        onClick = onOpenInTelegram,
        modifier = Modifier.fillMaxWidth(),
        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
        shape = RoundedCornerShape(12.dp),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(stringResource(R.string.open_in_telegram))
    }
    Spacer(modifier = Modifier.height(12.dp))
    OutlinedTextField(
        value = proxyLink,
        onValueChange = {},
        modifier = Modifier.fillMaxWidth(),
        readOnly = true,
        singleLine = true,
        label = { Text(stringResource(R.string.proxy_link)) },
        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
        shape = RoundedCornerShape(12.dp),
        trailingIcon = {
            IconButton(onClick = onCopyLink) {
                Icon(
                    Icons.Default.ContentCopy,
                    contentDescription = stringResource(R.string.copy_link),
                )
            }
        },
    )
}

private fun formatUptime(seconds: Long): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

@Composable
private fun StatsCard(stats: StatsSnapshot) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.stats_connections),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(modifier = Modifier.height(12.dp))

            // Row 1: Total, Active, WS, CF
            Row(modifier = Modifier.fillMaxWidth()) {
                StatItem(
                    label = stringResource(R.string.stats_total),
                    value = stats.connectionsTotal.toString(),
                    modifier = Modifier.weight(1f),
                )
                StatItem(
                    label = stringResource(R.string.stats_active),
                    value = stats.connectionsActive.toString(),
                    modifier = Modifier.weight(1f),
                )
                StatItem(
                    label = stringResource(R.string.stats_ws),
                    value = stats.connectionsWs.toString(),
                    modifier = Modifier.weight(1f),
                )
                StatItem(
                    label = stringResource(R.string.stats_cf_proxy),
                    value = stats.connectionsCfProxy.toString(),
                    modifier = Modifier.weight(1f),
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Row 2: TCP, Sent, Recv, Pool
            Row(modifier = Modifier.fillMaxWidth()) {
                StatItem(
                    label = stringResource(R.string.stats_tcp_fallback),
                    value = stats.connectionsTcpFallback.toString(),
                    modifier = Modifier.weight(1f),
                )
                StatItem(
                    label = stringResource(R.string.stats_upload),
                    value = MtProtoConstants.humanBytes(stats.bytesUp),
                    modifier = Modifier.weight(1f),
                )
                StatItem(
                    label = stringResource(R.string.stats_download),
                    value = MtProtoConstants.humanBytes(stats.bytesDown),
                    modifier = Modifier.weight(1f),
                )
                val poolTotal = stats.poolHits + stats.poolMisses
                val poolStr = if (poolTotal > 0) "${stats.poolHits}/$poolTotal" else "—"
                StatItem(
                    label = stringResource(R.string.stats_pool),
                    value = poolStr,
                    modifier = Modifier.weight(1f),
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Error counters use the full row instead of leaving empty grid cells.
            Row(modifier = Modifier.fillMaxWidth()) {
                StatItem(
                    label = stringResource(R.string.stats_bad),
                    value = stats.connectionsBad.toString(),
                    modifier = Modifier.weight(1f),
                    valueColor = if (stats.connectionsBad > 0) StatusOrange else MaterialTheme.colorScheme.primary,
                )
                StatItem(
                    label = stringResource(R.string.stats_ws_errors),
                    value = stats.wsErrors.toString(),
                    modifier = Modifier.weight(1f),
                    valueColor = if (stats.wsErrors > 0) StatusOrange else MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun StatItem(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.primary,
) {
    Column(
        modifier = modifier.padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = valueColor,
            maxLines = 1,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
