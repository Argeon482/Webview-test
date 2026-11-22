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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.ViewInAr
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.CandidateStagingItem
import com.example.DiagnosticsUiState
import com.example.DiscoveredEntitySample
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
import com.example.ui.theme.SignalEmerald
import com.example.ui.theme.TelemetryViolet
import java.util.Locale

/**
 * Multi-Scan Candidate Staging Buffer & Accumulation Drawer.
 * Preserves candidates across repeated Step 1 & Step 2 scans, supports pinning/bookmarking
 * items of interest, and lets developers lock any candidate into 200ms live coordinate polling.
 */
@Composable
fun StagingBufferView(
    state: DiagnosticsUiState,
    onTogglePin: (String) -> Unit,
    onTogglePinnedOnly: () -> Unit,
    onClearUnpinned: () -> Unit,
    onTrackLive: (String) -> Unit,
    onCopyPath: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val visibleItems = remember(state.stagingBuffer, state.showPinnedOnly) {
        if (state.showPinnedOnly) state.stagingBuffer.filter { it.isPinned }
        else state.stagingBuffer
    }

    Column(modifier = modifier.background(ObsidianBg)) {
        // Staging Buffer Toolbar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(ObsidianSurface)
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = "Scans: ${state.scanCounter} • Total: ${state.stagingBuffer.size}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )

            Surface(
                onClick = onTogglePinnedOnly,
                shape = RoundedCornerShape(6.dp),
                color = if (state.showPinnedOnly) AssetAmber.copy(alpha = 0.22f) else ObsidianBg,
                border = BorderStroke(
                    1.dp,
                    if (state.showPinnedOnly) AssetAmber else ObsidianBorder
                ),
                modifier = Modifier.testTag("staging_pinned_filter_button")
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Bookmark,
                        contentDescription = null,
                        tint = if (state.showPinnedOnly) AssetAmber else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(13.dp)
                    )
                    Text(
                        text = "Pinned (${state.pinnedCandidateCount})",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = if (state.showPinnedOnly) AssetAmber else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Surface(
                onClick = onClearUnpinned,
                shape = RoundedCornerShape(6.dp),
                color = ObsidianBg,
                border = BorderStroke(1.dp, ObsidianBorder),
                modifier = Modifier.testTag("staging_clear_unpinned_button")
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.DeleteOutline,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(13.dp)
                    )
                    Text(
                        text = "Prune",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        HorizontalDivider(color = ObsidianBorder.copy(alpha = 0.7f), thickness = 1.dp)

        if (visibleItems.isEmpty()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(16.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.BookmarkBorder,
                        contentDescription = null,
                        tint = AssetAmber.copy(alpha = 0.75f),
                        modifier = Modifier.size(28.dp)
                    )
                    Text(
                        text = if (state.showPinnedOnly) "No Pinned Candidates Yet" else stringResource(R.string.staging_empty_title),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = if (state.showPinnedOnly) {
                            "Tap the bookmark icon on any discovered path to pin it for JSON export."
                        } else {
                            stringResource(R.string.staging_empty_subtitle)
                        },
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
                    .testTag("staging_candidate_list"),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(visibleItems, key = { it.path }) { item ->
                    StagingCandidateCard(
                        item = item,
                        isActiveLiveTarget = state.discoveredPlayerPath == item.path,
                        onTogglePin = { onTogglePin(item.path) },
                        onTrackLive = { onTrackLive(item.path) },
                        onCopyPath = { onCopyPath(item.path) }
                    )
                }
            }
        }
    }
}

@Composable
private fun StagingCandidateCard(
    item: CandidateStagingItem,
    isActiveLiveTarget: Boolean,
    onTogglePin: () -> Unit,
    onTrackLive: () -> Unit,
    onCopyPath: () -> Unit
) {
    val borderColor = when {
        isActiveLiveTarget -> SignalEmerald
        item.isPinned -> AssetAmber.copy(alpha = 0.75f)
        else -> ObsidianBorder
    }

    Surface(
        shape = RoundedCornerShape(8.dp),
        color = ObsidianSurface,
        border = BorderStroke(1.dp, borderColor),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("staging_item_${item.path}")
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 7.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Surface(
                    color = ElectricCyan.copy(alpha = 0.16f),
                    shape = RoundedCornerShape(4.dp)
                ) {
                    Text(
                        text = "${item.sourceStep} #${item.scanNumber}",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = ElectricCyan,
                        fontSize = 9.sp,
                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                    )
                }

                if (item.seenCount > 1) {
                    Surface(
                        color = SignalEmerald.copy(alpha = 0.16f),
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Text(
                            text = "×${item.seenCount}",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = SignalEmerald,
                            fontSize = 9.sp,
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                        )
                    }
                }

                if (isActiveLiveTarget) {
                    Surface(
                        color = SignalEmerald.copy(alpha = 0.22f),
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Text(
                            text = "LIVE LOCK",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = SignalEmerald,
                            fontSize = 9.sp,
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                        )
                    }
                }

                Spacer(Modifier.weight(1f))

                IconButton(
                    onClick = onTrackLive,
                    modifier = Modifier
                        .size(32.dp)
                        .testTag("track_candidate_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.MyLocation,
                        contentDescription = stringResource(R.string.cd_track_candidate),
                        tint = if (isActiveLiveTarget) SignalEmerald else ElectricCyan,
                        modifier = Modifier.size(16.dp)
                    )
                }

                IconButton(
                    onClick = onTogglePin,
                    modifier = Modifier
                        .size(32.dp)
                        .testTag("pin_candidate_button")
                ) {
                    Icon(
                        imageVector = if (item.isPinned) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                        contentDescription = stringResource(R.string.cd_pin_candidate),
                        tint = if (item.isPinned) AssetAmber else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(17.dp)
                    )
                }

                IconButton(
                    onClick = onCopyPath,
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
                text = item.path,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = JetBrainsMonoFontFamily,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = item.detail,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = item.lastSeenTimestamp,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 9.sp
                )
            }
        }
    }
}

/**
 * Dedicated Visual Entity Inspection Console (Step 3).
 * Displays total detected entity count, confirmed sibling collection path, projection mode,
 * and a scrollable list of entities with World (X, Y, Z) and 2D Canvas Projections (screenX, screenY).
 */
@Composable
fun EntityInspectionConsoleView(
    state: DiagnosticsUiState,
    onRescanEntities: () -> Unit,
    onPinPath: (path: String, detail: String) -> Unit,
    onCopyPayload: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.background(ObsidianBg)) {
        // Entity Console Summary Header
        Surface(
            color = ObsidianSurface,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = "Detected Entities: ${state.discoveredEntities.size}",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = TelemetryViolet
                        )
                        if (state.entityViewportWidth > 0 && state.entityViewportHeight > 0) {
                            Text(
                                text = "${state.entityViewportWidth}×${state.entityViewportHeight}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Text(
                        text = state.discoveredEntityArrayPath?.let { "Array: $it (${state.entityProjectionMode})" }
                            ?: "Anchor: ${state.discoveredPlayerPath ?: "Complete Step 2B first"}",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = JetBrainsMonoFontFamily,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Surface(
                    onClick = onRescanEntities,
                    enabled = !state.discoveredPlayerPath.isNullOrBlank(),
                    shape = RoundedCornerShape(6.dp),
                    color = if (!state.discoveredPlayerPath.isNullOrBlank()) TelemetryViolet.copy(alpha = 0.2f) else ObsidianSurfaceElevated,
                    border = BorderStroke(
                        1.dp,
                        if (!state.discoveredPlayerPath.isNullOrBlank()) TelemetryViolet else ObsidianBorder
                    ),
                    modifier = Modifier.testTag("entity_console_rescan_button")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = null,
                            tint = if (!state.discoveredPlayerPath.isNullOrBlank()) TelemetryViolet else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(14.dp)
                        )
                        Text(
                            text = "Rescan",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = if (!state.discoveredPlayerPath.isNullOrBlank()) TelemetryViolet else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        HorizontalDivider(color = ObsidianBorder.copy(alpha = 0.7f), thickness = 1.dp)

        if (state.discoveredEntities.isEmpty()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(16.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.ViewInAr,
                        contentDescription = null,
                        tint = TelemetryViolet.copy(alpha = 0.75f),
                        modifier = Modifier.size(28.dp)
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
                    .testTag("entity_inspection_list"),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(state.discoveredEntities, key = { "${it.path}_${it.id}" }) { entity ->
                    EntityInspectionCard(
                        entity = entity,
                        onPin = { onPinPath(entity.path, "${entity.name} (${fmt2(entity.worldX)}, ${fmt2(entity.worldY)}, ${fmt2(entity.worldZ)})") },
                        onCopy = {
                            onCopyPayload("${entity.name} [${entity.path}] world=(${fmt2(entity.worldX)}, ${fmt2(entity.worldY)}, ${fmt2(entity.worldZ)}) screen=(${fmt1(entity.screenX)}, ${fmt1(entity.screenY)})")
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun EntityInspectionCard(
    entity: DiscoveredEntitySample,
    onPin: () -> Unit,
    onCopy: () -> Unit
) {
    val viewTint = if (entity.inViewport) SignalEmerald else AssetAmber
    val viewLabel = if (entity.inViewport) "IN VIEW" else "OFFSCREEN"

    Surface(
        shape = RoundedCornerShape(8.dp),
        color = ObsidianSurface,
        border = BorderStroke(1.dp, TelemetryViolet.copy(alpha = 0.45f)),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("entity_card_${entity.path}")
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 7.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = entity.name + if (entity.id.isNotBlank()) " #${entity.id}" else "",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Surface(
                    color = viewTint.copy(alpha = 0.16f),
                    shape = RoundedCornerShape(4.dp),
                    border = BorderStroke(1.dp, viewTint.copy(alpha = 0.5f))
                ) {
                    Text(
                        text = viewLabel,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = viewTint,
                        fontSize = 9.sp,
                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                    )
                }
                IconButton(onClick = onPin, modifier = Modifier.size(28.dp)) {
                    Icon(
                        imageVector = Icons.Default.BookmarkBorder,
                        contentDescription = stringResource(R.string.cd_pin_candidate),
                        tint = AssetAmber,
                        modifier = Modifier.size(15.dp)
                    )
                }
                IconButton(onClick = onCopy, modifier = Modifier.size(28.dp)) {
                    Icon(
                        imageVector = Icons.Default.ContentCopy,
                        contentDescription = stringResource(R.string.cd_copy_log),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Surface(
                    color = ObsidianBg,
                    shape = RoundedCornerShape(5.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Column(modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp)) {
                        Text(
                            text = "WORLD (X, Y, Z) • d=${fmt1(entity.distanceToPlayer)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 9.sp
                        )
                        Text(
                            text = "${fmt2(entity.worldX)}, ${fmt2(entity.worldY)}, ${fmt2(entity.worldZ)}",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = JetBrainsMonoFontFamily,
                            color = ElectricCyan,
                            fontSize = 10.sp
                        )
                    }
                }

                Surface(
                    color = ObsidianBg,
                    shape = RoundedCornerShape(5.dp),
                    modifier = Modifier.weight(0.9f)
                ) {
                    Column(modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp)) {
                        Text(
                            text = "2D CANVAS (screenX, Y)",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 9.sp
                        )
                        Text(
                            text = "${fmt1(entity.screenX)}px, ${fmt1(entity.screenY)}px",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = JetBrainsMonoFontFamily,
                            fontWeight = FontWeight.Bold,
                            color = TelemetryViolet,
                            fontSize = 10.sp
                        )
                    }
                }
            }

            Text(
                text = entity.path,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = JetBrainsMonoFontFamily,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontSize = 9.sp
            )
        }
    }
}

@Composable
fun LiveLogConsoleView(
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

internal fun categoryAccentColor(category: LogCategory): Color = when (category) {
    LogCategory.ALL -> ElectricCyan
    LogCategory.SCOPE -> ElectricCyan
    LogCategory.DELTA -> SignalEmerald
    LogCategory.ENTITY -> TelemetryViolet
    LogCategory.ASSET -> AssetAmber
    LogCategory.JS -> Color(0xFF60A5FA)
    LogCategory.SYSTEM -> Color(0xFF94A3B8)
    LogCategory.ERROR -> AlertCoral
}

private fun fmt1(value: Double): String = String.format(Locale.US, "%.1f", value)
private fun fmt2(value: Double): String = String.format(Locale.US, "%.2f", value)
