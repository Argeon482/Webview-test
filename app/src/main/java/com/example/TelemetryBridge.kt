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
 * Security controls:
 * - Strict payload length bounds, control-character sanitization, and sliding-window rate
 *   limiting prevent memory exhaustion or log flooding from untrusted web pages.
 * - Export writes are gated behind an explicit user-triggered arming flag ([armExport]) so an
 *   arbitrary page cannot write files to device storage unsolicited.
 * - Async scan status updates are validated against a strict allowlist ([AsyncScanStatus]).
 * - Live player coordinate telemetry validates finite numeric coordinates and throttles updates.
 * - All UI state mutations are marshaled safely onto the Android main thread.
 */
class TelemetryBridge(
    private val onTelemetryReceived: (category: String, payload: String) -> Unit,
    private val onReportPayloadReceived: (jsonContent: String) -> Unit,
    private val onScanStatusChanged: (status: AsyncScanStatus, detail: String) -> Unit = { _, _ -> },
    private val onPlayerCoordsUpdated: (path: String, x: Double, y: Double, z: Double) -> Unit = { _, _, _, _ -> }
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val exportArmed = AtomicBoolean(false)
    private val lastLogWindowStartMs = AtomicLong(0L)
    private val logsInCurrentWindow = AtomicLong(0L)
    private val lastCoordUpdateMs = AtomicLong(0L)

    /**
     * Arms the bridge to accept the next structured report payload produced by a user-initiated
     * export action.
     */
    fun armExport() {
        exportArmed.set(true)
    }

    /**
     * Disarms a pending export request (e.g., if a fallback timeout fires first).
     */
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

    @JavascriptInterface
    fun updatePlayerCoords(path: String, x: Double, y: Double, z: Double) {
        if (!x.isFinite() || !y.isFinite() || !z.isFinite()) return

        val now = System.currentTimeMillis()
        val prev = lastCoordUpdateMs.get()
        if (now - prev < MIN_COORD_INTERVAL_MS) return
        if (!lastCoordUpdateMs.compareAndSet(prev, now)) return

        val safePath = sanitizePayload(path, MAX_PATH_CHARS)
            .replace('\n', ' ')
            .replace('\r', ' ')
            .trim()
        if (safePath.isEmpty()) return

        mainHandler.post {
            onPlayerCoordsUpdated(safePath, x, y, z)
        }
    }

    @JavascriptInterface
    fun exportReport(jsonContent: String) {
        // Only honor exportReport calls explicitly triggered by the user tapping Export.
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
            // Verify well-formed JSON object before forwarding to Kotlin exporter.
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
        private const val MAX_PATH_CHARS = 240
        private const val MAX_LOG_PAYLOAD_CHARS = 16_384
        private const val MAX_EXPORT_PAYLOAD_CHARS = 1_500_000
        private const val RATE_LIMIT_WINDOW_MS = 1_000L
        private const val MAX_LOGS_PER_WINDOW = 60L
        private const val MIN_COORD_INTERVAL_MS = 150L
    }
}
