package com.example

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.webkit.WebResourceRequest
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

sealed interface DiagnosticsCommand {
    data class LoadUrl(val url: String) : DiagnosticsCommand
    data object Reload : DiagnosticsCommand
    data class EvaluateJs(val script: String) : DiagnosticsCommand
    data class ShowToast(val message: String) : DiagnosticsCommand
    data class ShareFile(val uri: Uri, val mimeType: String, val title: String) : DiagnosticsCommand
}

class DiagnosticsViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(DiagnosticsUiState())
    val uiState: StateFlow<DiagnosticsUiState> = _uiState.asStateFlow()

    private val _commands = MutableSharedFlow<DiagnosticsCommand>(extraBufferCapacity = 32)
    val commands: SharedFlow<DiagnosticsCommand> = _commands.asSharedFlow()

    private val nextLogId = AtomicLong(1L)
    private var exportFallbackJob: Job? = null
    private val inFlightAssetUrls = HashSet<String>()

    init {
        appendLog(
            category = LogCategory.SYSTEM,
            summary = "WebViewDiagnosticsApp ready (strictly observational, remote debugging OFF)",
            payload = "Verified target: window.HEAPF32[$VERIFIED_PLAYER_BASE_INDEX] (X), [${VERIFIED_PLAYER_BASE_INDEX + 1}] (Y), [${VERIFIED_PLAYER_BASE_INDEX + 2}] (Z) @ 200ms"
        )
    }

    // -------------------------------------------------------------------------
    // URL & WebView Lifecycle Controls
    // -------------------------------------------------------------------------

    fun onUrlInputChanged(value: String) {
        _uiState.update { it.copy(urlInput = value) }
    }

    fun onLoadUrlRequested() {
        val rawInput = _uiState.value.urlInput
        when (val validation = validateAndNormalizeUrl(rawInput)) {
            is UrlValidationResult.Invalid -> {
                appendLog(
                    category = LogCategory.ERROR,
                    summary = "Blocked invalid URL",
                    payload = validation.reason
                )
                _commands.tryEmit(DiagnosticsCommand.ShowToast(validation.reason))
            }

            is UrlValidationResult.Valid -> {
                _uiState.update {
                    it.copy(
                        urlInput = validation.normalizedUrl,
                        activeUrl = validation.normalizedUrl,
                        isLoading = true,
                        loadProgress = 5
                    )
                }
                appendLog(
                    category = LogCategory.SYSTEM,
                    summary = "Loading target URL",
                    payload = validation.normalizedUrl
                )
                _commands.tryEmit(DiagnosticsCommand.LoadUrl(validation.normalizedUrl))
            }
        }
    }

    fun onReloadRequested() {
        val current = _uiState.value.activeUrl
        if (current.isBlank() || current == "about:blank") {
            _commands.tryEmit(DiagnosticsCommand.ShowToast("Load a valid URL first"))
            return
        }
        // Preserve captured relock signature across reloads so Relock Coordinates works immediately.
        appendLog(
            category = LogCategory.SYSTEM,
            summary = "Reloading target page (signature preserved)",
            payload = current
        )
        _commands.tryEmit(DiagnosticsCommand.Reload)
    }

    fun onToggleDesktopMode() {
        val nextDesktop = !_uiState.value.isDesktopMode
        _uiState.update { it.copy(isDesktopMode = nextDesktop) }
        appendLog(
            category = LogCategory.SYSTEM,
            summary = if (nextDesktop) "Switched to Desktop User-Agent" else "Switched to Mobile User-Agent",
            payload = if (nextDesktop) DESKTOP_USER_AGENT else "Android WebView Default Mobile UA"
        )
        if (_uiState.value.activeUrl != "about:blank") {
            _commands.tryEmit(DiagnosticsCommand.Reload)
        }
    }

    fun onPageStarted(url: String) {
        _uiState.update { state ->
            state.copy(
                activeUrl = url,
                urlInput = if (url == "about:blank") state.urlInput else url,
                isLoading = true,
                loadProgress = 10,
                heapFeed = state.heapFeed.copy(
                    isHeapReady = false,
                    statusMessage = "Page loading… will auto-attach 200ms HEAPF32 feed"
                )
            )
        }
    }

    fun onPageProgressChanged(progress: Int) {
        _uiState.update { it.copy(loadProgress = progress.coerceIn(0, 100)) }
    }

    fun onPageFinished(url: String, title: String) {
        _uiState.update { state ->
            state.copy(
                activeUrl = url,
                pageTitle = title,
                isLoading = false,
                loadProgress = 100
            )
        }
        if (url != "about:blank") {
            appendLog(
                category = LogCategory.SYSTEM,
                summary = "Page loaded: ${title.ifBlank { url }}",
                payload = url
            )
            // Auto-attach the verified 200ms direct HEAPF32 feed on page load
            startDirectHeapFeed()
        }
    }

    fun onWebViewError(description: String, failingUrl: String) {
        appendLog(
            category = LogCategory.ERROR,
            summary = "WebView load error: $description",
            payload = failingUrl
        )
        _uiState.update { it.copy(isLoading = false) }
    }

    // -------------------------------------------------------------------------
    // UI Layout & Navigation State
    // -------------------------------------------------------------------------

    fun onSelectConsoleTab(tab: ConsoleTab) {
        _uiState.update { it.copy(activeConsoleTab = tab) }
    }

    fun onToggleWebViewExpanded() {
        _uiState.update { it.copy(isWebViewExpanded = !it.isWebViewExpanded) }
    }

    fun onToggleTelemetryStreamExpanded() {
        _uiState.update { it.copy(isTelemetryStreamExpanded = !it.isTelemetryStreamExpanded) }
    }

    // -------------------------------------------------------------------------
    // Direct Verified HEAPF32 Telemetry Feed (200ms) & Auto-Relock Signature
    // -------------------------------------------------------------------------

    fun onBaseIndexInputChanged(raw: String) {
        val digitsOnly = raw.filter { it.isDigit() }.take(9)
        _uiState.update { state ->
            state.copy(heapFeed = state.heapFeed.copy(baseIndexInput = digitsOnly))
        }
    }

    fun onApplyBaseIndex() {
        val parsed = _uiState.value.heapFeed.baseIndexInput.toIntOrNull()
        if (parsed == null || parsed < 0) {
            _commands.tryEmit(DiagnosticsCommand.ShowToast("Enter a valid non-negative float index"))
            return
        }
        _uiState.update { state ->
            state.copy(
                heapFeed = state.heapFeed.copy(
                    baseIndex = parsed,
                    baseIndexInput = parsed.toString(),
                    statusMessage = "Updated base index to window.HEAPF32[$parsed..${parsed + 2}]"
                )
            )
        }
        appendLog(
            category = LogCategory.FEED,
            summary = "Base index updated to $parsed (0x${(parsed.toLong() * 4L).toString(16).uppercase(Locale.US)})",
            payload = "window.HEAPF32[$parsed], [${parsed + 1}], [${parsed + 2}]"
        )
        if (_uiState.value.activeUrl != "about:blank") {
            startDirectHeapFeed()
        }
    }

    fun onResetToVerifiedBaseIndex() {
        _uiState.update { state ->
            state.copy(
                heapFeed = state.heapFeed.copy(
                    baseIndex = VERIFIED_PLAYER_BASE_INDEX,
                    baseIndexInput = VERIFIED_PLAYER_BASE_INDEX.toString(),
                    statusMessage = "Reset to verified window.HEAPF32[$VERIFIED_PLAYER_BASE_INDEX..${VERIFIED_PLAYER_BASE_INDEX + 2}]"
                )
            )
        }
        appendLog(
            category = LogCategory.FEED,
            summary = "Reset base index to verified $VERIFIED_PLAYER_BASE_INDEX",
            payload = "window.HEAPF32[$VERIFIED_PLAYER_BASE_INDEX..${VERIFIED_PLAYER_BASE_INDEX + 2}]"
        )
        if (_uiState.value.activeUrl != "about:blank") {
            startDirectHeapFeed()
        }
    }

    fun startDirectHeapFeed() {
        if (!ensurePageLoadedForInspection()) return
        val feed = _uiState.value.heapFeed
        _uiState.update { state ->
            state.copy(
                heapFeed = state.heapFeed.copy(
                    isPolling = true,
                    statusMessage = "Polling window.HEAPF32[${feed.baseIndex}..${feed.baseIndex + 2}] @ 200ms"
                )
            )
        }
        _commands.tryEmit(
            DiagnosticsCommand.EvaluateJs(
                WasmHeapScripts.buildStartDirectFeedScript(
                    baseIndex = feed.baseIndex,
                    savedSignatureJson = feed.signatureJson
                )
            )
        )
    }

    fun pauseDirectHeapFeed() {
        _uiState.update { state ->
            state.copy(
                heapFeed = state.heapFeed.copy(
                    isPolling = false,
                    statusMessage = "Direct 200ms feed paused"
                )
            )
        }
        if (_uiState.value.activeUrl != "about:blank") {
            _commands.tryEmit(DiagnosticsCommand.EvaluateJs(WasmHeapScripts.buildStopDirectFeedScript()))
        }
    }

    fun captureRelockSignature() {
        if (!ensurePageLoadedForInspection()) return
        val baseIndex = _uiState.value.heapFeed.baseIndex
        appendLog(
            category = LogCategory.RELOCK,
            summary = "Capturing memory signature around HEAPF32[$baseIndex]…",
            payload = "Sampling ±24 surrounding Int32/Float32 anchor words (read-only)"
        )
        _commands.tryEmit(
            DiagnosticsCommand.EvaluateJs(
                WasmHeapScripts.buildCaptureSignatureScript(baseIndex)
            )
        )
    }

    fun relockCoordinates() {
        if (!ensurePageLoadedForInspection()) return
        val feed = _uiState.value.heapFeed
        _uiState.update { state ->
            state.copy(
                scanStatus = AsyncScanStatus.RUNNING,
                scanStatusDetail = "Relocking HEAPF32 signature…",
                heapFeed = state.heapFeed.copy(
                    isRelocking = true,
                    statusMessage = if (feed.hasSignature) {
                        "Scanning window.HEAPF32 for ${feed.signatureAnchorCount}-anchor signature…"
                    } else {
                        "Capturing signature & verifying HEAPF32[${feed.baseIndex}]…"
                    }
                )
            )
        }
        appendLog(
            category = LogCategory.RELOCK,
            summary = "Auto-Relock triggered (base=${feed.baseIndex}, anchors=${feed.signatureAnchorCount})",
            payload = "Non-blocking chunked signature scan across window.HEAPF32"
        )
        _commands.tryEmit(
            DiagnosticsCommand.EvaluateJs(
                WasmHeapScripts.buildAutoRelockScript(
                    currentBaseIndex = feed.baseIndex,
                    savedSignatureJson = feed.signatureJson
                )
            )
        )
    }

    // -------------------------------------------------------------------------
    // Nearby Entity Struct Scanner (±50,000)
    // -------------------------------------------------------------------------

    fun scanNearbyEntities() {
        if (!ensurePageLoadedForInspection()) return
        val feed = _uiState.value.heapFeed
        val radius = _uiState.value.entityScanRadius
        _uiState.update { state ->
            state.copy(
                isScanningEntities = true,
                scanStatus = AsyncScanStatus.RUNNING,
                scanStatusDetail = "Scanning ±$radius indices around ${feed.baseIndex}…",
                activeConsoleTab = ConsoleTab.ENTITIES
            )
        }
        appendLog(
            category = LogCategory.ENTITY,
            summary = "Scanning nearby entity structs (±$radius around HEAPF32[${feed.baseIndex}])",
            payload = "Searching for repeating (X, Y, Z) float triplets within valid world bounds"
        )
        _commands.tryEmit(
            DiagnosticsCommand.EvaluateJs(
                WasmHeapScripts.buildScanNearbyEntitiesScript(
                    playerBaseIndex = feed.baseIndex,
                    searchRadius = radius
                )
            )
        )
    }

    fun onTogglePinEntity(baseIndex: Int) {
        _uiState.update { state ->
            val updated = state.discoveredEntities.map { entity ->
                if (entity.baseIndex == baseIndex) entity.copy(isPinned = !entity.isPinned) else entity
            }.sortedWith(
                compareByDescending<NearbyEntityStruct> { it.isPinned }
                    .thenByDescending { it.confidenceScore }
                    .thenBy { it.distanceToPlayer }
            )
            state.copy(discoveredEntities = updated)
        }
    }

    // -------------------------------------------------------------------------
    // Bridge Callbacks (Direct 200ms Coords, Status, Telemetry Events)
    // -------------------------------------------------------------------------

    fun onDirectHeapCoordsUpdatedFromBridge(
        baseIndex: Int,
        x: Double,
        y: Double,
        z: Double,
        heapByteLength: Long,
        heapSource: String
    ) {
        val nowMs = System.currentTimeMillis()
        val timeLabel = formatTime(nowMs)
        val streamLine = String.format(
            Locale.US,
            "[%s] [%d] X=%.3f  Y=%.3f  Z=%.3f",
            timeLabel,
            baseIndex,
            x,
            y,
            z
        )

        _uiState.update { state ->
            val prevFeed = state.heapFeed
            val updatedStream = (listOf(streamLine) + state.liveStreamHistory).take(MAX_LIVE_STREAM_LINES)
            state.copy(
                heapFeed = prevFeed.copy(
                    baseIndex = baseIndex,
                    baseIndexInput = if (prevFeed.baseIndex != baseIndex) baseIndex.toString() else prevFeed.baseIndexInput,
                    heapSourceLabel = heapSource,
                    heapByteLength = heapByteLength,
                    isPolling = true,
                    isHeapReady = true,
                    x = x,
                    y = y,
                    z = z,
                    tickCount = prevFeed.tickCount + 1,
                    lastUpdatedMs = nowMs,
                    statusMessage = "LIVE 200ms • $heapSource[$baseIndex..${baseIndex + 2}]"
                ),
                liveStreamHistory = updatedStream
            )
        }
    }

    fun onScanStatusChangedFromBridge(status: AsyncScanStatus, detail: String) {
        _uiState.update { state ->
            state.copy(
                scanStatus = status,
                scanStatusDetail = detail,
                isScanningEntities = if (status == AsyncScanStatus.COMPLETED || status == AsyncScanStatus.ERROR) {
                    false
                } else {
                    state.isScanningEntities
                },
                heapFeed = if (status == AsyncScanStatus.COMPLETED || status == AsyncScanStatus.ERROR) {
                    state.heapFeed.copy(isRelocking = false)
                } else {
                    state.heapFeed
                }
            )
        }
    }

    fun onTelemetryFromBridge(rawCategory: String, payload: String) {
        val category = LogCategory.fromRaw(rawCategory)
        val trimmed = payload.trim()
        if (trimmed.startsWith("{")) {
            val handled = tryHandleStructuredTelemetry(trimmed)
            if (handled) return
        }
        val summaryLine = trimmed.lineSequence().firstOrNull()?.take(110).orEmpty().ifBlank { category.label }
        appendLog(category = category, summary = summaryLine, payload = trimmed)
    }

    private fun tryHandleStructuredTelemetry(jsonStr: String): Boolean {
        val obj = runCatching { JSONObject(jsonStr) }.getOrNull() ?: return false
        return when (obj.optString("type")) {
            "FEED_STATUS" -> {
                val active = obj.optBoolean("active", false)
                val heapReady = obj.optBoolean("heapReady", false)
                val message = obj.optString("message", "")
                _uiState.update { state ->
                    state.copy(
                        heapFeed = state.heapFeed.copy(
                            isPolling = active,
                            isHeapReady = heapReady,
                            statusMessage = message.ifBlank { state.heapFeed.statusMessage }
                        )
                    )
                }
                if (message.isNotBlank()) {
                    appendLog(LogCategory.FEED, message, jsonStr)
                }
                true
            }

            "SIGNATURE_CAPTURED" -> {
                val baseIdx = obj.optInt("baseIndex", _uiState.value.heapFeed.baseIndex)
                val anchorCount = obj.optInt("anchorCount", 0)
                val sigJson = obj.optString("signatureJson", "")
                val auto = obj.optBoolean("auto", false)
                if (sigJson.isNotBlank()) {
                    _uiState.update { state ->
                        state.copy(
                            heapFeed = state.heapFeed.copy(
                                hasSignature = true,
                                signatureAnchorCount = anchorCount,
                                signatureCapturedAtIndex = baseIdx,
                                signatureJson = sigJson,
                                lastRelockSummary = if (auto) {
                                    "Auto-captured $anchorCount anchors @ $baseIdx"
                                } else {
                                    "Captured $anchorCount anchors @ $baseIdx"
                                }
                            )
                        )
                    }
                    appendLog(
                        category = LogCategory.RELOCK,
                        summary = "${if (auto) "Auto-captured" else "Captured"} $anchorCount signature anchors @ HEAPF32[$baseIdx]",
                        payload = "Ready to relocate player coordinates after page reload or zone change."
                    )
                    if (!auto) {
                        _commands.tryEmit(
                            DiagnosticsCommand.ShowToast("Signature captured ($anchorCount anchors @ $baseIdx)")
                        )
                    }
                }
                true
            }

            "RELOCK_COMPLETE" -> {
                val oldIdx = obj.optInt("oldBaseIndex", _uiState.value.heapFeed.baseIndex)
                val newIdx = obj.optInt("newBaseIndex", oldIdx)
                val confidence = obj.optInt("confidencePercent", 0)
                val matched = obj.optInt("matchedAnchors", 0)
                val total = obj.optInt("totalAnchors", 0)
                val nx = obj.optDouble("x", _uiState.value.heapFeed.x)
                val ny = obj.optDouble("y", _uiState.value.heapFeed.y)
                val nz = obj.optDouble("z", _uiState.value.heapFeed.z)
                val durationMs = obj.optDouble("durationMs", 0.0)
                val heapBytes = obj.optLong("heapByteLength", _uiState.value.heapFeed.heapByteLength)
                val heapSource = obj.optString("heapSource", _uiState.value.heapFeed.heapSourceLabel)
                val note = obj.optString("note", "Relocked @ $newIdx ($confidence%)")

                _uiState.update { state ->
                    state.copy(
                        scanStatus = AsyncScanStatus.COMPLETED,
                        scanStatusDetail = "Relocked @ HEAPF32[$newIdx] ($confidence%)",
                        heapFeed = state.heapFeed.copy(
                            baseIndex = newIdx,
                            baseIndexInput = newIdx.toString(),
                            x = if (nx.isFinite()) nx else state.heapFeed.x,
                            y = if (ny.isFinite()) ny else state.heapFeed.y,
                            z = if (nz.isFinite()) nz else state.heapFeed.z,
                            heapByteLength = heapBytes,
                            heapSourceLabel = heapSource,
                            isRelocking = false,
                            isHeapReady = true,
                            relockConfidencePercent = confidence,
                            lastRelockSummary = "$note • $matched/$total anchors • ${String.format(Locale.US, "%.0f", durationMs)}ms",
                            statusMessage = "Relocked @ $heapSource[$newIdx..${newIdx + 2}] ($confidence%)"
                        )
                    )
                }
                appendLog(
                    category = LogCategory.RELOCK,
                    summary = note,
                    payload = "old=$oldIdx -> new=$newIdx | confidence=$confidence% ($matched/$total anchors) | coords=(${fmtCoord(nx)}, ${fmtCoord(ny)}, ${fmtCoord(nz)}) | ${durationMs}ms"
                )
                _commands.tryEmit(DiagnosticsCommand.ShowToast("Relocked @ HEAPF32[$newIdx] ($confidence%)"))
                // Ensure the 200ms loop is actively polling the relocated base index
                startDirectHeapFeed()
                true
            }

            "RELOCK_FAILED" -> {
                val reason = obj.optString("reason", "Relock scan could not locate signature.")
                _uiState.update { state ->
                    state.copy(
                        scanStatus = AsyncScanStatus.ERROR,
                        scanStatusDetail = "Relock failed",
                        heapFeed = state.heapFeed.copy(
                            isRelocking = false,
                            lastRelockSummary = reason,
                            statusMessage = reason
                        )
                    )
                }
                appendLog(LogCategory.ERROR, "Auto-Relock failed", reason)
                _commands.tryEmit(DiagnosticsCommand.ShowToast(reason))
                true
            }

            "ENTITIES_SCANNED" -> {
                val error = obj.optString("error", "")
                if (error.isNotBlank()) {
                    _uiState.update { state ->
                        state.copy(
                            isScanningEntities = false,
                            scanStatus = AsyncScanStatus.ERROR,
                            scanStatusDetail = error
                        )
                    }
                    appendLog(LogCategory.ERROR, "Entity scan error", error)
                    _commands.tryEmit(DiagnosticsCommand.ShowToast(error))
                    return true
                }

                val arr = obj.optJSONArray("entities") ?: JSONArray()
                val durationMs = obj.optDouble("durationMs", 0.0)
                val dominantStride = obj.optInt("dominantStride", 0).takeIf { it > 0 }
                val totalCandidates = obj.optInt("totalCandidates", arr.length())
                val pinnedIndices = _uiState.value.discoveredEntities
                    .filter { it.isPinned }
                    .map { it.baseIndex }
                    .toSet()

                val parsed = buildList {
                    for (i in 0 until arr.length()) {
                        val item = arr.optJSONObject(i) ?: continue
                        val bIdx = item.optInt("baseIndex", -1)
                        if (bIdx < 0) continue
                        add(
                            NearbyEntityStruct(
                                baseIndex = bIdx,
                                byteAddress = item.optLong("byteAddress", bIdx.toLong() * 4L),
                                offsetFromPlayer = item.optInt("offsetFromPlayer", 0),
                                x = item.optDouble("x", 0.0),
                                y = item.optDouble("y", 0.0),
                                z = item.optDouble("z", 0.0),
                                distanceToPlayer = item.optDouble("distanceToPlayer", 0.0),
                                strideGroup = item.optInt("strideGroup", 0),
                                confidenceScore = item.optInt("confidenceScore", 50),
                                isPinned = bIdx in pinnedIndices
                            )
                        )
                    }
                }.sortedWith(
                    compareByDescending<NearbyEntityStruct> { it.isPinned }
                        .thenByDescending { it.confidenceScore }
                        .thenBy { it.distanceToPlayer }
                )

                _uiState.update { state ->
                    state.copy(
                        isScanningEntities = false,
                        scanStatus = AsyncScanStatus.COMPLETED,
                        scanStatusDetail = "Found ${parsed.size} entity structs (${String.format(Locale.US, "%.0f", durationMs)}ms)",
                        entityScanDurationMs = durationMs,
                        entityDetectedStride = dominantStride,
                        discoveredEntities = parsed
                    )
                }

                val strideNote = dominantStride?.let { " • repeating stride=$it floats (${it * 4}B)" }.orEmpty()
                appendLog(
                    category = LogCategory.ENTITY,
                    summary = "Scanned ${parsed.size} nearby entity structs ($totalCandidates raw hits)$strideNote",
                    payload = "Duration: ${durationMs}ms | Window: ±${_uiState.value.entityScanRadius} around HEAPF32[${_uiState.value.heapFeed.baseIndex}]"
                )
                true
            }

            "MANUAL_EVAL" -> {
                val expr = obj.optString("expression", "")
                val res = obj.optString("result", "")
                appendLog(
                    category = LogCategory.JS,
                    summary = "JS> ${expr.take(72)}",
                    payload = res
                )
                true
            }

            else -> false
        }
    }

    // -------------------------------------------------------------------------
    // Passive Binary Asset Interceptor (Preserved)
    // -------------------------------------------------------------------------

    fun onResourceRequestObserved(request: WebResourceRequest) {
        val url = request.url?.toString() ?: return
        val matchedExt = extractTrackedAssetExtension(url) ?: return
        val method = request.method ?: "GET"
        val isMainFrame = request.isForMainFrame
        val nowMs = System.currentTimeMillis()
        val fileName = BinaryAssetInspector.extractFileName(url, matchedExt)
        val shouldMirror = BinaryAssetInspector.shouldCaptureLocally(matchedExt)

        val observed = InterceptedAsset(
            url = url,
            extension = matchedExt,
            method = method,
            timestamp = formatTime(nowMs),
            isForMainFrame = isMainFrame,
            fileName = fileName,
            captureStatus = if (shouldMirror) AssetCaptureStatus.CACHING else AssetCaptureStatus.OBSERVED,
            structuralSummary = if (shouldMirror) "Mirroring copy to filesDir for ByteBuffer audit…" else "Observed network asset"
        )

        var isNewUrl = false
        _uiState.update { state ->
            val exists = state.interceptedAssets.any { it.url == url }
            if (exists) {
                state
            } else {
                isNewUrl = true
                state.copy(interceptedAssets = (listOf(observed) + state.interceptedAssets).take(MAX_ASSETS))
            }
        }

        if (isNewUrl) {
            appendLog(
                category = LogCategory.ASSET,
                summary = "Observed $matchedExt asset ($fileName)",
                payload = "$method $url"
            )
            if (shouldMirror) {
                val shouldLaunch = synchronized(inFlightAssetUrls) {
                    inFlightAssetUrls.add(url)
                }
                if (shouldLaunch) {
                    val userAgent = if (_uiState.value.isDesktopMode) DESKTOP_USER_AGENT else "Android WebView"
                    val referer = _uiState.value.activeUrl
                    viewModelScope.launch(Dispatchers.IO) {
                        try {
                            val inspected = BinaryAssetInspector.fetchSaveAndInspect(
                                context = getApplication(),
                                asset = observed,
                                userAgent = userAgent,
                                refererUrl = referer
                            )
                            _uiState.update { state ->
                                state.copy(
                                    interceptedAssets = state.interceptedAssets.map { existing ->
                                        if (existing.url == url) inspected else existing
                                    }
                                )
                            }
                            if (inspected.captureStatus == AssetCaptureStatus.CAPTURED) {
                                appendLog(
                                    category = LogCategory.ASSET,
                                    summary = "Cached $fileName (${BinaryAssetInspector.formatBytes(inspected.byteSize)})",
                                    payload = inspected.structuralSummary
                                )
                            }
                        } finally {
                            synchronized(inFlightAssetUrls) {
                                inFlightAssetUrls.remove(url)
                            }
                        }
                    }
                }
            }
        }
    }

    fun onClearAssetCache() {
        viewModelScope.launch(Dispatchers.IO) {
            val removed = BinaryAssetInspector.clearLocalCache(getApplication())
            _uiState.update { state ->
                state.copy(interceptedAssets = emptyList())
            }
            appendLog(
                category = LogCategory.ASSET,
                summary = "Cleared local binary asset cache ($removed files deleted)",
                payload = "filesDir/captured_assets cleared."
            )
            _commands.tryEmit(DiagnosticsCommand.ShowToast("Cleared $removed cached binary files"))
        }
    }

    // -------------------------------------------------------------------------
    // Manual JS Console & Log Filter
    // -------------------------------------------------------------------------

    fun onToggleJsDrawer() {
        _uiState.update { it.copy(isJsDrawerOpen = !it.isJsDrawerOpen) }
    }

    fun onManualJsInputChanged(value: String) {
        _uiState.update { it.copy(manualJsInput = value) }
    }

    fun onExecuteManualScript() {
        val code = _uiState.value.manualJsInput.trim()
        if (code.isEmpty()) return
        if (!ensurePageLoadedForInspection()) return
        _commands.tryEmit(DiagnosticsCommand.EvaluateJs(WasmHeapScripts.buildManualEvalScript(code)))
    }

    fun onSelectFilter(category: LogCategory) {
        _uiState.update { it.copy(selectedFilter = category) }
    }

    fun onClearLogs() {
        _uiState.update { it.copy(logs = emptyList(), liveStreamHistory = emptyList()) }
    }

    // -------------------------------------------------------------------------
    // Structured Report & Full Log Dump Exports (MediaStore Downloads)
    // -------------------------------------------------------------------------

    fun requestExportReport(armBridge: () -> Unit, disarmBridge: () -> Boolean) {
        if (_uiState.value.isExporting) return
        _uiState.update { it.copy(isExporting = true) }

        val activeUrl = _uiState.value.activeUrl
        if (activeUrl == "about:blank") {
            writeCombinedJsonReport(webViewMetricsJson = null)
            return
        }

        armBridge()
        _commands.tryEmit(
            DiagnosticsCommand.EvaluateJs(
                WasmHeapScripts.buildCollectViewportMetricsScript(_uiState.value.heapFeed.baseIndex)
            )
        )

        exportFallbackJob?.cancel()
        exportFallbackJob = viewModelScope.launch {
            delay(2_200L)
            if (disarmBridge()) {
                writeCombinedJsonReport(webViewMetricsJson = null)
            }
        }
    }

    fun onReportPayloadFromBridge(webViewMetricsJson: String) {
        exportFallbackJob?.cancel()
        writeCombinedJsonReport(webViewMetricsJson)
    }

    private fun writeCombinedJsonReport(webViewMetricsJson: String?) {
        val snapshot = _uiState.value
        viewModelScope.launch {
            val combinedJson = withContext(Dispatchers.Default) {
                buildCombinedReportJson(snapshot, webViewMetricsJson)
            }
            val outcome = withContext(Dispatchers.IO) {
                ReportExporter.exportJsonToDownloads(
                    context = getApplication(),
                    jsonContent = combinedJson,
                    filePrefix = "webview_heap_diagnostics"
                )
            }
            _uiState.update { it.copy(isExporting = false, lastExport = outcome) }
            if (outcome.success) {
                appendLog(
                    category = LogCategory.SYSTEM,
                    summary = "Exported JSON report: ${outcome.fileName}",
                    payload = outcome.displayPath
                )
                _commands.tryEmit(
                    DiagnosticsCommand.ShowToast("Report saved to ${outcome.displayPath}")
                )
            } else {
                appendLog(
                    category = LogCategory.ERROR,
                    summary = "Report export failed",
                    payload = outcome.errorMessage.orEmpty()
                )
                _commands.tryEmit(
                    DiagnosticsCommand.ShowToast("Export failed: ${outcome.errorMessage}")
                )
            }
        }
    }

    fun onShareLastReport() {
        val last = _uiState.value.lastExport
        if (last?.success == true && last.contentUri != null) {
            _commands.tryEmit(
                DiagnosticsCommand.ShareFile(
                    uri = last.contentUri,
                    mimeType = last.mimeType,
                    title = "Share Diagnostic Report (${last.fileName})"
                )
            )
        }
    }

    fun exportFullLogDump(asJson: Boolean = false) {
        if (_uiState.value.isExportingLogDump) return
        _uiState.update { it.copy(isExportingLogDump = true) }
        val snapshot = _uiState.value

        viewModelScope.launch {
            val outcome = withContext(Dispatchers.IO) {
                if (asJson) {
                    val json = buildCombinedReportJson(snapshot, null)
                    ReportExporter.exportJsonToDownloads(
                        context = getApplication(),
                        jsonContent = json,
                        filePrefix = "webview_log_dump"
                    )
                } else {
                    val text = buildPlainTextLogDump(snapshot)
                    ReportExporter.exportTextLogDumpToDownloads(
                        context = getApplication(),
                        textContent = text
                    )
                }
            }
            _uiState.update { it.copy(isExportingLogDump = false, lastLogDumpExport = outcome) }
            if (outcome.success) {
                appendLog(
                    category = LogCategory.SYSTEM,
                    summary = "Exported log dump: ${outcome.fileName}",
                    payload = outcome.displayPath
                )
                _commands.tryEmit(
                    DiagnosticsCommand.ShowToast("Log dump saved to ${outcome.displayPath}")
                )
            } else {
                appendLog(
                    category = LogCategory.ERROR,
                    summary = "Log dump export failed",
                    payload = outcome.errorMessage.orEmpty()
                )
                _commands.tryEmit(
                    DiagnosticsCommand.ShowToast("Export failed: ${outcome.errorMessage}")
                )
            }
        }
    }

    fun onShareLastLogDump() {
        val last = _uiState.value.lastLogDumpExport
        if (last?.success == true && last.contentUri != null) {
            _commands.tryEmit(
                DiagnosticsCommand.ShareFile(
                    uri = last.contentUri,
                    mimeType = last.mimeType,
                    title = "Share Log Dump (${last.fileName})"
                )
            )
        }
    }

    // -------------------------------------------------------------------------
    // Helpers & Serializers
    // -------------------------------------------------------------------------

    private fun ensurePageLoadedForInspection(): Boolean {
        val url = _uiState.value.activeUrl
        if (url.isBlank() || url == "about:blank") {
            _commands.tryEmit(DiagnosticsCommand.ShowToast("Load a target URL first"))
            return false
        }
        return true
    }

    private fun appendLog(category: LogCategory, summary: String, payload: String) {
        val nowMs = System.currentTimeMillis()
        val entry = TelemetryLogEntry(
            id = nextLogId.getAndIncrement(),
            timestamp = formatTime(nowMs),
            epochMs = nowMs,
            category = category,
            summary = summary,
            payload = payload
        )
        _uiState.update { state ->
            state.copy(logs = (listOf(entry) + state.logs).take(MAX_LOGS))
        }
    }

    private fun buildCombinedReportJson(
        state: DiagnosticsUiState,
        webViewMetricsJson: String?
    ): String {
        val root = JSONObject()
        root.put("app", "WebViewDiagnosticsApp")
        root.put("mode", "strictly_observational_read_only")
        root.put("remoteDebuggingEnabled", false)
        root.put("exportedAtEpochMs", System.currentTimeMillis())
        root.put("activeUrl", state.activeUrl)
        root.put("pageTitle", state.pageTitle)

        val feed = state.heapFeed
        val feedJson = JSONObject().apply {
            put("baseIndex", feed.baseIndex)
            put("byteAddress", feed.byteAddress)
            put("byteAddressHex", "0x${feed.byteAddress.toString(16).uppercase(Locale.US)}")
            put("heapSource", feed.heapSourceLabel)
            put("heapByteLength", feed.heapByteLength)
            put("isPolling", feed.isPolling)
            put("isHeapReady", feed.isHeapReady)
            put("x", feed.x)
            put("y", feed.y)
            put("z", feed.z)
            put("tickCount", feed.tickCount)
            put("hasSignature", feed.hasSignature)
            put("signatureAnchorCount", feed.signatureAnchorCount)
            put("signatureCapturedAtIndex", feed.signatureCapturedAtIndex ?: JSONObject.NULL)
            put("relockConfidencePercent", feed.relockConfidencePercent)
            put("lastRelockSummary", feed.lastRelockSummary)
        }
        root.put("directHeapFeed", feedJson)

        if (!webViewMetricsJson.isNullOrBlank()) {
            runCatching { JSONObject(webViewMetricsJson) }.getOrNull()?.let {
                root.put("webViewMetrics", it)
            }
        }

        val entitiesArr = JSONArray()
        state.discoveredEntities.forEach { entity ->
            entitiesArr.put(
                JSONObject().apply {
                    put("baseIndex", entity.baseIndex)
                    put("byteAddressHex", "0x${entity.byteAddress.toString(16).uppercase(Locale.US)}")
                    put("offsetFromPlayer", entity.offsetFromPlayer)
                    put("x", entity.x)
                    put("y", entity.y)
                    put("z", entity.z)
                    put("distanceToPlayer", entity.distanceToPlayer)
                    put("strideGroup", entity.strideGroup)
                    put("confidenceScore", entity.confidenceScore)
                    put("isPinned", entity.isPinned)
                }
            )
        }
        root.put("discoveredEntities", entitiesArr)

        val assetsArr = JSONArray()
        state.interceptedAssets.forEach { asset ->
            assetsArr.put(
                JSONObject().apply {
                    put("fileName", asset.fileName)
                    put("url", asset.url)
                    put("extension", asset.extension)
                    put("captureStatus", asset.captureStatus.name)
                    put("byteSize", asset.byteSize)
                    put("magicSignature", asset.magicSignature)
                    put("sha256Prefix", asset.sha256Prefix)
                    put("structuralSummary", asset.structuralSummary)
                }
            )
        }
        root.put("interceptedAssets", assetsArr)

        val logsArr = JSONArray()
        state.logs.forEach { log ->
            logsArr.put(
                JSONObject().apply {
                    put("id", log.id)
                    put("timestamp", log.timestamp)
                    put("category", log.category.tag)
                    put("summary", log.summary)
                    put("payload", log.payload)
                }
            )
        }
        root.put("logs", logsArr)
        return root.toString(2)
    }

    private fun buildPlainTextLogDump(state: DiagnosticsUiState): String = buildString {
        val feed = state.heapFeed
        appendLine("============================================================")
        appendLine("WebViewDiagnosticsApp - Observational Telemetry Log Dump")
        appendLine("============================================================")
        appendLine("Target URL   : ${state.activeUrl}")
        appendLine("Page Title   : ${state.pageTitle}")
        appendLine("Direct Feed  : ${feed.heapSourceLabel}[${feed.baseIndex}..${feed.baseIndex + 2}] (0x${feed.byteAddress.toString(16).uppercase(Locale.US)})")
        appendLine("Live Coords  : X=${fmtCoord(feed.x)}  Y=${fmtCoord(feed.y)}  Z=${fmtCoord(feed.z)}  (ticks=${feed.tickCount})")
        appendLine("Signature    : ${if (feed.hasSignature) "${feed.signatureAnchorCount} anchors @ ${feed.signatureCapturedAtIndex}" else "Not captured"}")
        appendLine("Entities     : ${state.discoveredEntities.size} nearby structs (stride=${state.entityDetectedStride ?: "n/a"})")
        appendLine("Assets       : ${state.cachedAssetCount}/${state.interceptedAssets.size} cached")
        appendLine()

        if (state.liveStreamHistory.isNotEmpty()) {
            appendLine("--- Recent 200ms Live Coordinate Stream ---")
            state.liveStreamHistory.forEach { appendLine(it) }
            appendLine()
        }

        if (state.discoveredEntities.isNotEmpty()) {
            appendLine("--- Nearby Entity Structs ---")
            state.discoveredEntities.forEach { e ->
                appendLine(
                    "[${e.baseIndex}] (0x${e.byteAddress.toString(16).uppercase(Locale.US)}, Δ${e.offsetFromPlayer}) " +
                        "X=${fmtCoord(e.x)} Y=${fmtCoord(e.y)} Z=${fmtCoord(e.z)} dist=${String.format(Locale.US, "%.2f", e.distanceToPlayer)} conf=${e.confidenceScore}%"
                )
            }
            appendLine()
        }

        appendLine("--- Telemetry Logs (${state.logs.size}) ---")
        state.logs.asReversed().forEach { entry ->
            appendLine("[${entry.timestamp}] [${entry.category.tag}] ${entry.summary}")
            if (entry.payload.isNotBlank() && entry.payload != entry.summary) {
                appendLine("    ${entry.payload}")
            }
        }
    }

    private fun fmtCoord(value: Double): String = String.format(Locale.US, "%.3f", value)

    private fun formatTime(epochMs: Long): String {
        return SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date(epochMs))
    }

    companion object {
        const val DESKTOP_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

        private const val MAX_LOGS = 400
        private const val MAX_ASSETS = 120
        private const val MAX_LIVE_STREAM_LINES = 60

        private val TRACKED_ASSET_EXTENSIONS = listOf(
            ".wasm", ".bin", ".json", ".glb", ".gltf", ".data", ".pck", ".pak"
        )

        fun validateAndNormalizeUrl(rawInput: String): UrlValidationResult {
            val trimmed = rawInput.trim()
            if (trimmed.isEmpty()) {
                return UrlValidationResult.Invalid("Please enter a target URL.")
            }
            val lower = trimmed.lowercase(Locale.US)
            val blockedSchemes = listOf("javascript:", "file:", "content:", "data:", "vbscript:", "intent:")
            if (blockedSchemes.any { lower.startsWith(it) }) {
                return UrlValidationResult.Invalid("Only http:// and https:// URLs are permitted.")
            }
            val normalized = if (lower.startsWith("http://") || lower.startsWith("https://")) {
                trimmed
            } else {
                "https://$trimmed"
            }
            val hostPart = normalized.substringAfter("://").substringBefore('/').substringBefore('?')
            if (hostPart.isBlank() || hostPart.contains(' ')) {
                return UrlValidationResult.Invalid("URL host appears malformed.")
            }
            return UrlValidationResult.Valid(normalized)
        }

        fun extractTrackedAssetExtension(url: String): String? {
            val cleanPath = url.substringBefore('?').substringBefore('#').lowercase(Locale.US)
            return TRACKED_ASSET_EXTENSIONS.firstOrNull { cleanPath.endsWith(it) }
        }

        fun createShareIntent(uri: Uri, mimeType: String, title: String): Intent {
            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                type = mimeType
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            return Intent.createChooser(sendIntent, title)
        }
    }
}
