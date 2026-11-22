package com.example

import android.net.Uri
import androidx.compose.runtime.Immutable

enum class AsyncScanStatus(val label: String) {
    IDLE("Idle"),
    RUNNING("Scanning"),
    COMPLETED("Completed"),
    ERROR("Error");

    companion object {
        fun fromRaw(raw: String): AsyncScanStatus {
            return when (raw.trim().uppercase()) {
                "RUNNING", "STARTED", "SCANNING" -> RUNNING
                "COMPLETED", "DONE", "SUCCESS" -> COMPLETED
                "ERROR", "FAILED" -> ERROR
                else -> IDLE
            }
        }
    }
}

enum class LogCategory(val tag: String, val label: String) {
    ALL("ALL", "All"),
    SCOPE("SCOPE", "Scope"),
    DELTA("DELTA", "Delta"),
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
    val isForMainFrame: Boolean
)

@Immutable
data class IsolatedCoordinatePath(
    val path: String,
    val baselineValue: Double,
    val currentValue: Double,
    val delta: Double
)

@Immutable
data class ExportReportOutcome(
    val success: Boolean,
    val fileName: String,
    val displayPath: String,
    val contentUri: Uri? = null,
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
    val discoveredShallowPaths: List<String> = emptyList(),
    val baselineCaptured: Boolean = false,
    val baselineMetricCount: Int = 0,
    val isolatedPaths: List<IsolatedCoordinatePath> = emptyList(),
    val lastEntityCount: Int = 0,
    val interceptedAssets: List<InterceptedAsset> = emptyList(),
    val logs: List<TelemetryLogEntry> = emptyList(),
    val selectedFilter: LogCategory = LogCategory.ALL,
    val isJsDrawerOpen: Boolean = false,
    val manualJsInput: String = "",
    val isConsoleExpanded: Boolean = false,
    val isExporting: Boolean = false,
    val lastExport: ExportReportOutcome? = null,
    val estimatedFps: Int? = null,
    val canvasCount: Int = 0
)

sealed interface UrlValidationResult {
    data class Valid(val normalizedUrl: String) : UrlValidationResult
    data class Invalid(val reason: String) : UrlValidationResult
}
