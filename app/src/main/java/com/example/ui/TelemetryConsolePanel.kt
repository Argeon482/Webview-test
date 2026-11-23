package com.example.ui

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MyLocation
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
import androidx.compose.ui.graphics.Color
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
import com.example.VERIFIED_PLAYER_BASE_INDEX
import com.example.ui.theme.AssetAmber
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.ObsidianBg
import com.example.ui.theme.ObsidianBorder
import com.example.ui.theme.ObsidianSurface
import com.example.ui.theme.ObsidianSurfaceElevated
import com.example.ui.theme.OnElectricCyan
import com.example.ui.theme.OnSignalEmerald
import com.example.ui.theme.OnTelemetryViolet
import com.example.ui.theme.SignalEmerald
import com.example.ui.theme.TelemetryViolet

/**
 * Step 3 Action Card: Prominent `"Auto-Relock"` button + Auto-Relock on Page Reload switch +
 * 200ms Feed toggle + `"Scan Entities"` & `"Export Report"` controls with generous >=50dp padding.
 */
@Composable
fun PrimaryActionButtonsBar(
    state: DiagnosticsUiState,
    onAutoRelock: () -> Unit,
    onToggleAutoRelockOnReload: (Boolean) -> Unit,
    onStartFeed: () -> Unit,
    onPauseFeed: () -> Unit,
    onScanEntities: () -> Unit,
    onExportReport: () -> Unit,
    onShareLastReport: () -> Unit,
    modifier: Modifier = Modifier
) {
    val feed = state.heapFeed
    val saved = state.savedProfile

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = ObsidianSurface,
        border = BorderStroke(1.2.dp, ObsidianBorder),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Prominent One-Tap Auto-Relock button
            Button(
                onClick = onAutoRelock,
                enabled = !feed.isRelocking,
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = ElectricCyan,
                    contentColor = OnElectricCyan,
                    disabledContainerColor = ElectricCyan.copy(alpha = 0.4f),
                    disabledContentColor = OnElectricCyan
                ),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 52.dp)
                    .testTag("auto_relock_button")
            ) {
                if (feed.isRelocking) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.2.dp,
                        color = OnElectricCyan
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.MyLocation,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(Modifier.width(8.dp))
                val suffix = when {
                    saved != null -> " (${saved.anchorCount} sig, ${saved.parentPointers.size} ptr)"
                    feed.hasSignature -> " (${feed.signatureAnchorCount} anchors)"
                    else -> ""
                }
                Text(
                    text = "${stringResource(R.string.action_auto_relock)}$suffix",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // Auto-Relock on Page Reload toggle + 200ms Feed Start/Pause pill
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(ObsidianBg)
                    .padding(horizontal = 10.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Switch(
                        checked = feed.autoRelockOnReload,
                        onCheckedChange = onToggleAutoRelockOnReload,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = OnSignalEmerald,
                            checkedTrackColor = SignalEmerald
                        ),
                        modifier = Modifier.testTag("auto_relock_reload_switch")
                    )
                    Text(
                        text = stringResource(R.string.label_auto_relock_on_reload),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Surface(
                    onClick = if (feed.isPolling) onPauseFeed else onStartFeed,
                    shape = RoundedCornerShape(8.dp),
                    color = if (feed.isPolling) SignalEmerald.copy(alpha = 0.16f) else ObsidianSurfaceElevated,
                    border = BorderStroke(
                        1.dp,
                        if (feed.isPolling) SignalEmerald.copy(alpha = 0.65f) else ObsidianBorder
                    ),
                    modifier = Modifier
                        .heightIn(min = 38.dp)
                        .testTag("toggle_direct_feed_button")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = if (feed.isPolling) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = null,
                            tint = if (feed.isPolling) SignalEmerald else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(15.dp)
                        )
                        Text(
                            text = if (feed.isPolling) "200ms ON" else "Start",
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = JetBrainsMonoFontFamily,
                            fontWeight = FontWeight.Bold,
                            color = if (feed.isPolling) SignalEmerald else MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }

            if (feed.lastRelockSummary.isNotBlank()) {
                Text(
                    text = feed.lastRelockSummary,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = JetBrainsMonoFontFamily,
                    color = ElectricCyan,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // Secondary row: Scan Entities & Export Report (+ Share button when exported)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = onScanEntities,
                    enabled = !state.isScanningEntities,
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = TelemetryViolet,
                        contentColor = OnTelemetryViolet,
                        disabledContainerColor = TelemetryViolet.copy(alpha = 0.4f),
                        disabledContentColor = OnTelemetryViolet
                    ),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 50.dp)
                        .testTag("scan_entities_button")
                ) {
                    if (state.isScanningEntities) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = OnTelemetryViolet
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.Radar,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = stringResource(R.string.action_scan_entities),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Button(
                    onClick = onExportReport,
                    enabled = !state.isExporting,
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = SignalEmerald,
                        contentColor = OnSignalEmerald,
                        disabledContainerColor = SignalEmerald.copy(alpha = 0.4f),
                        disabledContentColor = OnSignalEmerald
                    ),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 50.dp)
                        .testTag("export_report_button")
                ) {
                    if (state.isExporting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = OnSignalEmerald
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.Download,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = stringResource(R.string.action_export_report),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                if (state.lastExport?.success == true) {
                    Surface(
                        onClick = onShareLastReport,
                        shape = RoundedCornerShape(12.dp),
                        color = SignalEmerald.copy(alpha = 0.16f),
                        border = BorderStroke(1.2.dp, SignalEmerald.copy(alpha = 0.7f)),
                        modifier = Modifier
                            .size(50.dp)
                            .testTag("share_report_button")
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.Share,
                                contentDescription = stringResource(R.string.cd_share_report),
                                tint = SignalEmerald,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 5-Tab WASM Diagnostics Console Deck:
 * `Scanner` (Step 1) | `Ptr / Sig` (Step 2) | `Entities` | `Assets` | `Logs` + Manual JS Drawer.
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
    tabListHeight: Dp = 360.dp,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = ObsidianSurface,
        border = BorderStroke(1.2.dp, ObsidianBorder),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Scrollable Tab Bar Header + JS Toggle
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(ObsidianSurfaceElevated)
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    ConsoleTabButton(
                        label = stringResource(R.string.tab_scanner),
                        selected = state.activeConsoleTab == ConsoleTab.SCANNER,
                        accent = ElectricCyan,
                        onClick = { onSelectConsoleTab(ConsoleTab.SCANNER) },
                        modifier = Modifier.testTag("tab_scanner")
                    )
                    ConsoleTabButton(
                        label = stringResource(R.string.tab_analyzer),
                        selected = state.activeConsoleTab == ConsoleTab.ANALYZER,
                        accent = TelemetryViolet,
                        onClick = { onSelectConsoleTab(ConsoleTab.ANALYZER) },
                        modifier = Modifier.testTag("tab_analyzer")
                    )
                    ConsoleTabButton(
                        label = "${stringResource(R.string.tab_entities)} (${state.discoveredEntities.size})",
                        selected = state.activeConsoleTab == ConsoleTab.ENTITIES,
                        accent = SignalEmerald,
                        onClick = { onSelectConsoleTab(ConsoleTab.ENTITIES) },
                        modifier = Modifier.testTag("tab_entities")
                    )
                    ConsoleTabButton(
                        label = "${stringResource(R.string.tab_assets)} (${state.interceptedAssets.size})",
                        selected = state.activeConsoleTab == ConsoleTab.ASSETS,
                        accent = AssetAmber,
                        onClick = { onSelectConsoleTab(ConsoleTab.ASSETS) },
                        modifier = Modifier.testTag("tab_assets")
                    )
                    ConsoleTabButton(
                        label = "${stringResource(R.string.tab_logs)} (${state.logs.size})",
                        selected = state.activeConsoleTab == ConsoleTab.LOGS,
                        accent = ElectricCyan,
                        onClick = { onSelectConsoleTab(ConsoleTab.LOGS) },
                        modifier = Modifier.testTag("tab_logs")
                    )
                }

                Surface(
                    onClick = onToggleJsDrawer,
                    shape = RoundedCornerShape(8.dp),
                    color = if (state.isJsDrawerOpen) ElectricCyan.copy(alpha = 0.22f) else ObsidianBg,
                    border = BorderStroke(
                        1.dp,
                        if (state.isJsDrawerOpen) ElectricCyan else ObsidianBorder
                    ),
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("toggle_js_drawer_button")
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.Code,
                            contentDescription = stringResource(R.string.cd_toggle_js_drawer),
                            tint = if (state.isJsDrawerOpen) ElectricCyan else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(19.dp)
                        )
                    }
                }
            }

            AnimatedVisibility(visible = state.isJsDrawerOpen) {
                ManualJsDrawer(
                    jsInput = state.manualJsInput,
                    onJsInputChanged = onManualJsInputChanged,
                    onExecute = onExecuteManualScript,
                    onClose = onToggleJsDrawer
                )
            }

            HorizontalDivider(color = ObsidianBorder, thickness = 1.dp)

            when (state.activeConsoleTab) {
                ConsoleTab.SCANNER -> {
                    Step1FloatDeltaScannerTab(
                        state = state,
                        onCaptureBaseline = onCaptureBaseline,
                        onScanMovementDeltas = onScanMovementDeltas,
                        onResetScan = onResetFloatScan,
                        onInspectAndLockCandidate = onInspectAndLockCandidate
                    )
                }

                ConsoleTab.ANALYZER -> {
                    Step2PointerSignatureTab(
                        state = state,
                        onBaseIndexInputChanged = onBaseIndexInputChanged,
                        onApplyBaseIndex = onApplyBaseIndex,
                        onResetVerifiedIndex = onResetVerifiedIndex,
                        onAnalyzeCurrentIndex = onAnalyzeCurrentIndex,
                        onSaveAndLockProfile = onSaveAndLockProfile,
                        onClearSavedProfile = onClearSavedProfile,
                        onCopyText = onCopyText
                    )
                }

                ConsoleTab.ENTITIES -> {
                    NearbyEntitiesTabView(
                        state = state,
                        onTogglePinEntity = onTogglePinEntity,
                        onCopyEntity = onCopyText,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(tabListHeight)
                    )
                }

                ConsoleTab.ASSETS -> {
                    AssetMetadataInspectorView(
                        state = state,
                        onClearAssetCache = onClearAssetCache,
                        onExportAssetDumpJson = onExportLogDumpJson,
                        onCopyMetadata = onCopyText,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(tabListHeight)
                    )
                }

                ConsoleTab.LOGS -> {
                    LiveLogConsoleView(
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
}

@Composable
private fun ConsoleTabButton(
    label: String,
    selected: Boolean,
    accent: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(8.dp),
        color = if (selected) accent.copy(alpha = 0.22f) else ObsidianBg,
        border = BorderStroke(1.dp, if (selected) accent else ObsidianBorder),
        modifier = modifier.heightIn(min = 48.dp)
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                color = if (selected) accent else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun ManualJsDrawer(
    jsInput: String,
    onJsInputChanged: (String) -> Unit,
    onExecute: () -> Unit,
    onClose: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(ObsidianBg)
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "Read-Only JS Console (e.g. window.HEAPF32.slice($VERIFIED_PLAYER_BASE_INDEX, ${VERIFIED_PLAYER_BASE_INDEX + 3}))",
                style = MaterialTheme.typography.labelSmall,
                color = ElectricCyan,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = onClose, modifier = Modifier.size(36.dp)) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = stringResource(R.string.cd_close_drawer),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp)
                )
            }
        }

        OutlinedTextField(
            value = jsInput,
            onValueChange = onJsInputChanged,
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
                focusedContainerColor = ObsidianSurface,
                unfocusedContainerColor = ObsidianSurface
            ),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("manual_js_input")
        )

        Button(
            onClick = onExecute,
            shape = RoundedCornerShape(10.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = ElectricCyan,
                contentColor = OnElectricCyan
            ),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .testTag("execute_js_button")
        ) {
            Text(
                text = stringResource(R.string.action_execute_script),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

/**
 * Fixed, collapsible bottom Live Telemetry Stream Card.
 * Completely separated from interactive controls so 200ms text ticks never overlap buttons.
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
        color = ObsidianSurface,
        border = BorderStroke(
            1.dp,
            if (feed.isPolling && feed.isHeapReady) SignalEmerald.copy(alpha = 0.55f) else ObsidianBorder
        ),
        shape = RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp),
        modifier = modifier
            .fillMaxWidth()
            .testTag("collapsible_telemetry_stream")
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
                        .background(
                            when {
                                feed.isRelocking || state.isScanningEntities || state.floatScanner.isBusy -> ElectricCyan
                                feed.isPolling && feed.isHeapReady -> SignalEmerald
                                else -> AssetAmber
                            }
                        )
                )

                Text(
                    text = stringResource(R.string.telemetry_stream_title),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = if (feed.isPolling && feed.isHeapReady) SignalEmerald else ElectricCyan
                )

                Text(
                    text = latestLine,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = JetBrainsMonoFontFamily,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )

                Icon(
                    imageVector = if (state.isTelemetryStreamExpanded) {
                        Icons.Default.KeyboardArrowDown
                    } else {
                        Icons.Default.KeyboardArrowUp
                    },
                    contentDescription = stringResource(R.string.cd_toggle_telemetry_stream),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }

            AnimatedVisibility(visible = state.isTelemetryStreamExpanded) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(ObsidianBg)
                ) {
                    HorizontalDivider(color = ObsidianBorder, thickness = 1.dp)
                    if (state.liveStreamHistory.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(88.dp)
                                .padding(12.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = stringResource(R.string.telemetry_stream_empty),
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = JetBrainsMonoFontFamily,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(150.dp)
                                .testTag("live_telemetry_stream_list"),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            itemsIndexed(state.liveStreamHistory) { idx, line ->
                                Text(
                                    text = line,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = JetBrainsMonoFontFamily,
                                    fontSize = 11.sp,
                                    color = if (idx == 0) SignalEmerald else MaterialTheme.colorScheme.onSurfaceVariant,
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
}
