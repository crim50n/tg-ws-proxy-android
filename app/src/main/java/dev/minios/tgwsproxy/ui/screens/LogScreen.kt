package dev.minios.tgwsproxy.ui.screens

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.minios.tgwsproxy.R
import dev.minios.tgwsproxy.diagnostics.DiagnosticEntry
import dev.minios.tgwsproxy.diagnostics.DiagnosticState
import dev.minios.tgwsproxy.ui.theme.StatusGreen
import dev.minios.tgwsproxy.ui.theme.StatusRed

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
    var recordingDialogVisible by rememberSaveable { mutableStateOf(false) }
    var initialScrollCompleted by rememberSaveable { mutableStateOf(false) }
    var followTail by rememberSaveable { mutableStateOf(true) }
    val recordingAlpha = recordingIndicatorAlpha(diagnosticState.isRecording)
    LaunchedEffect(entries.size) {
        if (entries.isEmpty()) return@LaunchedEffect
        if (!initialScrollCompleted || followTail) {
            listState.scrollToItem(entries.lastIndex)
        }
        initialScrollCompleted = true
    }
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }.collect { isScrolling ->
            if (!isScrolling && initialScrollCompleted) {
                followTail = !listState.canScrollForward
            }
        }
    }

    if (recordingDialogVisible) {
        DiagnosticControlDialog(
            diagnosticState = diagnosticState,
            onStart = onStartDiagnostics,
            onStop = onStopDiagnostics,
            onDismiss = { recordingDialogVisible = false },
        )
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
                    IconButton(onClick = { recordingDialogVisible = true }) {
                        Icon(
                            Icons.Default.FiberManualRecord,
                            contentDescription = stringResource(R.string.diagnostics_controls),
                            modifier = Modifier.alpha(recordingAlpha),
                            tint = if (diagnosticState.isRecording) StatusRed else LocalContentColor.current,
                        )
                    }
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
            if (entries.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = stringResource(R.string.log_empty),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                Box(modifier = Modifier.fillMaxSize()) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(end = 8.dp),
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
                    LazyListScrollbar(
                        state = listState,
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .fillMaxHeight(),
                    )
                }
            }
        }
    }
}

@Composable
private fun DiagnosticControlDialog(
    diagnosticState: DiagnosticState,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onDismiss: () -> Unit,
) {
    val recordingAlpha = recordingIndicatorAlpha(diagnosticState.isRecording)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    modifier = Modifier
                        .size(10.dp)
                        .alpha(recordingAlpha),
                    shape = RoundedCornerShape(5.dp),
                    color = if (diagnosticState.isRecording) StatusRed else MaterialTheme.colorScheme.primary,
                ) {}
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = stringResource(
                        if (diagnosticState.isRecording) {
                            R.string.diagnostics_active_title
                        } else {
                            R.string.diagnostics_report_title
                        },
                    ),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        },
        text = {
            Column {
                Text(
                    text = if (diagnosticState.isRecording) {
                        stringResource(R.string.diagnostics_active_instructions)
                    } else {
                        stringResource(R.string.diagnostics_start_instructions)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = stringResource(R.string.diagnostics_report_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            Button(onClick = if (diagnosticState.isRecording) onStop else onStart) {
                Text(
                    stringResource(
                        if (diagnosticState.isRecording) R.string.diagnostics_stop else R.string.diagnostics_start,
                    ),
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(
                    stringResource(
                        if (diagnosticState.isRecording) {
                            R.string.diagnostics_continue_background
                        } else {
                            R.string.cancel
                        },
                    ),
                )
            }
        },
    )
}

@Composable
internal fun recordingIndicatorAlpha(isRecording: Boolean): Float {
    if (!isRecording) return 1f
    val transition = rememberInfiniteTransition(label = "diagnostic recording")
    return transition.animateFloat(
        initialValue = 1f,
        targetValue = 0.2f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 650),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "recording indicator alpha",
    ).value
}

@Composable
private fun LazyListScrollbar(
    state: LazyListState,
    modifier: Modifier = Modifier,
) {
    val layoutInfo = state.layoutInfo
    val visibleItems = layoutInfo.visibleItemsInfo
    val totalItems = layoutInfo.totalItemsCount
    if (visibleItems.isEmpty() || visibleItems.size >= totalItems) return

    BoxWithConstraints(modifier = modifier.width(5.dp)) {
        val visibleFraction = (visibleItems.size.toFloat() / totalItems).coerceIn(0f, 1f)
        val thumbHeight = (maxHeight * visibleFraction)
            .coerceAtLeast(32.dp)
            .coerceAtMost(maxHeight)
        val maxFirstIndex = (totalItems - visibleItems.size).coerceAtLeast(1)
        val scrollFraction = (state.firstVisibleItemIndex.toFloat() / maxFirstIndex).coerceIn(0f, 1f)
        val thumbOffset = (maxHeight - thumbHeight) * scrollFraction

        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .offset(y = thumbOffset)
                .width(4.dp)
                .height(thumbHeight)
                .clip(RoundedCornerShape(2.dp))
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)),
        )
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
