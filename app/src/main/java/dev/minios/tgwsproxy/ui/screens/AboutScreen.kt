package dev.minios.tgwsproxy.ui.screens

import android.content.Intent
import android.net.Uri
import android.widget.ImageView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import dev.minios.tgwsproxy.BuildConfig
import dev.minios.tgwsproxy.R
import dev.minios.tgwsproxy.ui.icons.TablerIcons
import dev.minios.tgwsproxy.update.UpdateState

private const val PROJECT_URL = "https://github.com/crim50n/tg-ws-proxy-android"
private const val LICENSE_URL = "$PROJECT_URL/blob/master/LICENSE"
private const val FLOWSEAL_URL = "https://github.com/Flowseal/tg-ws-proxy"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(
    updateState: UpdateState,
    onCheckForUpdates: () -> Unit,
    onOpenUpdate: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val openUrl: (String) -> Unit = { url ->
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: Exception) {}
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.about_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            TablerIcons.ArrowLeft,
                            contentDescription = stringResource(R.string.navigate_back),
                        )
                    }
                },
                colors =
                    TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        titleContentColor = MaterialTheme.colorScheme.onPrimary,
                        navigationIconContentColor = MaterialTheme.colorScheme.onPrimary,
                    ),
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
                        .padding(horizontal = 16.dp, vertical = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                AndroidView(
                    modifier = Modifier.size(88.dp),
                    factory = { viewContext ->
                        ImageView(viewContext).apply {
                            setImageResource(R.mipmap.ic_launcher)
                            importantForAccessibility =
                                android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO
                        }
                    },
                )

                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = stringResource(R.string.app_name),
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.primaryContainer,
                    ) {
                        Text(
                            text = stringResource(R.string.about_version, BuildConfig.VERSION_NAME),
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                }

                Text(
                    text = stringResource(R.string.about_description),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )

                UpdateCheckCard(
                    updateState = updateState,
                    onCheck = onCheckForUpdates,
                    onOpenUpdate = onOpenUpdate,
                )

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    colors =
                        CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                        ),
                ) {
                    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                        AboutFeature(
                            icon = TablerIcons.InfoCircle,
                            title = stringResource(R.string.about_limits_title),
                            text = stringResource(R.string.about_limits_text),
                        )
                    }
                }

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    colors =
                        CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                        ),
                ) {
                    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                        AboutFeature(
                            icon = TablerIcons.Server,
                            title = stringResource(R.string.about_local_title),
                            text = stringResource(R.string.about_local_text),
                        )
                        HorizontalDivider()
                        AboutFeature(
                            icon = TablerIcons.ArrowsLeftRight,
                            title = stringResource(R.string.about_transport_title),
                            text = stringResource(R.string.about_transport_text),
                        )
                        HorizontalDivider()
                        AboutFeature(
                            icon = TablerIcons.Bell,
                            title = stringResource(R.string.about_background_title),
                            text = stringResource(R.string.about_background_text),
                        )
                    }
                }

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    colors =
                        CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                        ),
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        OutlinedButton(
                            onClick = { openUrl(PROJECT_URL) },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(14.dp),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                        ) {
                            Icon(TablerIcons.Code, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.about_source_code))
                            Spacer(modifier = Modifier.width(6.dp))
                            Icon(
                                TablerIcons.ExternalLink,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                        OutlinedButton(
                            onClick = { openUrl(LICENSE_URL) },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(14.dp),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                        ) {
                            Text(stringResource(R.string.about_license))
                            Spacer(modifier = Modifier.width(6.dp))
                            Icon(
                                TablerIcons.ExternalLink,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                        TextButton(
                            onClick = { openUrl("https://github.com/tabler/tabler-icons") },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(R.string.about_tabler_icons))
                            Spacer(modifier = Modifier.width(6.dp))
                            Icon(
                                TablerIcons.ExternalLink,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                }

                Text(
                    text = stringResource(R.string.about_based_on),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                TextButton(onClick = { openUrl(FLOWSEAL_URL) }) {
                    Text(stringResource(R.string.about_flowseal_project))
                    Spacer(modifier = Modifier.width(6.dp))
                    Icon(
                        TablerIcons.ExternalLink,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun UpdateCheckCard(
    updateState: UpdateState,
    onCheck: () -> Unit,
    onOpenUpdate: () -> Unit,
) {
    val release =
        when (updateState) {
            is UpdateState.Available -> updateState.release
            is UpdateState.Downloading -> updateState.release
            else -> null
        }
    val installable = release?.let { it.apkUrl != null && it.checksumUrl != null } == true
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors =
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            when (updateState) {
                UpdateState.Idle -> Unit
                UpdateState.Checking ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(stringResource(R.string.update_checking))
                    }
                UpdateState.UpToDate ->
                    Text(
                        text = stringResource(R.string.update_up_to_date),
                        color = MaterialTheme.colorScheme.primary,
                    )
                UpdateState.Error ->
                    Text(
                        text = stringResource(R.string.update_check_failed),
                        color = MaterialTheme.colorScheme.error,
                    )
                is UpdateState.Available ->
                    Text(
                        text =
                            stringResource(
                                R.string.update_available_version,
                                updateState.release.versionName,
                            ),
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Medium,
                    )
                is UpdateState.Downloading ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(stringResource(R.string.update_downloading))
                    }
            }
            if (updateState is UpdateState.Available || updateState is UpdateState.Downloading) {
                Button(
                    onClick = onOpenUpdate,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = updateState !is UpdateState.Downloading,
                    colors =
                        ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary
                        ),
                    shape = RoundedCornerShape(14.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    Text(
                        stringResource(
                            if (updateState is UpdateState.Downloading) {
                                R.string.update_downloading
                            } else if (!installable) {
                                R.string.update_open_release
                            } else {
                                R.string.update_download_install
                            }
                        )
                    )
                }
            } else {
                OutlinedButton(
                    onClick = onCheck,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = updateState !is UpdateState.Checking,
                    shape = RoundedCornerShape(14.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    Text(stringResource(R.string.update_check_now))
                }
            }
            Text(
                text = stringResource(R.string.about_updates_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun AboutFeature(icon: ImageVector, title: String, text: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 14.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Surface(
            modifier = Modifier.size(40.dp),
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
