package dev.minios.tgwsproxy.ui.screens

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
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
    onExportDiagnostics: () -> Unit,
    onClearDiagnostics: () -> Unit,
    onBack: () -> Unit,
) {
    val listState = rememberLazyListState()
    var initialScrollCompleted by remember { mutableStateOf(false) }
    var followTail by remember { mutableStateOf(true) }
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
            Text(
                text = stringResource(R.string.diagnostics_always_on_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
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
private fun LazyListScrollbar(
    state: LazyListState,
    modifier: Modifier = Modifier,
) {
    val metrics by remember(state) {
        derivedStateOf {
            val layoutInfo = state.layoutInfo
            Triple(
                layoutInfo.visibleItemsInfo.size,
                layoutInfo.totalItemsCount,
                state.firstVisibleItemIndex,
            )
        }
    }
    val (visibleItems, totalItems, firstVisibleItemIndex) = metrics
    if (visibleItems == 0 || visibleItems >= totalItems) return

    BoxWithConstraints(modifier = modifier.width(5.dp)) {
        val visibleFraction = (visibleItems.toFloat() / totalItems).coerceIn(0f, 1f)
        val thumbHeight = (maxHeight * visibleFraction)
            .coerceAtLeast(32.dp)
            .coerceAtMost(maxHeight)
        val maxFirstIndex = (totalItems - visibleItems).coerceAtLeast(1)
        val scrollFraction = (firstVisibleItemIndex.toFloat() / maxFirstIndex).coerceIn(0f, 1f)
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
        entry.level >= dev.minios.tgwsproxy.diagnostics.DiagnosticLevel.ERROR -> MaterialTheme.colorScheme.error
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
                    text = entry.level.name,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = eventColor,
                )
                Spacer(modifier = Modifier.width(8.dp))
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
