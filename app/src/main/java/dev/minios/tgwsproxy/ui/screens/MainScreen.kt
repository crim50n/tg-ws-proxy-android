package dev.minios.tgwsproxy.ui.screens

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.unit.sp
import dev.minios.tgwsproxy.R
import dev.minios.tgwsproxy.proxy.MtProtoConstants
import dev.minios.tgwsproxy.proxy.ProxyConfig
import dev.minios.tgwsproxy.proxy.StatsSnapshot
import dev.minios.tgwsproxy.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    config: ProxyConfig,
    isRunning: Boolean,
    stats: StatsSnapshot,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onRestart: () -> Unit,
    onOpenInTelegram: () -> Unit,
    onCopyLink: () -> Unit,
    onCopySecret: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenAbout: () -> Unit,
) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("TG WS Proxy") },
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
            // Status indicator
            StatusCard(isRunning = isRunning, config = config)

            Spacer(modifier = Modifier.height(16.dp))

            // Action buttons with animated transitions
            ActionButtons(
                isRunning = isRunning,
                onStart = onStart,
                onStop = onStop,
                onRestart = onRestart,
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Proxy link actions (animated)
            AnimatedVisibility(
                visible = isRunning,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
            ) {
                Column {
                    LinkActions(
                        onOpenInTelegram = onOpenInTelegram,
                        onCopyLink = onCopyLink,
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                }
            }

            // Connection info
            ConnectionInfoCard(
                config = config,
                onCopySecret = onCopySecret,
                onCopyLink = onCopyLink,
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Stats (when running)
            AnimatedVisibility(
                visible = isRunning,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
            ) {
                StatsCard(stats = stats)
            }
        }
    }
}

@Composable
private fun StatusCard(isRunning: Boolean, config: ProxyConfig) {
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
            Column {
                Text(
                    text = if (isRunning) {
                        stringResource(R.string.proxy_running)
                    } else {
                        stringResource(R.string.proxy_stopped)
                    },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                if (isRunning) {
                    Text(
                        text = "${config.host}:${config.port}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontFamily = FontFamily.Monospace,
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
    onRestart: () -> Unit,
) {
    AnimatedContent(
        targetState = isRunning,
        transitionSpec = {
            fadeIn() togetherWith fadeOut()
        },
        label = "action_buttons",
    ) { running ->
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
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
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = StatusRed),
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
                ) {
                    Icon(Icons.Default.Stop, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(stringResource(R.string.btn_stop), fontSize = 13.sp, maxLines = 1)
                }
                OutlinedButton(
                    onClick = onRestart,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(stringResource(R.string.btn_restart), fontSize = 13.sp, maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun LinkActions(
    onOpenInTelegram: () -> Unit,
    onCopyLink: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Button(
            onClick = onOpenInTelegram,
            modifier = Modifier.weight(1f),
            colors = ButtonDefaults.buttonColors(containerColor = TgBlue),
            shape = RoundedCornerShape(12.dp),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
        ) {
            Text(stringResource(R.string.open_in_telegram), fontSize = 13.sp, maxLines = 1)
        }
        OutlinedButton(
            onClick = onCopyLink,
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(12.dp),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
        ) {
            Text(stringResource(R.string.copy_link), fontSize = 13.sp, maxLines = 1)
        }
    }
}

@Composable
private fun ConnectionInfoCard(
    config: ProxyConfig,
    onCopySecret: () -> Unit,
    onCopyLink: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.settings_connection),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(modifier = Modifier.height(12.dp))

            InfoRow(label = stringResource(R.string.settings_host), value = config.host)
            InfoRow(label = stringResource(R.string.settings_port), value = config.port.toString())

            // Secret — truncated, tappable to copy
            val fullSecret = "dd${config.secret}"
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onCopySecret)
                    .padding(vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.info_secret),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "${fullSecret.take(8)}...${fullSecret.takeLast(6)}",
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontFamily = FontFamily.Monospace,
                        ),
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Icon(
                        Icons.Default.ContentCopy,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // Link — tappable to copy
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onCopyLink)
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.info_link),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = config.proxyLink(),
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp,
                        ),
                        color = TgBlue,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(modifier = Modifier.width(4.dp))
                Icon(
                    Icons.Default.ContentCopy,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun InfoRow(
    label: String,
    value: String,
    monospace: Boolean = false,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium.copy(
                fontFamily = if (monospace) FontFamily.Monospace else FontFamily.Default,
            ),
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f, fill = false).padding(start = 8.dp),
        )
    }
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
            // Header with uptime
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.stats_connections),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                if (stats.uptimeSeconds > 0) {
                    Text(
                        text = formatUptime(stats.uptimeSeconds),
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontFamily = FontFamily.Monospace,
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
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
                val poolStr = if (poolTotal > 0) "${stats.poolHits}/$poolTotal" else "n/a"
                StatItem(
                    label = stringResource(R.string.stats_pool),
                    value = poolStr,
                    modifier = Modifier.weight(1f),
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Row 3: Bad, WS errors (balanced with 4 cols)
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
                // Filler cells for alignment
                Box(modifier = Modifier.weight(1f))
                Box(modifier = Modifier.weight(1f))
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
