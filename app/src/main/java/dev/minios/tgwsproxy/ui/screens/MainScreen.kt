package dev.minios.tgwsproxy.ui.screens

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.minios.tgwsproxy.R
import dev.minios.tgwsproxy.proxy.MtProtoConstants
import dev.minios.tgwsproxy.proxy.ProxyConfig
import dev.minios.tgwsproxy.proxy.StatsSnapshot
import dev.minios.tgwsproxy.ui.theme.*
import dev.minios.tgwsproxy.update.UpdateState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    config: ProxyConfig,
    isRunning: Boolean,
    stats: StatsSnapshot,
    updateState: UpdateState,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onOpenInTelegram: () -> Unit,
    onCopyLink: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenAbout: () -> Unit,
    onOpenUpdate: () -> Unit,
) {
    val proxyLink = remember(config) { config.proxyLink() }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = TgBlue,
                    titleContentColor = androidx.compose.ui.graphics.Color.White,
                    actionIconContentColor = androidx.compose.ui.graphics.Color.White,
                ),
                actions = {
                    IconButton(onClick = onOpenAbout) {
                        Icon(Icons.Default.Info, contentDescription = stringResource(R.string.about_title))
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.settings))
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
            val availableUpdate = (updateState as? UpdateState.Available)?.release
            AnimatedVisibility(
                visible = availableUpdate != null,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
            ) {
                if (availableUpdate != null) {
                    Column {
                        UpdateCard(
                            versionName = availableUpdate.versionName,
                            onOpenUpdate = onOpenUpdate,
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                    }
                }
            }

            StatusCard(
                isRunning = isRunning,
                config = config,
                uptimeSeconds = stats.uptimeSeconds,
            )

            Spacer(modifier = Modifier.height(16.dp))

            ControlsCard(
                isRunning = isRunning,
                onStart = onStart,
                onStop = onStop,
                onOpenInTelegram = onOpenInTelegram,
                onCopyLink = onCopyLink,
                proxyLink = proxyLink,
            )

            Spacer(modifier = Modifier.height(16.dp))

            AnimatedVisibility(
                visible = isRunning,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
            ) {
                Column {
                    TrafficCard(stats = stats)
                    AnimatedVisibility(
                        visible = config.showDetailedStats,
                        enter = fadeIn() + expandVertically(),
                        exit = fadeOut() + shrinkVertically(),
                    ) {
                        Column {
                            Spacer(modifier = Modifier.height(12.dp))
                            StatsCard(stats = stats)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun UpdateCard(versionName: String, onOpenUpdate: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = TgBlue.copy(alpha = 0.12f)),
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
                tint = TgBlue,
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
            TextButton(onClick = onOpenUpdate) {
                Text(stringResource(R.string.update_open))
            }
        }
    }
}

@Composable
private fun StatusCard(isRunning: Boolean, config: ProxyConfig, uptimeSeconds: Long) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isRunning) {
                StatusGreen.copy(alpha = 0.1f)
            } else {
                StatusRed.copy(alpha = 0.1f)
            },
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
                    .background(if (isRunning) StatusGreen else StatusRed),
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (isRunning) {
                        stringResource(R.string.proxy_running)
                    } else {
                        stringResource(R.string.proxy_stopped)
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
    isRunning: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onOpenInTelegram: () -> Unit,
    onCopyLink: () -> Unit,
    proxyLink: String,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            ActionButtons(
                isRunning = isRunning,
                onStart = onStart,
                onStop = onStop,
            )
            AnimatedVisibility(
                visible = isRunning,
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
    isRunning: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    AnimatedContent(
        targetState = isRunning,
        transitionSpec = {
            fadeIn() togetherWith fadeOut()
        },
        label = "action_buttons",
    ) { running ->
        if (!running) {
            Button(
                onClick = onStart,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = TgBlue),
                shape = RoundedCornerShape(12.dp),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            ) {
                Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.btn_start))
            }
        } else {
            Button(
                onClick = onStop,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = StatusRed),
                shape = RoundedCornerShape(12.dp),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            ) {
                Icon(Icons.Default.Stop, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.btn_stop))
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
        colors = ButtonDefaults.buttonColors(containerColor = TgBlue),
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
private fun TrafficCard(stats: StatsSnapshot) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                modifier = Modifier.size(44.dp),
                shape = RoundedCornerShape(12.dp),
                color = TgBlue.copy(alpha = 0.12f),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Default.SwapVert,
                        contentDescription = null,
                        tint = TgBlue,
                    )
                }
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(
                    text = stringResource(R.string.stats_traffic),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = MtProtoConstants.humanBytes(stats.bytesUp + stats.bytesDown),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = TgBlue,
                )
            }
        }
    }
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
                    valueColor = if (stats.connectionsBad > 0) StatusOrange else TgBlue,
                )
                StatItem(
                    label = stringResource(R.string.stats_ws_errors),
                    value = stats.wsErrors.toString(),
                    modifier = Modifier.weight(1f),
                    valueColor = if (stats.wsErrors > 0) StatusOrange else TgBlue,
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
    valueColor: androidx.compose.ui.graphics.Color = TgBlue,
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
