package com.example.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material.icons.filled.TrackChanges
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.DiagnosticsViewModel
import com.example.R
import com.example.Step2ScanMode
import com.example.WasmFloatDeltaCandidate
import com.example.WasmFloatDeltaState
import com.example.ui.theme.AssetAmber
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.ObsidianBg
import com.example.ui.theme.ObsidianBorder
import com.example.ui.theme.ObsidianSurface
import com.example.ui.theme.ObsidianSurfaceElevated
import com.example.ui.theme.SignalEmerald
import com.example.ui.theme.TelemetryViolet
import java.util.Locale

/**
 * Compact 2-pill mode switcher inside Step 2 allowing instant switching between
 * WASM Float Heap Delta Mode (`window.HEAPF32`) and JS Objects Delta Mode.
 */
@Composable
fun Step2ModeSelectorRow(
    selectedMode: Step2ScanMode,
    onSelectMode: (Step2ScanMode) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Step2ModePill(
            label = stringResource(R.string.step2_mode_wasm_float),
            selected = selectedMode == Step2ScanMode.WASM_FLOAT_HEAP,
            accent = ElectricCyan,
            testTag = "step2_mode_wasm_float_button",
            onClick = { onSelectMode(Step2ScanMode.WASM_FLOAT_HEAP) },
            modifier = Modifier.weight(1f)
        )
        Step2ModePill(
            label = stringResource(R.string.step2_mode_js),
            selected = selectedMode == Step2ScanMode.JS_OBJECTS,
            accent = SignalEmerald,
            testTag = "step2_mode_js_button",
            onClick = { onSelectMode(Step2ScanMode.JS_OBJECTS) },
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun Step2ModePill(
    label: String,
    selected: Boolean,
    accent: Color,
    testTag: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(7.dp),
        color = if (selected) accent.copy(alpha = 0.2f) else ObsidianBg,
        border = BorderStroke(
            1.dp,
            if (selected) accent else ObsidianBorder
        ),
        modifier = modifier
            .heightIn(min = 38.dp)
            .testTag(testTag)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
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

/**
 * Step 2A & 2B controls for WebAssembly Float Heap Delta Mode (`window.HEAPF32`).
 */
@Composable
fun Step2FloatModeControls(
    floatState: WasmFloatDeltaState,
    onCaptureFloatBaseline: () -> Unit,
    onScanFloatDeltas: () -> Unit,
    onResetFloatDelta: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        // Step 2A — Capture Float Baseline Snapshot
        Button(
            onClick = onCaptureFloatBaseline,
            enabled = !floatState.isSamplingBaseline && !floatState.isScanningDeltas,
            shape = RoundedCornerShape(8.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (floatState.baselineCaptured) ElectricCyan else ElectricCyan.copy(alpha = 0.18f),
                contentColor = if (floatState.baselineCaptured) ObsidianBg else MaterialTheme.colorScheme.onSurface,
                disabledContainerColor = ObsidianSurfaceElevated,
                disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
            ),
            border = BorderStroke(1.dp, ElectricCyan.copy(alpha = 0.8f)),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .testTag("step_2a_float_baseline_button")
        ) {
            if (floatState.isSamplingBaseline) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = ElectricCyan
                )
            } else {
                Icon(
                    imageVector = Icons.Default.TrackChanges,
                    contentDescription = null,
                    tint = if (floatState.baselineCaptured) ObsidianBg else ElectricCyan,
                    modifier = Modifier.size(18.dp)
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.step_2a_float_baseline),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        // Step 2B — Scan Float Heap Deltas + Reset
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(
                onClick = onScanFloatDeltas,
                enabled = !floatState.isSamplingBaseline && !floatState.isScanningDeltas && floatState.baselineCaptured,
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (floatState.candidates.isNotEmpty()) SignalEmerald else SignalEmerald.copy(alpha = 0.22f),
                    contentColor = if (floatState.candidates.isNotEmpty()) ObsidianBg else MaterialTheme.colorScheme.onSurface,
                    disabledContainerColor = ObsidianSurfaceElevated,
                    disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
                ),
                border = BorderStroke(
                    1.dp,
                    if (floatState.baselineCaptured) SignalEmerald else ObsidianBorder
                ),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp)
                    .testTag("step_2b_scan_float_deltas_button")
            ) {
                if (floatState.isScanningDeltas) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = SignalEmerald
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.Timeline,
                        contentDescription = null,
                        tint = when {
                            !floatState.baselineCaptured -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
                            floatState.candidates.isNotEmpty() -> ObsidianBg
                            else -> SignalEmerald
                        },
                        modifier = Modifier.size(18.dp)
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.step_2b_scan_float_deltas),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            if (floatState.baselineCaptured || floatState.candidates.isNotEmpty()) {
                Surface(
                    onClick = onResetFloatDelta,
                    shape = RoundedCornerShape(8.dp),
                    color = ObsidianBg,
                    border = BorderStroke(1.dp, ObsidianBorder),
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("step_2_float_reset_button")
                ) {
                    Row(
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = stringResource(R.string.wasm_action_reset),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }

        // Plain-English Instruction
        Text(
            text = stringResource(R.string.wizard_step_2_float_instruction),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        // Compact Float Baseline & Match Readout Box
        if (floatState.baselineCaptured || floatState.statusMessage.isNotBlank()) {
            Surface(
                color = ObsidianBg,
                shape = RoundedCornerShape(7.dp),
                border = BorderStroke(
                    1.dp,
                    if (floatState.candidates.isNotEmpty()) SignalEmerald.copy(alpha = 0.55f) else ElectricCyan.copy(alpha = 0.45f)
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    if (floatState.baselineCaptured) {
                        val source = floatState.heapSourceLabel.ifBlank { "window.HEAPF32" }
                        Text(
                            text = "$source • ${floatState.baselineSampleCount} stable floats (pruned ${floatState.baselinePrunedJitterCount} timers)" +
                                if (floatState.scanRound > 0) " • Round #${floatState.scanRound}: ${floatState.totalDeltaMatches} moved" else "",
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = JetBrainsMonoFontFamily,
                            fontWeight = FontWeight.Bold,
                            color = if (floatState.candidates.isNotEmpty()) SignalEmerald else ElectricCyan,
                            fontSize = 10.sp
                        )
                    }
                    if (floatState.statusMessage.isNotBlank()) {
                        Text(
                            text = floatState.statusMessage,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            lineHeight = 14.sp
                        )
                    }
                }
            }
        }
    }
}

/**
 * Persistent Live Float Watcher docked at the bottom of the diagnostic console.
 * Streams live 200ms decimal values for top filtered `window.HEAPF32` X/Y/Z candidate offsets
 * with one-tap pinning to Staging and direct jump to ±16B Neighboring Memory Inspector.
 */
@Composable
fun LiveFloatWatcherBottomBar(
    floatState: WasmFloatDeltaState,
    onToggleExpanded: () -> Unit,
    onTogglePinCandidate: (heapIndex: Int) -> Unit,
    onPinTopTriplet: () -> Unit,
    onInspectNeighbors: (heapIndex: Int) -> Unit,
    onStopWatch: () -> Unit,
    onCopyText: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    if (floatState.candidates.isEmpty()) return

    val topTriplet = floatState.candidates.take(3)
    val accent = if (floatState.isLiveWatching) SignalEmerald else ElectricCyan

    Surface(
        color = ObsidianSurfaceElevated,
        modifier = modifier
            .fillMaxWidth()
            .testTag("live_float_watcher_bottom_bar")
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            HorizontalDivider(color = accent.copy(alpha = 0.65f), thickness = 1.5.dp)

            // Header bar with live tick badge, Pin X/Y/Z, Stop, and Expand/Collapse
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggleExpanded)
                    .padding(horizontal = 8.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.MyLocation,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(14.dp)
                )
                Text(
                    text = stringResource(R.string.wasm_float_watcher_title),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = accent,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )

                Surface(
                    color = accent.copy(alpha = 0.16f),
                    shape = RoundedCornerShape(4.dp),
                    border = BorderStroke(1.dp, accent.copy(alpha = 0.5f))
                ) {
                    Text(
                        text = if (floatState.isLiveWatching) "LIVE #${floatState.liveWatchTickCount}" else "PAUSED",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = JetBrainsMonoFontFamily,
                        fontWeight = FontWeight.Bold,
                        color = accent,
                        fontSize = 9.sp,
                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                    )
                }

                Surface(
                    onClick = onPinTopTriplet,
                    shape = RoundedCornerShape(5.dp),
                    color = AssetAmber.copy(alpha = 0.16f),
                    border = BorderStroke(1.dp, AssetAmber.copy(alpha = 0.65f)),
                    modifier = Modifier.testTag("float_watcher_pin_xyz_button")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Bookmark,
                            contentDescription = null,
                            tint = AssetAmber,
                            modifier = Modifier.size(11.dp)
                        )
                        Text(
                            text = stringResource(R.string.wasm_float_pin_top),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = AssetAmber,
                            fontSize = 9.sp
                        )
                    }
                }

                if (floatState.isLiveWatching) {
                    Surface(
                        onClick = onStopWatch,
                        shape = RoundedCornerShape(5.dp),
                        color = ObsidianBg,
                        border = BorderStroke(1.dp, ObsidianBorder),
                        modifier = Modifier.testTag("float_watcher_stop_button")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Stop,
                                contentDescription = stringResource(R.string.wasm_float_stop_watch),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(11.dp)
                            )
                            Text(
                                text = stringResource(R.string.wasm_float_stop_watch),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 9.sp
                            )
                        }
                    }
                }

                Icon(
                    imageVector = if (floatState.isWatcherExpanded) Icons.Default.ExpandMore else Icons.Default.ExpandLess,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }

            // Always-visible X / Y / Z Candidate Live Decimal Tiles
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 8.dp, end = 8.dp, bottom = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                topTriplet.forEachIndexed { idx, candidate ->
                    val axisLabel = candidate.axisHint.ifBlank { listOf("X", "Y", "Z").getOrElse(idx) { "F$idx" } }
                    FloatAxisLiveTile(
                        axis = axisLabel,
                        candidate = candidate,
                        onClick = {
                            onCopyText(
                                "HEAPF32[${candidate.heapIndex}] (${DiagnosticsViewModel.formatHexAddress(candidate.byteAddress)}) = ${fmtDec(candidate.currentValue)}"
                            )
                        },
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            // Expandable Scrollable List of Top Filtered Float Offsets with Pin & ±16B Inspect
            AnimatedVisibility(
                visible = floatState.isWatcherExpanded,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    HorizontalDivider(color = ObsidianBorder.copy(alpha = 0.7f), thickness = 1.dp)
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 136.dp)
                            .background(ObsidianBg)
                            .testTag("float_watcher_candidate_list"),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        items(floatState.candidates, key = { it.heapIndex }) { candidate ->
                            FloatCandidateWatchRow(
                                candidate = candidate,
                                onTogglePin = { onTogglePinCandidate(candidate.heapIndex) },
                                onInspectNeighbors = { onInspectNeighbors(candidate.heapIndex) },
                                onCopy = {
                                    onCopyText(
                                        "window.HEAPF32[${candidate.heapIndex}] (${DiagnosticsViewModel.formatHexAddress(candidate.byteAddress)}) cur=${fmtDec(candidate.currentValue)} base=${fmtDec(candidate.baselineValue)}"
                                    )
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FloatAxisLiveTile(
    axis: String,
    candidate: WasmFloatDeltaCandidate,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val tileBorder = when {
        candidate.changedFromPrev -> SignalEmerald
        candidate.isPinned -> AssetAmber
        else -> SignalEmerald.copy(alpha = 0.35f)
    }
    val tileBg = if (candidate.changedFromPrev) SignalEmerald.copy(alpha = 0.16f) else ObsidianBg

    Surface(
        onClick = onClick,
        color = tileBg,
        shape = RoundedCornerShape(6.dp),
        border = BorderStroke(1.dp, tileBorder),
        modifier = modifier.testTag("float_axis_tile_$axis")
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(1.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = axis,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = if (candidate.isPinned) AssetAmber else SignalEmerald,
                    fontSize = 9.sp
                )
                Text(
                    text = "[${candidate.heapIndex}]",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = JetBrainsMonoFontFamily,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 8.sp
                )
            }
            Text(
                text = fmtDec(candidate.currentValue),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = JetBrainsMonoFontFamily,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontSize = 11.sp
            )
        }
    }
}

@Composable
private fun FloatCandidateWatchRow(
    candidate: WasmFloatDeltaCandidate,
    onTogglePin: () -> Unit,
    onInspectNeighbors: () -> Unit,
    onCopy: () -> Unit
) {
    val rowBg = when {
        candidate.changedFromPrev -> SignalEmerald.copy(alpha = 0.18f)
        candidate.isPinned -> AssetAmber.copy(alpha = 0.1f)
        else -> ObsidianSurface
    }
    val borderColor = when {
        candidate.isPinned -> AssetAmber.copy(alpha = 0.7f)
        candidate.changedFromPrev -> SignalEmerald.copy(alpha = 0.7f)
        else -> ObsidianBorder.copy(alpha = 0.8f)
    }

    Surface(
        shape = RoundedCornerShape(6.dp),
        color = rowBg,
        border = BorderStroke(1.dp, borderColor),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onCopy)
            .testTag("float_watch_row_${candidate.heapIndex}")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 7.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            if (candidate.axisHint.isNotBlank()) {
                Surface(
                    color = SignalEmerald.copy(alpha = 0.2f),
                    shape = RoundedCornerShape(4.dp)
                ) {
                    Text(
                        text = candidate.axisHint,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = JetBrainsMonoFontFamily,
                        fontWeight = FontWeight.Bold,
                        color = SignalEmerald,
                        fontSize = 9.sp,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                    )
                }
            }

            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(
                    text = "HEAPF32[${candidate.heapIndex}] • ${DiagnosticsViewModel.formatHexAddress(candidate.byteAddress)}",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = JetBrainsMonoFontFamily,
                    color = ElectricCyan,
                    fontSize = 9.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                val deltaSign = if (candidate.delta >= 0.0) "+" else ""
                val moveTag = if (candidate.changeCount > 0) " • LIVE ×${candidate.changeCount}" else ""
                Text(
                    text = "${fmtDec(candidate.currentValue)}  (Δ $deltaSign${String.format(Locale.US, "%.2f", candidate.delta)}$moveTag)",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = JetBrainsMonoFontFamily,
                    fontWeight = FontWeight.Bold,
                    color = if (candidate.changeCount > 0) SignalEmerald else MaterialTheme.colorScheme.onSurface,
                    fontSize = 10.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // Inspect ±16B neighbors around this float
            Surface(
                onClick = onInspectNeighbors,
                shape = RoundedCornerShape(5.dp),
                color = TelemetryViolet.copy(alpha = 0.16f),
                border = BorderStroke(1.dp, TelemetryViolet.copy(alpha = 0.55f)),
                modifier = Modifier.testTag("float_inspect_neighbors_${candidate.heapIndex}")
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Memory,
                        contentDescription = stringResource(R.string.cd_inspect_float_neighbors),
                        tint = TelemetryViolet,
                        modifier = Modifier.size(12.dp)
                    )
                    Text(
                        text = "±16B",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = JetBrainsMonoFontFamily,
                        fontWeight = FontWeight.Bold,
                        color = TelemetryViolet,
                        fontSize = 8.sp
                    )
                }
            }

            // Pin / Unpin button
            IconButton(
                onClick = onTogglePin,
                modifier = Modifier
                    .size(30.dp)
                    .testTag("float_pin_button_${candidate.heapIndex}")
            ) {
                Icon(
                    imageVector = if (candidate.isPinned) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                    contentDescription = stringResource(R.string.cd_pin_float_candidate),
                    tint = if (candidate.isPinned) AssetAmber else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

private fun fmtDec(value: Double): String {
    return if (value.isFinite()) String.format(Locale.US, "%.3f", value) else "NaN"
}
