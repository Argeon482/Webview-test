package com.example

import android.net.Uri
import androidx.compose.runtime.Immutable

const val VERIFIED_PLAYER_BASE_INDEX = 581246

enum class LockStatus(val label: String) {
    UNLOCKED("UNLOCKED"),
    LOCKED("LOCKED"),
    RELOCKING("RELOCKING")
}

enum class AsyncScanStatus(val label: String) {
    IDLE("Idle"),
    RUNNING("Scanning"),
    COMPLETED("Active"),
    ERROR("Error");

    companion object {
        fun fromRaw(raw: String): AsyncScanStatus {
            return when (raw.trim().uppercase()) {
                "RUNNING", "STARTED", "SCANNING", "RELOCKING" -> RUNNING
                "COMPLETED", "DONE", "SUCCESS", "ACTIVE" -> COMPLETED
                "ERROR", "FAILED" -> ERROR
                else -> IDLE
            }
        }
    }
}

enum class ConsoleTab {
    SCANNER,
    ANALYZER,
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
    FEED("FEED", "Feed"),
    SCAN("SCAN", "Scan"),
    PTR("PTR", "Ptr/Sig"),
    RELOCK("RELOCK", "Relock"),
    ENTITY("ENTITY", "Entity"),
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
data class DirectHeapFeedState(
    val baseIndex: Int = VERIFIED_PLAYER_BASE_INDEX,
    val baseIndexInput: String = VERIFIED_PLAYER_BASE_INDEX.toString(),
    val heapSourceLabel: String = "window.HEAPF32",
    val heapByteLength: Long = 0L,
    val lockStatus: LockStatus = LockStatus.UNLOCKED,
    val isPolling: Boolean = false,
    val isHeapReady: Boolean = false,
    val x: Double = 0.0,
    val y: Double = 0.0,
    val z: Double = 0.0,
    val tickCount: Int = 0,
    val lastUpdatedMs: Long = 0L,
    val statusMessage: String = "Ready to lock onto window.HEAPF32[$VERIFIED_PLAYER_BASE_INDEX..${VERIFIED_PLAYER_BASE_INDEX + 2}]",
    val hasSignature: Boolean = false,
    val signatureAnchorCount: Int = 0,
    val signatureCapturedAtIndex: Int? = null,
    val signatureJson: String? = null,
    val isRelocking: Boolean = false,
    val autoRelockOnReload: Boolean = true,
    val lastRelockSummary: String = "",
    val relockConfidencePercent: Int = 0,
    val relockMethodUsed: String = ""
) {
    val byteAddress: Long
        get() = baseIndex.toLong().coerceAtLeast(0L) * 4L
}

@Immutable
data class FloatDeltaCandidate(
    val heapIndex: Int,
    val byteAddress: Long,
    val baselineValue: Double,
    val currentValue: Double,
    val delta: Double,
    val previewY: Double = 0.0,
    val previewZ: Double = 0.0,
    val isStillNow: Boolean = true,
    val clusterSize: Int = 1,
    val axisHint: String = "X",
    val liveChangeCount: Int = 0
)

@Immutable
data class FloatDeltaScannerState(
    val isBusy: Boolean = false,
    val baselineCaptured: Boolean = false,
    val baselineSampleCount: Int = 0,
    val prunedJitterCount: Int = 0,
    val scanRound: Int = 0,
    val totalMatchesFound: Int = 0,
    val scanDurationMs: Double? = null,
    val isLivePreviewing: Boolean = false,
    val statusMessage: String = "Stand still, tap '1. Capture Standing Baseline', move in-game, stop, then tap '2. Scan Movement Deltas'.",
    val candidates: List<FloatDeltaCandidate> = emptyList()
)

@Immutable
data class MemorySlotPreview(
    val offset: Int,
    val heapIndex: Int,
    val byteAddress: Long,
    val floatValue: Double,
    val uint32Hex: String,
    val isCoordAxis: Boolean = false,
    val isAnchor: Boolean = false
)

@Immutable
data class ParentPointerMatch(
    val pointerHeapIndex: Int,
    val pointerByteAddress: Long,
    val targetByteAddress: Long,
    val fieldOffsetBytes: Int = 0,
    val isExactMatch: Boolean = true
)

@Immutable
data class PointerSignatureAnalysis(
    val inspectedBaseIndex: Int? = null,
    val inspectedByteAddress: Long = 0L,
    val isAnalyzing: Boolean = false,
    val aobPatternHex: String = "",
    val floatPatternSummary: String = "",
    val anchorCount: Int = 0,
    val signatureJson: String? = null,
    val parentPointers: List<ParentPointerMatch> = emptyList(),
    val surroundingSlots: List<MemorySlotPreview> = emptyList(),
    val analysisDurationMs: Double? = null,
    val errorMessage: String? = null
)

@Immutable
data class SavedLockProfile(
    val baseIndex: Int,
    val host: String,
    val aobPatternHex: String,
    val floatPatternSummary: String,
    val anchorCount: Int,
    val signatureJson: String,
    val parentPointers: List<ParentPointerMatch> = emptyList(),
    val autoRelockOnReload: Boolean = true,
    val savedAtEpochMs: Long = 0L
)

@Immutable
data class NearbyEntityStruct(
    val baseIndex: Int,
    val byteAddress: Long,
    val offsetFromPlayer: Int,
    val x: Double,
    val y: Double,
    val z: Double,
    val distanceToPlayer: Double,
    val strideGroup: Int = 0,
    val confidenceScore: Int = 0,
    val isPinned: Boolean = false
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
data class DiagnosticsUiState(
    val urlInput: String = "",
    val activeUrl: String = "about:blank",
    val pageTitle: String = "",
    val isLoading: Boolean = false,
    val loadProgress: Int = 0,
    val isDesktopMode: Boolean = true,
    val scanStatus: AsyncScanStatus = AsyncScanStatus.IDLE,
    val scanStatusDetail: String = "",
    val activeConsoleTab: ConsoleTab = ConsoleTab.SCANNER,
    val isWebViewExpanded: Boolean = false,
    // Fixed Top Live Telemetry Bar + Persistent Lock state
    val heapFeed: DirectHeapFeedState = DirectHeapFeedState(),
    val savedProfile: SavedLockProfile? = null,
    val liveStreamHistory: List<String> = emptyList(),
    val isTelemetryStreamExpanded: Boolean = false,
    // Step 1: Movement Float Delta Scanner
    val floatScanner: FloatDeltaScannerState = FloatDeltaScannerState(),
    // Step 2: Pointer & Memory Signature Analyzer
    val pointerAnalysis: PointerSignatureAnalysis = PointerSignatureAnalysis(),
    // Nearby ±50,000 Entity Struct Scanner
    val isScanningEntities: Boolean = false,
    val entityScanRadius: Int = 50_000,
    val entityScanDurationMs: Double? = null,
    val entityDetectedStride: Int? = null,
    val discoveredEntities: List<NearbyEntityStruct> = emptyList(),
    // Passive Binary Asset Interceptor & Rolling Logs
    val interceptedAssets: List<InterceptedAsset> = emptyList(),
    val logs: List<TelemetryLogEntry> = emptyList(),
    val selectedFilter: LogCategory = LogCategory.ALL,
    // Manual JS Console & Report Exports
    val isJsDrawerOpen: Boolean = false,
    val manualJsInput: String = "",
    val isExporting: Boolean = false,
    val lastExport: ExportReportOutcome? = null,
    val isExportingLogDump: Boolean = false,
    val lastLogDumpExport: ExportReportOutcome? = null
) {
    val cachedAssetCount: Int
        get() = interceptedAssets.count { it.captureStatus == AssetCaptureStatus.CAPTURED }

    val totalCachedAssetBytes: Long
        get() = interceptedAssets.filter { it.captureStatus == AssetCaptureStatus.CAPTURED }.sumOf { it.byteSize }

    val pinnedEntityCount: Int
        get() = discoveredEntities.count { it.isPinned }
}

sealed interface UrlValidationResult {
    data class Valid(val normalizedUrl: String) : UrlValidationResult
    data class Invalid(val reason: String) : UrlValidationResult
}
