package dev.minios.tgwsproxy.ui.screens

import androidx.activity.compose.BackHandler
import android.os.Build
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.minios.tgwsproxy.R
import dev.minios.tgwsproxy.proxy.ProxyConfig
import dev.minios.tgwsproxy.proxy.CfProxyDomains
import dev.minios.tgwsproxy.ui.theme.tgSwitchColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    config: ProxyConfig,
    cfTestResult: String?,
    onConfigChange: (ProxyConfig) -> Unit,
    onExit: (ProxyConfig) -> Unit,
    onRegenerateSecret: () -> String,
    onCopySecret: (String) -> Unit,
    onTestCfProxy: () -> Unit,
    onAppearanceChange: (String, Boolean) -> Unit,
) {
    var host by remember { mutableStateOf(config.host) }
    var port by remember { mutableStateOf(config.port.toString()) }
    var secret by remember { mutableStateOf(config.secret) }
    var dcRedirects by remember { mutableStateOf(config.dcRedirectsText()) }
    var cfEnabled by remember { mutableStateOf(config.cfProxyEnabled) }
    var cfPriority by remember { mutableStateOf(config.cfProxyPriority) }
    var cfFirst by remember { mutableStateOf(config.cfProxyFirst) }
    var autoOptimizeConnection by remember { mutableStateOf(config.autoOptimizeConnection) }
    var cfDomain by remember { mutableStateOf(config.cfProxyUserDomain) }
    var useCfDomain by remember { mutableStateOf(config.cfProxyUserDomain.isNotBlank()) }
    var bufKb by remember { mutableStateOf((config.bufferSize / 1024).toString()) }
    var poolSize by remember { mutableStateOf(config.poolSize.toString()) }
    var showDetailedStats by remember { mutableStateOf(config.showDetailedStats) }
    var appTheme by remember { mutableStateOf(config.appTheme) }
    var dynamicColor by remember { mutableStateOf(config.dynamicColor) }

    // Validation error states.
    var hostError by remember { mutableStateOf<String?>(null) }
    var portError by remember { mutableStateOf<String?>(null) }
    var secretError by remember { mutableStateOf<String?>(null) }
    var dcError by remember { mutableStateOf<String?>(null) }
    var cfDomainError by remember { mutableStateOf<String?>(null) }
    var bufferError by remember { mutableStateOf<String?>(null) }
    var poolError by remember { mutableStateOf<String?>(null) }

    val context = LocalContext.current

    fun currentConfig(updateErrors: Boolean): ProxyConfig? {
        var valid = true
        val hostVal = host.trim()
        val portVal = port.toIntOrNull()
        val secretVal = secret.trim()
        val parsedRedirects = ProxyConfig.parseDcRedirectsStrict(dcRedirects)
        val normalizedCfDomain = cfDomain.trim().lowercase()
        val bufferKb = bufKb.toIntOrNull()
        val parsedPoolSize = poolSize.toIntOrNull()

        fun error(condition: Boolean, message: Int, update: (String?) -> Unit) {
            if (condition) valid = false
            if (updateErrors) update(if (condition) context.getString(message) else null)
        }

        error(!ProxyConfig.isValidAddress(hostVal), R.string.validation_host_format) { hostError = it }
        error(portVal == null || portVal !in 1..65535, R.string.validation_port_range) { portError = it }
        error(!secretVal.matches(Regex("^[0-9a-fA-F]{32}$")), R.string.validation_secret_format) { secretError = it }
        error(!autoOptimizeConnection && parsedRedirects == null, R.string.validation_dc_format) { dcError = it }
        error(
            !autoOptimizeConnection && cfEnabled && useCfDomain && !CfProxyDomains.isValidDomain(normalizedCfDomain),
            R.string.validation_domain_format,
        ) { cfDomainError = it }
        error(
            !autoOptimizeConnection && (bufferKb == null || bufferKb !in 4..4096),
            R.string.validation_buffer_range,
        ) { bufferError = it }
        error(
            !autoOptimizeConnection && (parsedPoolSize == null || parsedPoolSize !in 0..16),
            R.string.validation_pool_range,
        ) { poolError = it }

        if (!valid) return null
        return ProxyConfig(
            host = hostVal,
            port = portVal!!,
            secret = secretVal,
            dcRedirects = parsedRedirects ?: config.dcRedirects,
            bufferSize = bufferKb?.takeIf { it in 4..4096 }?.times(1024) ?: config.bufferSize,
            poolSize = parsedPoolSize?.takeIf { it in 0..16 } ?: config.poolSize,
            cfProxyEnabled = cfEnabled,
            cfProxyPriority = cfPriority,
            cfProxyFirst = cfFirst,
            autoOptimizeConnection = autoOptimizeConnection,
            cfProxyUserDomain = if (useCfDomain) normalizedCfDomain else "",
            showDetailedStats = showDetailedStats,
            appTheme = appTheme,
            dynamicColor = dynamicColor,
        )
    }

    LaunchedEffect(
        host,
        port,
        secret,
        dcRedirects,
        cfEnabled,
        cfPriority,
        cfFirst,
        autoOptimizeConnection,
        cfDomain,
        useCfDomain,
        bufKb,
        poolSize,
        showDetailedStats,
        appTheme,
        dynamicColor,
    ) {
        kotlinx.coroutines.delay(350)
        currentConfig(updateErrors = true)?.let { updated ->
            if (updated != config) onConfigChange(updated)
        }
    }

    val finishSettings = {
        val updated = currentConfig(updateErrors = true)
        if (updated != null) onExit(updated)
    }
    BackHandler(onBack = finishSettings)

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = finishSettings) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.navigate_back),
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary,
                ),
            )
        },
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            // MTProto Connection section
            SectionHeader(text = stringResource(R.string.settings_connection))
            Spacer(modifier = Modifier.height(8.dp))

            OutlinedTextField(
                value = host,
                onValueChange = { host = it; hostError = null },
                label = { Text(stringResource(R.string.settings_host)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                isError = hostError != null,
                supportingText = hostError?.let { { Text(it) } },
            )
            Spacer(modifier = Modifier.height(8.dp))

            OutlinedTextField(
                value = port,
                onValueChange = { port = it; portError = null },
                label = { Text(stringResource(R.string.settings_port)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                shape = RoundedCornerShape(12.dp),
                isError = portError != null,
                supportingText = portError?.let { { Text(it) } },
            )
            Spacer(modifier = Modifier.height(8.dp))

            OutlinedTextField(
                value = secret,
                onValueChange = { secret = it; secretError = null },
                label = { Text(stringResource(R.string.settings_secret)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                isError = secretError != null,
                supportingText = secretError?.let { { Text(it) } },
                trailingIcon = {
                    Row {
                        IconButton(
                            onClick = { onCopySecret(secret.trim()) },
                            enabled = secret.trim().matches(Regex("^[0-9a-fA-F]{32}$")),
                        ) {
                            Icon(
                                Icons.Default.ContentCopy,
                                contentDescription = stringResource(R.string.settings_copy_secret),
                            )
                        }
                        IconButton(onClick = { secret = onRegenerateSecret(); secretError = null }) {
                            Icon(
                                Icons.Default.Refresh,
                                contentDescription = stringResource(R.string.settings_regenerate_secret),
                            )
                        }
                    }
                },
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Routing section
            SectionHeader(text = stringResource(R.string.settings_cf_section))
            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.settings_auto_optimize))
                    Text(
                        text = stringResource(R.string.settings_auto_optimize_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Switch(
                    checked = autoOptimizeConnection,
                    onCheckedChange = { autoOptimizeConnection = it },
                    colors = tgSwitchColors(),
                )
            }

            if (!autoOptimizeConnection) {
                Spacer(modifier = Modifier.height(16.dp))

                Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.settings_cf_enable))
                    Text(
                        text = stringResource(R.string.settings_cf_enable_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Switch(
                    checked = cfEnabled,
                    onCheckedChange = { cfEnabled = it },
                    colors = tgSwitchColors(),
                )
            }

                if (cfEnabled) {
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = stringResource(R.string.settings_route_priority),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                )
                Spacer(modifier = Modifier.height(8.dp))
                RouteOption(
                    selected = !cfFirst,
                    title = stringResource(R.string.settings_route_direct),
                    description = stringResource(
                        if (cfPriority) R.string.settings_route_direct_desc else R.string.settings_route_direct_tcp_desc,
                    ),
                    onClick = { cfFirst = false },
                )
                Spacer(modifier = Modifier.height(8.dp))
                RouteOption(
                    selected = cfFirst,
                    title = stringResource(R.string.settings_route_cf),
                    description = stringResource(R.string.settings_route_cf_desc),
                    onClick = { cfFirst = true },
                )

                if (!cfFirst) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.settings_cf_priority))
                            Text(
                                text = stringResource(R.string.settings_cf_priority_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Switch(
                            checked = cfPriority,
                            onCheckedChange = { cfPriority = it },
                            colors = tgSwitchColors(),
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.settings_cf_custom_domain))
                        Text(
                            text = stringResource(R.string.settings_cf_custom_domain_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Switch(
                        checked = useCfDomain,
                        onCheckedChange = { useCfDomain = it },
                        colors = tgSwitchColors(),
                    )
                }

                if (useCfDomain) {
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = cfDomain,
                        onValueChange = { cfDomain = it; cfDomainError = null },
                        label = { Text(stringResource(R.string.settings_cf_domain_label)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        isError = cfDomainError != null,
                        supportingText = cfDomainError?.let { { Text(it) } },
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        onClick = onTestCfProxy,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                    ) {
                        Icon(Icons.Default.NetworkCheck, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.settings_cf_test))
                    }
                }

                cfTestResult?.let { result ->
                    Spacer(modifier = Modifier.height(8.dp))
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Text(
                            text = result,
                            modifier = Modifier.padding(12.dp),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }

                Spacer(modifier = Modifier.height(24.dp))

                // Direct WebSocket targets
                SectionHeader(text = stringResource(R.string.settings_dc_section))
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.settings_dc_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = dcRedirects,
                    onValueChange = { dcRedirects = it; dcError = null },
                    label = { Text(stringResource(R.string.dc_ip_label)) },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                    maxLines = 6,
                    shape = RoundedCornerShape(12.dp),
                    placeholder = { Text(stringResource(R.string.settings_dc_hint)) },
                    isError = dcError != null,
                    supportingText = dcError?.let { { Text(it) } },
                )
            }

            if (!autoOptimizeConnection) {
                Spacer(modifier = Modifier.height(24.dp))

                // Performance section
                SectionHeader(text = stringResource(R.string.settings_perf_section))
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.settings_perf_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = bufKb,
                    onValueChange = { bufKb = it; bufferError = null },
                    label = { Text(stringResource(R.string.settings_buf_size)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    shape = RoundedCornerShape(12.dp),
                    isError = bufferError != null,
                    supportingText = bufferError?.let { { Text(it) } },
                )
                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = poolSize,
                    onValueChange = { poolSize = it; poolError = null },
                    label = { Text(stringResource(R.string.settings_pool_size)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    shape = RoundedCornerShape(12.dp),
                    isError = poolError != null,
                    supportingText = poolError?.let { { Text(it) } },
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            SectionHeader(text = stringResource(R.string.settings_theme))
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.settings_theme_mode),
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(modifier = Modifier.height(8.dp))
            val themeOptions = listOf(
                "system" to stringResource(R.string.settings_theme_system),
                "light" to stringResource(R.string.settings_theme_light),
                "dark" to stringResource(R.string.settings_theme_dark),
            )
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                themeOptions.forEachIndexed { index, (value, label) ->
                    SegmentedButton(
                        selected = appTheme == value,
                        onClick = {
                            appTheme = value
                            onAppearanceChange(value, dynamicColor)
                        },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = themeOptions.size),
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(label)
                    }
                }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.settings_dynamic_color))
                        Text(
                            text = stringResource(R.string.settings_dynamic_color_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Switch(
                        checked = dynamicColor,
                        onCheckedChange = {
                            dynamicColor = it
                            onAppearanceChange(appTheme, it)
                        },
                        colors = tgSwitchColors(),
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            SectionHeader(text = stringResource(R.string.settings_interface))
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.settings_detailed_stats))
                    Text(
                        text = stringResource(R.string.settings_detailed_stats_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Switch(
                    checked = showDetailedStats,
                    onCheckedChange = { showDetailedStats = it },
                    colors = tgSwitchColors(),
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(vertical = 4.dp),
    )
    HorizontalDivider()
}

@Composable
private fun RouteOption(
    selected: Boolean,
    title: String,
    description: String,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        border = if (selected) {
            androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.primary)
        } else {
            null
        },
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = selected, onClick = null)
            Spacer(modifier = Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, style = MaterialTheme.typography.titleSmall)
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
