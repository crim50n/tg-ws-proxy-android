package dev.minios.tgwsproxy.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.minios.tgwsproxy.R
import dev.minios.tgwsproxy.diagnostics.DiagnosticEntry
import dev.minios.tgwsproxy.diagnostics.DiagnosticState
import dev.minios.tgwsproxy.ui.theme.StatusGreen

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogScreen(
    entries: List<DiagnosticEntry>,
    diagnosticState: DiagnosticState,
    onStartDiagnostics: () -> Unit,
    onStopDiagnostics: () -> Unit,
    onExportDiagnostics: () -> Unit,
    onClearDiagnostics: () -> Unit,
    onBack: () -> Unit,
) {
    val listState = rememberLazyListState()
    LaunchedEffect(entries.size) {
        if (entries.isNotEmpty()) listState.scrollToItem(entries.lastIndex)
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.log_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.navigate_back),
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onExportDiagnostics, enabled = diagnosticState.hasLog) {
                        Icon(Icons.Default.FileUpload, contentDescription = stringResource(R.string.diagnostics_export))
                    }
                    IconButton(onClick = onClearDiagnostics, enabled = diagnosticState.hasLog) {
                        Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.diagnostics_clear))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary,
                    actionIconContentColor = MaterialTheme.colorScheme.onPrimary,
                ),
            )
        },
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp),
        ) {
            DiagnosticControlCard(
                diagnosticState = diagnosticState,
                onStart = onStartDiagnostics,
                onStop = onStopDiagnostics,
            )
            Spacer(modifier = Modifier.height(12.dp))

            if (entries.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = stringResource(R.string.log_empty),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(bottom = 12.dp),
                ) {
                    itemsIndexed(
                        items = entries,
                        key = { index, entry -> "$index:${entry.timestamp}:${entry.event}" },
                    ) { _, entry ->
                        LogEntryRow(entry)
                    }
                }
            }
        }
    }
}

@Composable
private fun DiagnosticControlCard(
    diagnosticState: DiagnosticState,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    modifier = Modifier.size(10.dp),
                    shape = RoundedCornerShape(5.dp),
                    color = if (diagnosticState.isRecording) StatusGreen else MaterialTheme.colorScheme.primary,
                ) {}
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = stringResource(
                        if (diagnosticState.isRecording) R.string.diagnostics_recording else R.string.log_not_recording,
                    ),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = if (diagnosticState.isRecording) {
                    stringResource(R.string.diagnostics_time_left, diagnosticState.remainingMinutes)
                } else {
                    stringResource(R.string.log_start_hint)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (diagnosticState.isRecording) {
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = stringResource(R.string.diagnostics_reproduce_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            Button(
                onClick = if (diagnosticState.isRecording) onStop else onStart,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    stringResource(
                        if (diagnosticState.isRecording) R.string.diagnostics_stop else R.string.diagnostics_start,
                    ),
                )
            }
        }
    }
}

@Composable
private fun LogEntryRow(entry: DiagnosticEntry) {
    val eventColor = when {
        entry.event.contains("failed", ignoreCase = true) ||
                entry.event.contains("rejected", ignoreCase = true) -> MaterialTheme.colorScheme.error
        entry.event.contains("ready", ignoreCase = true) ||
                entry.event.contains("connected", ignoreCase = true) -> StatusGreen
        else -> MaterialTheme.colorScheme.primary
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outline.copy(alpha = 0.55f),
        ),
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = entry.timestamp.substringAfter('T').take(12),
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = entry.event,
                    style = MaterialTheme.typography.labelMedium,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = eventColor,
                )
            }
            if (entry.details.isNotEmpty()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = entry.details,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
