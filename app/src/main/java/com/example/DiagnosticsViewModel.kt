package com.example

import android.content.Context
import android.net.Uri
import android.webkit.WebResourceRequest
import androidx.lifecycle.ViewModel
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

sealed interface DiagnosticsEffect {
    data class LoadUrl(val url: String) : DiagnosticsEffect
    data object ReloadWebView : DiagnosticsEffect
    data class ApplyUserAgent(val desktopMode: Boolean, val reloadAfter: Boolean) : DiagnosticsEffect
    data class EvaluateJs(val script: String) : DiagnosticsEffect
    data class ShowToast(val message: String) : DiagnosticsEffect
    data class ShareReport(val uri: Uri, val fileName: String) : DiagnosticsEffect
}

class DiagnosticsViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(DiagnosticsUiState())
    val uiState: StateFlow<DiagnosticsUiState> = _uiState.asStateFlow()

    private val _effects = MutableSharedFlow<DiagnosticsEffect>(extraBufferCapacity = 16)
    val effects: SharedFlow<DiagnosticsEffect> = _effects.asSharedFlow()

    private val logIdGenerator = AtomicLong(1L)
    private val seenAssetUrls = LinkedHashSet<String>()
    private var pendingExportJob: Job? = null
    private var pendingExportContext: Context? = null

    fun onUrlInputChanged(input: String) {
        _uiState.update { it.copy(urlInput = input) }
    }

    fun onLoadUrlRequested() {
        val raw = _uiState.value.urlInput
        when (val validation = normalizeAndValidateUrl(raw)) {
            is UrlValidationResult.Valid -> {
                val targetUrl = validation.normalizedUrl
                seenAssetUrls.clear()
                _uiState.update {
                    it.copy(
                        urlInput = if (targetUrl == "about:blank") "" else targetUrl,
                        activeUrl = targetUrl,
                        scanStatus = AsyncScanStatus.IDLE,
                        scanStatusDetail = "",
                        step1Completed = false,
                        customGlobals = emptyList(),
                        step1DurationMs = null,
                        baselineCaptured = false,
                        baselineMetricCount = 0,
                        isolatedPaths = emptyList(),
                        discoveredPlayerPath = null,
                        playerPropertyKeys = "",
                        livePlayerCoords = null,
                        livePollActive = false,
                        livePollTickCount = 0,
                        discoveredEntityArrayPath = null,
                        entityProjectionMode = "",
                        entityViewportWidth = 0,
                        entityViewportHeight = 0,
                        discoveredEntities = emptyList(),
                        lastEntityCount = 0,
                        interceptedAssets = emptyList(),
                        estimatedFps = null,
                        canvasCount = 0
                    )
                }
                appendLog(LogCategory.SYSTEM, "Navigating to $targetUrl", targetUrl)
                _effects.tryEmit(DiagnosticsEffect.LoadUrl(targetUrl))
            }
            is UrlValidationResult.Invalid -> {
                appendLog(LogCategory.ERROR, "Blocked invalid URL", validation.reason)
                _effects.tryEmit(DiagnosticsEffect.ShowToast(validation.reason))
            }
        }
    }

    fun onReloadRequested() {
        val current = _uiState.value.activeUrl
        _uiState.update { it.copy(livePollActive = false) }
        appendLog(LogCategory.SYSTEM, "Reloading $current", current)
        _effects.tryEmit(DiagnosticsEffect.ReloadWebView)
    }

    fun onToggleDesktopMode() {
        val nextDesktop = !_uiState.value.isDesktopMode
        _uiState.update { it.copy(isDesktopMode = nextDesktop) }
        val modeLabel = if (nextDesktop) "Desktop User-Agent" else "Mobile User-Agent"
        appendLog(LogCategory.SYSTEM, "Switched to $modeLabel", modeLabel)
        _effects.tryEmit(
            DiagnosticsEffect.ApplyUserAgent(
                desktopMode = nextDesktop,
                reloadAfter = _uiState.value.activeUrl != "about:blank"
            )
        )
    }

    fun onSelectConsoleTab(tab: ConsoleTab) {
        _uiState.update { it.copy(activeConsoleTab = tab) }
    }

    fun onPageStarted(url: String) {
        _uiState.update {
            it.copy(
                activeUrl = url,
                urlInput = if (url == "about:blank") it.urlInput else url,
                isLoading = true,
                loadProgress = 10,
                livePollActive = false
            )
        }
    }

    fun onPageFinished(url: String, title: String?) {
        _uiState.update {
            it.copy(
                activeUrl = url,
                pageTitle = title.orEmpty(),
                isLoading = false,
                loadProgress = 100
            )
        }
        if (url != "about:blank") {
            appendLog(
                LogCategory.SYSTEM,
                "Page ready: ${title?.takeIf { it.isNotBlank() } ?: url}",
                url
            )
        }
    }

    fun onProgressChanged(progress: Int) {
        _uiState.update {
            it.copy(
                loadProgress = progress.coerceIn(0, 100),
                isLoading = progress in 1..99
            )
        }
    }

    fun onWebViewError(description: String, failingUrl: String?) {
        appendLog(
            LogCategory.ERROR,
            "WebView load error: $description",
            failingUrl ?: _uiState.value.activeUrl
        )
    }

    fun onBlockedSchemeAttempt(url: String) {
        appendLog(LogCategory.ERROR, "Blocked disallowed URI scheme navigation", url)
    }

    fun onScanStatusChanged(status: AsyncScanStatus, detail: String) {
        _uiState.update {
            it.copy(
                scanStatus = status,
                scanStatusDetail = detail
            )
        }
    }

    fun onPlayerCoordsUpdated(path: String, x: Double, y: Double, z: Double) {
        _uiState.update { state ->
            state.copy(
                discoveredPlayerPath = state.discoveredPlayerPath ?: path,
                livePollActive = true,
                livePollTickCount = state.livePollTickCount + 1,
                livePlayerCoords = PlayerCoordinates(
                    path = path,
                    x = x,
                    y = y,
                    z = z,
                    updatedAtMs = System.currentTimeMillis()
                )
            )
        }
    }

    // Multi-Scan Candidate Staging Buffer Actions
    fun onTogglePinCandidate(path: String) {
        _uiState.update { state ->
            val updated = state.stagingBuffer.map { item ->
                if (item.path == path) item.copy(isPinned = !item.isPinned) else item
            }
            state.copy(stagingBuffer = sortStagingBuffer(updated))
        }
    }

    fun onTogglePinnedOnlyFilter() {
        _uiState.update { it.copy(showPinnedOnly = !it.showPinnedOnly) }
    }

    fun onClearUnpinnedCandidates() {
        _uiState.update { state ->
            state.copy(stagingBuffer = state.stagingBuffer.filter { it.isPinned })
        }
    }

    fun onPinExternalPath(path: String, sourceStep: String, detail: String) {
        val nowTs = formatTimestamp(System.currentTimeMillis())
        _uiState.update { state ->
            val nextScan = state.scanCounter.coerceAtLeast(1)
            val merged = mergeIntoStagingBuffer(
                existing = state.stagingBuffer,
                incoming = listOf(path to detail),
                sourceStep = sourceStep,
                scanNumber = nextScan,
                timestamp = nowTs,
                forcePin = true
            )
            state.copy(stagingBuffer = merged)
        }
        _effects.tryEmit(DiagnosticsEffect.ShowToast("Pinned to Staging: $path"))
    }

    fun onLockCandidateForLivePolling(candidatePath: String) {
        onScanStatusChanged(AsyncScanStatus.RUNNING, "Binding live 200ms ref: $candidatePath")
        appendLog(
            LogCategory.DELTA,
            "Binding live 200ms reference to $candidatePath…",
            candidatePath
        )
        _effects.tryEmit(DiagnosticsEffect.EvaluateJs(DiagnosticsScripts.buildLockCandidateRefScript(candidatePath)))
    }

    // Step 1: Zero-Freeze Filtered Custom Global Check (<2 ms)
    fun runStep1InspectScope() {
        onScanStatusChanged(AsyncScanStatus.RUNNING, "Step 1: Filtering custom globals…")
        appendLog(
            LogCategory.SCOPE,
            "Step 1: Scanning custom globals (appending to Staging)…",
            "Filtering standard DOM/Web APIs out of window keys and checking active Canvas/WebGL contexts"
        )
        _effects.tryEmit(DiagnosticsEffect.EvaluateJs(DiagnosticsScripts.buildInspectGlobalScopeScript()))
    }

    // Step 2A: Capture Standing Baseline
    fun runStep2ACaptureBaseline() {
        onScanStatusChanged(AsyncScanStatus.RUNNING, "Step 2A: Capturing standing baseline…")
        appendLog(
            LogCategory.DELTA,
            "Step 2A: Capturing standing baseline…",
            "Snapshotting numeric properties across filtered custom globals"
        )
        _effects.tryEmit(DiagnosticsEffect.EvaluateJs(DiagnosticsScripts.buildCaptureBaselineScript()))
    }

    // Step 2B: Isolate Player Coords + start 200ms direct object-reference polling
    fun runStep2BComputeDelta() {
        onScanStatusChanged(AsyncScanStatus.RUNNING, "Step 2B: Isolating live reference…")
        appendLog(
            LogCategory.DELTA,
            "Step 2B: Isolating live player object reference…",
            "Comparing live runtime state against standing baseline and starting 200ms direct-reference polling"
        )
        _effects.tryEmit(DiagnosticsEffect.EvaluateJs(DiagnosticsScripts.buildComputeDeltaScript()))
    }

    // Step 3: Player-Anchored Visual Entity Discovery
    fun runStep3ScanEntities() {
        val anchor = _uiState.value.discoveredPlayerPath
        if (anchor.isNullOrBlank()) {
            val msg = "Complete Step 2B first (or lock a Staging candidate) so Step 3 can anchor to your player coordinates."
            appendLog(LogCategory.ENTITY, msg, msg)
            _effects.tryEmit(DiagnosticsEffect.ShowToast(msg))
            return
        }
        onScanStatusChanged(AsyncScanStatus.RUNNING, "Step 3: Climbing parent tree from $anchor…")
        appendLog(
            LogCategory.ENTITY,
            "Step 3: Scanning sibling entity collections anchored to $anchor…",
            "Climbing 1–3 parent levels up from $anchor to inspect sibling Arrays/Maps/Sets & project to 2D canvas"
        )
        _effects.tryEmit(DiagnosticsEffect.EvaluateJs(DiagnosticsScripts.buildScanEntitiesScript(anchor)))
    }

    // Passive Resource & Asset Request Monitor (called from WebViewClient.shouldInterceptRequest)
    fun onResourceRequestObserved(request: WebResourceRequest) {
        val uri = request.url ?: return
        val fullUrl = uri.toString()
        val pathLower = (uri.path ?: fullUrl).lowercase(Locale.US)
        val matchedExt = matchTrackedAssetExtension(pathLower) ?: return

        val record = InterceptedAsset(
            url = fullUrl,
            extension = matchedExt,
            method = request.method ?: "GET",
            timestamp = formatTimestamp(System.currentTimeMillis()),
            isForMainFrame = request.isForMainFrame
        )

        viewModelScope.launch(Dispatchers.Main.immediate) {
            if (!seenAssetUrls.add(fullUrl)) return@launch
            if (seenAssetUrls.size > MAX_TRACKED_ASSETS) {
                val oldest = seenAssetUrls.firstOrNull()
                if (oldest != null) seenAssetUrls.remove(oldest)
            }
            _uiState.update { state ->
                val updated = (state.interceptedAssets + record).takeLast(MAX_TRACKED_ASSETS)
                state.copy(interceptedAssets = updated)
            }
            val fileLabel = uri.lastPathSegment ?: fullUrl.takeLast(48)
            appendLog(
                category = LogCategory.ASSET,
                summary = "Intercepted [$matchedExt] $fileLabel",
                payload = "${record.method} $fullUrl"
            )
        }
    }

    // Step 4 / 5: Export Complete Telemetry Handoff JSON Report
    fun requestExportReport(context: Context, armBridge: () -> Unit, disarmBridge: () -> Boolean) {
        if (_uiState.value.isExporting) return
        val appContext = context.applicationContext
        pendingExportContext = appContext
        _uiState.update { it.copy(isExporting = true) }
        armBridge()
        appendLog(
            LogCategory.SYSTEM,
            "Exporting full telemetry report (pinned candidates, live coords, entities, logs)…",
            "Invoking TelemetryBridge.exportReport()"
        )
        _effects.tryEmit(DiagnosticsEffect.EvaluateJs(DiagnosticsScripts.buildCollectViewportMetricsScript()))

        pendingExportJob?.cancel()
        pendingExportJob = viewModelScope.launch {
            delay(1_200L)
            if (disarmBridge()) {
                val fallbackViewportJson = JSONObject()
                    .put("url", _uiState.value.activeUrl)
                    .put("note", "Viewport metrics collected natively (WebView JS callback timed out)")
                    .toString()
                finalizeReportExport(appContext, fallbackViewportJson)
            }
        }
    }

    fun onReportPayloadFromBridge(viewportJson: String) {
        pendingExportJob?.cancel()
        val appContext = pendingExportContext ?: return
        finalizeReportExport(appContext, viewportJson)
    }

    private fun finalizeReportExport(appContext: Context, viewportJson: String) {
        val snapshot = _uiState.value
        viewModelScope.launch {
            val fullReportJson = withContext(Dispatchers.Default) {
                buildFullReportJson(snapshot, viewportJson)
            }
            val outcome = withContext(Dispatchers.IO) {
                ReportExporter.exportJsonToDownloads(appContext, fullReportJson)
            }
            _uiState.update {
                it.copy(
                    isExporting = false,
                    lastExport = outcome
                )
            }
            if (outcome.success) {
                appendLog(
                    LogCategory.SYSTEM,
                    "Exported JSON report -> ${outcome.displayPath}",
                    "Saved ${fullReportJson.length} bytes to ${outcome.displayPath}"
                )
                _effects.tryEmit(
                    DiagnosticsEffect.ShowToast(
                        appContext.getString(R.string.toast_report_saved, outcome.fileName)
                    )
                )
            } else {
                val err = outcome.errorMessage ?: "Unknown error"
                appendLog(LogCategory.ERROR, "Report export failed", err)
                _effects.tryEmit(
                    DiagnosticsEffect.ShowToast(
                        appContext.getString(R.string.toast_report_failed, err)
                    )
                )
            }
        }
    }

    fun onShareLastReport() {
        val export = _uiState.value.lastExport
        if (export != null && export.success && export.contentUri != null) {
            _effects.tryEmit(DiagnosticsEffect.ShareReport(export.contentUri, export.fileName))
        }
    }

    // Manual JavaScript Execution Drawer
    fun onToggleJsDrawer(open: Boolean? = null) {
        _uiState.update { it.copy(isJsDrawerOpen = open ?: !it.isJsDrawerOpen) }
    }

    fun onManualJsInputChanged(script: String) {
        _uiState.update { it.copy(manualJsInput = script) }
    }

    fun onExecuteManualScript() {
        val code = _uiState.value.manualJsInput.trim()
        if (code.isEmpty()) return
        onScanStatusChanged(AsyncScanStatus.RUNNING, "Evaluating manual JS…")
        appendLog(LogCategory.JS, "Exec> ${code.take(90)}", code)
        _effects.tryEmit(DiagnosticsEffect.EvaluateJs(DiagnosticsScripts.buildManualEvalScript(code)))
    }

    fun onSelectFilter(category: LogCategory) {
        _uiState.update { it.copy(selectedFilter = category) }
    }

    fun onToggleConsoleExpanded() {
        _uiState.update { it.copy(isConsoleExpanded = !it.isConsoleExpanded) }
    }

    fun onClearLogs() {
        _uiState.update { it.copy(logs = emptyList()) }
    }

    fun onTelemetryFromBridge(rawCategory: String, payload: String) {
        val category = LogCategory.fromRaw(rawCategory)
        val parsedSummary = parseStructuredTelemetry(category, payload)
        appendLog(category, parsedSummary, prettyFormatIfJson(payload))
    }

    private fun parseStructuredTelemetry(category: LogCategory, payload: String): String {
        val json = try {
            JSONObject(payload)
        } catch (_: Exception) {
            return payload.lineSequence().firstOrNull()?.take(110) ?: category.label
        }

        val nowTs = formatTimestamp(System.currentTimeMillis())
        val stage = json.optString("stage", "")

        return when (stage) {
            "GLOBAL_SCOPE_INSPECTED" -> {
                val canvases = json.optInt("canvasCount", 0)
                val durationMs = json.optDouble("durationMs", 0.0)
                val totalFiltered = json.optInt("totalWindowKeysFiltered", 0)
                val customArray = json.optJSONArray("customGlobals") ?: JSONArray()
                val globalKeys = mutableListOf<String>()
                val stagingPairs = mutableListOf<Pair<String, String>>()

                for (i in 0 until customArray.length()) {
                    val obj = customArray.optJSONObject(i) ?: continue
                    val key = obj.optString("key", "").trim()
                    if (key.isEmpty()) continue
                    globalKeys.add(key)
                    val path = obj.optString("path", "window.$key")
                    val ctor = obj.optString("constructor", obj.optString("type", "Object"))
                    val keyCount = obj.optInt("keyCount", 0)
                    val previewArr = obj.optJSONArray("previewKeys")
                    val previewList = buildList {
                        if (previewArr != null) {
                            for (pi in 0 until minOf(previewArr.length(), 4)) {
                                val pk = previewArr.optString(pi, "").trim()
                                if (pk.isNotEmpty()) add(pk)
                            }
                        }
                    }
                    val previewSuffix = if (previewList.isNotEmpty()) " • ${previewList.joinToString(", ")}" else ""
                    stagingPairs.add(path to "$ctor ($keyCount keys$previewSuffix)")
                }

                _uiState.update { state ->
                    val nextScan = state.scanCounter + 1
                    val mergedStaging = mergeIntoStagingBuffer(
                        existing = state.stagingBuffer,
                        incoming = stagingPairs,
                        sourceStep = "Step 1",
                        scanNumber = nextScan,
                        timestamp = nowTs
                    )
                    state.copy(
                        scanCounter = nextScan,
                        step1Completed = true,
                        customGlobals = (state.customGlobals + globalKeys).distinct(),
                        step1DurationMs = durationMs,
                        canvasCount = canvases,
                        stagingBuffer = mergedStaging,
                        scanStatus = AsyncScanStatus.COMPLETED,
                        scanStatusDetail = "Scan #$nextScan: ${globalKeys.size} globals (${durationMs}ms)"
                    )
                }
                "Step 1 (${durationMs}ms): Found ${globalKeys.size} custom globals (filtered $totalFiltered keys) • Accumulated in Staging"
            }

            "BASELINE_CAPTURED" -> {
                val metrics = json.optInt("metricCount", 0)
                val roots = json.optInt("rootObjectCount", 0)
                val chunks = json.optInt("chunksExecuted", 1)
                val containersArr = json.optJSONArray("candidateContainers") ?: JSONArray()
                val stagingPairs = buildList {
                    for (i in 0 until containersArr.length()) {
                        val obj = containersArr.optJSONObject(i) ?: continue
                        val p = obj.optString("path", "").trim()
                        val d = obj.optString("detail", "Coordinate container")
                        if (p.isNotEmpty()) add(p to d)
                    }
                }

                _uiState.update { state ->
                    val nextScan = state.scanCounter + 1
                    val mergedStaging = mergeIntoStagingBuffer(
                        existing = state.stagingBuffer,
                        incoming = stagingPairs,
                        sourceStep = "Step 2A",
                        scanNumber = nextScan,
                        timestamp = nowTs
                    )
                    state.copy(
                        scanCounter = nextScan,
                        baselineCaptured = true,
                        baselineMetricCount = metrics,
                        stagingBuffer = mergedStaging,
                        scanStatus = AsyncScanStatus.COMPLETED,
                        scanStatusDetail = "2A Captured ($metrics values)"
                    )
                }
                "Step 2A (${chunks} chunks): Captured $metrics standing values across $roots custom globals"
            }

            "BASELINE_MISSING" -> {
                _uiState.update {
                    it.copy(
                        scanStatus = AsyncScanStatus.IDLE,
                        scanStatusDetail = "Capture 2A while standing still first"
                    )
                }
                json.optString("message", "Stand still and capture 2A baseline first.")
            }

            "DELTA_COMPUTED" -> {
                val deltasArray = json.optJSONArray("deltas") ?: JSONArray()
                val isolatedList = buildList {
                    for (i in 0 until deltasArray.length()) {
                        val obj = deltasArray.optJSONObject(i) ?: continue
                        add(
                            IsolatedCoordinatePath(
                                path = obj.optString("path", ""),
                                baselineValue = obj.optDouble("baseline", 0.0),
                                currentValue = obj.optDouble("current", 0.0),
                                delta = obj.optDouble("delta", 0.0)
                            )
                        )
                    }
                }
                val playerPath = json.optString("discoveredPlayerPath", "").takeIf { it.isNotBlank() && it != "null" }
                val propKeys = json.optString("propertyKeys", "")
                val liveObj = json.optJSONObject("liveSample")
                val liveCoords = if (playerPath != null && liveObj != null) {
                    PlayerCoordinates(
                        path = playerPath,
                        x = liveObj.optDouble("x", 0.0),
                        y = liveObj.optDouble("y", 0.0),
                        z = liveObj.optDouble("z", 0.0)
                    )
                } else null

                val stagingPairs = buildList {
                    if (playerPath != null) {
                        val keySummary = if (propKeys.isNotBlank()) "Live ref [$propKeys]" else "Live player container"
                        add(playerPath to keySummary)
                    }
                    isolatedList.take(15).forEach { item ->
                        if (item.path.isNotBlank()) {
                            val sign = if (item.delta >= 0) "+" else ""
                            add(item.path to "Δ $sign${String.format(Locale.US, "%.2f", item.delta)} (cur: ${String.format(Locale.US, "%.2f", item.currentValue)})")
                        }
                    }
                }

                _uiState.update { state ->
                    val nextScan = state.scanCounter + 1
                    val mergedStaging = mergeIntoStagingBuffer(
                        existing = state.stagingBuffer,
                        incoming = stagingPairs,
                        sourceStep = "Step 2B",
                        scanNumber = nextScan,
                        timestamp = nowTs
                    )
                    state.copy(
                        scanCounter = nextScan,
                        isolatedPaths = isolatedList,
                        discoveredPlayerPath = playerPath ?: state.discoveredPlayerPath,
                        playerPropertyKeys = if (propKeys.isNotBlank()) propKeys else state.playerPropertyKeys,
                        livePlayerCoords = liveCoords ?: state.livePlayerCoords,
                        livePollActive = playerPath != null || state.livePollActive,
                        stagingBuffer = mergedStaging,
                        scanStatus = if (playerPath != null) AsyncScanStatus.COMPLETED else AsyncScanStatus.IDLE,
                        scanStatusDetail = playerPath?.let { "Live 200ms: $it" } ?: "No movement detected"
                    )
                }
                val elapsedMs = json.optInt("elapsedMs", 0)
                if (playerPath == null) {
                    "Step 2B (${elapsedMs}ms): No player movement detected — walk 5–10 steps and retry."
                } else {
                    "Step 2B (${elapsedMs}ms): Live 200ms lock on $playerPath [$propKeys] (${isolatedList.size} deltas staged)"
                }
            }

            "LIVE_REF_LOCKED" -> {
                val playerPath = json.optString("discoveredPlayerPath", "").takeIf { it.isNotBlank() && it != "null" }
                val propKeys = json.optString("propertyKeys", "")
                val liveObj = json.optJSONObject("liveSample")
                val liveCoords = if (playerPath != null && liveObj != null) {
                    PlayerCoordinates(
                        path = playerPath,
                        x = liveObj.optDouble("x", 0.0),
                        y = liveObj.optDouble("y", 0.0),
                        z = liveObj.optDouble("z", 0.0)
                    )
                } else null
                _uiState.update { state ->
                    state.copy(
                        discoveredPlayerPath = playerPath ?: state.discoveredPlayerPath,
                        playerPropertyKeys = propKeys,
                        livePlayerCoords = liveCoords ?: state.livePlayerCoords,
                        livePollActive = playerPath != null,
                        scanStatus = AsyncScanStatus.COMPLETED,
                        scanStatusDetail = playerPath?.let { "Live 200ms: $it" } ?: state.scanStatusDetail
                    )
                }
                "Locked live 200ms reference on ${playerPath ?: "candidate"} [$propKeys]"
            }

            "LIVE_REF_FAILED" -> {
                val msg = json.optString("message", "Could not bind candidate reference.")
                _uiState.update { it.copy(scanStatus = AsyncScanStatus.ERROR, scanStatusDetail = "Live bind failed") }
                msg
            }

            "ANCHOR_MISSING" -> {
                _uiState.update {
                    it.copy(
                        scanStatus = AsyncScanStatus.IDLE,
                        scanStatusDetail = "Complete Step 2B first"
                    )
                }
                json.optString("message", "Complete Step 2B first.")
            }

            "ENTITIES_SCANNED" -> {
                val count = json.optInt("entityCount", 0)
                val arrayPath = json.optString("entityArrayPath", "").takeIf { it.isNotBlank() && it != "null" }
                val mode = json.optString("projectionMode", "pinhole_viewport_fallback")
                val vpObj = json.optJSONObject("viewport")
                val vpW = vpObj?.optInt("width", 0) ?: 0
                val vpH = vpObj?.optInt("height", 0) ?: 0
                val entitiesArr = json.optJSONArray("entities") ?: JSONArray()
                val parsedEntities = buildList {
                    for (i in 0 until entitiesArr.length()) {
                        val obj = entitiesArr.optJSONObject(i) ?: continue
                        val world = obj.optJSONObject("world")
                        add(
                            DiscoveredEntitySample(
                                name = obj.optString("name", "Entity"),
                                id = obj.optString("id", ""),
                                path = obj.optString("path", ""),
                                worldX = world?.optDouble("x", 0.0) ?: 0.0,
                                worldY = world?.optDouble("y", 0.0) ?: 0.0,
                                worldZ = world?.optDouble("z", 0.0) ?: 0.0,
                                distanceToPlayer = obj.optDouble("distanceToPlayer", 0.0),
                                screenX = obj.optDouble("screenX", 0.0),
                                screenY = obj.optDouble("screenY", 0.0),
                                inViewport = obj.optBoolean("inViewport", false),
                                sampleKeys = extractStringArray(obj.optJSONArray("sampleKeys"))
                            )
                        )
                    }
                }
                _uiState.update { state ->
                    val mergedStaging = if (arrayPath != null) {
                        mergeIntoStagingBuffer(
                            existing = state.stagingBuffer,
                            incoming = listOf(arrayPath to "Entity collection ($count detected)"),
                            sourceStep = "Step 3",
                            scanNumber = state.scanCounter + 1,
                            timestamp = nowTs
                        )
                    } else state.stagingBuffer

                    state.copy(
                        scanCounter = state.scanCounter + 1,
                        discoveredEntityArrayPath = arrayPath,
                        entityProjectionMode = mode,
                        entityViewportWidth = vpW,
                        entityViewportHeight = vpH,
                        discoveredEntities = parsedEntities,
                        lastEntityCount = count,
                        stagingBuffer = mergedStaging,
                        scanStatus = if (count > 0) AsyncScanStatus.COMPLETED else AsyncScanStatus.IDLE,
                        scanStatusDetail = if (arrayPath != null) "$count entities in $arrayPath" else "0 sibling entities"
                    )
                }
                if (arrayPath != null) {
                    "Step 3: Found $count entities in $arrayPath (projection: $mode)"
                } else {
                    "Step 3: 0 sibling entity collections found near ${_uiState.value.discoveredPlayerPath}"
                }
            }

            "MANUAL_EVAL" -> {
                val type = json.optString("resultType", "unknown")
                val ms = json.optDouble("durationMs", 0.0)
                val preview = json.optString("result", "").replace('\n', ' ').take(72)
                _uiState.update {
                    it.copy(
                        scanStatus = AsyncScanStatus.COMPLETED,
                        scanStatusDetail = "JS eval (${ms}ms)"
                    )
                }
                "Result ($type, ${ms}ms): $preview"
            }

            else -> payload.take(110)
        }
    }

    private fun extractStringArray(array: JSONArray?): List<String> {
        if (array == null) return emptyList()
        return buildList {
            for (i in 0 until array.length()) {
                val item = array.optString(i, "").trim()
                if (item.isNotEmpty()) add(item)
            }
        }
    }

    private fun appendLog(category: LogCategory, summary: String, payload: String) {
        val now = System.currentTimeMillis()
        val entry = TelemetryLogEntry(
            id = logIdGenerator.getAndIncrement(),
            timestamp = formatTimestamp(now),
            epochMs = now,
            category = category,
            summary = summary,
            payload = payload
        )
        _uiState.update { state ->
            val updated = (state.logs + entry).takeLast(MAX_LOG_ENTRIES)
            state.copy(logs = updated)
        }
    }

    private fun buildFullReportJson(
        state: DiagnosticsUiState,
        viewportMetricsJson: String
    ): String {
        val root = JSONObject()
        root.put("application", "WebViewDiagnosticsApp")
        root.put("generatedAtIso", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US).format(Date()))
        root.put("activeUrl", state.activeUrl)
        root.put("pageTitle", state.pageTitle)
        root.put("desktopUserAgentEnabled", state.isDesktopMode)
        root.put("remoteWebContentsDebuggingEnabled", false)
        root.put("totalScansExecuted", state.scanCounter)

        val bridgeObj = try {
            JSONObject(viewportMetricsJson)
        } catch (_: Exception) {
            JSONObject().put("raw", viewportMetricsJson)
        }

        // 1. Viewport dimensions
        val viewportObj = bridgeObj.optJSONObject("viewport") ?: JSONObject()
        root.put(
            "viewport",
            JSONObject()
                .put("width", viewportObj.optInt("width", viewportObj.optInt("innerWidth", 0)))
                .put("height", viewportObj.optInt("height", viewportObj.optInt("innerHeight", 0)))
                .put("devicePixelRatio", viewportObj.optDouble("devicePixelRatio", 1.0))
                .put("canvases", bridgeObj.optJSONArray("canvases") ?: JSONArray())
        )

        // 2. Pinned Candidate Staging List & Full Accumulated Buffer
        val pinnedArr = JSONArray()
        val fullStagingArr = JSONArray()
        state.stagingBuffer.forEach { item ->
            val itemJson = JSONObject()
                .put("path", item.path)
                .put("sourceStep", item.sourceStep)
                .put("detail", item.detail)
                .put("scanNumber", item.scanNumber)
                .put("seenCount", item.seenCount)
                .put("isPinned", item.isPinned)
                .put("lastSeenTimestamp", item.lastSeenTimestamp)
            fullStagingArr.put(itemJson)
            if (item.isPinned) pinnedArr.put(itemJson)
        }
        root.put("pinnedCandidates", pinnedArr)
        root.put("candidateStagingBuffer", fullStagingArr)

        // 3. Confirmed Live Player Coordinate Reference Path + Live Sample
        val confirmedPlayerPath = state.discoveredPlayerPath
            ?: bridgeObj.optString("discoveredPlayerPath", "").takeIf { it.isNotBlank() && it != "null" }
        val liveSampleJson = JSONObject().apply {
            val live = state.livePlayerCoords
            val bridgeLive = bridgeObj.optJSONObject("livePlayerSample")
            if (bridgeLive != null) {
                put("x", bridgeLive.optDouble("x", live?.x ?: 0.0))
                put("y", bridgeLive.optDouble("y", live?.y ?: 0.0))
                put("z", bridgeLive.optDouble("z", live?.z ?: 0.0))
            } else if (live != null) {
                put("x", live.x)
                put("y", live.y)
                put("z", live.z)
            }
        }
        val deltasArr = JSONArray()
        state.isolatedPaths.forEach { item ->
            deltasArr.put(
                JSONObject()
                    .put("path", item.path)
                    .put("baseline", item.baselineValue)
                    .put("current", item.currentValue)
                    .put("delta", item.delta)
            )
        }
        root.put(
            "playerCoordinates",
            JSONObject()
                .put("confirmedJsPath", confirmedPlayerPath ?: JSONObject.NULL)
                .put("propertyKeys", bridgeObj.optJSONObject("liveRefPropertyKeys") ?: state.playerPropertyKeys)
                .put("livePollingIntervalMs", 200)
                .put("liveSample", if (liveSampleJson.length() > 0) liveSampleJson else JSONObject.NULL)
                .put("isolatedDeltas", deltasArr)
        )

        // 4. Confirmed Entity Array + Screen Projections
        val confirmedEntityPath = state.discoveredEntityArrayPath
            ?: bridgeObj.optString("discoveredEntityArrayPath", "").takeIf { it.isNotBlank() && it != "null" }
        val entitiesArr = JSONArray()
        state.discoveredEntities.forEach { ent ->
            val keysJson = JSONArray()
            ent.sampleKeys.forEach { keysJson.put(it) }
            entitiesArr.put(
                JSONObject()
                    .put("name", ent.name)
                    .put("id", ent.id)
                    .put("path", ent.path)
                    .put(
                        "worldCoords",
                        JSONObject()
                            .put("x", ent.worldX)
                            .put("y", ent.worldY)
                            .put("z", ent.worldZ)
                    )
                    .put("distanceToPlayer", ent.distanceToPlayer)
                    .put(
                        "projectedScreenCoords",
                        JSONObject()
                            .put("screenX", ent.screenX)
                            .put("screenY", ent.screenY)
                            .put("inViewport", ent.inViewport)
                    )
                    .put("sampleKeys", keysJson)
            )
        }
        root.put(
            "entityCollection",
            JSONObject()
                .put("confirmedJsPath", confirmedEntityPath ?: JSONObject.NULL)
                .put("projectionMode", state.entityProjectionMode)
                .put("totalDetectedEntities", state.discoveredEntities.size)
                .put("entities", entitiesArr)
        )

        // 5. Intercepted asset URLs
        val assetsArray = JSONArray()
        state.interceptedAssets.forEach { asset ->
            assetsArray.put(
                JSONObject()
                    .put("url", asset.url)
                    .put("extension", asset.extension)
                    .put("method", asset.method)
                    .put("timestamp", asset.timestamp)
                    .put("isForMainFrame", asset.isForMainFrame)
            )
        }
        root.put("interceptedAssetUrls", assetsArray)

        // 6. Session logs
        val logsArray = JSONArray()
        state.logs.forEach { entry ->
            logsArray.put(
                JSONObject()
                    .put("id", entry.id)
                    .put("timestamp", entry.timestamp)
                    .put("epochMs", entry.epochMs)
                    .put("category", entry.category.tag)
                    .put("summary", entry.summary)
                    .put("payload", entry.payload)
            )
        }
        root.put("sessionLogs", logsArray)

        return root.toString(2)
    }

    private fun prettyFormatIfJson(raw: String): String {
        val trimmed = raw.trim()
        if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) return raw
        return try {
            if (trimmed.startsWith("{")) JSONObject(trimmed).toString(2)
            else JSONArray(trimmed).toString(2)
        } catch (_: Exception) {
            raw
        }
    }

    private fun formatTimestamp(epochMs: Long): String {
        return SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date(epochMs))
    }

    companion object {
        private const val MAX_LOG_ENTRIES = 500
        private const val MAX_TRACKED_ASSETS = 250
        private const val MAX_STAGING_ITEMS = 250

        const val DESKTOP_USER_AGENT =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"

        private val TRACKED_EXTENSIONS = listOf(
            ".wasm", ".json", ".bin", ".glb", ".gltf", ".data", ".pck", ".pak", ".ktx2", ".dds"
        )

        fun mergeIntoStagingBuffer(
            existing: List<CandidateStagingItem>,
            incoming: List<Pair<String, String>>,
            sourceStep: String,
            scanNumber: Int,
            timestamp: String,
            forcePin: Boolean = false
        ): List<CandidateStagingItem> {
            val map = LinkedHashMap<String, CandidateStagingItem>(existing.size + incoming.size)
            existing.forEach { map[it.path] = it }

            incoming.forEach { (rawPath, detail) ->
                val path = rawPath.trim()
                if (path.isEmpty()) return@forEach
                val prev = map[path]
                if (prev != null) {
                    map[path] = prev.copy(
                        sourceStep = sourceStep,
                        detail = detail.ifBlank { prev.detail },
                        scanNumber = scanNumber,
                        seenCount = prev.seenCount + 1,
                        isPinned = prev.isPinned || forcePin,
                        lastSeenTimestamp = timestamp
                    )
                } else {
                    map[path] = CandidateStagingItem(
                        path = path,
                        sourceStep = sourceStep,
                        detail = detail,
                        scanNumber = scanNumber,
                        seenCount = 1,
                        isPinned = forcePin,
                        lastSeenTimestamp = timestamp
                    )
                }
            }
            return sortStagingBuffer(map.values.toList()).take(MAX_STAGING_ITEMS)
        }

        private fun sortStagingBuffer(items: List<CandidateStagingItem>): List<CandidateStagingItem> {
            return items.sortedWith(
                compareByDescending<CandidateStagingItem> { it.isPinned }
                    .thenByDescending { it.scanNumber }
                    .thenByDescending { it.seenCount }
            )
        }

        fun normalizeAndValidateUrl(rawInput: String): UrlValidationResult {
            val trimmed = rawInput.trim()
            if (trimmed.isEmpty() || trimmed.equals("about:blank", ignoreCase = true)) {
                return UrlValidationResult.Valid("about:blank")
            }

            val lower = trimmed.lowercase(Locale.US)
            val disallowedSchemes = listOf("javascript:", "file:", "content:", "data:", "intent:", "vbscript:")
            if (disallowedSchemes.any { lower.startsWith(it) }) {
                return UrlValidationResult.Invalid("Scheme not allowed in diagnostic WebView: ${trimmed.substringBefore(':')}:")
            }

            val withScheme = if (lower.startsWith("https://") || lower.startsWith("http://")) {
                trimmed
            } else if (trimmed.contains("://")) {
                return UrlValidationResult.Invalid("Only http://, https://, and about:blank URLs are supported.")
            } else {
                "https://$trimmed"
            }

            val parsed = Uri.parse(withScheme)
            val host = parsed.host
            if (host.isNullOrBlank()) {
                return UrlValidationResult.Invalid("Please enter a valid domain or URL.")
            }
            return UrlValidationResult.Valid(withScheme)
        }

        fun isAllowedNavigationUri(uri: Uri?): Boolean {
            if (uri == null) return false
            val full = uri.toString()
            if (full.equals("about:blank", ignoreCase = true)) return true
            val scheme = uri.scheme?.lowercase(Locale.US)
            return scheme == "https" || scheme == "http"
        }

        fun matchTrackedAssetExtension(pathLower: String): String? {
            for (ext in TRACKED_EXTENSIONS) {
                if (pathLower.endsWith(ext) || pathLower.contains("$ext?")) {
                    return ext
                }
            }
            if (pathLower.contains("chunk") && (pathLower.endsWith(".js") || pathLower.endsWith(".mjs") || pathLower.endsWith(".bin"))) {
                return "chunk"
            }
            return null
        }
    }
}
