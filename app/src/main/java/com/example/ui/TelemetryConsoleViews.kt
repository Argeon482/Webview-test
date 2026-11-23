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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import com.example.DiagnosticsUiState
import com.example.LogCategory
import com.example.NearbyEntityStruct
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
import com.example.ui.theme.SignalEmerald
import com.example.ui.theme.TelemetryViolet
import java.util.Locale

/**
 * Entities Tab: displays repeating `(X, Y, Z)` float triplets discovered within ±50,000 indices
 * of the player's `window.HEAPF32` base index.
 */
@Composable
fun NearbyEntitiesTabView(
    state: DiagnosticsUiState,
    onTogglePinEntity: (Int) -> Unit,
    onCopyEntity: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.background(ObsidianBg)) {
        // Summary strip
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(ObsidianSurface)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = "Nearby Structs: ${state.discoveredEntities.size} (±${state.entityScanRadius} window)",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = TelemetryViolet
                )
                val metaLine = buildString {
                    append("Origin: HEAPF32[${state.heapFeed.baseIndex}]")
                    state.entityDetectedStride?.let { append(" • Stride: $it floats (${it * 4}B)") }
                    state.entityScanDurationMs?.let { append(" • ${String.format(Locale.US, "%.0f", it)}ms") }
                }
                Text(
                    text = metaLine,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = JetBrainsMonoFontFamily,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 10.sp
                )
            }

            if (state.isScanningEntities) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = TelemetryViolet
                )
            }
        }

        HorizontalDivider(color = ObsidianBorder.copy(alpha = 0.8f), thickness = 1.dp)

        if (state.discoveredEntities.isEmpty()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(20.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Radar,
                        contentDescription = null,
                        tint = TelemetryViolet.copy(alpha = 0.8f),
                        modifier = Modifier.size(30.dp)
                    )
                    Text(
                        text = stringResource(R.string.entities_empty_title),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = stringResource(R.string.entities_empty_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .testTag("nearby_entities_list"),
                contentPadding = PaddingValues(10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(state.discoveredEntities, key = { it.baseIndex }) { entity ->
                    NearbyEntityCard(
                        entity = entity,
                        onTogglePin = { onTogglePinEntity(entity.baseIndex) },
                        onCopy = {
                            val hexAddr = "0x${entity.byteAddress.toString(16).uppercase(Locale.US)}"
                            val copyText = "window.HEAPF32[${entity.baseIndex}..${entity.baseIndex + 2}] ($hexAddr, Δ${entity.offsetFromPlayer}) " +
                                "X=${String.format(Locale.US, "%.3f", entity.x)} " +
                                "Y=${String.format(Locale.US, "%.3f", entity.y)} " +
                                "Z=${String.format(Locale.US, "%.3f", entity.z)} " +
                                "dist=${String.format(Locale.US, "%.2f", entity.distanceToPlayer)}"
                            onCopyEntity(copyText)
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun NearbyEntityCard(
    entity: NearbyEntityStruct,
    onTogglePin: () -> Unit,
    onCopy: () -> Unit
) {
    val hexAddr = remember(entity.byteAddress) {
        "0x${entity.byteAddress.toString(16).uppercase(Locale.US)}"
    }
    val offsetLabel = remember(entity.offsetFromPlayer) {
        if (entity.offsetFromPlayer >= 0) "+${entity.offsetFromPlayer}" else "${entity.offsetFromPlayer}"
    }
    val borderColor = if (entity.isPinned) AssetAmber.copy(alpha = 0.85f) else ObsidianBorder

    Surface(
        shape = RoundedCornerShape(10.dp),
        color = ObsidianSurface,
        border = BorderStroke(1.dp, borderColor),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("entity_card_${entity.baseIndex}")
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = TelemetryViolet.copy(alpha = 0.2f),
                    border = BorderStroke(1.dp, TelemetryViolet.copy(alpha = 0.55f))
                ) {
                    Text(
                        text = "[${entity.baseIndex}]",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = JetBrainsMonoFontFamily,
                        fontWeight = FontWeight.Bold,
                        color = TelemetryViolet,
                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                    )
                }

                Text(
                    text = "$hexAddr • Δ$offsetLabel",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = JetBrainsMonoFontFamily,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )

                if (entity.strideGroup > 0) {
                    Surface(
                        shape = RoundedCornerShape(5.dp),
                        color = ElectricCyan.copy(alpha = 0.16f)
                    ) {
                        Text(
                            text = "stride ${entity.strideGroup}",
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = JetBrainsMonoFontFamily,
                            fontWeight = FontWeight.SemiBold,
                            color = ElectricCyan,
                            fontSize = 9.sp,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }

                Surface(
                    shape = RoundedCornerShape(5.dp),
                    color = SignalEmerald.copy(alpha = 0.16f)
                ) {
                    Text(
                        text = "${String.format(Locale.US, "%.1f", entity.distanceToPlayer)}m",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = JetBrainsMonoFontFamily,
                        fontWeight = FontWeight.Bold,
                        color = SignalEmerald,
                        fontSize = 10.sp,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(7.dp))
                        .background(ObsidianBg)
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "X ${String.format(Locale.US, "%.2f", entity.x)}",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = JetBrainsMonoFontFamily,
                        fontWeight = FontWeight.SemiBold,
                        color = SignalEmerald
                    )
                    Text(
                        text = "Y ${String.format(Locale.US, "%.2f", entity.y)}",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = JetBrainsMonoFontFamily,
                        fontWeight = FontWeight.SemiBold,
                        color = ElectricCyan
                    )
                    Text(
                        text = "Z ${String.format(Locale.US, "%.2f", entity.z)}",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = JetBrainsMonoFontFamily,
                        fontWeight = FontWeight.SemiBold,
                        color = TelemetryViolet
                    )
                }

                IconButton(
                    onClick = onTogglePin,
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("pin_entity_${entity.baseIndex}")
                ) {
                    Icon(
                        imageVector = if (entity.isPinned) Icons.Filled.PushPin else Icons.Outlined.PushPin,
                        contentDescription = stringResource(R.string.cd_pin_entity),
                        tint = if (entity.isPinned) AssetAmber else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }

                IconButton(
                    onClick = onCopy,
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("copy_entity_${entity.baseIndex}")
                ) {
                    Icon(
                        imageVector = Icons.Default.ContentCopy,
                        contentDescription = stringResource(R.string.cd_copy_log),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}

/**
 * Logs Tab: category-filterable rolling telemetry log viewer with `.txt` / `.json` full log dump
 * export and share controls.
 */
@Composable
fun LiveLogConsoleView(
    state: DiagnosticsUiState,
    filteredLogs: List<TelemetryLogEntry>,
    onSelectFilter: (LogCategory) -> Unit,
    onClearLogs: () -> Unit,
    onExportLogDumpTxt: () -> Unit,
    onExportLogDumpJson: () -> Unit,
    onShareLastLogDump: () -> Unit,
    onCopyLog: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.background(ObsidianBg)) {
        // Export & Clear Toolbar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(ObsidianSurface)
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Surface(
                onClick = onExportLogDumpTxt,
                enabled = !state.isExportingLogDump,
                shape = RoundedCornerShape(8.dp),
                color = SignalEmerald.copy(alpha = 0.18f),
                border = BorderStroke(1.dp, SignalEmerald.copy(alpha = 0.7f)),
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .weight(1f)
                    .testTag("export_log_dump_txt_button")
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    if (state.isExportingLogDump) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(15.dp),
                            strokeWidth = 2.dp,
                            color = SignalEmerald
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.Download,
                            contentDescription = null,
                            tint = SignalEmerald,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    Text(
                        text = " " + stringResource(R.string.action_export_log_dump_txt),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = SignalEmerald,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Surface(
                onClick = onExportLogDumpJson,
                enabled = !state.isExportingLogDump,
                shape = RoundedCornerShape(8.dp),
                color = ElectricCyan.copy(alpha = 0.16f),
                border = BorderStroke(1.dp, ElectricCyan.copy(alpha = 0.65f)),
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag("export_log_dump_json_button")
            ) {
                Box(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(R.string.action_export_log_dump_json),
                        style = MaterialTheme.typography.labelMedium,
                        fontFamily = JetBrainsMonoFontFamily,
                        fontWeight = FontWeight.Bold,
                        color = ElectricCyan
                    )
                }
            }

            if (state.lastLogDumpExport?.success == true) {
                IconButton(
                    onClick = onShareLastLogDump,
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("share_log_dump_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Share,
                        contentDescription = stringResource(R.string.cd_share_log_dump),
                        tint = SignalEmerald,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            IconButton(
                onClick = onClearLogs,
                modifier = Modifier
                    .size(48.dp)
                    .testTag("clear_logs_button")
            ) {
                Icon(
                    imageVector = Icons.Default.DeleteOutline,
                    contentDescription = stringResource(R.string.cd_clear_logs),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(19.dp)
                )
            }
        }

        // Category Filter Row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(ObsidianSurface)
                .horizontalScroll(rememberScrollState())
                .padding(start = 10.dp, end = 10.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            LogCategory.entries.forEach { category ->
                val selected = state.selectedFilter == category
                val accent = categoryAccentColor(category)
                Surface(
                    onClick = { onSelectFilter(category) },
                    shape = RoundedCornerShape(6.dp),
                    color = if (selected) accent.copy(alpha = 0.22f) else ObsidianBg,
                    border = BorderStroke(1.dp, if (selected) accent else ObsidianBorder),
                    modifier = Modifier.testTag("log_filter_${category.name.lowercase(Locale.US)}")
                ) {
                    Text(
                        text = category.label,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                        color = if (selected) accent else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                    )
                }
            }
        }

        HorizontalDivider(color = ObsidianBorder.copy(alpha = 0.75f), thickness = 1.dp)

        if (filteredLogs.isEmpty()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(20.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Terminal,
                        contentDescription = null,
                        tint = ElectricCyan.copy(alpha = 0.75f),
                        modifier = Modifier.size(28.dp)
                    )
                    Text(
                        text = stringResource(R.string.console_empty_title),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = stringResource(R.string.console_empty_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .testTag("telemetry_log_list"),
                contentPadding = PaddingValues(10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(filteredLogs, key = { it.id }) { entry ->
                    LogEntryCard(
                        entry = entry,
                        onCopy = { onCopyLog("[${entry.timestamp}] [${entry.category.tag}] ${entry.summary}\n${entry.payload}") }
                    )
                }
            }
        }
    }
}

@Composable
private fun LogEntryCard(
    entry: TelemetryLogEntry,
    onCopy: () -> Unit
) {
    var expanded by remember(entry.id) { mutableStateOf(false) }
    val accent = categoryAccentColor(entry.category)

    Surface(
        shape = RoundedCornerShape(8.dp),
        color = ObsidianSurface,
        border = BorderStroke(1.dp, if (expanded) accent.copy(alpha = 0.65f) else ObsidianBorder),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded }
            .testTag("log_entry_${entry.id}")
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = accent.copy(alpha = 0.18f)
                ) {
                    Text(
                        text = entry.category.tag,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = accent,
                        fontSize = 9.sp,
                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                    )
                }

                Text(
                    text = entry.summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = if (expanded) 4 else 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )

                Text(
                    text = entry.timestamp,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = JetBrainsMonoFontFamily,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 9.sp
                )

                IconButton(
                    onClick = onCopy,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.ContentCopy,
                        contentDescription = stringResource(R.string.cd_copy_log),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }

            if (expanded && entry.payload.isNotBlank()) {
                Text(
                    text = entry.payload,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = JetBrainsMonoFontFamily,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 10.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(6.dp))
                        .background(ObsidianSurfaceElevated)
                        .padding(8.dp)
                )
            }
        }
    }
}

fun categoryAccentColor(category: LogCategory): Color = when (category) {
    LogCategory.ALL -> ElectricCyan
    LogCategory.FEED -> SignalEmerald
    LogCategory.SCAN -> ElectricCyan
    LogCategory.PTR -> TelemetryViolet
    LogCategory.RELOCK -> ElectricCyan
    LogCategory.ENTITY -> TelemetryViolet
    LogCategory.ASSET -> AssetAmber
    LogCategory.JS -> ElectricCyan
    LogCategory.SYSTEM -> Color(0xFF94A3B8)
    LogCategory.ERROR -> AlertCoral
}
