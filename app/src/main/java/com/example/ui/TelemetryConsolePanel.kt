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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
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
import com.example.DiagnosticsUiState
import com.example.LogCategory
import com.example.R
import com.example.TelemetryLogEntry
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

@Composable
fun TelemetryConsolePanel(
    state: DiagnosticsUiState,
    onRunStep1: () -> Unit,
    onRunStep2A: () -> Unit,
    onRunStep2B: () -> Unit,
    onRunStep3: () -> Unit,
    onExportReport: () -> Unit,
    onShareReport: () -> Unit,
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

    Surface(
        modifier = modifier.fillMaxSize(),
        color = ObsidianSurface
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Console Header Bar
            ConsoleHeaderBar(
                state = state,
                onToggleJsDrawer = onToggleJsDrawer,
                onClearLogs = onClearLogs,
                onToggleExpanded = onToggleExpanded
            )

            HorizontalDivider(color = ObsidianBorder, thickness = 1.dp)

            // Guided Diagnostic Wizard Action Rail
            DiagnosticWizardStrip(
                state = state,
                onRunStep1 = onRunStep1,
                onRunStep2A = onRunStep2A,
                onRunStep2B = onRunStep2B,
                onRunStep3 = onRunStep3,
                onExportReport = onExportReport,
                onShareReport = onShareReport
            )

            // Manual JavaScript Execution Drawer
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

            // Category Filter Chips
            LogFilterRow(
                selectedFilter = state.selectedFilter,
                assetCount = state.interceptedAssets.size,
                deltaCount = state.isolatedPaths.size,
                onSelectFilter = onSelectFilter
            )

            HorizontalDivider(color = ObsidianBorder.copy(alpha = 0.7f), thickness = 1.dp)

            // Live Scrolling Telemetry Log Console
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
}

@Composable
private fun ConsoleHeaderBar(
    state: DiagnosticsUiState,
    onToggleJsDrawer: () -> Unit,
    onClearLogs: () -> Unit,
    onToggleExpanded: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(ObsidianSurfaceElevated)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.weight(1f)
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(if (state.isLoading) AssetAmber else SignalEmerald)
            )
            Text(
                text = stringResource(R.string.console_title),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            state.estimatedFps?.let { fps ->
                MetricBadge(text = "${fps}fps", tint = SignalEmerald)
            }
            if (state.interceptedAssets.isNotEmpty()) {
                MetricBadge(text = "${state.interceptedAssets.size} assets", tint = AssetAmber)
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
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
                        text = stringResource(R.string.action_js_drawer),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (state.isJsDrawerOpen) ElectricCyan else MaterialTheme.colorScheme.onSurface
                    )
                }
            }

            IconButton(
                onClick = onClearLogs,
                modifier = Modifier
                    .size(40.dp)
                    .testTag("clear_logs_button")
            ) {
                Icon(
                    imageVector = Icons.Default.DeleteSweep,
                    contentDescription = stringResource(R.string.cd_clear_logs),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }

            IconButton(
                onClick = onToggleExpanded,
                modifier = Modifier
                    .size(40.dp)
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
}

@Composable
private fun DiagnosticWizardStrip(
    state: DiagnosticsUiState,
    onRunStep1: () -> Unit,
    onRunStep2A: () -> Unit,
    onRunStep2B: () -> Unit,
    onRunStep3: () -> Unit,
    onExportReport: () -> Unit,
    onShareReport: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(ObsidianSurface)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            WizardActionPill(
                label = stringResource(R.string.step_1_inspect_scope),
                icon = Icons.Default.Radar,
                accent = ElectricCyan,
                highlighted = false,
                testTag = "step_1_inspect_scope_button",
                onClick = onRunStep1
            )
            WizardActionPill(
                label = stringResource(R.string.step_2a_capture_baseline),
                icon = Icons.Default.TrackChanges,
                accent = SignalEmerald,
                highlighted = state.baselineCaptured,
                testTag = "step_2a_baseline_button",
                onClick = onRunStep2A
            )
            WizardActionPill(
                label = stringResource(R.string.step_2b_compute_delta),
                icon = Icons.Default.Timeline,
                accent = SignalEmerald,
                highlighted = state.isolatedPaths.isNotEmpty(),
                testTag = "step_2b_delta_button",
                onClick = onRunStep2B
            )
            WizardActionPill(
                label = stringResource(R.string.step_3_scan_entities),
                icon = Icons.Default.ViewInAr,
                accent = TelemetryViolet,
                highlighted = state.lastEntityCount > 0,
                testTag = "step_3_entities_button",
                onClick = onRunStep3
            )
            WizardActionPill(
                label = stringResource(R.string.step_5_export_report),
                icon = Icons.Default.Download,
                accent = AssetAmber,
                highlighted = state.lastExport?.success == true,
                isBusy = state.isExporting,
                testTag = "step_5_export_report_button",
                onClick = onExportReport
            )

            if (state.lastExport?.success == true && state.lastExport.contentUri != null) {
                OutlinedButton(
                    onClick = onShareReport,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, SignalEmerald),
                    modifier = Modifier
                        .height(38.dp)
                        .testTag("share_report_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Share,
                        contentDescription = stringResource(R.string.cd_share_report),
                        tint = SignalEmerald,
                        modifier = Modifier.size(15.dp)
                    )
                    Spacer(Modifier.width(5.dp))
                    Text(
                        text = stringResource(R.string.action_share_report),
                        style = MaterialTheme.typography.labelMedium,
                        color = SignalEmerald
                    )
                }
            }
        }

        // Step 2 Guidance Strip (always visible, pulses emerald once 2A baseline is armed)
        Surface(
            color = if (state.baselineCaptured) SignalEmerald.copy(alpha = 0.12f) else ObsidianSurfaceElevated,
            shape = RoundedCornerShape(6.dp),
            border = BorderStroke(
                width = 1.dp,
                color = if (state.baselineCaptured) SignalEmerald.copy(alpha = 0.45f) else ObsidianBorder.copy(alpha = 0.7f)
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = if (state.baselineCaptured) "2A ARMED (${state.baselineMetricCount})" else "STEP 2 GUIDE",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = if (state.baselineCaptured) SignalEmerald else ElectricCyan
                )
                Text(
                    text = stringResource(R.string.step_2_instruction),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun WizardActionPill(
    label: String,
    icon: ImageVector,
    accent: Color,
    highlighted: Boolean,
    testTag: String,
    isBusy: Boolean = false,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(8.dp),
        color = if (highlighted) accent.copy(alpha = 0.18f) else ObsidianSurfaceElevated,
        border = BorderStroke(
            width = 1.dp,
            color = if (highlighted) accent else accent.copy(alpha = 0.45f)
        ),
        modifier = Modifier
            .heightIn(min = 40.dp)
            .testTag(testTag)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (isBusy) {
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    strokeWidth = 2.dp,
                    color = accent
                )
            } else {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(16.dp)
                )
            }
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1
            )
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
            "Canvas count" to "document.querySelectorAll('canvas').length",
            "Window keys" to "Object.keys(window).slice(0, 25)",
            "Viewport" to "({w: innerWidth, h: innerHeight, dpr: devicePixelRatio})",
            "Isolated" to "window.__wvDiagState ? window.__wvDiagState.isolatedPaths : []"
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
                    "Switch back to All or trigger the corresponding diagnostic step above."
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
private fun MetricBadge(text: String, tint: Color) {
    Surface(
        color = tint.copy(alpha = 0.16f),
        shape = RoundedCornerShape(4.dp),
        border = BorderStroke(1.dp, tint.copy(alpha = 0.4f))
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = tint,
            fontSize = 9.sp,
            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
        )
    }
}

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
