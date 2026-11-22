package com.example.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material.icons.filled.TrackChanges
import androidx.compose.material.icons.filled.ViewInAr
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.AsyncScanStatus
import com.example.ConsoleTab
import com.example.DiagnosticsUiState
import com.example.LogCategory
import com.example.PlayerCoordinates
import com.example.R
import com.example.TelemetryLogEntry
import com.example.WizardStepBadge
import com.example.ui.theme.AlertCoral
import com.example.ui.theme.AssetAmber
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.ObsidianBg
import com.example.ui.theme.ObsidianBorder
import com.example.ui.theme.ObsidianSurface
import com.example.ui.theme.ObsidianSurfaceElevated
import com.example.ui.theme.ObsidianSurfaceHighest
import com.example.ui.theme.SignalEmerald
import com.example.ui.theme.TelemetryViolet
import java.util.Locale

@Composable
fun TelemetryConsolePanel(
    state: DiagnosticsUiState,
    onRunStep1: () -> Unit,
    onRunStep2A: () -> Unit,
    onRunStep2B: () -> Unit,
    onRunStep3: () -> Unit,
    onExportReport: () -> Unit,
    onShareReport: () -> Unit,
    onSelectConsoleTab: (ConsoleTab) -> Unit,
    onSelectFilter: (LogCategory) -> Unit,
    onToggleJsDrawer: () -> Unit,
    onManualJsChanged: (String) -> Unit,
    onExecuteManualJs: () -> Unit,
    onClearLogs: () -> Unit,
    onToggleExpanded: () -> Unit,
    onCopyPayload: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    if (state.isJsDrawerOpen) {
        BackHandler { onToggleJsDrawer() }
    }

    Surface(
        modifier = modifier.fillMaxSize(),
        color = ObsidianSurface
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Top Header: Wizard / Logs Tab Switcher + Live Async Scan Status + JS Console + Expand
            ConsoleHeaderBar(
                state = state,
                onSelectTab = onSelectConsoleTab,
                onToggleJsDrawer = onToggleJsDrawer,
                onClearLogs = onClearLogs,
                onToggleExpanded = onToggleExpanded
            )

            HorizontalDivider(color = ObsidianBorder, thickness = 1.dp)

            // Manual JavaScript Execution Drawer (available over either tab)
            AnimatedVisibility(
                visible = state.isJsDrawerOpen,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                ManualJsExecutionDrawer(
                    script = state.manualJsInput,
                    onScriptChanged = onManualJsChanged,
                    onExecute = onExecuteManualJs,
                    onClose = onToggleJsDrawer
                )
            }

            when (state.activeConsoleTab) {
                ConsoleTab.WIZARD -> {
                    GuidedFourStepWizard(
                        state = state,
                        onRunStep1 = onRunStep1,
                        onRunStep2A = onRunStep2A,
                        onRunStep2B = onRunStep2B,
                        onRunStep3 = onRunStep3,
                        onExportReport = onExportReport,
                        onShareReport = onShareReport,
                        onOpenLogs = { onSelectConsoleTab(ConsoleTab.LOGS) },
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                    )
                }
                ConsoleTab.LOGS -> {
                    LiveLogConsoleView(
                        state = state,
                        onSelectFilter = onSelectFilter,
                        onCopyPayload = onCopyPayload,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                    )
                }
            }
        }
    }
}

@Composable
private fun ConsoleHeaderBar(
    state: DiagnosticsUiState,
    onSelectTab: (ConsoleTab) -> Unit,
    onToggleJsDrawer: () -> Unit,
    onClearLogs: () -> Unit,
    onToggleExpanded: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(ObsidianSurfaceElevated)
            .padding(horizontal = 8.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        // Segmented Wizard / Logs Switcher
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(ObsidianBg)
                .padding(2.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            HeaderTabPill(
                label = stringResource(R.string.tab_wizard),
                selected = state.activeConsoleTab == ConsoleTab.WIZARD,
                testTag = "tab_wizard_button",
                onClick = { onSelectTab(ConsoleTab.WIZARD) }
            )
            HeaderTabPill(
                label = "${stringResource(R.string.tab_logs)} (${state.logs.size})",
                selected = state.activeConsoleTab == ConsoleTab.LOGS,
                testTag = "tab_logs_button",
                onClick = { onSelectTab(ConsoleTab.LOGS) }
            )
        }

        // Compact Async Scan Status Pill
        Box(modifier = Modifier.weight(1f)) {
            AsyncScanStatusPill(
                status = state.scanStatus,
                detail = state.scanStatusDetail
            )
        }

        // JS Console Toggle
        Surface(
            shape = RoundedCornerShape(6.dp),
            color = if (state.isJsDrawerOpen) MaterialTheme.colorScheme.primaryContainer else ObsidianSurfaceHighest,
            border = BorderStroke(
                1.dp,
                if (state.isJsDrawerOpen) ElectricCyan else ObsidianBorder
            ),
            modifier = Modifier
                .heightIn(min = 36.dp)
                .clip(RoundedCornerShape(6.dp))
                .clickable(onClick = onToggleJsDrawer)
                .testTag("toggle_js_drawer_button")
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Terminal,
                    contentDescription = stringResource(R.string.cd_toggle_js_drawer),
                    tint = if (state.isJsDrawerOpen) ElectricCyan else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(15.dp)
                )
                Text(
                    text = "JS",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = if (state.isJsDrawerOpen) ElectricCyan else MaterialTheme.colorScheme.onSurface
                )
            }
        }

        if (state.activeConsoleTab == ConsoleTab.LOGS) {
            IconButton(
                onClick = onClearLogs,
                modifier = Modifier
                    .size(36.dp)
                    .testTag("clear_logs_button")
            ) {
                Icon(
                    imageVector = Icons.Default.DeleteSweep,
                    contentDescription = stringResource(R.string.cd_clear_logs),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }
        }

        IconButton(
            onClick = onToggleExpanded,
            modifier = Modifier
                .size(36.dp)
                .testTag("toggle_split_button")
        ) {
            Icon(
                imageVector = if (state.isConsoleExpanded) Icons.Default.ExpandMore else Icons.Default.ExpandLess,
                contentDescription = stringResource(R.string.cd_toggle_split),
                tint = ElectricCyan,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
private fun HeaderTabPill(
    label: String,
    selected: Boolean,
    testTag: String,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(6.dp),
        color = if (selected) ElectricCyan.copy(alpha = 0.2f) else Color.Transparent,
        border = if (selected) BorderStroke(1.dp, ElectricCyan.copy(alpha = 0.6f)) else null,
        modifier = Modifier.testTag(testTag)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            color = if (selected) ElectricCyan else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp)
        )
    }
}

@Composable
private fun GuidedFourStepWizard(
    state: DiagnosticsUiState,
    onRunStep1: () -> Unit,
    onRunStep2A: () -> Unit,
    onRunStep2B: () -> Unit,
    onRunStep3: () -> Unit,
    onExportReport: () -> Unit,
    onShareReport: () -> Unit,
    onOpenLogs: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.background(ObsidianBg)) {
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // STEP 1 — Find Game Engine
            WizardStepCard(
                title = stringResource(R.string.wizard_step_1_title),
                badge = state.step1Badge,
                accent = ElectricCyan
            ) {
                StepActionButton(
                    label = stringResource(R.string.step_1_inspect_scope),
                    icon = Icons.Default.Radar,
                    accent = ElectricCyan,
                    enabled = true,
                    testTag = "step_1_inspect_scope_button",
                    onClick = onRunStep1
                )
                Text(
                    text = stringResource(R.string.wizard_step_1_instruction),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (state.step1Completed) {
                    val timing = state.step1DurationMs?.let { String.format(Locale.US, "%.2fms", it) } ?: ""
                    val preview = state.customGlobals.take(6).joinToString(", ")
                    CapturedSnippetBox(
                        accent = ElectricCyan,
                        text = "Found ${state.customGlobals.size} custom globals ($timing) • ${state.canvasCount} canvas(es)" +
                            if (preview.isNotEmpty()) "\nGlobals: $preview" else ""
                    )
                }
            }

            // STEP 2 — Lock On to Player Coordinates
            WizardStepCard(
                title = stringResource(R.string.wizard_step_2_title),
                badge = state.step2Badge,
                accent = SignalEmerald
            ) {
                // Button 2A + plain-English instruction
                StepActionButton(
                    label = stringResource(R.string.step_2a_capture_baseline),
                    icon = Icons.Default.TrackChanges,
                    accent = SignalEmerald,
                    enabled = true,
                    filled = state.baselineCaptured,
                    testTag = "step_2a_baseline_button",
                    onClick = onRunStep2A
                )
                Text(
                    text = stringResource(R.string.wizard_step_2a_instruction),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(Modifier.height(2.dp))

                // Button 2B + plain-English instruction
                StepActionButton(
                    label = stringResource(R.string.step_2b_compute_delta),
                    icon = Icons.Default.Timeline,
                    accent = SignalEmerald,
                    enabled = true,
                    filled = state.livePlayerCoords != null,
                    testTag = "step_2b_delta_button",
                    onClick = onRunStep2B
                )
                Text(
                    text = stringResource(R.string.wizard_step_2b_instruction),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // Live Real-Time X, Y, Z Readout once isolated
                state.livePlayerCoords?.let { coords ->
                    LivePlayerCoordsReadout(coords = coords)
                }
            }

            // STEP 3 — Find Nearby Monsters / Entities
            WizardStepCard(
                title = stringResource(R.string.wizard_step_3_title),
                badge = state.step3Badge,
                accent = TelemetryViolet
            ) {
                val canRunStep3 = !state.discoveredPlayerPath.isNullOrBlank()
                StepActionButton(
                    label = stringResource(R.string.step_3_scan_entities),
                    icon = Icons.Default.ViewInAr,
                    accent = TelemetryViolet,
                    enabled = canRunStep3,
                    filled = state.discoveredEntities.isNotEmpty(),
                    testTag = "step_3_entities_button",
                    onClick = onRunStep3
                )
                Text(
                    text = stringResource(R.string.wizard_step_3_instruction),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (!state.discoveredEntityArrayPath.isNullOrBlank() && state.discoveredEntities.isNotEmpty()) {
                    val sampleLine = state.discoveredEntities.take(2).joinToString("\n") { e ->
                        "• ${e.name} world(${fmtCoord(e.worldX)}, ${fmtCoord(e.worldY)}, ${fmtCoord(e.worldZ)}) -> screen(${fmtCoord(e.screenX)}, ${fmtCoord(e.screenY)})"
                    }
                    CapturedSnippetBox(
                        accent = TelemetryViolet,
                        text = "Array: ${state.discoveredEntityArrayPath} (${state.discoveredEntities.size} entities)\n$sampleLine"
                    )
                }
            }

            // STEP 4 — Export Handoff File
            WizardStepCard(
                title = stringResource(R.string.wizard_step_4_title),
                badge = state.step4Badge,
                accent = AssetAmber
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    StepActionButton(
                        label = stringResource(R.string.step_4_export_report),
                        icon = Icons.Default.Download,
                        accent = AssetAmber,
                        enabled = !state.isExporting,
                        isBusy = state.isExporting,
                        filled = state.step4Badge == WizardStepBadge.READY || state.step4Badge == WizardStepBadge.CAPTURED,
                        testTag = "step_5_export_report_button",
                        onClick = onExportReport,
                        modifier = Modifier.weight(1f)
                    )

                    if (state.lastExport?.success == true && state.lastExport.contentUri != null) {
                        OutlinedButton(
                            onClick = onShareReport,
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(1.dp, SignalEmerald),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .testTag("share_report_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Share,
                                contentDescription = stringResource(R.string.cd_share_report),
                                tint = SignalEmerald,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                text = stringResource(R.string.action_share_report),
                                style = MaterialTheme.typography.labelMedium,
                                color = SignalEmerald
                            )
                        }
                    }
                }

                Text(
                    text = stringResource(R.string.wizard_step_4_instruction),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (state.lastExport?.success == true) {
                    CapturedSnippetBox(
                        accent = SignalEmerald,
                        text = "Saved to ${state.lastExport.displayPath} • ${state.interceptedAssets.size} asset URLs included"
                    )
                }
            }
        }

        // Latest Log Ticker Bar (tap to jump straight to Live Logs)
        val latest = state.logs.lastOrNull()
        if (latest != null) {
            HorizontalDivider(color = ObsidianBorder, thickness = 1.dp)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(ObsidianSurface)
                    .clickable(onClick = onOpenLogs)
                    .padding(horizontal = 10.dp, vertical = 6.dp)
                    .testTag("latest_log_ticker"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                val accent = categoryAccentColor(latest.category)
                Surface(
                    color = accent.copy(alpha = 0.2f),
                    shape = RoundedCornerShape(4.dp)
                ) {
                    Text(
                        text = latest.category.tag,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = accent,
                        fontSize = 9.sp,
                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                    )
                }
                Text(
                    text = latest.summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = "View Logs ›",
                    style = MaterialTheme.typography.labelSmall,
                    color = ElectricCyan
                )
            }
        }
    }
}

@Composable
private fun WizardStepCard(
    title: String,
    badge: WizardStepBadge,
    accent: Color,
    content: @Composable () -> Unit
) {
    val borderColor = when (badge) {
        WizardStepBadge.CAPTURED -> SignalEmerald.copy(alpha = 0.55f)
        WizardStepBadge.READY -> accent.copy(alpha = 0.45f)
        WizardStepBadge.PENDING -> ObsidianBorder
    }

    Surface(
        shape = RoundedCornerShape(10.dp),
        color = ObsidianSurface,
        border = BorderStroke(1.dp, borderColor),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                StepStatusBadgeChip(badge = badge)
            }
            content()
        }
    }
}

@Composable
private fun StepStatusBadgeChip(badge: WizardStepBadge) {
    val (bg, fg) = when (badge) {
        WizardStepBadge.CAPTURED -> SignalEmerald.copy(alpha = 0.18f) to SignalEmerald
        WizardStepBadge.READY -> ElectricCyan.copy(alpha = 0.18f) to ElectricCyan
        WizardStepBadge.PENDING -> ObsidianSurfaceElevated to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        color = bg,
        shape = RoundedCornerShape(5.dp),
        border = BorderStroke(1.dp, fg.copy(alpha = 0.45f)),
        modifier = Modifier.testTag("badge_${badge.name.lowercase()}")
    ) {
        Text(
            text = badge.displayText,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = fg,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}

@Composable
private fun StepActionButton(
    label: String,
    icon: ImageVector,
    accent: Color,
    enabled: Boolean,
    testTag: String,
    filled: Boolean = false,
    isBusy: Boolean = false,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(8.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (filled) accent else accent.copy(alpha = 0.18f),
            contentColor = if (filled) ObsidianBg else MaterialTheme.colorScheme.onSurface,
            disabledContainerColor = ObsidianSurfaceElevated,
            disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
        ),
        border = BorderStroke(
            1.dp,
            if (enabled) accent.copy(alpha = if (filled) 1f else 0.65f) else ObsidianBorder
        ),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .testTag(testTag)
    ) {
        if (isBusy) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                strokeWidth = 2.dp,
                color = if (filled) ObsidianBg else accent
            )
        } else {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = when {
                    !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
                    filled -> ObsidianBg
                    else -> accent
                },
                modifier = Modifier.size(18.dp)
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun LivePlayerCoordsReadout(coords: PlayerCoordinates) {
    Surface(
        color = SignalEmerald.copy(alpha = 0.12f),
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, SignalEmerald.copy(alpha = 0.55f)),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("live_player_coords_readout")
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.MyLocation,
                    contentDescription = null,
                    tint = SignalEmerald,
                    modifier = Modifier.size(15.dp)
                )
                Text(
                    text = "LIVE PLAYER LOCK",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = SignalEmerald
                )
                Text(
                    text = coords.path,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = JetBrainsMonoFontFamily,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                CoordAxisTile(axis = "X", value = coords.x, modifier = Modifier.weight(1f))
                CoordAxisTile(axis = "Y", value = coords.y, modifier = Modifier.weight(1f))
                CoordAxisTile(axis = "Z", value = coords.z, modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun CoordAxisTile(
    axis: String,
    value: Double,
    modifier: Modifier = Modifier
) {
    Surface(
        color = ObsidianBg,
        shape = RoundedCornerShape(6.dp),
        border = BorderStroke(1.dp, SignalEmerald.copy(alpha = 0.35f)),
        modifier = modifier
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = axis,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = SignalEmerald
            )
            Text(
                text = fmtCoord(value),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = JetBrainsMonoFontFamily,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
private fun CapturedSnippetBox(accent: Color, text: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(ObsidianBg)
            .padding(horizontal = 8.dp, vertical = 6.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = JetBrainsMonoFontFamily,
            color = accent,
            lineHeight = 15.sp
        )
    }
}

@Composable
private fun LiveLogConsoleView(
    state: DiagnosticsUiState,
    onSelectFilter: (LogCategory) -> Unit,
    onCopyPayload: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val filteredLogs = remember(state.logs, state.selectedFilter) {
        if (state.selectedFilter == LogCategory.ALL) state.logs
        else state.logs.filter { it.category == state.selectedFilter }
    }

    val listState = rememberLazyListState()
    LaunchedEffect(filteredLogs.size) {
        if (filteredLogs.isNotEmpty()) {
            listState.animateScrollToItem(filteredLogs.lastIndex)
        }
    }

    Column(modifier = modifier) {
        LogFilterRow(
            selectedFilter = state.selectedFilter,
            assetCount = state.interceptedAssets.size,
            deltaCount = state.isolatedPaths.size,
            onSelectFilter = onSelectFilter
        )
        HorizontalDivider(color = ObsidianBorder.copy(alpha = 0.7f), thickness = 1.dp)

        if (filteredLogs.isEmpty()) {
            ConsoleEmptyState(
                hasAnyLogs = state.logs.isNotEmpty(),
                selectedFilter = state.selectedFilter,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            )
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(ObsidianBg)
                    .testTag("telemetry_log_list"),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                items(filteredLogs, key = { it.id }) { entry ->
                    TelemetryLogRow(
                        entry = entry,
                        onCopyPayload = onCopyPayload
                    )
                }
            }
        }
    }
}

@Composable
private fun ManualJsExecutionDrawer(
    script: String,
    onScriptChanged: (String) -> Unit,
    onExecute: () -> Unit,
    onClose: () -> Unit
) {
    val quickProbes = remember {
        listOf(
            "Player path" to "window.__wvDiagState ? window.__wvDiagState.discoveredPlayerPath : null",
            "Entity array" to "window.__wvDiagState ? window.__wvDiagState.discoveredEntityArrayPath : null",
            "Custom globals" to "window.__wvDiagState ? window.__wvDiagState.customGlobals : []",
            "Viewport" to "({w: innerWidth, h: innerHeight, dpr: devicePixelRatio})"
        )
    }

    Surface(
        color = ObsidianSurfaceElevated,
        border = BorderStroke(1.dp, ElectricCyan.copy(alpha = 0.4f)),
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .testTag("manual_js_drawer")
    ) {
        Column(
            modifier = Modifier.padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Code,
                        contentDescription = null,
                        tint = ElectricCyan,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = "Manual JavaScript Evaluator",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = ElectricCyan
                    )
                }
                IconButton(
                    onClick = onClose,
                    modifier = Modifier
                        .size(32.dp)
                        .testTag("close_js_drawer_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = stringResource(R.string.cd_close_drawer),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                quickProbes.forEach { (title, snippet) ->
                    Surface(
                        onClick = { onScriptChanged(snippet) },
                        shape = RoundedCornerShape(6.dp),
                        color = ObsidianBg,
                        border = BorderStroke(1.dp, ObsidianBorder)
                    ) {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                OutlinedTextField(
                    value = script,
                    onValueChange = onScriptChanged,
                    placeholder = {
                        Text(
                            text = stringResource(R.string.js_input_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    },
                    textStyle = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = JetBrainsMonoFontFamily,
                        color = MaterialTheme.colorScheme.onSurface
                    ),
                    maxLines = 3,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { onExecute() }),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = ElectricCyan,
                        unfocusedBorderColor = ObsidianBorder,
                        focusedContainerColor = ObsidianBg,
                        unfocusedContainerColor = ObsidianBg
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("manual_js_input")
                )

                Button(
                    onClick = onExecute,
                    enabled = script.isNotBlank(),
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = ElectricCyan,
                        contentColor = ObsidianBg
                    ),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag("execute_script_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = stringResource(R.string.action_execute_script),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@Composable
private fun LogFilterRow(
    selectedFilter: LogCategory,
    assetCount: Int,
    deltaCount: Int,
    onSelectFilter: (LogCategory) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(ObsidianSurface)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        LogCategory.entries.forEach { category ->
            val selected = category == selectedFilter
            val accent = categoryAccentColor(category)
            val suffix = when (category) {
                LogCategory.ASSET -> if (assetCount > 0) " ($assetCount)" else ""
                LogCategory.DELTA -> if (deltaCount > 0) " ($deltaCount)" else ""
                else -> ""
            }
            Surface(
                onClick = { onSelectFilter(category) },
                shape = RoundedCornerShape(6.dp),
                color = if (selected) accent.copy(alpha = 0.22f) else ObsidianBg,
                border = BorderStroke(
                    width = 1.dp,
                    color = if (selected) accent else ObsidianBorder
                ),
                modifier = Modifier.testTag("filter_chip_${category.tag.lowercase()}")
            ) {
                Text(
                    text = "${category.label}$suffix",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                    color = if (selected) accent else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
        }
    }
}

@Composable
private fun TelemetryLogRow(
    entry: TelemetryLogEntry,
    onCopyPayload: (String) -> Unit
) {
    var expanded by remember(entry.id) { mutableStateOf(false) }
    val accent = categoryAccentColor(entry.category)
    val hasRichPayload = entry.payload.isNotBlank() && entry.payload != entry.summary

    Surface(
        shape = RoundedCornerShape(6.dp),
        color = ObsidianSurface,
        border = BorderStroke(
            width = 1.dp,
            color = if (expanded) accent.copy(alpha = 0.5f) else ObsidianBorder.copy(alpha = 0.55f)
        ),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = hasRichPayload) { expanded = !expanded }
            .testTag("log_entry_${entry.id}")
    ) {
        Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = entry.timestamp,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 9.sp
                )
                Surface(
                    color = accent.copy(alpha = 0.18f),
                    shape = RoundedCornerShape(4.dp)
                ) {
                    Text(
                        text = entry.category.tag,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = accent,
                        fontSize = 9.sp,
                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                    )
                }
                Text(
                    text = entry.summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = if (expanded) Int.MAX_VALUE else 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (hasRichPayload) {
                    IconButton(
                        onClick = { onCopyPayload(entry.payload) },
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.ContentCopy,
                            contentDescription = stringResource(R.string.cd_copy_log),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
            }

            if (expanded && hasRichPayload) {
                Spacer(Modifier.height(4.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(4.dp))
                        .background(ObsidianBg)
                        .padding(6.dp)
                ) {
                    Text(
                        text = entry.payload,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = JetBrainsMonoFontFamily,
                        color = accent.copy(alpha = 0.92f),
                        fontSize = 10.sp,
                        lineHeight = 14.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun ConsoleEmptyState(
    hasAnyLogs: Boolean,
    selectedFilter: LogCategory,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .background(ObsidianBg)
            .padding(16.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Radar,
                contentDescription = null,
                tint = ElectricCyan.copy(alpha = 0.7f),
                modifier = Modifier.size(28.dp)
            )
            Text(
                text = if (hasAnyLogs) "No ${selectedFilter.label} events yet" else stringResource(R.string.console_empty_title),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = if (hasAnyLogs) {
                    "Switch back to All or run the corresponding Wizard step."
                } else {
                    stringResource(R.string.console_empty_subtitle)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3
            )
        }
    }
}

@Composable
private fun AsyncScanStatusPill(
    status: AsyncScanStatus,
    detail: String
) {
    val accent = when (status) {
        AsyncScanStatus.IDLE -> MaterialTheme.colorScheme.onSurfaceVariant
        AsyncScanStatus.RUNNING -> ElectricCyan
        AsyncScanStatus.COMPLETED -> SignalEmerald
        AsyncScanStatus.ERROR -> AlertCoral
    }
    val statusLabel = when (status) {
        AsyncScanStatus.IDLE -> stringResource(R.string.scan_status_idle)
        AsyncScanStatus.RUNNING -> stringResource(R.string.scan_status_running)
        AsyncScanStatus.COMPLETED -> stringResource(R.string.scan_status_completed)
        AsyncScanStatus.ERROR -> stringResource(R.string.scan_status_error)
    }
    val displayText = if (detail.isNotBlank() && status != AsyncScanStatus.IDLE) {
        detail
    } else {
        statusLabel
    }

    Surface(
        color = accent.copy(alpha = if (status == AsyncScanStatus.IDLE) 0.12f else 0.18f),
        shape = RoundedCornerShape(6.dp),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.45f)),
        modifier = Modifier.testTag("async_scan_status_pill")
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            if (status == AsyncScanStatus.RUNNING) {
                CircularProgressIndicator(
                    modifier = Modifier.size(10.dp),
                    strokeWidth = 1.6.dp,
                    color = accent
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(accent)
                )
            }
            Text(
                text = displayText,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = accent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

private fun fmtCoord(value: Double): String = String.format(Locale.US, "%.2f", value)

private fun categoryAccentColor(category: LogCategory): Color = when (category) {
    LogCategory.ALL -> ElectricCyan
    LogCategory.SCOPE -> ElectricCyan
    LogCategory.DELTA -> SignalEmerald
    LogCategory.ENTITY -> TelemetryViolet
    LogCategory.ASSET -> AssetAmber
    LogCategory.JS -> Color(0xFF60A5FA)
    LogCategory.SYSTEM -> Color(0xFF94A3B8)
    LogCategory.ERROR -> AlertCoral
}
