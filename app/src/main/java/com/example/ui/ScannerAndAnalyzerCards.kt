package com.example.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BookmarkAdded
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.DirectionsRun
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Save
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.DiagnosticsUiState
import com.example.FloatDeltaCandidate
import com.example.MemorySlotPreview
import com.example.ParentPointerMatch
import com.example.R
import com.example.ui.theme.AlertCoral
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
 * Step 1: Fast, non-blocking 2-button Movement Float Delta Scanner (`window.HEAPF32`).
 */
@Composable
fun Step1FloatDeltaScannerTab(
    state: DiagnosticsUiState,
    onCaptureBaseline: () -> Unit,
    onScanMovementDeltas: () -> Unit,
    onResetScan: () -> Unit,
    onInspectAndLockCandidate: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val scanner = state.floatScanner

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(ObsidianBg)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Controls Card
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = ObsidianSurface,
            border = BorderStroke(1.dp, ElectricCyan.copy(alpha = 0.45f)),
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
                        text = stringResource(R.string.step1_card_title),
                        style = MaterialTheme.typography.titleSmall,
                        color = ElectricCyan,
                        modifier = Modifier.weight(1f)
                    )
                    if (scanner.baselineCaptured) {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = SignalEmerald.copy(alpha = 0.18f)
                        ) {
                            Text(
                                text = "${scanner.baselineSampleCount} floats",
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = JetBrainsMonoFontFamily,
                                fontWeight = FontWeight.Bold,
                                color = SignalEmerald,
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                            )
                        }
                    }
                }

                Text(
                    text = scanner.statusMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // Button A: 1. Capture Standing Baseline
                Button(
                    onClick = onCaptureBaseline,
                    enabled = !scanner.isBusy,
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = ElectricCyan,
                        contentColor = OnElectricCyan
                    ),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 50.dp)
                        .testTag("capture_baseline_button")
                ) {
                    if (scanner.isBusy && !scanner.baselineCaptured) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(17.dp),
                            strokeWidth = 2.dp,
                            color = OnElectricCyan
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.CameraAlt,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.action_capture_baseline),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold
                    )
                }

                // Button B: 2. Scan Movement Deltas + Reset Scan
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = onScanMovementDeltas,
                        enabled = !scanner.isBusy && scanner.baselineCaptured,
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = SignalEmerald,
                            contentColor = OnSignalEmerald,
                            disabledContainerColor = SignalEmerald.copy(alpha = 0.25f),
                            disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant
                        ),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 50.dp)
                            .testTag("scan_movement_deltas_button")
                    ) {
                        if (scanner.isBusy && scanner.baselineCaptured) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(17.dp),
                                strokeWidth = 2.dp,
                                color = OnSignalEmerald
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.DirectionsRun,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = stringResource(R.string.action_scan_movement_deltas),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    OutlinedButton(
                        onClick = onResetScan,
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, ObsidianBorder),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
                        modifier = Modifier
                            .heightIn(min = 50.dp)
                            .testTag("reset_float_scan_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(17.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = stringResource(R.string.action_reset_scan),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }

        // Candidate Results Card
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = ObsidianSurface,
            border = BorderStroke(1.dp, ObsidianBorder),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(ObsidianSurfaceElevated)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = if (scanner.totalMatchesFound > 0) {
                            "Candidates: ${scanner.candidates.size} of ${scanner.totalMatchesFound} (Round #${scanner.scanRound})"
                        } else {
                            "Movement Delta Candidates"
                        },
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    if (scanner.isLivePreviewing) {
                        Text(
                            text = "LIVE 200ms",
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = JetBrainsMonoFontFamily,
                            fontWeight = FontWeight.Bold,
                            color = SignalEmerald
                        )
                    }
                }

                HorizontalDivider(color = ObsidianBorder, thickness = 1.dp)

                if (scanner.candidates.isEmpty()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(18.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.scanner_empty_title),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = stringResource(R.string.scanner_empty_subtitle),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(340.dp)
                            .testTag("float_delta_candidates_list"),
                        contentPadding = PaddingValues(10.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(scanner.candidates, key = { it.heapIndex }) { candidate ->
                            FloatDeltaCandidateRow(
                                candidate = candidate,
                                isCurrentlyLocked = state.heapFeed.baseIndex == candidate.heapIndex,
                                onInspectAndLock = { onInspectAndLockCandidate(candidate.heapIndex) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FloatDeltaCandidateRow(
    candidate: FloatDeltaCandidate,
    isCurrentlyLocked: Boolean,
    onInspectAndLock: () -> Unit
) {
    val hexAddr = remember(candidate.byteAddress) {
        "0x${candidate.byteAddress.toString(16).uppercase(Locale.US)}"
    }
    val deltaPrefix = if (candidate.delta >= 0.0) "+" else ""

    Surface(
        shape = RoundedCornerShape(10.dp),
        color = ObsidianBg,
        border = BorderStroke(
            1.dp,
            if (isCurrentlyLocked) SignalEmerald else ObsidianBorder
        ),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("delta_candidate_${candidate.heapIndex}")
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(5.dp),
                    color = ElectricCyan.copy(alpha = 0.2f)
                ) {
                    Text(
                        text = "[${candidate.heapIndex}]",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = JetBrainsMonoFontFamily,
                        fontWeight = FontWeight.Bold,
                        color = ElectricCyan,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }

                Text(
                    text = hexAddr,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = JetBrainsMonoFontFamily,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )

                if (candidate.clusterSize >= 2) {
                    Surface(
                        shape = RoundedCornerShape(5.dp),
                        color = TelemetryViolet.copy(alpha = 0.2f)
                    ) {
                        Text(
                            text = "Vec${candidate.clusterSize.coerceAtMost(3)} ${candidate.axisHint}",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = TelemetryViolet,
                            fontSize = 9.sp,
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                        )
                    }
                }

                Surface(
                    shape = RoundedCornerShape(5.dp),
                    color = if (candidate.isStillNow) SignalEmerald.copy(alpha = 0.18f) else AssetAmber.copy(alpha = 0.18f)
                ) {
                    Text(
                        text = if (candidate.isStillNow) "STOPPED" else "MOVING",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = if (candidate.isStillNow) SignalEmerald else AssetAmber,
                        fontSize = 9.sp,
                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                    )
                }
            }

            // Live values preview (X + neighboring Y, Z)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(7.dp))
                    .background(ObsidianSurface)
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = String.format(
                        Locale.US,
                        "Base %.3f -> Now %.3f (%s%.3f)",
                        candidate.baselineValue,
                        candidate.currentValue,
                        deltaPrefix,
                        candidate.delta
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = JetBrainsMonoFontFamily,
                    fontWeight = FontWeight.SemiBold,
                    color = SignalEmerald,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = String.format(
                        Locale.US,
                        "Triplet Preview: X=%.3f  Y=%.3f  Z=%.3f",
                        candidate.currentValue,
                        candidate.previewY,
                        candidate.previewZ
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = JetBrainsMonoFontFamily,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Button(
                onClick = onInspectAndLock,
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isCurrentlyLocked) SignalEmerald else ElectricCyan,
                    contentColor = if (isCurrentlyLocked) OnSignalEmerald else OnElectricCyan
                ),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .testTag("inspect_lock_candidate_${candidate.heapIndex}")
            ) {
                Icon(
                    imageVector = Icons.Default.Lock,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = stringResource(R.string.action_inspect_and_lock),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

/**
 * Step 2: Pointer & Memory Signature Analyzer card (`±16` AOB / Float Pattern + `HEAPU32` Reverse
 * Pointer Lookup + Persistent `SharedPreferences` Profile Lock).
 */
@Composable
fun Step2PointerSignatureTab(
    state: DiagnosticsUiState,
    onBaseIndexInputChanged: (String) -> Unit,
    onApplyBaseIndex: () -> Unit,
    onResetVerifiedIndex: () -> Unit,
    onAnalyzeCurrentIndex: () -> Unit,
    onSaveAndLockProfile: () -> Unit,
    onClearSavedProfile: () -> Unit,
    onCopyText: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val analysis = state.pointerAnalysis
    val feed = state.heapFeed
    val saved = state.savedProfile

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(ObsidianBg)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Main Pointer & Signature Card
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = ObsidianSurface,
            border = BorderStroke(
                1.2.dp,
                if (saved != null) SignalEmerald.copy(alpha = 0.65f) else TelemetryViolet.copy(alpha = 0.55f)
            ),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("pointer_signature_card")
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
                        text = stringResource(R.string.step2_card_title),
                        style = MaterialTheme.typography.titleSmall,
                        color = TelemetryViolet
                    )
                    if (saved != null) {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = SignalEmerald.copy(alpha = 0.2f),
                            border = BorderStroke(1.dp, SignalEmerald.copy(alpha = 0.65f))
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.BookmarkAdded,
                                    contentDescription = null,
                                    tint = SignalEmerald,
                                    modifier = Modifier.size(12.dp)
                                )
                                Text(
                                    text = "SAVED IN PREFS",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = SignalEmerald,
                                    fontSize = 9.sp
                                )
                            }
                        }
                    }
                }

                Text(
                    text = stringResource(R.string.step2_card_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // Manual Base Index Selector + Verified 581246 shortcut
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
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
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 12.dp),
                        modifier = Modifier
                            .heightIn(min = 50.dp)
                            .testTag("apply_base_index_button")
                    ) {
                        Text(
                            text = stringResource(R.string.action_apply_base_index),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = ElectricCyan
                        )
                    }

                    OutlinedButton(
                        onClick = onResetVerifiedIndex,
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, ObsidianBorder),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 12.dp),
                        modifier = Modifier
                            .heightIn(min = 50.dp)
                            .testTag("reset_verified_index_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.RestartAlt,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(15.dp)
                        )
                        Spacer(Modifier.width(3.dp))
                        Text(
                            text = stringResource(R.string.action_reset_verified_index),
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = JetBrainsMonoFontFamily,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }

                // Analyze ±16 block & Reverse HEAPU32 Pointers button
                OutlinedButton(
                    onClick = onAnalyzeCurrentIndex,
                    enabled = !analysis.isAnalyzing,
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.2.dp, TelemetryViolet.copy(alpha = 0.8f)),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 50.dp)
                        .testTag("analyze_pointer_signature_button")
                ) {
                    if (analysis.isAnalyzing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(17.dp),
                            strokeWidth = 2.dp,
                            color = TelemetryViolet
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.Fingerprint,
                            contentDescription = null,
                            tint = TelemetryViolet,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "${stringResource(R.string.action_inspect_current_index)} [${feed.baseIndex}]",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = TelemetryViolet,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // Captured Signature Pattern Display
                if (analysis.aobPatternHex.isNotBlank()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(ObsidianBg)
                            .padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "AOB / Struct Signature (${analysis.anchorCount} anchors)",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = ElectricCyan
                            )
                            IconButton(
                                onClick = {
                                    onCopyText(
                                        "Base: HEAPF32[${analysis.inspectedBaseIndex}] (0x${analysis.inspectedByteAddress.toString(16).uppercase(Locale.US)})\n" +
                                            "AOB: ${analysis.aobPatternHex}\nFloats: ${analysis.floatPatternSummary}"
                                    )
                                },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.ContentCopy,
                                    contentDescription = stringResource(R.string.cd_copy_log),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(15.dp)
                                )
                            }
                        }

                        Text(
                            text = analysis.aobPatternHex,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = JetBrainsMonoFontFamily,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = 10.sp
                        )

                        if (analysis.floatPatternSummary.isNotBlank()) {
                            Text(
                                text = "Static Floats: ${analysis.floatPatternSummary}",
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = JetBrainsMonoFontFamily,
                                color = SignalEmerald,
                                fontSize = 10.sp
                            )
                        }
                    }
                } else {
                    Text(
                        text = stringResource(R.string.analyzer_empty_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // Discovered Parent Pointers (window.HEAPU32 reverse lookup)
                if (analysis.parentPointers.isNotEmpty()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(ObsidianBg)
                            .padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Link,
                                contentDescription = null,
                                tint = AssetAmber,
                                modifier = Modifier.size(15.dp)
                            )
                            Text(
                                text = "Parent Pointers in HEAPU32 (${analysis.parentPointers.size} found)",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = AssetAmber
                            )
                        }

                        analysis.parentPointers.take(8).forEach { ptr ->
                            ParentPointerRow(ptr = ptr)
                        }
                    }
                }

                // Primary Save & Lock Profile Button (persists into SharedPreferences)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = onSaveAndLockProfile,
                        enabled = analysis.signatureJson != null || feed.signatureJson != null,
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = SignalEmerald,
                            contentColor = OnSignalEmerald,
                            disabledContainerColor = SignalEmerald.copy(alpha = 0.25f),
                            disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant
                        ),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 52.dp)
                            .testTag("save_and_lock_profile_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Save,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.action_save_lock_profile),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    if (saved != null) {
                        OutlinedButton(
                            onClick = onClearSavedProfile,
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, AlertCoral.copy(alpha = 0.65f)),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
                            modifier = Modifier
                                .heightIn(min = 52.dp)
                                .testTag("clear_saved_profile_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.DeleteOutline,
                                contentDescription = stringResource(R.string.action_clear_saved_profile),
                                tint = AlertCoral,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }
        }

        // ±16 Surrounding Memory Block Inspector (when analyzed)
        if (analysis.surroundingSlots.isNotEmpty()) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = ObsidianSurface,
                border = BorderStroke(1.dp, ObsidianBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "±16 Surrounding Memory Block (HEAPF32 / HEAPU32)",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = ElectricCyan,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(ObsidianSurfaceElevated)
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                    )
                    HorizontalDivider(color = ObsidianBorder, thickness = 1.dp)
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(220.dp)
                            .testTag("surrounding_memory_slots_list"),
                        contentPadding = PaddingValues(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        items(analysis.surroundingSlots, key = { it.heapIndex }) { slot ->
                            MemorySlotRow(slot = slot)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ParentPointerRow(ptr: ParentPointerMatch) {
    val ptrHex = "0x${ptr.pointerByteAddress.toString(16).uppercase(Locale.US)}"
    val targetHex = "0x${ptr.targetByteAddress.toString(16).uppercase(Locale.US)}"
    val offsetBadge = if (ptr.isExactMatch) "EXACT" else "+0x${ptr.fieldOffsetBytes.toString(16).uppercase(Locale.US)}"

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(ObsidianSurface)
            .padding(horizontal = 8.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            text = "HEAPU32[${ptr.pointerHeapIndex}] ($ptrHex) -> $targetHex",
            style = MaterialTheme.typography.labelSmall,
            fontFamily = JetBrainsMonoFontFamily,
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 10.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Surface(
            shape = RoundedCornerShape(4.dp),
            color = if (ptr.isExactMatch) SignalEmerald.copy(alpha = 0.2f) else AssetAmber.copy(alpha = 0.2f)
        ) {
            Text(
                text = offsetBadge,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = JetBrainsMonoFontFamily,
                fontWeight = FontWeight.Bold,
                color = if (ptr.isExactMatch) SignalEmerald else AssetAmber,
                fontSize = 9.sp,
                modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
            )
        }
    }
}

@Composable
private fun MemorySlotRow(slot: MemorySlotPreview) {
    val offStr = if (slot.offset >= 0) "+${slot.offset}" else "${slot.offset}"
    val bgColor = when {
        slot.isCoordAxis -> SignalEmerald.copy(alpha = 0.14f)
        slot.isAnchor -> ElectricCyan.copy(alpha = 0.10f)
        else -> ObsidianBg
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(5.dp))
            .background(bgColor)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = offStr.padStart(3, ' '),
            style = MaterialTheme.typography.labelSmall,
            fontFamily = JetBrainsMonoFontFamily,
            fontWeight = FontWeight.Bold,
            color = when {
                slot.isCoordAxis -> SignalEmerald
                slot.isAnchor -> ElectricCyan
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            }
        )
        Text(
            text = slot.uint32Hex,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = JetBrainsMonoFontFamily,
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 10.sp
        )
        Text(
            text = String.format(Locale.US, "f32=%.3f", slot.floatValue),
            style = MaterialTheme.typography.labelSmall,
            fontFamily = JetBrainsMonoFontFamily,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 10.sp,
            modifier = Modifier.weight(1f)
        )
        if (slot.isCoordAxis) {
            Text(
                text = when (slot.offset) {
                    0 -> "X"
                    1 -> "Y"
                    else -> "Z"
                },
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = SignalEmerald,
                fontSize = 10.sp
            )
        } else if (slot.isAnchor) {
            Text(
                text = "SIG",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = ElectricCyan,
                fontSize = 9.sp
            )
        }
    }
}
