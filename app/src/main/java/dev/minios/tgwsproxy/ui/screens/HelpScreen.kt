package dev.minios.tgwsproxy.ui.screens

import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.minios.tgwsproxy.R
import dev.minios.tgwsproxy.ui.icons.TablerIcons

private data class HelpSection(
    val icon: ImageVector,
    @StringRes val title: Int,
    @StringRes val text: Int,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HelpScreen(onBack: () -> Unit) {
    val sections =
        listOf(
            HelpSection(
                TablerIcons.PlayerPlay,
                R.string.help_quick_start_title,
                R.string.help_quick_start_text,
            ),
            HelpSection(
                TablerIcons.Schema,
                R.string.help_overview_title,
                R.string.help_overview_text,
            ),
            HelpSection(
                TablerIcons.InfoCircle,
                R.string.help_status_title,
                R.string.help_status_text,
            ),
            HelpSection(
                TablerIcons.Tool,
                R.string.help_troubleshooting_title,
                R.string.help_troubleshooting_text,
            ),
            HelpSection(TablerIcons.Route, R.string.help_mode_title, R.string.help_mode_text),
            HelpSection(TablerIcons.Cloud, R.string.help_cf_title, R.string.help_cf_text),
            HelpSection(TablerIcons.RouteAltLeft, R.string.help_dc_title, R.string.help_dc_text),
            HelpSection(
                TablerIcons.AdjustmentsHorizontal,
                R.string.help_advanced_title,
                R.string.help_advanced_text,
            ),
            HelpSection(
                TablerIcons.BatteryEco,
                R.string.help_battery_title,
                R.string.help_battery_text,
            ),
            HelpSection(TablerIcons.Palette, R.string.help_theme_title, R.string.help_theme_text),
            HelpSection(TablerIcons.Receipt, R.string.help_log_title, R.string.help_log_text),
            HelpSection(
                TablerIcons.BatteryEco,
                R.string.help_background_title,
                R.string.help_background_text,
            ),
            HelpSection(
                TablerIcons.Lock,
                R.string.help_security_title,
                R.string.help_security_text,
            ),
            HelpSection(
                TablerIcons.DeviceMobileDown,
                R.string.help_updates_title,
                R.string.help_updates_text,
            ),
        )

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.help_title)) },
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
            LazyColumn(
                modifier = Modifier.widthIn(max = 640.dp).fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item(key = "intro") {
                    Text(
                        text = stringResource(R.string.help_intro),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
                    )
                }
                items(sections, key = { it.title }) { section ->
                    var expanded by rememberSaveable {
                        mutableStateOf(section.title == R.string.help_quick_start_title)
                    }
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(20.dp),
                        colors =
                            CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                            ),
                    ) {
                        Row(
                            modifier =
                                Modifier.fillMaxWidth()
                                    .clickable(
                                        role = Role.Button,
                                        onClickLabel =
                                            stringResource(
                                                if (expanded) R.string.help_collapse
                                                else R.string.help_expand
                                            ),
                                    ) {
                                        expanded = !expanded
                                    }
                                    .semantics { heading() }
                                    .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Surface(
                                modifier = Modifier.size(40.dp),
                                shape = RoundedCornerShape(14.dp),
                                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        section.icon,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(22.dp),
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = stringResource(section.title),
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Icon(
                                TablerIcons.ChevronDown,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp).rotate(if (expanded) 180f else 0f),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        AnimatedVisibility(visible = expanded) {
                            SelectionContainer {
                                Text(
                                    text = stringResource(section.text),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier =
                                        Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
