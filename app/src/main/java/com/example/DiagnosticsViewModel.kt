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
                        discoveredShallowPaths = emptyList(),
                        baselineCaptured = false,
                        baselineMetricCount = 0,
                        isolatedPaths = emptyList(),
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

    fun onPageStarted(url: String) {
        _uiState.update {
            it.copy(
                activeUrl = url,
                urlInput = if (url == "about:blank") it.urlInput else url,
                isLoading = true,
                loadProgress = 10
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
        appendLog(
            LogCategory.ERROR,
            "Blocked disallowed URI scheme navigation",
            url
        )
    }

    fun onScanStatusChanged(status: AsyncScanStatus, detail: String) {
        _uiState.update {
            it.copy(
                scanStatus = status,
                scanStatusDetail = detail
            )
        }
    }

    // Step 1: Non-Blocking Shallow Heuristic Global Scope Inspection
    fun runStep1InspectScope() {
        onScanStatusChanged(AsyncScanStatus.RUNNING, "Step 1: Scheduling shallow scan…")
        appendLog(
            LogCategory.SCOPE,
            "Running Step 1: Async Shallow Heuristic Scope Scan…",
            "Scanning window globals & engine namespaces (depth 1–2, chunked via rAF)"
        )
        _effects.tryEmit(DiagnosticsEffect.EvaluateJs(DiagnosticsScripts.buildInspectGlobalScopeScript()))
    }

    // Step 2A: Non-Blocking Shallow State Baseline
    fun runStep2ACaptureBaseline() {
        onScanStatusChanged(AsyncScanStatus.RUNNING, "Step 2A: Capturing shallow baseline…")
        appendLog(
            LogCategory.DELTA,
            "Running Step 2A: Async Shallow State Baseline…",
            "Snapshotting top-level numeric properties & vectors (max depth 2)"
        )
        _effects.tryEmit(DiagnosticsEffect.EvaluateJs(DiagnosticsScripts.buildCaptureBaselineScript()))
    }

    // Step 2B: Non-Blocking Shallow State Delta
    fun runStep2BComputeDelta() {
        onScanStatusChanged(AsyncScanStatus.RUNNING, "Step 2B: Computing shallow deltas…")
        appendLog(
            LogCategory.DELTA,
            "Running Step 2B: Async Shallow State Delta…",
            "Comparing live shallow runtime values against baseline snapshot"
        )
        _effects.tryEmit(DiagnosticsEffect.EvaluateJs(DiagnosticsScripts.buildComputeDeltaScript()))
    }

    // Step 3: Non-Blocking Shallow Entity Bounding Box & Canvas Projection Analysis
    fun runStep3ScanEntities() {
        onScanStatusChanged(AsyncScanStatus.RUNNING, "Step 3: Scanning shallow entities…")
        appendLog(
            LogCategory.ENTITY,
            "Running Step 3: Async Shallow Entity Scan…",
            "Probing shallow entity collections and 3D→2D canvas projection"
        )
        _effects.tryEmit(DiagnosticsEffect.EvaluateJs(DiagnosticsScripts.buildScanEntitiesScript()))
    }

    // Step 4: Automatic Resource & Asset Request Monitor (called from WebViewClient.shouldInterceptRequest)
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

    // Step 5: Export Diagnostic Report (.json)
    fun requestExportReport(context: Context, armBridge: () -> Unit, disarmBridge: () -> Boolean) {
        if (_uiState.value.isExporting) return
        val appContext = context.applicationContext
        pendingExportContext = appContext
        _uiState.update { it.copy(isExporting = true) }
        armBridge()
        appendLog(LogCategory.SYSTEM, "Collecting viewport telemetry for JSON export…", "Invoking TelemetryBridge.exportReport()")
        _effects.tryEmit(DiagnosticsEffect.EvaluateJs(DiagnosticsScripts.buildCollectViewportMetricsScript()))

        // Safety fallback if the current page cannot execute JS (e.g. blank/network error)
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

    /**
     * Receives structured telemetry messages forwarded from [TelemetryBridge.logTelemetry].
     */
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

        val stage = json.optString("stage", "")
        return when (stage) {
            "GLOBAL_SCOPE_INSPECTED" -> {
                val canvases = json.optInt("canvasCount", 0)
                val candidateCount = json.optInt("candidateCount", json.optInt("customObjectCount", 0))
                val chunks = json.optInt("chunksExecuted", 1)
                val fps = if (json.isNull("estimatedFps")) null else json.optInt("estimatedFps", -1).takeIf { it > 0 }
                val discovered = extractStringArray(json.optJSONArray("shallowPaths"))
                _uiState.update {
                    it.copy(
                        canvasCount = canvases,
                        estimatedFps = fps ?: it.estimatedFps,
                        discoveredShallowPaths = mergeShallowPaths(it.discoveredShallowPaths, discovered),
                        scanStatus = AsyncScanStatus.COMPLETED,
                        scanStatusDetail = "Step 1: $candidateCount containers (${chunks} chunks)"
                    )
                }
                val fpsLabel = fps?.let { "${it}fps" } ?: "rAF idle"
                "Scope (depth≤2, ${chunks} chunks): $candidateCount containers • ${discovered.size} shallow paths • $canvases canvas(es) • $fpsLabel"
            }
            "BASELINE_CAPTURED" -> {
                val metrics = json.optInt("metricCount", 0)
                val roots = json.optInt("rootObjectCount", 0)
                val chunks = json.optInt("chunksExecuted", 1)
                val discovered = extractStringArray(json.optJSONArray("shallowPaths"))
                _uiState.update {
                    it.copy(
                        baselineCaptured = true,
                        baselineMetricCount = metrics,
                        discoveredShallowPaths = mergeShallowPaths(it.discoveredShallowPaths, discovered),
                        scanStatus = AsyncScanStatus.COMPLETED,
                        scanStatusDetail = "2A Armed ($metrics shallow metrics)"
                    )
                }
                "Shallow baseline (${chunks} chunks): $metrics numeric metrics across $roots roots"
            }
            "BASELINE_MISSING" -> {
                _uiState.update {
                    it.copy(
                        scanStatus = AsyncScanStatus.IDLE,
                        scanStatusDetail = "Capture 2A baseline first"
                    )
                }
                json.optString("message", "Capture baseline (2A) first.")
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
                val newPaths = isolatedList.map { it.path }.filter { it.isNotBlank() }
                _uiState.update {
                    it.copy(
                        isolatedPaths = isolatedList,
                        discoveredShallowPaths = mergeShallowPaths(it.discoveredShallowPaths, newPaths),
                        scanStatus = AsyncScanStatus.COMPLETED,
                        scanStatusDetail = "Step 2B: ${isolatedList.size} deltas isolated"
                    )
                }
                val totalChanged = json.optInt("totalChanged", isolatedList.size)
                val elapsedMs = json.optInt("elapsedMs", 0)
                val chunks = json.optInt("chunksExecuted", 1)
                if (isolatedList.isEmpty()) {
                    "Delta (${elapsedMs}ms, ${chunks} chunks): 0 active coordinate changes detected"
                } else {
                    "Delta (${elapsedMs}ms, ${chunks} chunks): Isolated ${isolatedList.size}/$totalChanged paths (top: ${isolatedList.first().path})"
                }
            }
            "ENTITIES_SCANNED" -> {
                val count = json.optInt("entityCount", 0)
                val mode = json.optString("projectionMode", "pinhole")
                val chunks = json.optInt("chunksExecuted", 1)
                _uiState.update {
                    it.copy(
                        lastEntityCount = count,
                        scanStatus = AsyncScanStatus.COMPLETED,
                        scanStatusDetail = "Step 3: $count entities projected"
                    )
                }
                "Entities (${chunks} chunks): $count projected to 2D canvas via $mode"
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

    private fun mergeShallowPaths(existing: List<String>, incoming: List<String>): List<String> {
        if (incoming.isEmpty()) return existing
        val merged = LinkedHashSet<String>(existing.size + incoming.size)
        merged.addAll(existing)
        merged.addAll(incoming)
        return merged.take(MAX_SHALLOW_PATHS)
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
        root.put("scannerArchitecture", "async_chunked_shallow_heuristic_depth_2")
        root.put("lastScanStatus", state.scanStatus.label)
        root.put("lastScanDetail", state.scanStatusDetail)
        root.put("estimatedFps", state.estimatedFps ?: JSONObject.NULL)
        root.put("canvasCount", state.canvasCount)

        val viewportObj = try {
            JSONObject(viewportMetricsJson)
        } catch (_: Exception) {
            JSONObject().put("raw", viewportMetricsJson)
        }
        root.put("viewportMetrics", viewportObj)

        val combinedShallow = mergeShallowPaths(
            state.discoveredShallowPaths,
            extractStringArray(viewportObj.optJSONArray("discoveredShallowPaths"))
        )
        val shallowPathsArray = JSONArray()
        combinedShallow.forEach { path -> shallowPathsArray.put(path) }
        root.put("discoveredShallowPaths", shallowPathsArray)

        val pathsArray = JSONArray()
        state.isolatedPaths.forEach { item ->
            pathsArray.put(
                JSONObject()
                    .put("path", item.path)
                    .put("baseline", item.baselineValue)
                    .put("current", item.currentValue)
                    .put("delta", item.delta)
            )
        }
        root.put("isolatedVariablePaths", pathsArray)

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
        root.put("interceptedAssets", assetsArray)

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
        root.put("rawLogs", logsArray)

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
        private const val MAX_SHALLOW_PATHS = 200

        const val DESKTOP_USER_AGENT =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"

        private val TRACKED_EXTENSIONS = listOf(
            ".wasm", ".json", ".bin", ".glb", ".gltf", ".data", ".pck", ".pak", ".ktx2", ".dds"
        )

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
