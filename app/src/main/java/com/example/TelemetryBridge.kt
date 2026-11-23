package com.example

import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Secure native telemetry bridge exposed to the diagnostic WebView execution context.
 *
 * Security & performance controls:
 * - Strictly observational: receives read-only coordinate telemetry, movement float delta
 *   previews, pointer/signature inspection results, and user-initiated report exports.
 * - Strict payload length bounds, control-character sanitization, and sliding-window rate
 *   limiting prevent memory exhaustion or log flooding from web pages.
 * - Export writes are gated behind an explicit user-triggered arming flag ([armExport]).
 * - High-frequency 200 ms coordinate & candidate preview updates marshal cleanly onto the main thread.
 */
class TelemetryBridge(
    private val onTelemetryReceived: (category: String, payload: String) -> Unit,
    private val onReportPayloadReceived: (jsonContent: String) -> Unit,
    private val onScanStatusChanged: (status: AsyncScanStatus, detail: String) -> Unit = { _, _ -> },
    private val onDirectHeapCoordsUpdated: (
        baseIndex: Int,
        x: Double,
        y: Double,
        z: Double,
        heapByteLength: Long,
        heapSource: String
    ) -> Unit = { _, _, _, _, _, _ -> },
    private val onFloatDeltaPreviewUpdated: (jsonPayload: String) -> Unit = {}
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val exportArmed = AtomicBoolean(false)
    private val lastLogWindowStartMs = AtomicLong(0L)
    private val logsInCurrentWindow = AtomicLong(0L)
    private val lastCoordUpdateMs = AtomicLong(0L)
    private val lastDeltaPreviewMs = AtomicLong(0L)

    fun armExport() {
        exportArmed.set(true)
    }

    fun disarmExport(): Boolean = exportArmed.getAndSet(false)

    @JavascriptInterface
    fun logTelemetry(category: String, payload: String) {
        if (!allowLogUnderRateLimit()) return

        val safeCategory = sanitizeCategory(category)
        val safePayload = sanitizePayload(payload, MAX_LOG_PAYLOAD_CHARS)

        mainHandler.post {
            onTelemetryReceived(safeCategory, safePayload)
        }
    }

    @JavascriptInterface
    fun updateScanStatus(status: String, detail: String) {
        val parsedStatus = AsyncScanStatus.fromRaw(status)
        val safeDetail = sanitizePayload(detail, MAX_STATUS_DETAIL_CHARS)
            .replace('\n', ' ')
            .replace('\r', ' ')
            .trim()

        mainHandler.post {
            onScanStatusChanged(parsedStatus, safeDetail)
        }
    }

    /**
     * Receives live 200 ms player coordinates read directly from
     * `window.HEAPF32[baseIndex..baseIndex + 2]`.
     */
    @JavascriptInterface
    fun updateDirectHeapCoords(
        baseIndex: Int,
        x: Double,
        y: Double,
        z: Double,
        heapByteLength: Long,
        heapSource: String
    ) {
        if (baseIndex < 0 || !x.isFinite() || !y.isFinite() || !z.isFinite()) return

        val now = System.currentTimeMillis()
        val prev = lastCoordUpdateMs.get()
        if (now - prev < MIN_COORD_INTERVAL_MS) return
        if (!lastCoordUpdateMs.compareAndSet(prev, now)) return

        val safeSource = sanitizePayload(heapSource, MAX_SOURCE_CHARS)
            .replace('\n', ' ')
            .replace('\r', ' ')
            .trim()
            .ifEmpty { "window.HEAPF32" }
        val safeBytes = heapByteLength.coerceAtLeast(0L)

        mainHandler.post {
            onDirectHeapCoordsUpdated(baseIndex, x, y, z, safeBytes, safeSource)
        }
    }

    /**
     * Receives live 200 ms preview values for top Movement Float Delta Scanner candidates.
     */
    @JavascriptInterface
    fun updateFloatDeltaPreview(jsonPayload: String) {
        val now = System.currentTimeMillis()
        val prev = lastDeltaPreviewMs.get()
        if (now - prev < MIN_PREVIEW_INTERVAL_MS) return
        if (!lastDeltaPreviewMs.compareAndSet(prev, now)) return

        val safeJson = sanitizePayload(jsonPayload, MAX_PREVIEW_PAYLOAD_CHARS)
        mainHandler.post {
            onFloatDeltaPreviewUpdated(safeJson)
        }
    }

    @JavascriptInterface
    fun exportReport(jsonContent: String) {
        if (!exportArmed.compareAndSet(true, false)) {
            mainHandler.post {
                onTelemetryReceived(
                    LogCategory.ERROR.tag,
                    "Blocked unsolicited exportReport() call from page context."
                )
            }
            return
        }

        val boundedPayload = jsonContent.take(MAX_EXPORT_PAYLOAD_CHARS).trim()
        val validatedJson = try {
            JSONObject(boundedPayload).toString()
        } catch (_: Exception) {
            JSONObject().put("rawMetricsError", "Invalid JSON payload received from WebView").toString()
        }

        mainHandler.post {
            onReportPayloadReceived(validatedJson)
        }
    }

    private fun allowLogUnderRateLimit(): Boolean {
        val now = System.currentTimeMillis()
        val windowStart = lastLogWindowStartMs.get()
        if (now - windowStart > RATE_LIMIT_WINDOW_MS) {
            lastLogWindowStartMs.set(now)
            logsInCurrentWindow.set(1L)
            return true
        }
        return logsInCurrentWindow.incrementAndGet() <= MAX_LOGS_PER_WINDOW
    }

    private fun sanitizeCategory(raw: String?): String {
        if (raw.isNullOrBlank()) return LogCategory.SYSTEM.tag
        return raw.trim()
            .uppercase()
            .filter { it in 'A'..'Z' || it in '0'..'9' || it == '_' }
            .take(MAX_CATEGORY_CHARS)
            .ifEmpty { LogCategory.SYSTEM.tag }
    }

    private fun sanitizePayload(raw: String?, maxChars: Int): String {
        if (raw == null) return ""
        val trimmed = if (raw.length > maxChars) {
            raw.substring(0, maxChars) + "… [truncated]"
        } else {
            raw
        }
        return trimmed.replace('\u0000', ' ')
    }

    companion object {
        const val BRIDGE_NAME = "TelemetryBridge"
        private const val MAX_CATEGORY_CHARS = 16
        private const val MAX_STATUS_DETAIL_CHARS = 120
        private const val MAX_SOURCE_CHARS = 80
        private const val MAX_LOG_PAYLOAD_CHARS = 65_536
        private const val MAX_PREVIEW_PAYLOAD_CHARS = 32_768
        private const val MAX_EXPORT_PAYLOAD_CHARS = 1_500_000
        private const val RATE_LIMIT_WINDOW_MS = 1_000L
        private const val MAX_LOGS_PER_WINDOW = 60L
        private const val MIN_COORD_INTERVAL_MS = 120L
        private const val MIN_PREVIEW_INTERVAL_MS = 160L
    }
}
