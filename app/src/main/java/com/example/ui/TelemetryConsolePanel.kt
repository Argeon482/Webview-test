package com.example.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.RestartAlt
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
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
import java.util.Locale

/**
 * High-contrast, permanently accessible primary action buttons with generous touch targets (>= 50dp).
 * Placed inside the scrollable diagnostics deck so buttons never clip or get overlapped by live text.
 */
@Composable
fun PrimaryActionButtonsBar(
    state: DiagnosticsUiState,
    onRelockCoordinates: () -> Unit,
    onScanEntities: () -> Unit,
    onExportReport: () -> Unit,
    onShareLastReport: () -> Unit,
    modifier: Modifier = Modifier
) {
    val feed = state.heapFeed
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
            // Primary prominent button: Relock Coordinates (full width, unmistakable, never truncated)
            Button(
                onClick = onRelockCoordinates,
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
                    .testTag("relock_coordinates_button")
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
                Text(
                    text = if (feed.hasSignature) {
                        "${stringResource(R.string.action_relock_coordinates)} (${feed.signatureAnchorCount} anchors)"
                    } else {
                        stringResource(R.string.action_relock_coordinates)
                    },
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
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
 * Tabbed Diagnostic Console Deck (`Controls`, `Entities`, `Assets`, `Logs` + Manual JS Drawer).
 * Uses bounded inner height ([tabListHeight]) for `LazyColumn` tabs so it safely nests inside
 * the scrollable parent container on portrait mobile screens.
 */
@Composable
fun TelemetryConsolePanel(
    state: DiagnosticsUiState,
    filteredLogs: List<TelemetryLogEntry>,
    onSelectConsoleTab: (ConsoleTab) -> Unit,
    onBaseIndexInputChanged: (String) -> Unit,
    onApplyBaseIndex: () -> Unit,
    onResetVerifiedIndex: () -> Unit,
    onStartFeed: () -> Unit,
    onPauseFeed: () -> Unit,
    onCaptureSignature: () -> Unit,
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
            // Tab Bar Header + JS Toggle
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(ObsidianSurfaceElevated)
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                ConsoleTabButton(
                    label = stringResource(R.string.tab_controls),
                    selected = state.activeConsoleTab == ConsoleTab.CONTROLS,
                    accent = SignalEmerald,
                    onClick = { onSelectConsoleTab(ConsoleTab.CONTROLS) },
                    modifier = Modifier
                        .weight(1f)
                        .testTag("tab_controls")
                )
                ConsoleTabButton(
                    label = "${stringResource(R.string.tab_entities)} (${state.discoveredEntities.size})",
                    selected = state.activeConsoleTab == ConsoleTab.ENTITIES,
                    accent = TelemetryViolet,
                    onClick = { onSelectConsoleTab(ConsoleTab.ENTITIES) },
                    modifier = Modifier
                        .weight(1f)
                        .testTag("tab_entities")
                )
                ConsoleTabButton(
                    label = "${stringResource(R.string.tab_assets)} (${state.interceptedAssets.size})",
                    selected = state.activeConsoleTab == ConsoleTab.ASSETS,
                    accent = AssetAmber,
                    onClick = { onSelectConsoleTab(ConsoleTab.ASSETS) },
                    modifier = Modifier
                        .weight(1f)
                        .testTag("tab_assets")
                )
                ConsoleTabButton(
                    label = "${stringResource(R.string.tab_logs)} (${state.logs.size})",
                    selected = state.activeConsoleTab == ConsoleTab.LOGS,
                    accent = ElectricCyan,
                    onClick = { onSelectConsoleTab(ConsoleTab.LOGS) },
                    modifier = Modifier
                        .weight(1f)
                        .testTag("tab_logs")
                )

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

            // Collapsible Manual JS Drawer
            AnimatedVisibility(visible = state.isJsDrawerOpen) {
                ManualJsDrawer(
                    jsInput = state.manualJsInput,
                    onJsInputChanged = onManualJsInputChanged,
                    onExecute = onExecuteManualScript,
                    onClose = onToggleJsDrawer
                )
            }

            HorizontalDivider(color = ObsidianBorder, thickness = 1.dp)

            // Active Tab Content
            when (state.activeConsoleTab) {
                ConsoleTab.CONTROLS -> {
                    DirectFeedControlsTab(
                        state = state,
                        onBaseIndexInputChanged = onBaseIndexInputChanged,
                        onApplyBaseIndex = onApplyBaseIndex,
                        onResetVerifiedIndex = onResetVerifiedIndex,
                        onStartFeed = onStartFeed,
                        onPauseFeed = onPauseFeed,
                        onCaptureSignature = onCaptureSignature,
                        onOpenEntitiesTab = { onSelectConsoleTab(ConsoleTab.ENTITIES) }
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
        color = if (selected) accent.copy(alpha = 0.2f) else ObsidianBg,
        border = BorderStroke(1.dp, if (selected) accent else ObsidianBorder),
        modifier = modifier.heightIn(min = 48.dp)
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp)
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                color = if (selected) accent else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun DirectFeedControlsTab(
    state: DiagnosticsUiState,
    onBaseIndexInputChanged: (String) -> Unit,
    onApplyBaseIndex: () -> Unit,
    onResetVerifiedIndex: () -> Unit,
    onStartFeed: () -> Unit,
    onPauseFeed: () -> Unit,
    onCaptureSignature: () -> Unit,
    onOpenEntitiesTab: () -> Unit
) {
    val feed = state.heapFeed
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(ObsidianBg)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Card 1: Direct Verified HEAPF32 Feed Controls
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = ObsidianSurface,
            border = BorderStroke(1.dp, SignalEmerald.copy(alpha = 0.45f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = stringResource(R.string.controls_feed_title),
                        style = MaterialTheme.typography.titleSmall,
                        color = SignalEmerald
                    )
                    Text(
                        text = "Ticks: ${feed.tickCount}",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = JetBrainsMonoFontFamily,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Text(
                    text = stringResource(R.string.controls_feed_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // Start / Pause 200ms polling & Reset 581246
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = if (feed.isPolling) onPauseFeed else onStartFeed,
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (feed.isPolling) ObsidianSurfaceElevated else SignalEmerald,
                            contentColor = if (feed.isPolling) SignalEmerald else OnSignalEmerald
                        ),
                        border = if (feed.isPolling) BorderStroke(1.dp, SignalEmerald.copy(alpha = 0.7f)) else null,
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp)
                            .testTag("toggle_direct_feed_button")
                    ) {
                        Icon(
                            imageVector = if (feed.isPolling) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = if (feed.isPolling) {
                                stringResource(R.string.action_pause_feed)
                            } else {
                                stringResource(R.string.action_start_feed)
                            },
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    OutlinedButton(
                        onClick = onResetVerifiedIndex,
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, ObsidianBorder),
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .testTag("reset_verified_index_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.RestartAlt,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = stringResource(R.string.action_reset_verified_index),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }

                // Custom Base Index override row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = feed.baseIndexInput,
                        onValueChange = onBaseIndexInputChanged,
                        singleLine = true,
                        placeholder = {
                            Text(
                                text = stringResource(R.string.base_index_input_hint),
                                style = MaterialTheme.typography.bodySmall
                            )
                        },
                        textStyle = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = JetBrainsMonoFontFamily,
                            color = MaterialTheme.colorScheme.onSurface
                        ),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Number,
                            imeAction = ImeAction.Done
                        ),
                        keyboardActions = KeyboardActions(onDone = { onApplyBaseIndex() }),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = ElectricCyan,
                            unfocusedBorderColor = ObsidianBorder,
                            focusedContainerColor = ObsidianBg,
                            unfocusedContainerColor = ObsidianBg
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("base_index_input")
                    )

                    OutlinedButton(
                        onClick = onApplyBaseIndex,
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, ElectricCyan.copy(alpha = 0.7f)),
                        modifier = Modifier
                            .heightIn(min = 52.dp)
                            .testTag("apply_base_index_button")
                    ) {
                        Text(
                            text = stringResource(R.string.action_apply_base_index),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = ElectricCyan
                        )
                    }
                }
            }
        }

        // Card 2: Auto-Relock Memory Signature Status & Manual Capture
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = ObsidianSurface,
            border = BorderStroke(1.dp, ElectricCyan.copy(alpha = 0.45f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = stringResource(R.string.controls_relock_title),
                        style = MaterialTheme.typography.titleSmall,
                        color = ElectricCyan
                    )
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = if (feed.hasSignature) SignalEmerald.copy(alpha = 0.18f) else AssetAmber.copy(alpha = 0.16f)
                    ) {
                        Text(
                            text = if (feed.hasSignature) {
                                "${feed.signatureAnchorCount} anchors saved"
                            } else {
                                "No signature yet"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = if (feed.hasSignature) SignalEmerald else AssetAmber,
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                        )
                    }
                }

                Text(
                    text = stringResource(R.string.controls_relock_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (feed.lastRelockSummary.isNotBlank()) {
                    Text(
                        text = feed.lastRelockSummary,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = JetBrainsMonoFontFamily,
                        color = ElectricCyan,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(6.dp))
                            .background(ObsidianBg)
                            .padding(8.dp)
                    )
                }

                OutlinedButton(
                    onClick = onCaptureSignature,
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, ElectricCyan.copy(alpha = 0.75f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .testTag("capture_signature_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Fingerprint,
                        contentDescription = null,
                        tint = ElectricCyan,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "${stringResource(R.string.action_capture_signature)} @ HEAPF32[${feed.baseIndex}]",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = ElectricCyan
                    )
                }
            }
        }

        // Card 3: Nearby Entity Scanner Summary
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = ObsidianSurface,
            border = BorderStroke(1.dp, TelemetryViolet.copy(alpha = 0.45f)),
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onOpenEntitiesTab)
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = stringResource(R.string.controls_entities_title),
                        style = MaterialTheme.typography.titleSmall,
                        color = TelemetryViolet
                    )
                    Text(
                        text = "${state.discoveredEntities.size} found →",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = JetBrainsMonoFontFamily,
                        fontWeight = FontWeight.Bold,
                        color = TelemetryViolet
                    )
                }
                Text(
                    text = stringResource(R.string.controls_entities_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
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
 * Completely isolated from the interactive buttons above so rapid 200ms text stream updates
 * never shift, overlap, or clip primary controls.
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
        border = BorderStroke(1.dp, if (feed.isPolling && feed.isHeapReady) SignalEmerald.copy(alpha = 0.55f) else ObsidianBorder),
        shape = RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp),
        modifier = modifier
            .fillMaxWidth()
            .testTag("collapsible_telemetry_stream")
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Fixed 48dp header bar (always tappable, never obscures content above)
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
                                feed.isRelocking || state.isScanningEntities -> ElectricCyan
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
