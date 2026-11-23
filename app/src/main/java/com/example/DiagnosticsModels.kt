package com.example

import android.net.Uri
import androidx.compose.runtime.Immutable

enum class AsyncScanStatus(val label: String) {
    IDLE("Idle"),
    RUNNING("Scanning"),
    PAUSED("Paused"),
    COMPLETED("Completed"),
    ERROR("Error");

    companion object {
        fun fromRaw(raw: String): AsyncScanStatus {
            return when (raw.trim().uppercase()) {
                "RUNNING", "STARTED", "SCANNING" -> RUNNING
                "PAUSED", "PAUSE" -> PAUSED
                "COMPLETED", "DONE", "SUCCESS" -> COMPLETED
                "ERROR", "FAILED" -> ERROR
                else -> IDLE
            }
        }
    }
}

@Immutable
data class ScopeBatchProgress(
    val isActive: Boolean = false,
    val isPaused: Boolean = false,
    val currentBatch: Int = 0,
    val totalBatches: Int = 0,
    val itemStart: Int = 0,
    val itemEnd: Int = 0,
    val totalItems: Int = 0,
    val foundCount: Int = 0
) {
    val fraction: Float
        get() = if (totalBatches > 0) (currentBatch.toFloat() / totalBatches.toFloat()).coerceIn(0f, 1f) else 0f

    val statusText: String
        get() = when {
            isPaused && totalBatches > 0 ->
                "Paused at batch $currentBatch/$totalBatches (items $itemStart–$itemEnd) • $foundCount custom"
            isActive && totalBatches > 0 ->
                "Scanning batch $currentBatch/$totalBatches (items $itemStart–$itemEnd)…"
            totalBatches > 0 && currentBatch >= totalBatches ->
                "Completed $totalBatches/$totalBatches batches ($totalItems keys) • $foundCount custom"
            else -> ""
        }
}

enum class WizardStepBadge(val displayText: String) {
    PENDING("[PENDING]"),
    READY("[READY]"),
    CAPTURED("[CAPTURED ✓]")
}

enum class ConsoleTab {
    WIZARD,
    WASM_HEAP,
    STAGING,
    ENTITIES,
    ASSETS,
    LOGS
}

enum class AssetCaptureStatus(val label: String) {
    OBSERVED("Observed"),
    CACHING("Syncing"),
    CAPTURED("Cached ✓"),
    FAILED("Metadata Only")
}

enum class LogCategory(val tag: String, val label: String) {
    ALL("ALL", "All"),
    SCOPE("SCOPE", "Scope"),
    DELTA("DELTA", "Delta"),
    ENTITY("ENTITY", "Entity"),
    WASM("WASM", "WASM"),
    ASSET("ASSET", "Asset"),
    JS("JS", "JS"),
    SYSTEM("SYS", "Sys"),
    ERROR("ERR", "Err");

    companion object {
        fun fromRaw(raw: String): LogCategory {
            val normalized = raw.trim().uppercase()
            return entries.firstOrNull {
                it != ALL && (it.name == normalized || it.tag == normalized)
            } ?: SYSTEM
        }
    }
}

@Immutable
data class TelemetryLogEntry(
    val id: Long,
    val timestamp: String,
    val epochMs: Long,
    val category: LogCategory,
    val summary: String,
    val payload: String
)

@Immutable
data class CandidateStagingItem(
    val path: String,
    val sourceStep: String,
    val detail: String,
    val scanNumber: Int,
    val seenCount: Int = 1,
    val isPinned: Boolean = false,
    val lastSeenTimestamp: String
)

@Immutable
data class InterceptedAsset(
    val url: String,
    val extension: String,
    val method: String,
    val timestamp: String,
    val isForMainFrame: Boolean,
    val fileName: String = "",
    val captureStatus: AssetCaptureStatus = AssetCaptureStatus.OBSERVED,
    val localRelativePath: String? = null,
    val httpStatus: Int? = null,
    val contentType: String = "",
    val byteSize: Long = 0L,
    val isTruncated: Boolean = false,
    val sha256Prefix: String = "",
    val magicSignature: String = "",
    val hexHeaderPreview: String = "",
    val entropyBits: Double = 0.0,
    val structuralSummary: String = "",
    val structuralDetails: String = ""
)

@Immutable
data class IsolatedCoordinatePath(
    val path: String,
    val baselineValue: Double,
    val currentValue: Double,
    val delta: Double
)

@Immutable
data class PlayerCoordinates(
    val path: String,
    val x: Double,
    val y: Double,
    val z: Double,
    val updatedAtMs: Long = System.currentTimeMillis()
)

@Immutable
data class DiscoveredEntitySample(
    val name: String,
    val id: String,
    val path: String,
    val worldX: Double,
    val worldY: Double,
    val worldZ: Double,
    val distanceToPlayer: Double,
    val screenX: Double,
    val screenY: Double,
    val inViewport: Boolean,
    val sampleKeys: List<String>
)

@Immutable
data class ExportReportOutcome(
    val success: Boolean,
    val fileName: String,
    val displayPath: String,
    val contentUri: Uri? = null,
    val mimeType: String = "application/json",
    val errorMessage: String? = null
)

@Immutable
data class WasmNeighborFloat(
    val byteOffset: Int,
    val heapIndex: Int,
    val byteAddress: Long,
    val floatValue: Double,
    val intValue: Int,
    val changedFromPrev: Boolean = false,
    val changeCount: Int = 0
)

@Immutable
data class WasmCandidateAddress(
    val heapType: String,
    val index: Int,
    val byteAddress: Long,
    val currentValue: String
)

@Immutable
data class WasmHeapTrackerState(
    val targetValueInput: String = "",
    val isScanning: Boolean = false,
    val scanProgressPercent: Int = 0,
    val heapSourceLabel: String = "",
    val heapByteLength: Long = 0L,
    val initialSearchValue: Double? = null,
    val lastFilterValue: Double? = null,
    val totalMatches: Int = 0,
    val heap32MatchCount: Int = 0,
    val heapF32MatchCount: Int = 0,
    val filterRound: Int = 0,
    val survivingCandidates: List<WasmCandidateAddress> = emptyList(),
    val isolatedHeapType: String? = null,
    val isolatedIndex: Int? = null,
    val isolatedByteAddress: Long? = null,
    val isolatedCurrentValue: String = "",
    val isNeighborPolling: Boolean = false,
    val neighborPollTickCount: Int = 0,
    val neighborFloats: List<WasmNeighborFloat> = emptyList(),
    val statusMessage: String = "",
    val isExportingMemoryMap: Boolean = false,
    val lastMemoryMapExport: ExportReportOutcome? = null
)

@Immutable
data class DiagnosticsUiState(
    val urlInput: String = "",
    val activeUrl: String = "about:blank",
    val pageTitle: String = "",
    val isLoading: Boolean = false,
    val loadProgress: Int = 0,
    val isDesktopMode: Boolean = true,
    val scanStatus: AsyncScanStatus = AsyncScanStatus.IDLE,
    val scanStatusDetail: String = "",
    val activeConsoleTab: ConsoleTab = ConsoleTab.WIZARD,
    // Multi-Scan Candidate Staging Buffer
    val scanCounter: Int = 0,
    val stagingBuffer: List<CandidateStagingItem> = emptyList(),
    val showPinnedOnly: Boolean = false,
    val stagingSearchQuery: String = "",
    // Step 1 state + Batched Chunked Scanning (100 items / batch) + Manual Path Inspector
    val step1Completed: Boolean = false,
    val customGlobals: List<String> = emptyList(),
    val detectedGameNamespaces: List<String> = emptyList(),
    val step1DurationMs: Double? = null,
    val scopeBatchProgress: ScopeBatchProgress = ScopeBatchProgress(),
    val manualPropertyPathInput: String = "",
    val lastManualQuerySummary: String = "",
    // Step 2 state + Live 200ms Reference Polling
    val baselineCaptured: Boolean = false,
    val baselineMetricCount: Int = 0,
    val isolatedPaths: List<IsolatedCoordinatePath> = emptyList(),
    val discoveredPlayerPath: String? = null,
    val playerPropertyKeys: String = "",
    val livePlayerCoords: PlayerCoordinates? = null,
    val livePollActive: Boolean = false,
    val livePollTickCount: Int = 0,
    // Step 3 state (Visual Entity Inspection Console)
    val discoveredEntityArrayPath: String? = null,
    val entityProjectionMode: String = "",
    val entityViewportWidth: Int = 0,
    val entityViewportHeight: Int = 0,
    val discoveredEntities: List<DiscoveredEntitySample> = emptyList(),
    val lastEntityCount: Int = 0,
    // Step 4/5 Passive asset & console state
    val wasmHeap: WasmHeapTrackerState = WasmHeapTrackerState(),
    val interceptedAssets: List<InterceptedAsset> = emptyList(),
    val logs: List<TelemetryLogEntry> = emptyList(),
    val selectedFilter: LogCategory = LogCategory.ALL,
    val isJsDrawerOpen: Boolean = false,
    val manualJsInput: String = "",
    val isConsoleExpanded: Boolean = false,
    val isExporting: Boolean = false,
    val lastExport: ExportReportOutcome? = null,
    val isExportingLogDump: Boolean = false,
    val lastLogDumpExport: ExportReportOutcome? = null,
    val estimatedFps: Int? = null,
    val canvasCount: Int = 0
) {
    val pinnedCandidateCount: Int
        get() = stagingBuffer.count { it.isPinned }

    val cachedAssetCount: Int
        get() = interceptedAssets.count { it.captureStatus == AssetCaptureStatus.CAPTURED }

    val totalCachedAssetBytes: Long
        get() = interceptedAssets.filter { it.captureStatus == AssetCaptureStatus.CAPTURED }.sumOf { it.byteSize }

    val step1Badge: WizardStepBadge
        get() = if (step1Completed && (customGlobals.isNotEmpty() || canvasCount > 0)) {
            WizardStepBadge.CAPTURED
        } else {
            WizardStepBadge.READY
        }

    val step2Badge: WizardStepBadge
        get() = when {
            !discoveredPlayerPath.isNullOrBlank() && livePlayerCoords != null -> WizardStepBadge.CAPTURED
            step1Completed || baselineCaptured -> WizardStepBadge.READY
            else -> WizardStepBadge.PENDING
        }

    val step3Badge: WizardStepBadge
        get() = when {
            !discoveredEntityArrayPath.isNullOrBlank() && discoveredEntities.isNotEmpty() -> WizardStepBadge.CAPTURED
            !discoveredPlayerPath.isNullOrBlank() -> WizardStepBadge.READY
            else -> WizardStepBadge.PENDING
        }

    val step4Badge: WizardStepBadge
        get() = when {
            lastExport?.success == true -> WizardStepBadge.CAPTURED
            step2Badge == WizardStepBadge.CAPTURED && step3Badge == WizardStepBadge.CAPTURED -> WizardStepBadge.READY
            else -> WizardStepBadge.PENDING
        }
}

sealed interface UrlValidationResult {
    data class Valid(val normalizedUrl: String) : UrlValidationResult
    data class Invalid(val reason: String) : UrlValidationResult
}
