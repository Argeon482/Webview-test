package com.example.ui

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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FilterAlt
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.WasmStructFieldKind
import com.example.WasmStructProbeCandidate
import com.example.WasmStructProberState
import com.example.ui.theme.AlertCoral
import com.example.ui.theme.AssetAmber
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.ObsidianBg
import com.example.ui.theme.ObsidianBorder
import com.example.ui.theme.ObsidianSurface
import com.example.ui.theme.ObsidianSurfaceElevated
import com.example.ui.theme.SignalEmerald
import com.example.ui.theme.TelemetryViolet

/**
 * Semantic accent color for each entity struct field (`HP`, `MP`, `FP`, `Name`).
 */
fun structFieldAccent(kind: WasmStructFieldKind): Color = when (kind) {
    WasmStructFieldKind.HP, WasmStructFieldKind.MAX_HP -> AlertCoral
    WasmStructFieldKind.MP, WasmStructFieldKind.MAX_MP -> ElectricCyan
    WasmStructFieldKind.FP, WasmStructFieldKind.MAX_FP -> AssetAmber
    WasmStructFieldKind.NAME -> TelemetryViolet
}

/**
 * Compact color-coded vital stat badge with an optional 3dp micro-progress bar,
 * shared by both the persistent Top HUD Strip and Entity Cards.
 */
@Composable
fun VitalGaugePill(
    label: String,
    current: Int,
    max: Int?,
    accent: Color,
    modifier: Modifier = Modifier
) {
    val ratio = if (max != null && max > 0) {
        (current.toFloat() / max.toFloat()).coerceIn(0f, 1f)
    } else null
    val valueText = if (max != null && max > 0) "$current/$max" else "$current"

    Surface(
        shape = RoundedCornerShape(5.dp),
        color = accent.copy(alpha = 0.13f),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.45f)),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = accent,
                    fontSize = 9.sp
                )
                Text(
                    text = valueText,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = JetBrainsMonoFontFamily,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 10.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (ratio != null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(ObsidianBg.copy(alpha = 0.85f))
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(ratio)
                            .height(3.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(accent)
                    )
                }
            }
        }
    }
}

/**
 * Collapsible read-only Entity Struct Field Prober embedded in the Entities tab.
 * Probes offsets relative to the live player coordinate address (plus 1-hop sub-struct pointers)
 * for HP / MaxHP / MP / MaxMP / FP / MaxFP and Mob/Player Names, cross-checks nearby entities,
 * and persists bound offsets per host.
 */
@Composable
fun StructProberPanel(
    prober: WasmStructProberState,
    hasPlayerAnchor: Boolean,
    trackedMobCount: Int,
    onToggleExpanded: () -> Unit,
    onSelectField: (WasmStructFieldKind) -> Unit,
    onInputChanged: (String) -> Unit,
    onRunProbe: () -> Unit,
    onRunRefine: () -> Unit,
    onRunAutoProbeNames: () -> Unit,
    onBindCandidate: (WasmStructProbeCandidate) -> Unit,
    onClearField: (WasmStructFieldKind) -> Unit,
    onClearAllFields: () -> Unit,
    modifier: Modifier = Modifier
) {
    val activeField = prober.activeField
    val activeAccent = structFieldAccent(activeField)
    val schema = prober.schema

    Surface(
        shape = RoundedCornerShape(9.dp),
        color = ObsidianSurface,
        border = BorderStroke(1.dp, if (schema.hasAnyBound) SignalEmerald.copy(alpha = 0.55f) else ObsidianBorder),
        modifier = modifier
            .fillMaxWidth()
            .testTag("struct_prober_panel")
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Collapsible Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .clickable(onClick = onToggleExpanded)
                    .padding(horizontal = 10.dp, vertical = 7.dp)
                    .testTag("struct_prober_header"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Memory,
                    contentDescription = null,
                    tint = if (schema.hasAnyBound) SignalEmerald else TelemetryViolet,
                    modifier = Modifier.size(16.dp)
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.struct_prober_title),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    val summaryText = if (schema.hasAnyBound) {
                        val boundLabels = WasmStructFieldKind.entries
                            .filter { schema.fieldFor(it) != null }
                            .joinToString(" • ") { "${it.shortLabel}✓" }
                        "$boundLabels" + if (schema.isSavedLocally) " (Saved)" else ""
                    } else if (hasPlayerAnchor) {
                        "Probe HP / MP / FP & Names relative to live player pointer"
                    } else {
                        "Activate a live WASM player pointer to probe struct offsets"
                    }
                    Text(
                        text = summaryText,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = JetBrainsMonoFontFamily,
                        color = if (schema.hasAnyBound) SignalEmerald else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 9.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Icon(
                    imageVector = if (prober.isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = stringResource(R.string.cd_toggle_struct_prober),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }

            AnimatedVisibility(
                visible = prober.isExpanded,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 10.dp, end = 10.dp, bottom = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(7.dp)
                ) {
                    HorizontalDivider(color = ObsidianBorder, thickness = 1.dp)

                    // Field Selector Pills: HP, MaxHP, MP, MaxMP, FP, MaxFP, Name
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        WasmStructFieldKind.entries.forEach { kind ->
                            val isSelected = kind == activeField
                            val isBound = schema.fieldFor(kind) != null
                            val accent = structFieldAccent(kind)
                            Surface(
                                onClick = { onSelectField(kind) },
                                shape = RoundedCornerShape(6.dp),
                                color = when {
                                    isSelected -> accent.copy(alpha = 0.24f)
                                    isBound -> accent.copy(alpha = 0.10f)
                                    else -> ObsidianBg
                                },
                                border = BorderStroke(
                                    1.dp,
                                    when {
                                        isSelected -> accent
                                        isBound -> accent.copy(alpha = 0.55f)
                                        else -> ObsidianBorder
                                    }
                                ),
                                modifier = Modifier
                                    .heightIn(min = 36.dp)
                                    .testTag("struct_field_pill_${kind.jsonKey}")
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(3.dp)
                                ) {
                                    Text(
                                        text = kind.shortLabel,
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = if (isSelected || isBound) FontWeight.Bold else FontWeight.Medium,
                                        color = if (isSelected || isBound) accent else MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontSize = 10.sp
                                    )
                                    if (isBound) {
                                        Icon(
                                            imageVector = Icons.Default.CheckCircle,
                                            contentDescription = null,
                                            tint = SignalEmerald,
                                            modifier = Modifier.size(11.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Bound Schema Chips Strip (tap × to unbind a single field)
                    if (schema.hasAnyBound) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(5.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            WasmStructFieldKind.entries.forEach { kind ->
                                val bound = schema.fieldFor(kind) ?: return@forEach
                                val accent = structFieldAccent(kind)
                                Surface(
                                    onClick = { onClearField(kind) },
                                    shape = RoundedCornerShape(5.dp),
                                    color = ObsidianBg,
                                    border = BorderStroke(1.dp, accent.copy(alpha = 0.6f)),
                                    modifier = Modifier.testTag("bound_schema_chip_${kind.jsonKey}")
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Text(
                                            text = "${kind.shortLabel} ${bound.displayExpression}",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontFamily = JetBrainsMonoFontFamily,
                                            fontWeight = FontWeight.Bold,
                                            color = accent,
                                            fontSize = 9.sp
                                        )
                                        Icon(
                                            imageVector = Icons.Default.Close,
                                            contentDescription = stringResource(R.string.cd_clear_bound_field),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(11.dp)
                                        )
                                    }
                                }
                            }
                            Surface(
                                onClick = onClearAllFields,
                                shape = RoundedCornerShape(5.dp),
                                color = AlertCoral.copy(alpha = 0.12f),
                                border = BorderStroke(1.dp, AlertCoral.copy(alpha = 0.5f)),
                                modifier = Modifier.testTag("clear_all_struct_schema_button")
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(3.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.DeleteSweep,
                                        contentDescription = null,
                                        tint = AlertCoral,
                                        modifier = Modifier.size(11.dp)
                                    )
                                    Text(
                                        text = stringResource(R.string.action_clear_schema),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = AlertCoral,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 9.sp
                                    )
                                }
                            }
                        }
                    }

                    // Contextual instruction
                    Text(
                        text = stringResource(
                            if (activeField.isNameField) R.string.struct_prober_hint_name
                            else R.string.struct_prober_hint_stat
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 10.sp
                    )

                    // Input + Action Controls Row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        OutlinedTextField(
                            value = prober.queryInput,
                            onValueChange = onInputChanged,
                            singleLine = true,
                            placeholder = {
                                Text(
                                    text = stringResource(
                                        if (activeField.isNameField) R.string.struct_prober_name_placeholder
                                        else R.string.struct_prober_stat_placeholder
                                    ),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontFamily = JetBrainsMonoFontFamily,
                                    fontSize = 11.sp
                                )
                            },
                            textStyle = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = JetBrainsMonoFontFamily,
                                color = MaterialTheme.colorScheme.onSurface
                            ),
                            keyboardOptions = KeyboardOptions(
                                keyboardType = if (activeField.isNameField) KeyboardType.Text else KeyboardType.Number,
                                imeAction = ImeAction.Search
                            ),
                            keyboardActions = KeyboardActions(
                                onSearch = { onRunProbe() }
                            ),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = activeAccent,
                                unfocusedBorderColor = ObsidianBorder,
                                focusedContainerColor = ObsidianBg,
                                unfocusedContainerColor = ObsidianBg
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 48.dp)
                                .testTag("struct_prober_input")
                        )

                        if (activeField.isNameField) {
                            Button(
                                onClick = onRunAutoProbeNames,
                                enabled = !prober.isProbing && (hasPlayerAnchor || trackedMobCount > 0),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = TelemetryViolet,
                                    contentColor = ObsidianBg
                                ),
                                shape = RoundedCornerShape(7.dp),
                                modifier = Modifier
                                    .heightIn(min = 48.dp)
                                    .testTag("struct_auto_probe_names_button")
                            ) {
                                if (prober.isProbing) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(14.dp),
                                        strokeWidth = 2.dp,
                                        color = ObsidianBg
                                    )
                                } else {
                                    Icon(
                                        imageVector = Icons.Default.AutoAwesome,
                                        contentDescription = null,
                                        modifier = Modifier.size(15.dp)
                                    )
                                    Spacer(Modifier.width(4.dp))
                                    Text(
                                        text = stringResource(R.string.action_auto_probe_names),
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                            if (prober.queryInput.isNotBlank()) {
                                OutlinedButton(
                                    onClick = onRunProbe,
                                    enabled = !prober.isProbing,
                                    contentPadding = PaddingValues(horizontal = 9.dp, vertical = 6.dp),
                                    border = BorderStroke(1.dp, TelemetryViolet.copy(alpha = 0.7f)),
                                    shape = RoundedCornerShape(7.dp),
                                    modifier = Modifier
                                        .heightIn(min = 48.dp)
                                        .testTag("struct_search_names_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Search,
                                        contentDescription = null,
                                        tint = TelemetryViolet,
                                        modifier = Modifier.size(15.dp)
                                    )
                                }
                            }
                        } else {
                            Button(
                                onClick = onRunProbe,
                                enabled = !prober.isProbing && hasPlayerAnchor && prober.queryInput.isNotBlank(),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = activeAccent,
                                    contentColor = ObsidianBg
                                ),
                                shape = RoundedCornerShape(7.dp),
                                modifier = Modifier
                                    .heightIn(min = 48.dp)
                                    .testTag("struct_probe_button")
                            ) {
                                if (prober.isProbing) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(14.dp),
                                        strokeWidth = 2.dp,
                                        color = ObsidianBg
                                    )
                                } else {
                                    Icon(
                                        imageVector = Icons.Default.Search,
                                        contentDescription = null,
                                        modifier = Modifier.size(15.dp)
                                    )
                                    Spacer(Modifier.width(4.dp))
                                    Text(
                                        text = stringResource(R.string.action_probe_struct),
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }

                            if (prober.candidates.isNotEmpty()) {
                                OutlinedButton(
                                    onClick = onRunRefine,
                                    enabled = !prober.isProbing && prober.queryInput.isNotBlank(),
                                    contentPadding = PaddingValues(horizontal = 9.dp, vertical = 6.dp),
                                    border = BorderStroke(1.dp, SignalEmerald.copy(alpha = 0.75f)),
                                    shape = RoundedCornerShape(7.dp),
                                    modifier = Modifier
                                        .heightIn(min = 48.dp)
                                        .testTag("struct_refine_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.FilterAlt,
                                        contentDescription = null,
                                        tint = SignalEmerald,
                                        modifier = Modifier.size(15.dp)
                                    )
                                    Spacer(Modifier.width(3.dp))
                                    Text(
                                        text = stringResource(R.string.action_refine_struct),
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = SignalEmerald
                                    )
                                }
                            }
                        }
                    }

                    if (prober.statusMessage.isNotBlank()) {
                        Text(
                            text = prober.statusMessage,
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = JetBrainsMonoFontFamily,
                            color = activeAccent,
                            fontSize = 10.sp
                        )
                    }

                    // Candidate List with Cross-Mob Verification Preview & Bind Button
                    if (prober.candidates.isNotEmpty()) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("struct_probe_candidates_list"),
                            verticalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            prober.candidates.take(10).forEach { cand ->
                                StructProbeCandidateRow(
                                    candidate = cand,
                                    accent = activeAccent,
                                    onBind = { onBindCandidate(cand) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StructProbeCandidateRow(
    candidate: WasmStructProbeCandidate,
    accent: Color,
    onBind: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(7.dp),
        color = ObsidianBg,
        border = BorderStroke(
            1.dp,
            if (candidate.nonZeroMobCount > 0) SignalEmerald.copy(alpha = 0.55f) else ObsidianBorder
        ),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("struct_candidate_${candidate.id}")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Text(
                        text = candidate.expression,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = JetBrainsMonoFontFamily,
                        fontWeight = FontWeight.Bold,
                        color = accent,
                        fontSize = 10.sp
                    )
                    Surface(
                        color = ObsidianSurfaceElevated,
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Text(
                            text = "Player: ${candidate.playerDisplayValue}",
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = JetBrainsMonoFontFamily,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = 9.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                        )
                    }
                    if (candidate.adjacentMaxValue != null) {
                        Text(
                            text = "Max~${candidate.adjacentMaxValue}",
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = JetBrainsMonoFontFamily,
                            color = SignalEmerald,
                            fontSize = 9.sp
                        )
                    }
                }
                if (candidate.mobPreviews.isNotEmpty()) {
                    Text(
                        text = "Mobs: ${candidate.mobPreviews.joinToString("  ")}",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = JetBrainsMonoFontFamily,
                        color = if (candidate.nonZeroMobCount > 0) SignalEmerald else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 9.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Surface(
                onClick = onBind,
                shape = RoundedCornerShape(6.dp),
                color = SignalEmerald.copy(alpha = 0.2f),
                border = BorderStroke(1.dp, SignalEmerald.copy(alpha = 0.75f)),
                modifier = Modifier
                    .heightIn(min = 40.dp)
                    .testTag("bind_struct_candidate_${candidate.id}")
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = stringResource(R.string.action_bind_offset),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = SignalEmerald
                    )
                }
            }
        }
    }
}
