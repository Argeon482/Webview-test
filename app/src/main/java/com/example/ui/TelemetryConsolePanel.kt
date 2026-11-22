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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.Search
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
import androidx.compose.runtime.remember
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
    onPauseStep1: () -> Unit,
    onResumeStep1: () -> Unit,
    onManualPathInputChanged: (String) -> Unit,
    onQueryManualPath: (String?) -> Unit,
    onRunStep2A: () -> Unit,
    onRunStep2B: () -> Unit,
    onRunStep3: () -> Unit,
    onExportReport: () -> Unit,
    onShareReport: () -> Unit,
    onExportLogDumpTxt: () -> Unit,
    onExportLogDumpJson: () -> Unit,
    onShareLogDump: () -> Unit,
    onSelectConsoleTab: (ConsoleTab) -> Unit,
    onSelectFilter: (LogCategory) -> Unit,
    onTogglePinCandidate: (String) -> Unit,
    onTogglePinnedOnly: () -> Unit,
    onStagingSearchQueryChanged: (String) -> Unit,
    onClearUnpinnedCandidates: () -> Unit,
    onLockCandidateForLivePolling: (String) -> Unit,
    onPinExternalPath: (path: String, sourceStep: String, detail: String) -> Unit,
    onToggleJsDrawer: () -> Unit,
    onManualJsChanged: (String) -> Unit,
    onExecuteManualJs: () -> Unit,
    onClearAssetCache: () -> Unit,
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
            ConsoleHeaderBar(
                state = state,
                onSelectTab = onSelectConsoleTab,
                onToggleJsDrawer = onToggleJsDrawer,
                onClearLogs = onClearLogs,
                onToggleExpanded = onToggleExpanded
            )

            HorizontalDivider(color = ObsidianBorder, thickness = 1.dp)

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
                        onPauseStep1 = onPauseStep1,
                        onResumeStep1 = onResumeStep1,
                        onManualPathInputChanged = onManualPathInputChanged,
                        onQueryManualPath = onQueryManualPath,
                        onRunStep2A = onRunStep2A,
                        onRunStep2B = onRunStep2B,
                        onRunStep3 = onRunStep3,
                        onExportReport = onExportReport,
                        onShareReport = onShareReport,
                        onOpenStaging = { onSelectConsoleTab(ConsoleTab.STAGING) },
                        onOpenEntities = { onSelectConsoleTab(ConsoleTab.ENTITIES) },
                        onOpenLogs = { onSelectConsoleTab(ConsoleTab.LOGS) },
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                    )
                }

                ConsoleTab.STAGING -> {
                    StagingBufferView(
                        state = state,
                        onTogglePin = onTogglePinCandidate,
                        onTogglePinnedOnly = onTogglePinnedOnly,
                        onSearchQueryChanged = onStagingSearchQueryChanged,
                        onClearUnpinned = onClearUnpinnedCandidates,
                        onTrackLive = onLockCandidateForLivePolling,
                        onPauseBatchScan = onPauseStep1,
                        onResumeBatchScan = onResumeStep1,
                        onCopyPath = onCopyPayload,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                    )
                }

                ConsoleTab.ENTITIES -> {
                    EntityInspectionConsoleView(
                        state = state,
                        onRescanEntities = onRunStep3,
                        onPinPath = { path, detail -> onPinExternalPath(path, "Step 3", detail) },
                        onCopyPayload = onCopyPayload,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                    )
                }

                ConsoleTab.ASSETS -> {
                    AssetMetadataInspectorView(
                        state = state,
                        onClearAssetCache = onClearAssetCache,
                        onExportAssetDumpJson = onExportLogDumpJson,
                        onCopyMetadata = onCopyPayload,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                    )
                }

                ConsoleTab.LOGS -> {
                    LiveLogConsoleView(
                        state = state,
                        onSelectFilter = onSelectFilter,
                        onExportLogDumpTxt = onExportLogDumpTxt,
                        onExportLogDumpJson = onExportLogDumpJson,
                        onShareLogDump = onShareLogDump,
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
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(ObsidianSurfaceElevated)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            // Horizontally scrollable 4-tab console switcher
            Row(
                modifier = Modifier
                    .weight(1f)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                HeaderTabPill(
                    label = stringResource(R.string.tab_wizard),
                    selected = state.activeConsoleTab == ConsoleTab.WIZARD,
                    accent = ElectricCyan,
                    testTag = "tab_wizard_button",
                    onClick = { onSelectTab(ConsoleTab.WIZARD) }
                )
                HeaderTabPill(
                    label = "${stringResource(R.string.tab_staging)} (${state.stagingBuffer.size})",
                    selected = state.activeConsoleTab == ConsoleTab.STAGING,
                    accent = AssetAmber,
                    testTag = "tab_staging_button",
                    onClick = { onSelectTab(ConsoleTab.STAGING) }
                )
                HeaderTabPill(
                    label = "${stringResource(R.string.tab_entities)} (${state.discoveredEntities.size})",
                    selected = state.activeConsoleTab == ConsoleTab.ENTITIES,
                    accent = TelemetryViolet,
                    testTag = "tab_entities_button",
                    onClick = { onSelectTab(ConsoleTab.ENTITIES) }
                )
                HeaderTabPill(
                    label = "${stringResource(R.string.tab_assets)} (${state.interceptedAssets.size})",
                    selected = state.activeConsoleTab == ConsoleTab.ASSETS,
                    accent = ElectricCyan,
                    testTag = "tab_assets_button",
                    onClick = { onSelectTab(ConsoleTab.ASSETS) }
                )
                HeaderTabPill(
                    label = "${stringResource(R.string.tab_logs)} (${state.logs.size})",
                    selected = state.activeConsoleTab == ConsoleTab.LOGS,
                    accent = SignalEmerald,
                    testTag = "tab_logs_button",
                    onClick = { onSelectTab(ConsoleTab.LOGS) }
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
                    .heightIn(min = 34.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .clickable(onClick = onToggleJsDrawer)
                    .testTag("toggle_js_drawer_button")
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Terminal,
                        contentDescription = stringResource(R.string.cd_toggle_js_drawer),
                        tint = if (state.isJsDrawerOpen) ElectricCyan else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(14.dp)
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
                        .size(34.dp)
                        .testTag("clear_logs_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.DeleteSweep,
                        contentDescription = stringResource(R.string.cd_clear_logs),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(17.dp)
                    )
                }
            }

            IconButton(
                onClick = onToggleExpanded,
                modifier = Modifier
                    .size(34.dp)
                    .testTag("toggle_split_button")
            ) {
                Icon(
                    imageVector = if (state.isConsoleExpanded) Icons.Default.ExpandMore else Icons.Default.ExpandLess,
                    contentDescription = stringResource(R.string.cd_toggle_split),
                    tint = ElectricCyan,
                    modifier = Modifier.size(19.dp)
                )
            }
        }

        // Slim live telemetry status strip
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(ObsidianBg.copy(alpha = 0.65f))
                .padding(horizontal = 8.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            AsyncScanStatusPill(
                status = state.scanStatus,
                detail = state.scanStatusDetail,
                modifier = Modifier.weight(1f)
            )
            if (state.livePollActive) {
                Surface(
                    color = SignalEmerald.copy(alpha = 0.16f),
                    shape = RoundedCornerShape(4.dp),
                    border = BorderStroke(1.dp, SignalEmerald.copy(alpha = 0.5f))
                ) {
                    Text(
                        text = "LIVE 200ms",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = SignalEmerald,
                        fontSize = 9.sp,
                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                    )
                }
            }
            if (state.pinnedCandidateCount > 0) {
                Surface(
                    color = AssetAmber.copy(alpha = 0.16f),
                    shape = RoundedCornerShape(4.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Bookmark,
                            contentDescription = null,
                            tint = AssetAmber,
                            modifier = Modifier.size(10.dp)
                        )
                        Text(
                            text = "${state.pinnedCandidateCount}",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = AssetAmber,
                            fontSize = 9.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HeaderTabPill(
    label: String,
    selected: Boolean,
    accent: Color,
    testTag: String,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(6.dp),
        color = if (selected) accent.copy(alpha = 0.2f) else ObsidianBg,
        border = BorderStroke(
            1.dp,
            if (selected) accent.copy(alpha = 0.7f) else ObsidianBorder
        ),
        modifier = Modifier.testTag(testTag)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            color = if (selected) accent else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)
        )
    }
}

@Composable
private fun GuidedFourStepWizard(
    state: DiagnosticsUiState,
    onRunStep1: () -> Unit,
    onPauseStep1: () -> Unit,
    onResumeStep1: () -> Unit,
    onManualPathInputChanged: (String) -> Unit,
    onQueryManualPath: (String?) -> Unit,
    onRunStep2A: () -> Unit,
    onRunStep2B: () -> Unit,
    onRunStep3: () -> Unit,
    onExportReport: () -> Unit,
    onShareReport: () -> Unit,
    onOpenStaging: () -> Unit,
    onOpenEntities: () -> Unit,
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
            // STEP 1 — Find Game Engine (Non-Blocking Yielding Batches + Auto-Roots + Manual Path Inspector)
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
                    isBusy = state.scopeBatchProgress.isActive && !state.scopeBatchProgress.isPaused,
                    testTag = "step_1_inspect_scope_button",
                    onClick = onRunStep1
                )
                if (state.scopeBatchProgress.isActive || state.scopeBatchProgress.isPaused) {
                    BatchScanProgressBanner(
                        progress = state.scopeBatchProgress,
                        onPause = onPauseStep1,
                        onResume = onResumeStep1,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                if (state.detectedGameNamespaces.isNotEmpty()) {
                    CapturedSnippetBox(
                        accent = SignalEmerald,
                        text = "Auto-detected roots: ${state.detectedGameNamespaces.joinToString(", ")}",
                        actionLabel = "View in Staging (${state.stagingBuffer.size}) ›",
                        onActionClick = onOpenStaging
                    )
                }
                Text(
                    text = stringResource(R.string.wizard_step_1_instruction),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // Step 1 Manual Property Inspector Drawer (for closure-scoped / custom paths)
                Step1ManualPropertyInspectorBox(
                    pathInput = state.manualPropertyPathInput,
                    lastResultSummary = state.lastManualQuerySummary,
                    onPathInputChanged = onManualPathInputChanged,
                    onQueryPath = onQueryManualPath,
                    onOpenStaging = onOpenStaging
                )

                if (state.step1Completed) {
                    val timing = state.step1DurationMs?.let { String.format(Locale.US, "%.0fms", it) } ?: ""
                    val preview = state.customGlobals.take(6).joinToString(", ")
                    CapturedSnippetBox(
                        accent = ElectricCyan,
                        text = "Found ${state.customGlobals.size} custom globals" +
                            (if (timing.isNotEmpty()) " ($timing)" else "") +
                            " • ${state.stagingBuffer.size} staged" +
                            if (preview.isNotEmpty()) "\nGlobals: $preview" else "",
                        actionLabel = "Search & Pin in Staging (${state.stagingBuffer.size}) ›",
                        onActionClick = onOpenStaging
                    )
                }
            }

            // STEP 2 — Lock On to Live Player Coordinates (200ms direct ref polling)
            WizardStepCard(
                title = stringResource(R.string.wizard_step_2_title),
                badge = state.step2Badge,
                accent = SignalEmerald
            ) {
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

                state.livePlayerCoords?.let { coords ->
                    LivePlayerCoordsReadout(
                        coords = coords,
                        propertyKeys = state.playerPropertyKeys,
                        tickCount = state.livePollTickCount,
                        isLive = state.livePollActive
                    )
                }
            }

            // STEP 3 — Visual Entity Inspection
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
                        text = "Array: ${state.discoveredEntityArrayPath} (${state.discoveredEntities.size} detected)\n$sampleLine",
                        actionLabel = "Open Entity Console (${state.discoveredEntities.size}) ›",
                        onActionClick = onOpenEntities
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
                        text = "Saved to ${state.lastExport.displayPath} • ${state.pinnedCandidateCount} pinned • ${state.discoveredEntities.size} entities"
                    )
                }
            }
        }

        // Latest Log Ticker Bar
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
                    text = "Logs ›",
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
private fun LivePlayerCoordsReadout(
    coords: PlayerCoordinates,
    propertyKeys: String,
    tickCount: Int,
    isLive: Boolean
) {
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
                    text = if (isLive) "LIVE 200ms (#$tickCount)" else "PLAYER LOCK",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = SignalEmerald
                )
                Text(
                    text = coords.path + if (propertyKeys.isNotBlank()) " [$propertyKeys]" else "",
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
private fun CapturedSnippetBox(
    accent: Color,
    text: String,
    actionLabel: String? = null,
    onActionClick: (() -> Unit)? = null
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(ObsidianBg)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = JetBrainsMonoFontFamily,
            color = accent,
            lineHeight = 15.sp
        )
        if (actionLabel != null && onActionClick != null) {
            Text(
                text = actionLabel,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .clickable(onClick = onActionClick)
                    .padding(vertical = 2.dp)
            )
        }
    }
}

@Composable
private fun Step1ManualPropertyInspectorBox(
    pathInput: String,
    lastResultSummary: String,
    onPathInputChanged: (String) -> Unit,
    onQueryPath: (String?) -> Unit,
    onOpenStaging: () -> Unit
) {
    val quickRoots = remember {
        listOf("game.player", "window.game", "window.app", "window.Module", "window.PIXI")
    }

    Surface(
        color = ObsidianBg,
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, ElectricCyan.copy(alpha = 0.35f)),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("step_1_manual_property_inspector")
    ) {
        Column(
            modifier = Modifier.padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Search,
                    contentDescription = null,
                    tint = ElectricCyan,
                    modifier = Modifier.size(15.dp)
                )
                Text(
                    text = stringResource(R.string.step_1_manual_query_title),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = ElectricCyan
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                OutlinedTextField(
                    value = pathInput,
                    onValueChange = onPathInputChanged,
                    singleLine = true,
                    placeholder = {
                        Text(
                            text = stringResource(R.string.step_1_manual_query_hint),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = JetBrainsMonoFontFamily,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    textStyle = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = JetBrainsMonoFontFamily,
                        color = MaterialTheme.colorScheme.onSurface
                    ),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { onQueryPath(null) }),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = ElectricCyan,
                        unfocusedBorderColor = ObsidianBorder,
                        focusedContainerColor = ObsidianSurface,
                        unfocusedContainerColor = ObsidianSurface
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("manual_path_query_input")
                )

                Button(
                    onClick = { onQueryPath(null) },
                    enabled = pathInput.isNotBlank(),
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = ElectricCyan,
                        contentColor = ObsidianBg,
                        disabledContainerColor = ObsidianSurfaceElevated,
                        disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant
                    ),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag("manual_path_query_button")
                ) {
                    Text(
                        text = stringResource(R.string.action_query_path),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                quickRoots.forEach { samplePath ->
                    Surface(
                        onClick = { onQueryPath(samplePath) },
                        shape = RoundedCornerShape(5.dp),
                        color = ObsidianSurfaceElevated,
                        border = BorderStroke(1.dp, ObsidianBorder)
                    ) {
                        Text(
                            text = samplePath,
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = JetBrainsMonoFontFamily,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 10.sp,
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp)
                        )
                    }
                }
            }

            if (lastResultSummary.isNotBlank()) {
                val isFound = lastResultSummary.startsWith("Staged")
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = lastResultSummary,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = JetBrainsMonoFontFamily,
                        color = if (isFound) SignalEmerald else AssetAmber,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    if (isFound) {
                        Text(
                            text = "Staging ›",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = ElectricCyan,
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .clickable(onClick = onOpenStaging)
                                .padding(horizontal = 4.dp, vertical = 2.dp)
                        )
                    }
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
            "Live ref" to "window.__wvDiagState && window.__wvDiagState.liveRef ? ({path: window.__wvDiagState.liveRef.path, keys: [window.__wvDiagState.liveRef.xKey, window.__wvDiagState.liveRef.yKey, window.__wvDiagState.liveRef.zKey]}) : null",
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
private fun AsyncScanStatusPill(
    status: AsyncScanStatus,
    detail: String,
    modifier: Modifier = Modifier
) {
    val accent = when (status) {
        AsyncScanStatus.IDLE -> MaterialTheme.colorScheme.onSurfaceVariant
        AsyncScanStatus.RUNNING -> ElectricCyan
        AsyncScanStatus.PAUSED -> AssetAmber
        AsyncScanStatus.COMPLETED -> SignalEmerald
        AsyncScanStatus.ERROR -> AlertCoral
    }
    val statusLabel = when (status) {
        AsyncScanStatus.IDLE -> stringResource(R.string.scan_status_idle)
        AsyncScanStatus.RUNNING -> stringResource(R.string.scan_status_running)
        AsyncScanStatus.PAUSED -> stringResource(R.string.scan_status_paused)
        AsyncScanStatus.COMPLETED -> stringResource(R.string.scan_status_completed)
        AsyncScanStatus.ERROR -> stringResource(R.string.scan_status_error)
    }
    val displayText = if (detail.isNotBlank() && status != AsyncScanStatus.IDLE) {
        detail
    } else {
        "Scan: $statusLabel"
    }

    Row(
        modifier = modifier.testTag("async_scan_status_pill"),
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

private fun fmtCoord(value: Double): String = String.format(Locale.US, "%.2f", value)
