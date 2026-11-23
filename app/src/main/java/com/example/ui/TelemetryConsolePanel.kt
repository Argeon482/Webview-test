package com.example.ui

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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.Share
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
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ConsoleTab
import com.example.DiagnosticsUiState
import com.example.LogCategory
import com.example.R
import com.example.TelemetryLogEntry
import com.example.ui.theme.AssetAmber
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.ObsidianBg
import com.example.ui.theme.ObsidianBorder
import com.example.ui.theme.ObsidianSurface
import com.example.ui.theme.ObsidianSurfaceElevated
import com.example.ui.theme.OnElectricCyan
import com.example.ui.theme.OnSignalEmerald
import com.example.ui.theme.SignalEmerald
import com.example.ui.theme.TelemetryViolet
import java.util.Locale

/**
 * Primary action deck providing one-tap access to Step 3 Auto-Relock, Direct 200ms Feed start/pause,
 * Nearby Entity Struct Scan, and JSON Diagnostic Report Export / Share.
 */
@Composable
fun PrimaryActionButtonsBar(
    state: DiagnosticsUiState,
    onAutoRelock: () -> Unit,
    onToggleAutoRelockOnReload: () -> Unit,
    onStartFeed: () -> Unit,
    onPauseFeed: () -> Unit,
    onScanEntities: () -> Unit,
    onExportReport: () -> Unit,
    onShareLastReport: () -> Unit,
    modifier: Modifier = Modifier
) {
    val feed = state.heapFeed

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = ObsidianSurface,
        border = BorderStroke(1.dp, ObsidianBorder),
        modifier = modifier
            .fillMaxWidth()
            .testTag("primary_action_buttons_bar")
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Row 1: Auto-Relock + Start/Pause 200ms Feed
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onAutoRelock,
                    enabled = !feed.isRelocking,
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = ElectricCyan,
                        contentColor = OnElectricCyan
                    ),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .testTag("auto_relock_button")
                ) {
                    if (feed.isRelocking) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = OnElectricCyan
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.GpsFixed,
                            contentDescription = null,
                            modifier = Modifier.size(17.dp)
                        )
                    }
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = stringResource(R.string.action_auto_relock),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                if (feed.isPolling) {
                    OutlinedButton(
                        onClick = onPauseFeed,
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, AssetAmber.copy(alpha = 0.75f)),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp)
                            .testTag("pause_feed_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Pause,
                            contentDescription = null,
                            tint = AssetAmber,
                            modifier = Modifier.size(17.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = stringResource(R.string.action_pause_feed),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = AssetAmber,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                } else {
                    Button(
                        onClick = onStartFeed,
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = SignalEmerald,
                            contentColor = OnSignalEmerald
                        ),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp)
                            .testTag("start_feed_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = null,
                            modifier = Modifier.size(17.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = stringResource(R.string.action_start_feed),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            // Row 2: Scan Entities + Export JSON Report (+ Share icon when exported)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    onClick = onScanEntities,
                    enabled = !state.isScanningEntities,
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, TelemetryViolet.copy(alpha = 0.75f)),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .testTag("scan_entities_button")
                ) {
                    if (state.isScanningEntities) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = TelemetryViolet
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.Radar,
                            contentDescription = null,
                            tint = TelemetryViolet,
                            modifier = Modifier.size(17.dp)
                        )
                    }
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = stringResource(R.string.action_scan_entities),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = TelemetryViolet,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                OutlinedButton(
                    onClick = onExportReport,
                    enabled = !state.isExporting,
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, SignalEmerald.copy(alpha = 0.7f)),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .testTag("export_report_button")
                ) {
                    if (state.isExporting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = SignalEmerald
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.Download,
                            contentDescription = null,
                            tint = SignalEmerald,
                            modifier = Modifier.size(17.dp)
                        )
                    }
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = stringResource(R.string.action_export_report),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = SignalEmerald,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                if (state.lastExport?.success == true) {
                    IconButton(
                        onClick = onShareLastReport,
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("share_report_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Share,
                            contentDescription = stringResource(R.string.cd_share_report),
                            tint = ElectricCyan,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }

            // Row 3: Auto-Relock on Page Reload toggle + status summary
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(ObsidianBg)
                    .padding(horizontal = 10.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.label_auto_relock_on_reload),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = if (feed.lastRelockSummary.isNotBlank()) {
                            feed.lastRelockSummary
                        } else if (state.savedProfile != null) {
                            "Profile ready: [${state.savedProfile.baseIndex}] (${state.savedProfile.anchorCount} anchors)"
                        } else {
                            feed.statusMessage
                        },
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = JetBrainsMonoFontFamily,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 10.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Switch(
                    checked = feed.autoRelockOnReload,
                    onCheckedChange = { onToggleAutoRelockOnReload() },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = OnElectricCyan,
                        checkedTrackColor = ElectricCyan,
                        uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        uncheckedTrackColor = ObsidianSurfaceElevated
                    ),
                    modifier = Modifier.testTag("auto_relock_switch")
                )
            }
        }
    }
}

/**
 * Tabbed diagnostic console hosting Step 1 Movement Float Delta Scanner, Step 2 Pointer &
 * Signature Analyzer, Nearby Entity Structs, Binary Asset Inspector, Rolling Logs, and the
 * optional read-only Manual JS Evaluator drawer.
 */
@Composable
fun TelemetryConsolePanel(
    state: DiagnosticsUiState,
    filteredLogs: List<TelemetryLogEntry>,
    onSelectConsoleTab: (ConsoleTab) -> Unit,
    onCaptureBaseline: () -> Unit,
    onScanMovementDeltas: () -> Unit,
    onResetFloatScan: () -> Unit,
    onInspectAndLockCandidate: (Int) -> Unit,
    onBaseIndexInputChanged: (String) -> Unit,
    onApplyBaseIndex: () -> Unit,
    onResetVerifiedIndex: () -> Unit,
    onAnalyzeCurrentIndex: () -> Unit,
    onSaveAndLockProfile: () -> Unit,
    onClearSavedProfile: () -> Unit,
    onTogglePinEntity: (Int) -> Unit,
    onClearAssetCache: () -> Unit,
    onToggleJsDrawer: () -> Unit,
    onManualJsInputChanged: (String) -> Unit,
    onExecuteManualScript: () -> Unit,
    onSelectFilter: (LogCategory) -> Unit,
    onClearLogs: () -> Unit,
    onExportLogDumpTxt: () -> Unit,
    onExportLogDumpJson: () -> Unit,
    onShareLastLogDump: () -> Unit,
    onCopyText: (String) -> Unit,
    tabListHeight: Dp,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = ObsidianSurface,
        border = BorderStroke(1.dp, ObsidianBorder),
        modifier = modifier
            .fillMaxWidth()
            .testTag("telemetry_console_panel")
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Top Tab Strip + Manual JS Drawer toggle
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(ObsidianSurfaceElevated)
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ConsoleTabPill(
                        label = stringResource(R.string.tab_scanner),
                        badge = state.floatScanner.candidates.size.takeIf { it > 0 }?.toString(),
                        selected = state.activeConsoleTab == ConsoleTab.SCANNER,
                        accent = ElectricCyan,
                        onClick = { onSelectConsoleTab(ConsoleTab.SCANNER) },
                        testTag = "console_tab_scanner"
                    )
                    ConsoleTabPill(
                        label = stringResource(R.string.tab_analyzer),
                        badge = state.pointerAnalysis.anchorCount.takeIf { it > 0 }?.toString(),
                        selected = state.activeConsoleTab == ConsoleTab.ANALYZER,
                        accent = TelemetryViolet,
                        onClick = { onSelectConsoleTab(ConsoleTab.ANALYZER) },
                        testTag = "console_tab_analyzer"
                    )
                    ConsoleTabPill(
                        label = stringResource(R.string.tab_entities),
                        badge = state.discoveredEntities.size.takeIf { it > 0 }?.toString(),
                        selected = state.activeConsoleTab == ConsoleTab.ENTITIES,
                        accent = TelemetryViolet,
                        onClick = { onSelectConsoleTab(ConsoleTab.ENTITIES) },
                        testTag = "console_tab_entities"
                    )
                    ConsoleTabPill(
                        label = stringResource(R.string.tab_assets),
                        badge = state.interceptedAssets.size.takeIf { it > 0 }?.toString(),
                        selected = state.activeConsoleTab == ConsoleTab.ASSETS,
                        accent = AssetAmber,
                        onClick = { onSelectConsoleTab(ConsoleTab.ASSETS) },
                        testTag = "console_tab_assets"
                    )
                    ConsoleTabPill(
                        label = stringResource(R.string.tab_logs),
                        badge = state.logs.size.takeIf { it > 0 }?.toString(),
                        selected = state.activeConsoleTab == ConsoleTab.LOGS,
                        accent = SignalEmerald,
                        onClick = { onSelectConsoleTab(ConsoleTab.LOGS) },
                        testTag = "console_tab_logs"
                    )
                }

                Surface(
                    onClick = onToggleJsDrawer,
                    shape = RoundedCornerShape(8.dp),
                    color = if (state.isJsDrawerOpen) ElectricCyan.copy(alpha = 0.22f) else ObsidianBg,
                    border = BorderStroke(1.dp, if (state.isJsDrawerOpen) ElectricCyan else ObsidianBorder),
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag("toggle_js_drawer_button")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Code,
                            contentDescription = stringResource(R.string.cd_toggle_js_drawer),
                            tint = if (state.isJsDrawerOpen) ElectricCyan else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = stringResource(R.string.action_js_drawer),
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = JetBrainsMonoFontFamily,
                            fontWeight = FontWeight.Bold,
                            color = if (state.isJsDrawerOpen) ElectricCyan else MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }

            // Optional Manual Read-Only JS Evaluation Drawer
            if (state.isJsDrawerOpen) {
                HorizontalDivider(color = ObsidianBorder, thickness = 1.dp)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(ObsidianSurface)
                        .padding(10.dp)
                        .testTag("manual_js_drawer"),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = state.manualJsInput,
                        onValueChange = onManualJsInputChanged,
                        placeholder = {
                            Text(
                                text = stringResource(R.string.js_input_hint),
                                style = MaterialTheme.typography.bodySmall
                            )
                        },
                        textStyle = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = JetBrainsMonoFontFamily,
                            color = MaterialTheme.colorScheme.onSurface
                        ),
                        minLines = 2,
                        maxLines = 4,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = ElectricCyan,
                            unfocusedBorderColor = ObsidianBorder,
                            focusedContainerColor = ObsidianBg,
                            unfocusedContainerColor = ObsidianBg
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("manual_js_input")
                    )

                    Button(
                        onClick = onExecuteManualScript,
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = ElectricCyan,
                            contentColor = OnElectricCyan
                        ),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .testTag("execute_manual_js_button")
                    ) {
                        Text(
                            text = stringResource(R.string.action_execute_script),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            HorizontalDivider(color = ObsidianBorder, thickness = 1.dp)

            // Active Tab Content
            when (state.activeConsoleTab) {
                ConsoleTab.SCANNER -> Step1FloatDeltaScannerTab(
                    state = state,
                    onCaptureBaseline = onCaptureBaseline,
                    onScanMovementDeltas = onScanMovementDeltas,
                    onResetScan = onResetFloatScan,
                    onInspectAndLockCandidate = onInspectAndLockCandidate
                )

                ConsoleTab.ANALYZER -> Step2PointerSignatureTab(
                    state = state,
                    onBaseIndexInputChanged = onBaseIndexInputChanged,
                    onApplyBaseIndex = onApplyBaseIndex,
                    onResetVerifiedIndex = onResetVerifiedIndex,
                    onAnalyzeCurrentIndex = onAnalyzeCurrentIndex,
                    onSaveAndLockProfile = onSaveAndLockProfile,
                    onClearSavedProfile = onClearSavedProfile,
                    onCopyText = onCopyText
                )

                ConsoleTab.ENTITIES -> NearbyEntitiesTabView(
                    state = state,
                    onTogglePinEntity = onTogglePinEntity,
                    onCopyEntity = onCopyText,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(tabListHeight)
                )

                ConsoleTab.ASSETS -> AssetMetadataInspectorView(
                    state = state,
                    onClearAssetCache = onClearAssetCache,
                    onExportAssetDumpJson = onExportLogDumpJson,
                    onCopyMetadata = onCopyText,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(tabListHeight)
                )

                ConsoleTab.LOGS -> LiveLogConsoleView(
                    state = state,
                    filteredLogs = filteredLogs,
                    onSelectFilter = onSelectFilter,
                    onClearLogs = onClearLogs,
                    onExportLogDumpTxt = onExportLogDumpTxt,
                    onExportLogDumpJson = onExportLogDumpJson,
                    onShareLastLogDump = onShareLastLogDump,
                    onCopyLog = onCopyText,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(tabListHeight)
                )
            }
        }
    }
}

@Composable
private fun ConsoleTabPill(
    label: String,
    badge: String?,
    selected: Boolean,
    accent: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit,
    testTag: String
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(8.dp),
        color = if (selected) accent.copy(alpha = 0.2f) else ObsidianBg,
        border = BorderStroke(1.dp, if (selected) accent else ObsidianBorder),
        modifier = Modifier
            .heightIn(min = 48.dp)
            .testTag(testTag)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                color = if (selected) accent else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
            if (badge != null) {
                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = accent.copy(alpha = 0.22f)
                ) {
                    Text(
                        text = badge,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = JetBrainsMonoFontFamily,
                        fontWeight = FontWeight.Bold,
                        color = accent,
                        fontSize = 9.sp,
                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                    )
                }
            }
        }
    }
}

/**
 * Collapsible Live Telemetry Stream card pinned to the bottom of the screen.
 * Shows the latest 200ms `window.HEAPF32` sample when collapsed and expands to display the
 * rolling 60-sample stream history.
 */
@Composable
fun CollapsibleLiveTelemetryStreamCard(
    state: DiagnosticsUiState,
    onToggleExpanded: () -> Unit,
    modifier: Modifier = Modifier
) {
    val feed = state.heapFeed
    val latestLine = state.liveStreamHistory.firstOrNull()
        ?: stringResource(R.string.telemetry_stream_empty)

    Surface(
        color = ObsidianSurfaceElevated,
        border = BorderStroke(1.dp, ObsidianBorder),
        modifier = modifier
            .fillMaxWidth()
            .testTag("live_telemetry_stream_bar")
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .clickable(onClick = onToggleExpanded)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(if (feed.isPolling && feed.isHeapReady) SignalEmerald else AssetAmber)
                )

                Text(
                    text = stringResource(R.string.telemetry_stream_title),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = if (feed.isPolling && feed.isHeapReady) SignalEmerald else AssetAmber
                )

                Text(
                    text = latestLine,
                    style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
                    fontFamily = JetBrainsMonoFontFamily,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )

                Text(
                    text = "#${feed.tickCount}",
                    style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
                    fontFamily = JetBrainsMonoFontFamily,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 10.sp
                )

                Icon(
                    imageVector = if (state.isTelemetryStreamExpanded) Icons.Default.ExpandMore else Icons.Default.ExpandLess,
                    contentDescription = stringResource(R.string.cd_toggle_telemetry_stream),
                    tint = ElectricCyan,
                    modifier = Modifier.size(20.dp)
                )
            }

            if (state.isTelemetryStreamExpanded) {
                HorizontalDivider(color = ObsidianBorder, thickness = 1.dp)
                if (state.liveStreamHistory.isEmpty()) {
                    Text(
                        text = stringResource(R.string.telemetry_stream_empty),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = JetBrainsMonoFontFamily,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(ObsidianBg)
                            .padding(12.dp)
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(148.dp)
                            .background(ObsidianBg)
                            .testTag("live_telemetry_stream_list"),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        verticalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        items(state.liveStreamHistory) { sample ->
                            Text(
                                text = sample,
                                style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
                                fontFamily = JetBrainsMonoFontFamily,
                                color = MaterialTheme.colorScheme.onSurface,
                                fontSize = 10.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
    }
}
