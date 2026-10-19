package dev.minios.tgwsproxy.ui.screens

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.minios.tgwsproxy.R
import dev.minios.tgwsproxy.diagnostics.ConnectionAdvice
import dev.minios.tgwsproxy.proxy.ConnectivitySnapshot
import dev.minios.tgwsproxy.proxy.MtProtoConstants
import dev.minios.tgwsproxy.proxy.ProxyConfig
import dev.minios.tgwsproxy.proxy.RuntimeRouteMode
import dev.minios.tgwsproxy.proxy.StatsSnapshot
import dev.minios.tgwsproxy.service.ProxyServiceState
import dev.minios.tgwsproxy.service.ProxyStartFailure
import dev.minios.tgwsproxy.ui.ConnectionSummary
import dev.minios.tgwsproxy.ui.icons.TablerIcons
import dev.minios.tgwsproxy.ui.presentConnectivity
import dev.minios.tgwsproxy.ui.theme.*
import dev.minios.tgwsproxy.update.UpdateState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    config: ProxyConfig,
    serviceState: ProxyServiceState,
    proxyEnabled: Boolean,
    commandFailed: Boolean,
    connectivity: ConnectivitySnapshot,
    runtimeRouteMode: RuntimeRouteMode?,
    startFailure: ProxyStartFailure?,
    stats: StatsSnapshot,
    connectionAdvice: ConnectionAdvice?,
    updateState: UpdateState,
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
                colors =
                    TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        titleContentColor = MaterialTheme.colorScheme.onPrimary,
                        actionIconContentColor = MaterialTheme.colorScheme.onPrimary,
                    ),
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(
                            TablerIcons.Settings,
                            contentDescription = stringResource(R.string.settings),
                        )
                    }
                    Box {
                        IconButton(onClick = { menuExpanded = true }) {
                            Icon(
                                TablerIcons.DotsVertical,
                                contentDescription = stringResource(R.string.more_options),
                            )
                        }
                        DropdownMenu(
                            expanded = menuExpanded,
                            onDismissRequest = { menuExpanded = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.help_title)) },
                                leadingIcon = { Icon(TablerIcons.Help, contentDescription = null) },
                                onClick = {
                                    menuExpanded = false
                                    onOpenHelp()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.about_title)) },
                                leadingIcon = {
                                    Icon(TablerIcons.InfoCircle, contentDescription = null)
                                },
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
        Box(
            modifier = Modifier.fillMaxSize().padding(paddingValues),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                modifier =
                    Modifier.widthIn(max = 640.dp)
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                val availableUpdate =
                    when (updateState) {
                        is UpdateState.Available -> updateState.release
                        is UpdateState.Downloading -> updateState.release
                        else -> null
                    }
                StatusCard(
                    serviceState = serviceState,
                    config = config,
                    uptimeSeconds = stats.uptimeSeconds,
                    runtimeRouteMode = runtimeRouteMode,
                ) {
                    ProxyControls(
                        serviceState = serviceState,
                        proxyEnabled = proxyEnabled,
                        commandFailed = commandFailed,
                        onStart = onStart,
                        onStop = onStop,
                        onOpenInTelegram = onOpenInTelegram,
                        onCopyLink = onCopyLink,
                        proxyLink = proxyLink,
                    )
                }

                AnimatedVisibility(
                    visible =
                        startFailure is ProxyStartFailure.PortInUse &&
                            startFailure.port == config.port,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically(),
                ) {
                    val failure = startFailure as? ProxyStartFailure.PortInUse
                    if (failure != null) {
                        Column {
                            Spacer(modifier = Modifier.height(16.dp))
                            PortInUseCard(port = failure.port, onOpenSettings = onOpenSettings)
                        }
                    }
                }

                AnimatedVisibility(
                    visible = isActive,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically(),
                ) {
                    Column {
                        Spacer(modifier = Modifier.height(16.dp))
                        ConnectivityCard(snapshot = connectivity)
                    }
                }

                AnimatedVisibility(
                    visible = isRunning && connectionAdvice != null,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically(),
                ) {
                    if (connectionAdvice != null) {
                        Column {
                            Spacer(modifier = Modifier.height(16.dp))
                            ConnectionAdviceCard(connectionAdvice)
                        }
                    }
                }

                AnimatedVisibility(
                    visible = availableUpdate != null,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically(),
                ) {
                    if (availableUpdate != null) {
                        Column {
                            Spacer(modifier = Modifier.height(16.dp))
                            UpdateCard(
                                versionName = availableUpdate.versionName,
                                downloading = updateState is UpdateState.Downloading,
                                installable =
                                    availableUpdate.apkUrl != null &&
                                        availableUpdate.checksumUrl != null,
                                onOpenUpdate = onOpenUpdate,
                            )
                        }
                    }
                }

                AnimatedVisibility(
                    visible =
                        shouldWarnRejectedConnections(
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
                    TablerIcons.AlertTriangle,
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
                    TablerIcons.AlertCircle,
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
                colors =
                    ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.onErrorContainer
                    ),
            ) {
                Text(stringResource(R.string.port_in_use_open_settings))
            }
        }
    }
}

@Composable
private fun ConnectivityCard(snapshot: ConnectivitySnapshot) {
    val state = presentConnectivity(snapshot)
    val statusColor =
        when (state) {
            ConnectionSummary.WORKING -> StatusGreen
            ConnectionSummary.OFFLINE,
            ConnectionSummary.BLOCKED,
            ConnectionSummary.UNREACHABLE -> StatusRed
            else -> StatusOrange
        }
    val statusIcon =
        when (state) {
            ConnectionSummary.WORKING -> TablerIcons.CircleCheck
            ConnectionSummary.OFFLINE -> TablerIcons.WifiOff
            ConnectionSummary.BLOCKED -> TablerIcons.Ban
            ConnectionSummary.UNREACHABLE -> TablerIcons.AlertCircle
            ConnectionSummary.CAPTIVE_PORTAL,
            ConnectionSummary.DEGRADED -> TablerIcons.AlertTriangle
            else -> TablerIcons.Network
        }
    val summary =
        when (state) {
            ConnectionSummary.WAITING_FOR_NETWORK_STATE -> R.string.connectivity_wait_network_state
            ConnectionSummary.OFFLINE -> R.string.connectivity_offline
            ConnectionSummary.CHECKING -> R.string.connectivity_checking
            ConnectionSummary.CAPTIVE_PORTAL -> R.string.connectivity_captive_portal
            ConnectionSummary.BLOCKED -> R.string.connectivity_blocked
            ConnectionSummary.UNCERTAIN -> R.string.connectivity_uncertain
            ConnectionSummary.WAITING_FOR_TELEGRAM -> R.string.connectivity_wait_telegram
            ConnectionSummary.WORKING -> R.string.connectivity_working
            ConnectionSummary.WAITING_FOR_DATA -> R.string.connectivity_wait_data
            ConnectionSummary.CONNECTING -> R.string.connectivity_connecting
            ConnectionSummary.RETRYING -> R.string.connectivity_retrying
            ConnectionSummary.UNREACHABLE -> R.string.connectivity_unreachable
            ConnectionSummary.DEGRADED -> R.string.connectivity_degraded
        }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = statusColor.copy(alpha = 0.1f)),
    ) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(statusIcon, contentDescription = null, tint = statusColor)
            Spacer(modifier = Modifier.width(10.dp))
            Text(text = stringResource(summary), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun ConnectionAdviceCard(advice: ConnectionAdvice) {
    val message =
        when (advice) {
            ConnectionAdvice.ENABLE_CLOUDFLARE -> R.string.connection_advice_enable_cf
            ConnectionAdvice.USE_CLOUDFLARE_FIRST -> R.string.connection_advice_cf_first
            ConnectionAdvice.USE_DIRECT_FIRST -> R.string.connection_advice_direct_first
        }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors =
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    TablerIcons.Network,
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
        colors =
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                TablerIcons.DeviceMobileDown,
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
                            if (installable) R.string.update_install
                            else R.string.update_open_release
                        )
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
    controls: @Composable () -> Unit,
) {
    val isRunning = serviceState == ProxyServiceState.RUNNING
    val isTransitioning =
        serviceState == ProxyServiceState.STARTING ||
            serviceState == ProxyServiceState.RESTARTING ||
            serviceState == ProxyServiceState.STOPPING
    val statusColor =
        when {
            isRunning -> StatusGreen
            isTransitioning -> MaterialTheme.colorScheme.primary
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier =
                    Modifier.size(48.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(statusColor.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    TablerIcons.Server,
                    contentDescription = null,
                    tint = statusColor,
                    modifier = Modifier.size(26.dp),
                )
            }
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text =
                        when (serviceState) {
                            ProxyServiceState.STOPPED -> stringResource(R.string.proxy_stopped)
                            ProxyServiceState.STARTING -> stringResource(R.string.proxy_starting)
                            ProxyServiceState.RUNNING -> stringResource(R.string.proxy_running)
                            ProxyServiceState.RESTARTING ->
                                stringResource(R.string.proxy_restarting)
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
                if (isRunning && config.showDetailedStats && runtimeRouteMode != null) {
                    Text(
                        text =
                            stringResource(
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
                if (isRunning && uptimeSeconds > 0) {
                    Text(
                        text = stringResource(R.string.proxy_uptime, formatUptime(uptimeSeconds)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        HorizontalDivider(
            modifier = Modifier.padding(horizontal = 20.dp),
            color = MaterialTheme.colorScheme.outlineVariant,
        )
        controls()
    }
}

@Composable
private fun ProxyControls(
    serviceState: ProxyServiceState,
    proxyEnabled: Boolean,
    commandFailed: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onOpenInTelegram: () -> Unit,
    onCopyLink: () -> Unit,
    proxyLink: String,
) {
    val isActive = serviceState != ProxyServiceState.STOPPED
    Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
        if (commandFailed) {
            Text(
                text = stringResource(R.string.proxy_command_failed),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
            Spacer(modifier = Modifier.height(8.dp))
        }
        ActionButtons(
            serviceState = serviceState,
            proxyEnabled = proxyEnabled,
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

@OptIn(ExperimentalAnimationApi::class)
@Composable
private fun ActionButtons(
    serviceState: ProxyServiceState,
    proxyEnabled: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    AnimatedContent(
        targetState = serviceState,
        transitionSpec = { fadeIn() togetherWith fadeOut() },
        label = "action_buttons",
    ) { state ->
        if (state == ProxyServiceState.STOPPED) {
            Column {
                if (proxyEnabled) {
                    Text(
                        text = stringResource(R.string.proxy_resume_pending),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }
                Button(
                    onClick = onStart,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                    colors =
                        ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary
                        ),
                    shape = RoundedCornerShape(14.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    Icon(
                        TablerIcons.PlayerPlay,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        stringResource(if (proxyEnabled) R.string.btn_retry else R.string.btn_start)
                    )
                }
                if (proxyEnabled) {
                    TextButton(
                        onClick = onStop,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    ) {
                        Text(stringResource(R.string.btn_stop))
                    }
                }
            }
        } else {
            val stopping = state == ProxyServiceState.STOPPING
            Button(
                onClick = onStop,
                enabled = !stopping,
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                colors =
                    ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    ),
                shape = RoundedCornerShape(14.dp),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            ) {
                if (!stopping) {
                    Icon(
                        TablerIcons.PlayerStop,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text(stringResource(if (stopping) R.string.btn_stopping else R.string.btn_stop))
            }
        }
    }
}

@Composable
private fun LinkActions(proxyLink: String, onOpenInTelegram: () -> Unit, onCopyLink: () -> Unit) {
    var showLink by rememberSaveable { mutableStateOf(false) }
    Button(
        onClick = onOpenInTelegram,
        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
        shape = RoundedCornerShape(14.dp),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(stringResource(R.string.open_in_telegram))
    }
    Spacer(modifier = Modifier.height(8.dp))
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Row(
            modifier =
                Modifier.weight(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .clickable(onClickLabel = stringResource(R.string.view_proxy_link)) {
                        showLink = true
                    }
                    .heightIn(min = 48.dp)
                    .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.proxy_link),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Icon(
                TablerIcons.InfoCircle,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        IconButton(onClick = onCopyLink) {
            Icon(
                TablerIcons.Copy,
                contentDescription = stringResource(R.string.copy_link),
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
    if (showLink) {
        AlertDialog(
            onDismissRequest = { showLink = false },
            title = { Text(stringResource(R.string.proxy_link)) },
            text = {
                SelectionContainer {
                    Text(
                        text = proxyLink,
                        style =
                            MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace
                            ),
                        modifier =
                            Modifier.heightIn(max = 280.dp).verticalScroll(rememberScrollState()),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onCopyLink()
                        showLink = false
                    }
                ) {
                    Text(stringResource(R.string.copy_link))
                }
            },
            dismissButton = {
                TextButton(onClick = { showLink = false }) { Text(stringResource(R.string.close)) }
            },
        )
    }
}

private fun formatUptime(seconds: Long): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StatsCard(stats: StatsSnapshot) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors =
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.stats_connections),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(modifier = Modifier.height(12.dp))

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
            }
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                stringResource(R.string.stats_transport),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(4.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listOf(
                        R.string.stats_ws to stats.connectionsWs,
                        R.string.stats_cf_proxy to stats.connectionsCfProxy,
                        R.string.stats_tcp_fallback to stats.connectionsTcpFallback,
                    )
                    .forEach { (label, count) ->
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceContainer,
                            shape = RoundedCornerShape(8.dp),
                        ) {
                            Text(
                                text = "${stringResource(label)}  $count",
                                style = MaterialTheme.typography.labelLarge,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            )
                        }
                    }
            }
            HorizontalDivider(
                modifier = Modifier.padding(vertical = 12.dp),
                color = MaterialTheme.colorScheme.outlineVariant,
            )
            Text(
                stringResource(R.string.stats_traffic),
                style = MaterialTheme.typography.titleSmall,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth()) {
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
            }
            HorizontalDivider(
                modifier = Modifier.padding(vertical = 12.dp),
                color = MaterialTheme.colorScheme.outlineVariant,
            )
            val poolTotal = stats.poolHits + stats.poolMisses
            val poolStr =
                if (poolTotal > 0) {
                    stringResource(R.string.stats_pool_ratio, stats.poolHits, poolTotal)
                } else "—"
            listOf(
                    Triple(R.string.stats_pool, poolStr, false),
                    Triple(
                        R.string.stats_bad,
                        stats.connectionsBad.toString(),
                        stats.connectionsBad > 0,
                    ),
                    Triple(R.string.stats_ws_errors, stats.wsErrors.toString(), stats.wsErrors > 0),
                )
                .forEach { (label, value, warning) ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            stringResource(label),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            value,
                            style = MaterialTheme.typography.labelLarge,
                            color =
                                if (warning) StatusOrange else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
        }
    }
}

@Composable
private fun StatItem(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier.padding(end = 8.dp)) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
