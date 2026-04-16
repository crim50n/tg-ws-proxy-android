package dev.minios.tgwsproxy.ui.screens

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
import dev.minios.tgwsproxy.ui.theme.TgBlue
import dev.minios.tgwsproxy.ui.theme.tgSwitchColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    config: ProxyConfig,
    cfTestResult: String?,
    onSave: (ProxyConfig) -> Unit,
    onBack: () -> Unit,
    onRegenerateSecret: () -> String,
    onTestCfProxy: () -> Unit,
    onClearCfTestResult: () -> Unit,
) {
    var host by remember(config) { mutableStateOf(config.host) }
    var port by remember(config) { mutableStateOf(config.port.toString()) }
    var secret by remember(config) { mutableStateOf(config.secret) }
    var dcRedirects by remember(config) { mutableStateOf(config.dcRedirectsText()) }
    var cfEnabled by remember(config) { mutableStateOf(config.cfProxyEnabled) }
    var cfPriority by remember(config) { mutableStateOf(config.cfProxyPriority) }
    var cfDomain by remember(config) { mutableStateOf(config.cfProxyUserDomain) }
    var useCfDomain by remember(config) { mutableStateOf(config.cfProxyUserDomain.isNotBlank()) }
    var bufKb by remember(config) { mutableStateOf((config.bufferSize / 1024).toString()) }
    var poolSize by remember(config) { mutableStateOf(config.poolSize.toString()) }

    // #8: Validation error states
    var hostError by remember { mutableStateOf<String?>(null) }
    var portError by remember { mutableStateOf<String?>(null) }
    var secretError by remember { mutableStateOf<String?>(null) }

    val context = LocalContext.current

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = TgBlue,
                    titleContentColor = androidx.compose.ui.graphics.Color.White,
                    navigationIconContentColor = androidx.compose.ui.graphics.Color.White,
                ),
            )
        },
        bottomBar = {
            Surface(
                shadowElevation = 8.dp,
                color = MaterialTheme.colorScheme.surface,
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    OutlinedButton(
                        onClick = onBack,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Text(stringResource(R.string.settings_cancel))
                    }
                    Button(
                        onClick = {
                            // #8: Validate before saving
                            var valid = true

                            // Validate host (must be valid IP or hostname)
                            val hostVal = host.trim()
                            if (hostVal.isEmpty()) {
                                hostError = context.getString(R.string.validation_host_empty)
                                valid = false
                            } else {
                                hostError = null
                            }

                            // Validate port (1-65535)
                            val portVal = port.toIntOrNull()
                            if (portVal == null || portVal < 1 || portVal > 65535) {
                                portError = context.getString(R.string.validation_port_range)
                                valid = false
                            } else {
                                portError = null
                            }

                            // Validate secret (32 hex chars)
                            val secretVal = secret.trim()
                            if (!secretVal.matches(Regex("^[0-9a-fA-F]{32}$"))) {
                                secretError = context.getString(R.string.validation_secret_format)
                                valid = false
                            } else {
                                secretError = null
                            }

                            if (!valid) return@Button

                            val newConfig = ProxyConfig(
                                host = hostVal,
                                port = portVal!!,
                                secret = secretVal,
                                dcRedirects = ProxyConfig.parseDcRedirects(dcRedirects),
                                bufferSize = (bufKb.toIntOrNull() ?: 256) * 1024,
                                poolSize = poolSize.toIntOrNull() ?: 4,
                                cfProxyEnabled = cfEnabled,
                                cfProxyPriority = cfPriority,
                                cfProxyUserDomain = if (useCfDomain) cfDomain else "",
                            )
                            onSave(newConfig)
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = TgBlue),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Text(stringResource(R.string.settings_save))
                    }
                }
            }
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
                    IconButton(onClick = { secret = onRegenerateSecret(); secretError = null }) {
                        Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.settings_regenerate_secret))
                    }
                },
            )

            Spacer(modifier = Modifier.height(24.dp))

            // DC Redirects section
            SectionHeader(text = stringResource(R.string.settings_dc_section))
            Spacer(modifier = Modifier.height(8.dp))

            OutlinedTextField(
                value = dcRedirects,
                onValueChange = { dcRedirects = it },
                label = { Text("DC:IP") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 3,
                maxLines = 6,
                shape = RoundedCornerShape(12.dp),
                placeholder = { Text(stringResource(R.string.settings_dc_hint)) },
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Cloudflare Proxy section
            SectionHeader(text = stringResource(R.string.settings_cf_section))
            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(stringResource(R.string.settings_cf_enable))
                Switch(
                    checked = cfEnabled,
                    onCheckedChange = { cfEnabled = it },
                    colors = tgSwitchColors(),
                )
            }

            if (cfEnabled) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(stringResource(R.string.settings_cf_priority))
                    Switch(
                        checked = cfPriority,
                        onCheckedChange = { cfPriority = it },
                        colors = tgSwitchColors(),
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(stringResource(R.string.settings_cf_custom_domain))
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
                        onValueChange = { cfDomain = it },
                        label = { Text(stringResource(R.string.settings_cf_domain_label)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        onClick = onTestCfProxy,
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = TgBlue),
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

            // Performance section
            SectionHeader(text = stringResource(R.string.settings_perf_section))
            Spacer(modifier = Modifier.height(8.dp))

            OutlinedTextField(
                value = bufKb,
                onValueChange = { bufKb = it },
                label = { Text(stringResource(R.string.settings_buf_size)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                shape = RoundedCornerShape(12.dp),
            )
            Spacer(modifier = Modifier.height(8.dp))

            OutlinedTextField(
                value = poolSize,
                onValueChange = { poolSize = it },
                label = { Text(stringResource(R.string.settings_pool_size)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                shape = RoundedCornerShape(12.dp),
            )

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = TgBlue,
        modifier = Modifier.padding(vertical = 4.dp),
    )
    HorizontalDivider()
}
