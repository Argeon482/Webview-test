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
    data class ShareReport(val uri: Uri, val fileName: String, val mimeType: String = "application/json") : DiagnosticsEffect
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
                        detectedGameNamespaces = emptyList(),
                        step1DurationMs = null,
                        scopeBatchProgress = ScopeBatchProgress(),
                        lastManualQuerySummary = "",
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
                        wasmHeap = WasmHeapTrackerState(),
                        wasmFloatDelta = WasmFloatDeltaState(),
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
        _uiState.update {
            it.copy(
                livePollActive = false,
                wasmHeap = it.wasmHeap.copy(isNeighborPolling = false, isScanning = false),
                wasmFloatDelta = it.wasmFloatDelta.copy(
                    isLiveWatching = false,
                    isSamplingBaseline = false,
                    isScanningDeltas = false
                )
            )
        }
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
                livePollActive = false,
                wasmHeap = it.wasmHeap.copy(isNeighborPolling = false, isScanning = false),
                wasmFloatDelta = it.wasmFloatDelta.copy(
                    isLiveWatching = false,
                    isSamplingBaseline = false,
                    isScanningDeltas = false
                )
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

    fun onBatchProgressChanged(progress: ScopeBatchProgress) {
        _uiState.update { state ->
            val nextStatus = when {
                progress.isPaused -> AsyncScanStatus.PAUSED
                progress.isActive -> AsyncScanStatus.RUNNING
                progress.totalBatches > 0 && progress.currentBatch >= progress.totalBatches -> AsyncScanStatus.COMPLETED
                else -> state.scanStatus
            }
            state.copy(
                scopeBatchProgress = progress,
                scanStatus = nextStatus,
                scanStatusDetail = progress.statusText.ifBlank { state.scanStatusDetail }
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

    fun onStagingSearchQueryChanged(query: String) {
        _uiState.update { it.copy(stagingSearchQuery = query) }
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

    // Step 1: Non-Blocking 100-Item Batched Global Variable Inspection (with Pause / Resume)
    fun runStep1InspectScope() {
        _uiState.update {
            it.copy(
                scanStatus = AsyncScanStatus.RUNNING,
                scanStatusDetail = "Starting batched global scan (100/batch)…",
                scopeBatchProgress = ScopeBatchProgress(
                    isActive = true,
                    isPaused = false,
                    currentBatch = 1,
                    totalBatches = 1,
                    itemStart = 1,
                    itemEnd = 100,
                    totalItems = 100,
                    foundCount = 0
                )
            )
        }
        appendLog(
            LogCategory.SCOPE,
            "Step 1: Starting batched global scan (100 items/batch)…",
            "Processing window keys in staggered async batches of 100 items; custom globals stream live into Staging"
        )
        _effects.tryEmit(DiagnosticsEffect.EvaluateJs(DiagnosticsScripts.buildInspectGlobalScopeScript()))
    }

    fun pauseStep1BatchScan() {
        _uiState.update { state ->
            state.copy(
                scanStatus = AsyncScanStatus.PAUSED,
                scopeBatchProgress = state.scopeBatchProgress.copy(isPaused = true)
            )
        }
        appendLog(LogCategory.SCOPE, "Step 1: Paused batched scan", "You can search/pin staged items and resume anytime.")
        _effects.tryEmit(DiagnosticsEffect.EvaluateJs(DiagnosticsScripts.buildPauseScopeScanScript()))
    }

    fun resumeStep1BatchScan() {
        _uiState.update { state ->
            state.copy(
                scanStatus = AsyncScanStatus.RUNNING,
                scopeBatchProgress = state.scopeBatchProgress.copy(isPaused = false)
            )
        }
        appendLog(LogCategory.SCOPE, "Step 1: Resumed batched scan", "Continuing remaining 100-item batches…")
        _effects.tryEmit(DiagnosticsEffect.EvaluateJs(DiagnosticsScripts.buildResumeScopeScanScript()))
    }

    // Step 1 Manual Property Inspector (fallback for closure-scoped or known custom paths)
    fun onManualPathInputChanged(input: String) {
        _uiState.update { it.copy(manualPropertyPathInput = input) }
    }

    fun onQueryManualPropertyPath(overridePath: String? = null) {
        val targetPath = (overridePath ?: _uiState.value.manualPropertyPathInput).trim()
        if (targetPath.isEmpty()) {
            _effects.tryEmit(DiagnosticsEffect.ShowToast("Enter a path like game.player or window.AppInstance"))
            return
        }
        if (overridePath != null) {
            _uiState.update { it.copy(manualPropertyPathInput = targetPath) }
        }
        onScanStatusChanged(AsyncScanStatus.RUNNING, "Querying path: $targetPath…")
        appendLog(
            LogCategory.SCOPE,
            "Manual Path Query: $targetPath",
            "Inspecting property path directly on window and staging if resolved"
        )
        _effects.tryEmit(DiagnosticsEffect.EvaluateJs(DiagnosticsScripts.buildQueryPropertyPathScript(targetPath)))
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

    // Targeted WebAssembly Heap Value-Tracker (Read-Only HP / Stat Scanner & ±16B Neighbor Inspector)
    fun onWasmTargetValueChanged(input: String) {
        val sanitized = input.filter { it.isDigit() || it == '.' || it == '-' }.take(18)
        _uiState.update { state ->
            state.copy(wasmHeap = state.wasmHeap.copy(targetValueInput = sanitized))
        }
    }

    fun runWasmHeapInitialScan() {
        val raw = _uiState.value.wasmHeap.targetValueInput.trim()
        val parsed = raw.toDoubleOrNull()
        if (parsed == null || !parsed.isFinite()) {
            val msg = "Enter a valid current HP / stat number (e.g. 4338) first."
            appendLog(LogCategory.WASM, msg, raw)
            _effects.tryEmit(DiagnosticsEffect.ShowToast(msg))
            return
        }
        _uiState.update { state ->
            state.copy(
                scanStatus = AsyncScanStatus.RUNNING,
                scanStatusDetail = "Scanning HEAP32 / HEAPF32 for $raw…",
                wasmHeap = state.wasmHeap.copy(
                    isScanning = true,
                    initialSearchValue = parsed,
                    lastFilterValue = null,
                    filterRound = 0,
                    isolatedHeapType = null,
                    isolatedIndex = null,
                    isolatedByteAddress = null,
                    isolatedCurrentValue = "",
                    isNeighborPolling = false,
                    neighborPollTickCount = 0,
                    neighborFloats = emptyList(),
                    statusMessage = "Scanning HEAP32 / HEAPF32 in non-blocking 1 MB chunks for $raw…"
                )
            )
        }
        appendLog(
            LogCategory.WASM,
            "WASM Heap Scan: Searching HEAP32 & HEAPF32 for $raw…",
            "Target value: $parsed"
        )
        _effects.tryEmit(DiagnosticsEffect.EvaluateJs(WasmHeapScripts.buildInitialScanScript(parsed)))
    }

    fun runWasmHeapFilterDelta() {
        val raw = _uiState.value.wasmHeap.targetValueInput.trim()
        val parsed = raw.toDoubleOrNull()
        if (parsed == null || !parsed.isFinite()) {
            val msg = "Enter your updated HP value after taking damage or healing, then tap Filter."
            appendLog(LogCategory.WASM, msg, raw)
            _effects.tryEmit(DiagnosticsEffect.ShowToast(msg))
            return
        }
        if (_uiState.value.wasmHeap.totalMatches <= 0) {
            val msg = "Run '1. Scan HEAP32 / F32' with your initial HP value first."
            appendLog(LogCategory.WASM, msg, msg)
            _effects.tryEmit(DiagnosticsEffect.ShowToast(msg))
            return
        }
        _uiState.update { state ->
            state.copy(
                scanStatus = AsyncScanStatus.RUNNING,
                scanStatusDetail = "Filtering WASM matches for new value $raw…",
                wasmHeap = state.wasmHeap.copy(
                    isScanning = true,
                    lastFilterValue = parsed,
                    statusMessage = "Filtering ${_uiState.value.wasmHeap.totalMatches} stored addresses for new value $raw…"
                )
            )
        }
        appendLog(
            LogCategory.WASM,
            "WASM Value-Delta Filter: Narrowing matches to $raw…",
            "Updated target value: $parsed"
        )
        _effects.tryEmit(DiagnosticsEffect.EvaluateJs(WasmHeapScripts.buildFilterDeltaScript(parsed)))
    }

    fun onInspectWasmCandidate(heapType: String, index: Int) {
        val hexAddr = formatHexAddress(index.toLong() * 4L)
        _uiState.update { state ->
            state.copy(
                wasmHeap = state.wasmHeap.copy(
                    isolatedHeapType = heapType,
                    isolatedIndex = index,
                    isolatedByteAddress = index.toLong() * 4L,
                    isNeighborPolling = true,
                    neighborPollTickCount = 0,
                    neighborFloats = emptyList(),
                    statusMessage = "Polling ±16B around $heapType[$index] ($hexAddr) every 200ms…"
                )
            )
        }
        appendLog(
            LogCategory.WASM,
            "Locked WASM Neighbor Inspector on $heapType[$index] ($hexAddr)",
            "Reading ±4 to ±16 byte floats every 200ms"
        )
        _effects.tryEmit(DiagnosticsEffect.EvaluateJs(WasmHeapScripts.buildInspectNeighborsScript(heapType, index)))
    }

    fun onStopWasmNeighborPolling() {
        _uiState.update { state ->
            state.copy(
                wasmHeap = state.wasmHeap.copy(
                    isNeighborPolling = false,
                    statusMessage = "Paused neighbor polling (memory map still ready to export)."
                )
            )
        }
        _effects.tryEmit(DiagnosticsEffect.EvaluateJs(WasmHeapScripts.buildStopNeighborPollScript()))
    }

    fun onResetWasmHeapTracker() {
        _uiState.update { state ->
            state.copy(wasmHeap = WasmHeapTrackerState())
        }
        appendLog(LogCategory.WASM, "Reset WASM Heap Value-Tracker", "Cleared stored candidate indices and stopped neighbor polling")
        _effects.tryEmit(DiagnosticsEffect.EvaluateJs(WasmHeapScripts.buildResetTrackerScript()))
    }

    fun onWasmNeighborsUpdatedFromBridge(jsonPayload: String) {
        val json = try {
            JSONObject(jsonPayload)
        } catch (_: Exception) {
            return
        }
        if (json.optString("stage", "") != "WASM_NEIGHBORS_TICK") return

        val heapType = json.optString("heapType", "HEAP32")
        val isolatedIndex = json.optInt("isolatedIndex", -1)
        if (isolatedIndex < 0) return
        val isolatedByteAddress = json.optLong("isolatedByteAddress", isolatedIndex.toLong() * 4L)
        val currentVal = json.optString("currentValue", "")
        val tick = json.optInt("tick", 1)
        val sourceLabel = json.optString("heapSourceLabel", "")
        val byteLen = json.optLong("heapByteLength", 0L)
        val neighborsArr = json.optJSONArray("neighbors") ?: JSONArray()

        _uiState.update { state ->
            val prevWasm = state.wasmHeap
            val isSameAnchor = prevWasm.isolatedIndex == isolatedIndex && prevWasm.isolatedHeapType == heapType
            val prevByOffset = if (isSameAnchor) {
                prevWasm.neighborFloats.associateBy { it.byteOffset }
            } else {
                emptyMap()
            }

            val updatedNeighbors = buildList {
                for (i in 0 until neighborsArr.length()) {
                    val obj = neighborsArr.optJSONObject(i) ?: continue
                    val offset = obj.optInt("byteOffset", 0)
                    val idx = obj.optInt("heapIndex", isolatedIndex + (offset / 4))
                    val addr = obj.optLong("byteAddress", idx.toLong() * 4L)
                    val fv = obj.optDouble("floatValue", 0.0)
                    val iv = obj.optInt("intValue", 0)
                    val prev = prevByOffset[offset]
                    val changed = prev != null && (
                        kotlin.math.abs(fv - prev.floatValue) > 0.0001 || iv != prev.intValue
                    )
                    val nextCount = when {
                        prev == null -> 0
                        changed -> prev.changeCount + 1
                        else -> prev.changeCount
                    }
                    add(
                        WasmNeighborFloat(
                            byteOffset = offset,
                            heapIndex = idx,
                            byteAddress = addr,
                            floatValue = fv,
                            intValue = iv,
                            changedFromPrev = changed,
                            changeCount = nextCount
                        )
                    )
                }
            }

            state.copy(
                wasmHeap = prevWasm.copy(
                    heapSourceLabel = sourceLabel.ifBlank { prevWasm.heapSourceLabel },
                    heapByteLength = if (byteLen > 0L) byteLen else prevWasm.heapByteLength,
                    isolatedHeapType = heapType,
                    isolatedIndex = isolatedIndex,
                    isolatedByteAddress = isolatedByteAddress,
                    isolatedCurrentValue = currentVal,
                    isNeighborPolling = true,
                    neighborPollTickCount = tick,
                    neighborFloats = updatedNeighbors
                )
            )
        }
    }

    fun exportWasmMemoryMapJson(context: Context) {
        val snapshot = _uiState.value
        if (snapshot.wasmHeap.isExportingMemoryMap) return
        if (snapshot.wasmHeap.isolatedIndex == null && snapshot.wasmHeap.totalMatches == 0) {
            _effects.tryEmit(DiagnosticsEffect.ShowToast("Scan and isolate a WASM memory address first."))
            return
        }
        val appContext = context.applicationContext
        _uiState.update { state ->
            state.copy(wasmHeap = state.wasmHeap.copy(isExportingMemoryMap = true))
        }

        viewModelScope.launch {
            val mapJson = withContext(Dispatchers.Default) {
                buildWasmMemoryMapJson(snapshot)
            }
            val outcome = withContext(Dispatchers.IO) {
                ReportExporter.exportJsonToDownloads(
                    context = appContext,
                    jsonContent = mapJson,
                    filePrefix = "wasm_memory_map"
                )
            }
            _uiState.update { state ->
                state.copy(
                    wasmHeap = state.wasmHeap.copy(
                        isExportingMemoryMap = false,
                        lastMemoryMapExport = outcome
                    )
                )
            }
            if (outcome.success) {
                appendLog(
                    LogCategory.WASM,
                    "Exported WASM Memory Map -> ${outcome.displayPath}",
                    "Saved ${mapJson.length} bytes (${snapshot.wasmHeap.isolatedHeapType ?: "HEAP"}[${snapshot.wasmHeap.isolatedIndex ?: -1}]) to ${outcome.displayPath}"
                )
                _effects.tryEmit(
                    DiagnosticsEffect.ShowToast(
                        appContext.getString(R.string.toast_wasm_map_saved, outcome.fileName)
                    )
                )
            } else {
                val err = outcome.errorMessage ?: "Unknown error"
                appendLog(LogCategory.ERROR, "WASM Memory Map export failed", err)
                _effects.tryEmit(
                    DiagnosticsEffect.ShowToast(
                        appContext.getString(R.string.toast_report_failed, err)
                    )
                )
            }
        }
    }

    fun onShareLastWasmMemoryMap() {
        val export = _uiState.value.wasmHeap.lastMemoryMapExport
        if (export != null && export.success && export.contentUri != null) {
            _effects.tryEmit(DiagnosticsEffect.ShareReport(export.contentUri, export.fileName, export.mimeType))
        }
    }

    // Step 2A / 2B (Float Mode) — WebAssembly Float Heap Delta Scanner targeting window.HEAPF32
    fun onSelectStep2ScanMode(mode: Step2ScanMode) {
        _uiState.update { it.copy(step2ScanMode = mode) }
    }

    fun runWasmFloatBaselineSnapshot() {
        if (_uiState.value.wasmFloatDelta.isSamplingBaseline) return
        _uiState.update { state ->
            state.copy(
                scanStatus = AsyncScanStatus.RUNNING,
                scanStatusDetail = "Step 2A (Float): Sampling window.HEAPF32 baseline…",
                wasmFloatDelta = state.wasmFloatDelta.copy(
                    isSamplingBaseline = true,
                    isScanningDeltas = false,
                    baselineCaptured = false,
                    totalDeltaMatches = 0,
                    scanRound = 0,
                    isLiveWatching = false,
                    liveWatchTickCount = 0,
                    statusMessage = "Sampling window.HEAPF32 in non-blocking 1 MB slices & pruning standing jitter…"
                )
            )
        }
        appendLog(
            LogCategory.WASM,
            "Step 2A (Float Mode): Capturing window.HEAPF32 standing baseline…",
            "Skipping low guard/out-of-bounds slots and pruning ticking timers while standing still"
        )
        _effects.tryEmit(DiagnosticsEffect.EvaluateJs(WasmHeapScripts.buildFloatBaselineSnapshotScript()))
    }

    fun runWasmFloatDeltaScan() {
        val fd = _uiState.value.wasmFloatDelta
        if (fd.isSamplingBaseline || fd.isScanningDeltas) return
        if (!fd.baselineCaptured) {
            val msg = "Stand still and tap 2A. Capture Float Baseline first."
            appendLog(LogCategory.WASM, msg, msg)
            _effects.tryEmit(DiagnosticsEffect.ShowToast(msg))
            return
        }
        _uiState.update { state ->
            state.copy(
                scanStatus = AsyncScanStatus.RUNNING,
                scanStatusDetail = "Step 2B (Float): Scanning HEAPF32 movement deltas…",
                wasmFloatDelta = state.wasmFloatDelta.copy(
                    isScanningDeltas = true,
                    statusMessage = "Comparing live window.HEAPF32 against baseline for positional deltas…"
                )
            )
        }
        appendLog(
            LogCategory.WASM,
            "Step 2B (Float Mode): Scanning HEAPF32 movement deltas (round #${fd.scanRound + 1})…",
            "Isolating floats that changed by a positional delta range (0.05..250.0) and clustering X/Y/Z neighbors"
        )
        _effects.tryEmit(DiagnosticsEffect.EvaluateJs(WasmHeapScripts.buildFloatDeltaScanScript()))
    }

    fun onTogglePinWasmFloatCandidate(heapIndex: Int) {
        val nowTs = formatTimestamp(System.currentTimeMillis())
        var nowPinned = false
        var updatedPinnedSet: Set<Int> = emptySet()

        _uiState.update { state ->
            val currentPinned = state.wasmFloatDelta.pinnedIndices
            val nextPinned = if (currentPinned.contains(heapIndex)) {
                nowPinned = false
                currentPinned - heapIndex
            } else {
                nowPinned = true
                currentPinned + heapIndex
            }
            updatedPinnedSet = nextPinned

            val candidate = state.wasmFloatDelta.candidates.firstOrNull { it.heapIndex == heapIndex }
            val updatedCandidates = state.wasmFloatDelta.candidates.map { item ->
                if (item.heapIndex == heapIndex) item.copy(isPinned = nowPinned) else item
            }

            val path = "window.HEAPF32[$heapIndex]"
            val hex = formatHexAddress(heapIndex.toLong() * 4L)
            val detail = if (candidate != null) {
                val hint = if (candidate.axisHint.isNotBlank()) "${candidate.axisHint} • " else ""
                "${hint}$hex • val=${String.format(Locale.US, "%.4f", candidate.currentValue)} (Δ ${String.format(Locale.US, "%+.3f", candidate.delta)})"
            } else {
                "$hex • Pinned WASM float"
            }

            val mergedStaging = if (nowPinned) {
                mergeIntoStagingBuffer(
                    existing = state.stagingBuffer,
                    incoming = listOf(path to detail),
                    sourceStep = "Float Delta",
                    scanNumber = state.scanCounter.coerceAtLeast(1),
                    timestamp = nowTs,
                    forcePin = true
                )
            } else {
                state.stagingBuffer.map { item ->
                    if (item.path == path) item.copy(isPinned = false) else item
                }
            }

            state.copy(
                stagingBuffer = sortStagingBuffer(mergedStaging),
                wasmFloatDelta = state.wasmFloatDelta.copy(
                    pinnedIndices = nextPinned,
                    candidates = updatedCandidates
                )
            )
        }

        _effects.tryEmit(DiagnosticsEffect.EvaluateJs(WasmHeapScripts.buildSyncPinnedFloatsScript(updatedPinnedSet)))
        val toastMsg = if (nowPinned) "Pinned HEAPF32[$heapIndex] to Live Watcher & Staging" else "Unpinned HEAPF32[$heapIndex]"
        _effects.tryEmit(DiagnosticsEffect.ShowToast(toastMsg))
    }

    fun onPinTopWasmFloatTriplet() {
        val topThree = _uiState.value.wasmFloatDelta.candidates.take(3)
        if (topThree.isEmpty()) return
        val nowTs = formatTimestamp(System.currentTimeMillis())
        var updatedPinnedSet: Set<Int> = emptySet()

        _uiState.update { state ->
            val topIndices = topThree.map { it.heapIndex }.toSet()
            val nextPinned = state.wasmFloatDelta.pinnedIndices + topIndices
            updatedPinnedSet = nextPinned

            val incoming = topThree.mapIndexed { idx, c ->
                val axis = c.axisHint.ifBlank { listOf("X", "Y", "Z").getOrElse(idx) { "F" } }
                val hex = formatHexAddress(c.byteAddress)
                "window.HEAPF32[${c.heapIndex}]" to "[$axis] $hex • val=${String.format(Locale.US, "%.4f", c.currentValue)}"
            }
            val mergedStaging = mergeIntoStagingBuffer(
                existing = state.stagingBuffer,
                incoming = incoming,
                sourceStep = "Float Delta",
                scanNumber = state.scanCounter.coerceAtLeast(1),
                timestamp = nowTs,
                forcePin = true
            )
            val updatedCandidates = state.wasmFloatDelta.candidates.map { item ->
                if (nextPinned.contains(item.heapIndex)) item.copy(isPinned = true) else item
            }
            state.copy(
                stagingBuffer = mergedStaging,
                wasmFloatDelta = state.wasmFloatDelta.copy(
                    pinnedIndices = nextPinned,
                    candidates = updatedCandidates
                )
            )
        }
        _effects.tryEmit(DiagnosticsEffect.EvaluateJs(WasmHeapScripts.buildSyncPinnedFloatsScript(updatedPinnedSet)))
        _effects.tryEmit(DiagnosticsEffect.ShowToast("Pinned top ${topThree.size} X/Y/Z float offsets to Staging"))
    }

    fun onToggleFloatWatcherExpanded() {
        _uiState.update { state ->
            state.copy(
                wasmFloatDelta = state.wasmFloatDelta.copy(
                    isWatcherExpanded = !state.wasmFloatDelta.isWatcherExpanded
                )
            )
        }
    }

    fun onStopWasmFloatWatch() {
        _uiState.update { state ->
            state.copy(
                wasmFloatDelta = state.wasmFloatDelta.copy(
                    isLiveWatching = false,
                    statusMessage = "Paused Live Float Watcher (pinned offsets preserved)."
                )
            )
        }
        _effects.tryEmit(DiagnosticsEffect.EvaluateJs(WasmHeapScripts.buildStopFloatWatchScript()))
    }

    fun onResetWasmFloatDelta() {
        _uiState.update { state ->
            state.copy(wasmFloatDelta = WasmFloatDeltaState())
        }
        appendLog(LogCategory.WASM, "Reset WASM Float Heap Delta Scanner", "Released HEAPF32 baseline snapshot and stopped Live Float Watcher")
        _effects.tryEmit(DiagnosticsEffect.EvaluateJs(WasmHeapScripts.buildResetFloatDeltaScript()))
    }

    fun onInspectWasmFloatNeighbors(heapIndex: Int) {
        _uiState.update { it.copy(activeConsoleTab = ConsoleTab.WASM_HEAP) }
        onInspectWasmCandidate("HEAPF32", heapIndex)
    }

    fun onWasmFloatCandidatesUpdatedFromBridge(jsonPayload: String) {
        val json = try {
            JSONObject(jsonPayload)
        } catch (_: Exception) {
            return
        }
        if (json.optString("stage", "") != "WASM_FLOAT_WATCH_TICK") return

        val tick = json.optInt("tick", 1)
        val sourceLabel = json.optString("heapSourceLabel", "")
        val byteLen = json.optLong("heapByteLength", 0L)
        val arr = json.optJSONArray("candidates") ?: JSONArray()

        _uiState.update { state ->
            val prevState = state.wasmFloatDelta
            val prevByIdx = prevState.candidates.associateBy { it.heapIndex }
            val pinnedSet = prevState.pinnedIndices

            val parsed = buildList {
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val idx = obj.optInt("heapIndex", -1)
                    if (idx < 0) continue
                    val byteAddr = obj.optLong("byteAddress", idx.toLong() * 4L)
                    val baseVal = obj.optDouble("baselineValue", 0.0)
                    val curVal = obj.optDouble("currentValue", 0.0)
                    val deltaVal = obj.optDouble("delta", curVal - baseVal)
                    val cluster = obj.optInt("clusterSize", 1)
                    val axis = obj.optString("axisHint", "")
                    val isPinned = pinnedSet.contains(idx) || obj.optBoolean("isPinned", false)
                    val prev = prevByIdx[idx]
                    val changed = prev != null && kotlin.math.abs(curVal - prev.currentValue) > 0.0005
                    val nextCount = when {
                        prev == null -> 0
                        changed -> prev.changeCount + 1
                        else -> prev.changeCount
                    }
                    add(
                        WasmFloatDeltaCandidate(
                            heapIndex = idx,
                            byteAddress = byteAddr,
                            baselineValue = baseVal,
                            currentValue = curVal,
                            delta = deltaVal,
                            clusterSize = cluster,
                            axisHint = axis,
                            isPinned = isPinned,
                            changedFromPrev = changed,
                            changeCount = nextCount
                        )
                    )
                }
            }

            state.copy(
                wasmFloatDelta = prevState.copy(
                    heapSourceLabel = sourceLabel.ifBlank { prevState.heapSourceLabel },
                    heapByteLength = if (byteLen > 0L) byteLen else prevState.heapByteLength,
                    isLiveWatching = parsed.isNotEmpty(),
                    liveWatchTickCount = tick,
                    candidates = parsed
                )
            )
        }
    }

    // Passive Resource & Asset Request Monitor (called from WebViewClient.shouldInterceptRequest)
    // Never modifies or blocks WebView traffic; asynchronously mirrors .bin/.wasm/.json to filesDir for ByteBuffer audit.
    fun onResourceRequestObserved(context: Context, request: WebResourceRequest) {
        val uri = request.url ?: return
        val fullUrl = uri.toString()
        val pathLower = (uri.path ?: fullUrl).lowercase(Locale.US)
        val matchedExt = matchTrackedAssetExtension(pathLower) ?: return
        val fileName = BinaryAssetInspector.extractFileName(fullUrl, matchedExt)
        val shouldCache = BinaryAssetInspector.shouldCaptureLocally(matchedExt)
        val appContext = context.applicationContext

        val initialRecord = InterceptedAsset(
            url = fullUrl,
            extension = matchedExt,
            method = request.method ?: "GET",
            timestamp = formatTimestamp(System.currentTimeMillis()),
            isForMainFrame = request.isForMainFrame,
            fileName = fileName,
            captureStatus = if (shouldCache) AssetCaptureStatus.CACHING else AssetCaptureStatus.OBSERVED,
            structuralSummary = if (shouldCache) "Mirroring to filesDir for ByteBuffer audit…" else "Observed network request"
        )

        viewModelScope.launch(Dispatchers.Main.immediate) {
            if (!seenAssetUrls.add(fullUrl)) return@launch
            if (seenAssetUrls.size > MAX_TRACKED_ASSETS) {
                val oldest = seenAssetUrls.firstOrNull()
                if (oldest != null) seenAssetUrls.remove(oldest)
            }
            _uiState.update { state ->
                val updated = (state.interceptedAssets + initialRecord).takeLast(MAX_TRACKED_ASSETS)
                state.copy(interceptedAssets = updated)
            }
            appendLog(
                category = LogCategory.ASSET,
                summary = "Intercepted [$matchedExt] $fileName",
                payload = "${initialRecord.method} $fullUrl"
            )

            if (shouldCache) {
                val activeReferer = _uiState.value.activeUrl
                val userAgent = if (_uiState.value.isDesktopMode) DESKTOP_USER_AGENT else "Mozilla/5.0 (Linux; Android)"
                val audited = withContext(Dispatchers.IO) {
                    BinaryAssetInspector.fetchSaveAndInspect(
                        context = appContext,
                        asset = initialRecord,
                        userAgent = userAgent,
                        refererUrl = activeReferer
                    )
                }
                _uiState.update { state ->
                    val updatedList = state.interceptedAssets.map { item ->
                        if (item.url == fullUrl) audited else item
                    }
                    state.copy(interceptedAssets = updatedList)
                }
                if (audited.captureStatus == AssetCaptureStatus.CAPTURED) {
                    appendLog(
                        category = LogCategory.ASSET,
                        summary = "Audited [$matchedExt] $fileName (${BinaryAssetInspector.formatBytes(audited.byteSize)}) • ${audited.magicSignature}",
                        payload = buildString {
                            appendLine("URL: ${audited.url}")
                            appendLine("Local: filesDir/${audited.localRelativePath}")
                            appendLine("SHA-256: ${audited.sha256Prefix}…")
                            appendLine(audited.structuralDetails)
                        }.trimEnd()
                    )
                }
            }
        }
    }

    fun onClearAssetCache(context: Context) {
        val appContext = context.applicationContext
        viewModelScope.launch {
            val removedCount = withContext(Dispatchers.IO) {
                BinaryAssetInspector.clearLocalCache(appContext)
            }
            seenAssetUrls.clear()
            _uiState.update { it.copy(interceptedAssets = emptyList()) }
            appendLog(
                LogCategory.ASSET,
                "Cleared local binary asset cache ($removedCount files removed)",
                "Cleared filesDir/captured_assets"
            )
            _effects.tryEmit(DiagnosticsEffect.ShowToast("Cleared $removedCount cached asset files"))
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
            _effects.tryEmit(DiagnosticsEffect.ShareReport(export.contentUri, export.fileName, export.mimeType))
        }
    }

    // Complete Log Dump Export (.txt or .json) from Logs tab
    fun exportFullLogDump(context: Context, asJson: Boolean = false) {
        if (_uiState.value.isExportingLogDump) return
        val appContext = context.applicationContext
        val snapshot = _uiState.value
        _uiState.update { it.copy(isExportingLogDump = true) }

        viewModelScope.launch {
            val outcome = withContext(Dispatchers.IO) {
                if (asJson) {
                    val fallbackViewport = JSONObject()
                        .put("url", snapshot.activeUrl)
                        .put("width", snapshot.entityViewportWidth)
                        .put("height", snapshot.entityViewportHeight)
                        .toString()
                    val jsonContent = buildFullReportJson(snapshot, fallbackViewport)
                    ReportExporter.exportJsonToDownloads(
                        context = appContext,
                        jsonContent = jsonContent,
                        filePrefix = "webview_log_dump"
                    )
                } else {
                    val textDump = buildFullLogDumpText(snapshot)
                    ReportExporter.exportTextLogDumpToDownloads(appContext, textDump)
                }
            }

            _uiState.update {
                it.copy(
                    isExportingLogDump = false,
                    lastLogDumpExport = outcome
                )
            }

            if (outcome.success) {
                appendLog(
                    LogCategory.SYSTEM,
                    "Exported full log dump -> ${outcome.displayPath}",
                    "Saved ${snapshot.logs.size} logs, ${snapshot.stagingBuffer.size} staged variables, ${snapshot.discoveredEntities.size} entities, and ${snapshot.interceptedAssets.size} asset requests to ${outcome.displayPath}"
                )
                _effects.tryEmit(
                    DiagnosticsEffect.ShowToast(
                        appContext.getString(R.string.toast_log_dump_saved, outcome.fileName)
                    )
                )
            } else {
                val err = outcome.errorMessage ?: "Unknown error"
                appendLog(LogCategory.ERROR, "Log dump export failed", err)
                _effects.tryEmit(
                    DiagnosticsEffect.ShowToast(
                        appContext.getString(R.string.toast_report_failed, err)
                    )
                )
            }
        }
    }

    fun onShareLastLogDump() {
        val export = _uiState.value.lastLogDumpExport
        if (export != null && export.success && export.contentUri != null) {
            _effects.tryEmit(DiagnosticsEffect.ShareReport(export.contentUri, export.fileName, export.mimeType))
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
            "GAME_NAMESPACE_DETECTED" -> {
                val nsArray = json.optJSONArray("detectedNamespaces") ?: JSONArray()
                val detectedNs = extractStringArray(nsArray)
                val entriesArr = json.optJSONArray("detectedEntries") ?: JSONArray()
                val rootKeys = mutableListOf<String>()
                val stagingPairs = mutableListOf<Pair<String, String>>()

                for (i in 0 until entriesArr.length()) {
                    val obj = entriesArr.optJSONObject(i) ?: continue
                    val key = obj.optString("key", "").trim()
                    val path = obj.optString("path", if (key.isNotEmpty()) "window.$key" else "").trim()
                    if (path.isEmpty()) continue
                    if (key.isNotEmpty() && !key.contains(".")) {
                        rootKeys.add(key)
                    }
                    val ctor = obj.optString("constructor", obj.optString("type", "Object"))
                    val keyCount = obj.optInt("keyCount", 0)
                    val previewList = extractStringArray(obj.optJSONArray("previewKeys")).take(4)
                    val previewSuffix = if (previewList.isNotEmpty()) " • ${previewList.joinToString(", ")}" else ""
                    stagingPairs.add(path to "[Auto-Root] $ctor ($keyCount keys$previewSuffix)")
                }

                _uiState.update { state ->
                    val currentScan = state.scanCounter.coerceAtLeast(1)
                    val mergedStaging = mergeIntoStagingBuffer(
                        existing = state.stagingBuffer,
                        incoming = stagingPairs,
                        sourceStep = "Auto-Root",
                        scanNumber = currentScan,
                        timestamp = nowTs
                    )
                    state.copy(
                        customGlobals = (detectedNs.map { it.removePrefix("window.") } + rootKeys + state.customGlobals).distinct(),
                        detectedGameNamespaces = (state.detectedGameNamespaces + detectedNs).distinct(),
                        stagingBuffer = mergedStaging
                    )
                }
                "Auto-detected ${detectedNs.size} game namespace(s): ${detectedNs.joinToString(", ")} • Staged immediately"
            }

            "MANUAL_PATH_QUERY" -> {
                val found = json.optBoolean("found", false)
                val normalizedPath = json.optString("normalizedPath", json.optString("requestedPath", "")).trim()
                if (!found) {
                    val msg = json.optString("message", "Property path not found on window.")
                    _uiState.update {
                        it.copy(
                            scanStatus = AsyncScanStatus.IDLE,
                            scanStatusDetail = "Not found: $normalizedPath",
                            lastManualQuerySummary = "Not found: $msg"
                        )
                    }
                    _effects.tryEmit(DiagnosticsEffect.ShowToast("Not found: $normalizedPath"))
                    "Manual Query failed: $msg"
                } else {
                    val rootKey = json.optString("rootKey", "").trim()
                    val detail = json.optString("detail", json.optString("type", "Object"))
                    val childArr = json.optJSONArray("childEntries") ?: JSONArray()
                    val childPairs = buildList {
                        for (i in 0 until childArr.length()) {
                            val obj = childArr.optJSONObject(i) ?: continue
                            val cp = obj.optString("path", "").trim()
                            val cd = obj.optString("detail", "Object")
                            if (cp.isNotEmpty()) add(cp to cd)
                        }
                    }

                    _uiState.update { state ->
                        val nextScan = state.scanCounter.coerceAtLeast(1)
                        val withMain = mergeIntoStagingBuffer(
                            existing = state.stagingBuffer,
                            incoming = listOf(normalizedPath to detail),
                            sourceStep = "Manual Query",
                            scanNumber = nextScan,
                            timestamp = nowTs,
                            forcePin = true
                        )
                        val withChildren = if (childPairs.isNotEmpty()) {
                            mergeIntoStagingBuffer(
                                existing = withMain,
                                incoming = childPairs,
                                sourceStep = "Manual Query",
                                scanNumber = nextScan,
                                timestamp = nowTs,
                                forcePin = false
                            )
                        } else withMain

                        state.copy(
                            step1Completed = true,
                            customGlobals = if (rootKey.isNotEmpty()) {
                                (listOf(rootKey) + state.customGlobals).distinct()
                            } else state.customGlobals,
                            stagingBuffer = withChildren,
                            lastManualQuerySummary = "Staged & pinned $normalizedPath → $detail",
                            scanStatus = AsyncScanStatus.COMPLETED,
                            scanStatusDetail = "Staged $normalizedPath"
                        )
                    }
                    _effects.tryEmit(DiagnosticsEffect.ShowToast("Staged & pinned: $normalizedPath"))
                    "Manual Query staged $normalizedPath ($detail)" +
                        if (childPairs.isNotEmpty()) " + ${childPairs.size} child hints" else ""
                }
            }

            "GLOBAL_SCOPE_BATCH" -> {
                val batchIndex = json.optInt("batchIndex", 1)
                val totalBatches = json.optInt("totalBatches", 1)
                val itemStart = json.optInt("itemStart", 1)
                val itemEnd = json.optInt("itemEnd", 100)
                val totalItems = json.optInt("totalItems", itemEnd)
                val foundCount = json.optInt("foundCount", 0)
                val batchArray = json.optJSONArray("batchCustomGlobals") ?: JSONArray()
                val batchKeys = mutableListOf<String>()
                val stagingPairs = mutableListOf<Pair<String, String>>()

                for (i in 0 until batchArray.length()) {
                    val obj = batchArray.optJSONObject(i) ?: continue
                    val key = obj.optString("key", "").trim()
                    if (key.isEmpty()) continue
                    batchKeys.add(key)
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
                    val currentScan = state.scanCounter.coerceAtLeast(1)
                    val mergedStaging = mergeIntoStagingBuffer(
                        existing = state.stagingBuffer,
                        incoming = stagingPairs,
                        sourceStep = "Step 1 B$batchIndex",
                        scanNumber = currentScan,
                        timestamp = nowTs
                    )
                    state.copy(
                        customGlobals = (state.customGlobals + batchKeys).distinct(),
                        stagingBuffer = mergedStaging,
                        scopeBatchProgress = ScopeBatchProgress(
                            isActive = batchIndex < totalBatches,
                            isPaused = false,
                            currentBatch = batchIndex,
                            totalBatches = totalBatches,
                            itemStart = itemStart,
                            itemEnd = itemEnd,
                            totalItems = totalItems,
                            foundCount = foundCount
                        )
                    )
                }
                "Batch $batchIndex/$totalBatches ($itemStart–$itemEnd): +${batchKeys.size} custom globals staged (${batchKeys.take(4).joinToString(", ")})"
            }

            "GLOBAL_SCOPE_INSPECTED" -> {
                val canvases = json.optInt("canvasCount", 0)
                val durationMs = json.optDouble("durationMs", 0.0)
                val batchesExecuted = json.optInt("batchesExecuted", 1)
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

                val detectedNs = extractStringArray(json.optJSONArray("detectedNamespaces"))
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
                        detectedGameNamespaces = (state.detectedGameNamespaces + detectedNs).distinct(),
                        step1DurationMs = durationMs,
                        canvasCount = canvases,
                        stagingBuffer = mergedStaging,
                        scopeBatchProgress = ScopeBatchProgress(
                            isActive = false,
                            isPaused = false,
                            currentBatch = batchesExecuted,
                            totalBatches = batchesExecuted,
                            itemStart = 1,
                            itemEnd = totalFiltered,
                            totalItems = totalFiltered,
                            foundCount = globalKeys.size
                        ),
                        scanStatus = AsyncScanStatus.COMPLETED,
                        scanStatusDetail = "Scan #$nextScan: ${globalKeys.size} globals ($batchesExecuted/$batchesExecuted batches)"
                    )
                }
                "Step 1 ($batchesExecuted/$batchesExecuted batches, ${durationMs}ms): Found ${globalKeys.size} custom globals across $totalFiltered keys • Staged"
            }

            "BASELINE_CAPTURED" -> {
                val metrics = json.optInt("metricCount", 0)
                val roots = json.optInt("rootObjectCount", 0)
                val durationMs = json.optDouble("durationMs", -1.0)
                val skippedBinary = json.optInt("skippedBinaryOrWasm", 0)
                val containersArr = json.optJSONArray("candidateContainers") ?: JSONArray()
                val stagingPairs = buildList {
                    for (i in 0 until containersArr.length()) {
                        val obj = containersArr.optJSONObject(i) ?: continue
                        val p = obj.optString("path", "").trim()
                        val d = obj.optString("detail", "Coordinate container")
                        if (p.isNotEmpty()) add(p to d)
                    }
                }
                val timingLabel = if (durationMs >= 0.0) "${String.format(Locale.US, "%.2f", durationMs)}ms" else "fast"

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
                        scanStatusDetail = "2A Captured ($metrics nums in $timingLabel)"
                    )
                }
                "Step 2A ($timingLabel): Captured $metrics primitive numbers across $roots globals (skipped $skippedBinary WASM/buffer objects)"
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

            "WASM_HEAP_SCAN_COMPLETE" -> {
                val totalMatches = json.optInt("totalMatches", 0)
                val count32 = json.optInt("heap32MatchCount", 0)
                val countF32 = json.optInt("heapF32MatchCount", 0)
                val durationMs = json.optDouble("durationMs", 0.0)
                val sourceLabel = json.optString("heapSourceLabel", "window.HEAP32 / HEAPF32")
                val byteLength = json.optLong("heapByteLength", 0L)
                val targetVal = json.optDouble("targetValue", 0.0)
                val samples = parseWasmCandidates(json.optJSONArray("samples"))
                val autoObj = json.optJSONObject("autoIsolated")
                val autoType = autoObj?.optString("heapType", "")?.takeIf { it.isNotBlank() }
                val autoIdx = autoObj?.optInt("index", -1)?.takeIf { it >= 0 }
                val autoByteAddr = autoObj?.optLong("byteAddress", (autoIdx ?: 0).toLong() * 4L)
                val autoVal = autoObj?.optString("currentValue", "").orEmpty()

                val statusMsg = when {
                    totalMatches == 0 ->
                        "0 matches for $targetVal in $sourceLabel. Double-check your exact stat value."
                    autoIdx != null && autoType != null ->
                        "Exact match isolated: $autoType[$autoIdx] (${formatHexAddress(autoByteAddr ?: 0L)})! Streaming ±16B neighbors."
                    else ->
                        "Found $totalMatches matches (HEAP32: $count32, HEAPF32: $countF32) in ${durationMs}ms. Take damage/heal, enter new HP, and tap Filter."
                }

                _uiState.update { state ->
                    state.copy(
                        scanStatus = if (totalMatches > 0) AsyncScanStatus.COMPLETED else AsyncScanStatus.IDLE,
                        scanStatusDetail = "WASM Scan: $totalMatches matches (${durationMs}ms)",
                        wasmHeap = state.wasmHeap.copy(
                            isScanning = false,
                            heapSourceLabel = sourceLabel,
                            heapByteLength = byteLength,
                            initialSearchValue = targetVal,
                            totalMatches = totalMatches,
                            heap32MatchCount = count32,
                            heapF32MatchCount = countF32,
                            filterRound = 0,
                            survivingCandidates = samples,
                            isolatedHeapType = autoType,
                            isolatedIndex = autoIdx,
                            isolatedByteAddress = if (autoIdx != null) autoByteAddr else null,
                            isolatedCurrentValue = autoVal,
                            isNeighborPolling = autoIdx != null,
                            statusMessage = statusMsg
                        )
                    )
                }
                "WASM Scan (${durationMs}ms): Found $totalMatches addresses for $targetVal (HEAP32: $count32, HEAPF32: $countF32) in $sourceLabel"
            }

            "WASM_HEAP_DELTA_FILTERED" -> {
                val totalMatches = json.optInt("totalMatches", 0)
                val prevCount = json.optInt("previousCount", 0)
                val count32 = json.optInt("heap32MatchCount", 0)
                val countF32 = json.optInt("heapF32MatchCount", 0)
                val filterRound = json.optInt("filterRound", 1)
                val newVal = json.optDouble("newValue", 0.0)
                val durationMs = json.optDouble("durationMs", 0.0)
                val sourceLabel = json.optString("heapSourceLabel", "")
                val byteLength = json.optLong("heapByteLength", 0L)
                val samples = parseWasmCandidates(json.optJSONArray("samples"))
                val autoObj = json.optJSONObject("autoIsolated")
                val autoType = autoObj?.optString("heapType", "")?.takeIf { it.isNotBlank() }
                val autoIdx = autoObj?.optInt("index", -1)?.takeIf { it >= 0 }
                val autoByteAddr = autoObj?.optLong("byteAddress", (autoIdx ?: 0).toLong() * 4L)
                val autoVal = autoObj?.optString("currentValue", "").orEmpty()

                val statusMsg = when {
                    autoIdx != null && autoType != null ->
                        "Isolated $autoType[$autoIdx] (${formatHexAddress(autoByteAddr ?: 0L)})! Live ±16B neighbor polling started."
                    totalMatches == 0 ->
                        "0 matches left after filtering for $newVal (from $prevCount). Tap Reset and run a fresh Step 1 scan."
                    else ->
                        "Filter #$filterRound narrowed $prevCount → $totalMatches matches. Change HP again or tap a candidate below to inspect ±16B."
                }

                _uiState.update { state ->
                    val mergedStaging = if (autoIdx != null && autoType != null) {
                        mergeIntoStagingBuffer(
                            existing = state.stagingBuffer,
                            incoming = listOf("$autoType[$autoIdx]" to "Isolated WASM stat (${formatHexAddress(autoByteAddr ?: 0L)}) = $autoVal"),
                            sourceStep = "WASM Heap",
                            scanNumber = state.scanCounter.coerceAtLeast(1),
                            timestamp = nowTs,
                            forcePin = true
                        )
                    } else state.stagingBuffer

                    state.copy(
                        stagingBuffer = mergedStaging,
                        scanStatus = if (totalMatches > 0) AsyncScanStatus.COMPLETED else AsyncScanStatus.IDLE,
                        scanStatusDetail = if (autoIdx != null && autoType != null) {
                            "Isolated $autoType[$autoIdx] • Polling ±16B"
                        } else {
                            "WASM Filter #$filterRound: $totalMatches remaining"
                        },
                        wasmHeap = state.wasmHeap.copy(
                            isScanning = false,
                            heapSourceLabel = sourceLabel.ifBlank { state.wasmHeap.heapSourceLabel },
                            heapByteLength = if (byteLength > 0L) byteLength else state.wasmHeap.heapByteLength,
                            lastFilterValue = newVal,
                            totalMatches = totalMatches,
                            heap32MatchCount = count32,
                            heapF32MatchCount = countF32,
                            filterRound = filterRound,
                            survivingCandidates = samples,
                            isolatedHeapType = autoType ?: state.wasmHeap.isolatedHeapType,
                            isolatedIndex = autoIdx ?: state.wasmHeap.isolatedIndex,
                            isolatedByteAddress = if (autoIdx != null) autoByteAddr else state.wasmHeap.isolatedByteAddress,
                            isolatedCurrentValue = if (autoIdx != null) autoVal else state.wasmHeap.isolatedCurrentValue,
                            isNeighborPolling = autoIdx != null || state.wasmHeap.isNeighborPolling,
                            statusMessage = statusMsg
                        )
                    )
                }
                "WASM Filter #$filterRound (${durationMs}ms): $prevCount → $totalMatches matches for $newVal" +
                    if (autoIdx != null && autoType != null) " • Isolated $autoType[$autoIdx] (${formatHexAddress(autoByteAddr ?: 0L)})" else ""
            }

            "WASM_HEAP_ERROR" -> {
                val msg = json.optString("message", "WASM heap operation failed.")
                _uiState.update { state ->
                    state.copy(
                        scanStatus = AsyncScanStatus.ERROR,
                        scanStatusDetail = msg.take(60),
                        wasmHeap = state.wasmHeap.copy(
                            isScanning = false,
                            statusMessage = msg
                        )
                    )
                }
                _effects.tryEmit(DiagnosticsEffect.ShowToast(msg))
                "WASM Tracker: $msg"
            }

            "WASM_FLOAT_BASELINE_CAPTURED" -> {
                val sampledCount = json.optInt("sampledCount", 0)
                val prunedJitter = json.optInt("prunedJitterCount", 0)
                val durationMs = json.optDouble("durationMs", 0.0)
                val sourceLabel = json.optString("heapSourceLabel", "window.HEAPF32")
                val byteLen = json.optLong("heapByteLength", 0L)

                val msg = "Captured $sampledCount stable floats in ${durationMs}ms (pruned $prunedJitter ticking timers). Walk 5 steps forward, then tap 'Scan Float Heap Deltas'."
                _uiState.update { state ->
                    state.copy(
                        scanStatus = AsyncScanStatus.COMPLETED,
                        scanStatusDetail = "Float Baseline: $sampledCount stable floats (${durationMs}ms)",
                        wasmFloatDelta = state.wasmFloatDelta.copy(
                            isSamplingBaseline = false,
                            baselineCaptured = sampledCount > 0,
                            baselineSampleCount = sampledCount,
                            baselinePrunedJitterCount = prunedJitter,
                            heapSourceLabel = sourceLabel,
                            heapByteLength = byteLen,
                            statusMessage = msg
                        )
                    )
                }
                "Step 2A (Float Mode, ${durationMs}ms): Sampled $sampledCount stable floats in $sourceLabel (pruned $prunedJitter jitter slots)"
            }

            "WASM_FLOAT_DELTA_SCANNED" -> {
                val totalMatches = json.optInt("totalMatches", 0)
                val scanRound = json.optInt("scanRound", 1)
                val durationMs = json.optDouble("durationMs", 0.0)
                val sourceLabel = json.optString("heapSourceLabel", "window.HEAPF32")
                val byteLen = json.optLong("heapByteLength", 0L)
                val parsedCandidates = parseWasmFloatDeltaCandidates(
                    array = json.optJSONArray("candidates"),
                    pinnedIndices = _uiState.value.wasmFloatDelta.pinnedIndices
                )

                val stagingPairs = parsedCandidates.take(6).map { c ->
                    val axisTag = if (c.axisHint.isNotBlank()) "[${c.axisHint}] " else ""
                    val hex = formatHexAddress(c.byteAddress)
                    "window.HEAPF32[${c.heapIndex}]" to "${axisTag}$hex • cur=${String.format(Locale.US, "%.3f", c.currentValue)} (Δ ${String.format(Locale.US, "%+.3f", c.delta)})"
                }

                val statusMsg = if (totalMatches > 0) {
                    "Round #$scanRound: Found $totalMatches moving floats (${durationMs}ms). Streaming top ${parsedCandidates.size} live below — walk again & re-tap to refine!"
                } else {
                    "Round #$scanRound: 0 floats moved in positional range (0.05..250). Walk further or recapture Baseline."
                }

                _uiState.update { state ->
                    val nextScan = state.scanCounter + 1
                    val mergedStaging = if (stagingPairs.isNotEmpty()) {
                        mergeIntoStagingBuffer(
                            existing = state.stagingBuffer,
                            incoming = stagingPairs,
                            sourceStep = "Float Delta #$scanRound",
                            scanNumber = nextScan,
                            timestamp = nowTs
                        )
                    } else state.stagingBuffer

                    state.copy(
                        scanCounter = nextScan,
                        stagingBuffer = mergedStaging,
                        scanStatus = if (totalMatches > 0) AsyncScanStatus.COMPLETED else AsyncScanStatus.IDLE,
                        scanStatusDetail = if (totalMatches > 0) {
                            "Float Deltas #$scanRound: $totalMatches matches • Live 200ms"
                        } else {
                            "Float Deltas #$scanRound: 0 matches"
                        },
                        wasmFloatDelta = state.wasmFloatDelta.copy(
                            isScanningDeltas = false,
                            heapSourceLabel = sourceLabel.ifBlank { state.wasmFloatDelta.heapSourceLabel },
                            heapByteLength = if (byteLen > 0L) byteLen else state.wasmFloatDelta.heapByteLength,
                            totalDeltaMatches = totalMatches,
                            scanRound = scanRound,
                            isLiveWatching = parsedCandidates.isNotEmpty(),
                            candidates = parsedCandidates,
                            isWatcherExpanded = true,
                            statusMessage = statusMsg
                        )
                    )
                }
                "Step 2B (Float Mode #$scanRound, ${durationMs}ms): Isolated $totalMatches moving floats in $sourceLabel • Watching top ${parsedCandidates.size}"
            }

            "WASM_FLOAT_ERROR" -> {
                val msg = json.optString("message", "WASM Float Heap scan failed.")
                _uiState.update { state ->
                    state.copy(
                        scanStatus = AsyncScanStatus.ERROR,
                        scanStatusDetail = msg.take(60),
                        wasmFloatDelta = state.wasmFloatDelta.copy(
                            isSamplingBaseline = false,
                            isScanningDeltas = false,
                            statusMessage = msg
                        )
                    )
                }
                _effects.tryEmit(DiagnosticsEffect.ShowToast(msg))
                "WASM Float Delta: $msg"
            }

            else -> payload.take(110)
        }
    }

    private fun parseWasmFloatDeltaCandidates(
        array: JSONArray?,
        pinnedIndices: Set<Int>
    ): List<WasmFloatDeltaCandidate> {
        if (array == null) return emptyList()
        return buildList {
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val idx = obj.optInt("heapIndex", -1)
                if (idx < 0) continue
                val byteAddr = obj.optLong("byteAddress", idx.toLong() * 4L)
                val baseVal = obj.optDouble("baselineValue", 0.0)
                val curVal = obj.optDouble("currentValue", 0.0)
                val deltaVal = obj.optDouble("delta", curVal - baseVal)
                val cluster = obj.optInt("clusterSize", 1)
                val axis = obj.optString("axisHint", "")
                add(
                    WasmFloatDeltaCandidate(
                        heapIndex = idx,
                        byteAddress = byteAddr,
                        baselineValue = baseVal,
                        currentValue = curVal,
                        delta = deltaVal,
                        clusterSize = cluster,
                        axisHint = axis,
                        isPinned = pinnedIndices.contains(idx)
                    )
                )
            }
        }
    }

    private fun parseWasmCandidates(array: JSONArray?): List<WasmCandidateAddress> {
        if (array == null) return emptyList()
        return buildList {
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val heapType = obj.optString("heapType", "HEAP32")
                val index = obj.optInt("index", -1)
                if (index < 0) continue
                val byteAddr = obj.optLong("byteAddress", index.toLong() * 4L)
                val curVal = obj.optString("currentValue", "")
                add(
                    WasmCandidateAddress(
                        heapType = heapType,
                        index = index,
                        byteAddress = byteAddr,
                        currentValue = curVal
                    )
                )
            }
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

        // 5. Intercepted asset catalog with local ByteBuffer metadata
        val assetsArray = JSONArray()
        state.interceptedAssets.forEach { asset ->
            assetsArray.put(
                JSONObject()
                    .put("url", asset.url)
                    .put("fileName", asset.fileName)
                    .put("extension", asset.extension)
                    .put("method", asset.method)
                    .put("timestamp", asset.timestamp)
                    .put("isForMainFrame", asset.isForMainFrame)
                    .put("captureStatus", asset.captureStatus.name)
                    .put("localRelativePath", asset.localRelativePath ?: JSONObject.NULL)
                    .put("httpStatus", asset.httpStatus ?: JSONObject.NULL)
                    .put("contentType", asset.contentType)
                    .put("byteSize", asset.byteSize)
                    .put("isTruncated", asset.isTruncated)
                    .put("sha256Prefix", asset.sha256Prefix)
                    .put("magicSignature", asset.magicSignature)
                    .put("entropyBits", asset.entropyBits)
                    .put("hexHeaderPreview", asset.hexHeaderPreview)
                    .put("structuralSummary", asset.structuralSummary)
                    .put("structuralDetails", asset.structuralDetails)
            )
        }
        root.put("interceptedAssetUrls", assetsArray)
        root.put(
            "interceptedAssetCatalog",
            JSONObject()
                .put("totalObserved", state.interceptedAssets.size)
                .put("totalCachedLocally", state.cachedAssetCount)
                .put("totalCachedBytes", state.totalCachedAssetBytes)
                .put("assets", assetsArray)
        )

        // 6. WASM Heap Value-Tracker & Float Delta Memory Map (if active/isolated)
        if (state.wasmHeap.isolatedIndex != null || state.wasmHeap.totalMatches > 0 || state.wasmFloatDelta.candidates.isNotEmpty()) {
            root.put("wasmHeapMemoryMap", JSONObject(buildWasmMemoryMapJson(state)))
        }

        // 7. Session logs
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
        private const val MAX_TRACKED_ASSETS = 500
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

        fun filterStagingBuffer(
            items: List<CandidateStagingItem>,
            showPinnedOnly: Boolean,
            rawQuery: String
        ): List<CandidateStagingItem> {
            val base = if (showPinnedOnly) items.filter { it.isPinned } else items
            val tokens = rawQuery.trim()
                .lowercase(Locale.US)
                .split(Regex("\\s+"))
                .filter { it.isNotBlank() }
            if (tokens.isEmpty()) return base
            return base.filter { item ->
                val haystack = "${item.path} ${item.detail} ${item.sourceStep}".lowercase(Locale.US)
                tokens.all { token -> haystack.contains(token) }
            }
        }

        fun buildFullLogDumpText(state: DiagnosticsUiState): String {
            val isoNow = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US).format(Date())
            return buildString {
                appendLine("========================================================================")
                appendLine("WebViewDiagnosticsApp — Complete Diagnostic Log Dump")
                appendLine("========================================================================")
                appendLine("Generated At : $isoNow")
                appendLine("Active URL   : ${state.activeUrl}")
                appendLine("Page Title   : ${state.pageTitle.ifBlank { "(untitled)" }}")
                appendLine("User-Agent   : ${if (state.isDesktopMode) "Desktop" else "Mobile"}")
                appendLine("Remote Debug : false (disabled)")
                appendLine("Scans Run    : ${state.scanCounter}")
                appendLine("Canvases     : ${state.canvasCount}")
                if (state.entityViewportWidth > 0 && state.entityViewportHeight > 0) {
                    appendLine("Viewport     : ${state.entityViewportWidth}x${state.entityViewportHeight}")
                }
                appendLine()

                appendLine("--- [1] LIVE PLAYER COORDINATE LOCK ---")
                appendLine("Confirmed Path : ${state.discoveredPlayerPath ?: "(not isolated)"}")
                appendLine("Property Keys  : ${state.playerPropertyKeys.ifBlank { "(none)" }}")
                state.livePlayerCoords?.let { coords ->
                    appendLine(
                        "Live Coords    : X=${String.format(Locale.US, "%.4f", coords.x)}, " +
                            "Y=${String.format(Locale.US, "%.4f", coords.y)}, " +
                            "Z=${String.format(Locale.US, "%.4f", coords.z)} (ticks=${state.livePollTickCount})"
                    )
                }
                if (state.isolatedPaths.isNotEmpty()) {
                    appendLine("Isolated Deltas (${state.isolatedPaths.size}):")
                    state.isolatedPaths.forEach { d ->
                        appendLine("  • ${d.path} | base=${d.baselineValue} -> cur=${d.currentValue} (Δ ${d.delta})")
                    }
                }
                appendLine()

                appendLine("--- [2] STAGED VARIABLES (${state.stagingBuffer.size} total, ${state.pinnedCandidateCount} pinned) ---")
                if (state.stagingBuffer.isEmpty()) {
                    appendLine("  (none)")
                } else {
                    state.stagingBuffer.forEach { item ->
                        val pinTag = if (item.isPinned) "[PINNED] " else ""
                        appendLine("  $pinTag${item.path} | ${item.detail} | ${item.sourceStep} #${item.scanNumber} (×${item.seenCount}, ${item.lastSeenTimestamp})")
                    }
                }
                appendLine()

                appendLine("--- [3] ENTITY SCAN RESULTS (${state.discoveredEntities.size} detected) ---")
                appendLine("Entity Array   : ${state.discoveredEntityArrayPath ?: "(not scanned)"}")
                if (state.entityProjectionMode.isNotBlank()) {
                    appendLine("Projection     : ${state.entityProjectionMode}")
                }
                if (state.discoveredEntities.isEmpty()) {
                    appendLine("  (none)")
                } else {
                    state.discoveredEntities.forEachIndexed { idx, e ->
                        val vis = if (e.inViewport) "IN VIEW" else "OFFSCREEN"
                        appendLine(
                            "  [${idx + 1}] ${e.name}${if (e.id.isNotBlank()) " #${e.id}" else ""} ($vis) | " +
                                "world=(${String.format(Locale.US, "%.2f", e.worldX)}, ${String.format(Locale.US, "%.2f", e.worldY)}, ${String.format(Locale.US, "%.2f", e.worldZ)}) | " +
                                "dist=${String.format(Locale.US, "%.1f", e.distanceToPlayer)} | " +
                                "screen=(${String.format(Locale.US, "%.1f", e.screenX)}px, ${String.format(Locale.US, "%.1f", e.screenY)}px) | " +
                                "path=${e.path}"
                        )
                    }
                }
                appendLine()

                appendLine("--- [4] INTERCEPTED ASSET CATALOG (${state.interceptedAssets.size} observed, ${state.cachedAssetCount} cached locally) ---")
                if (state.interceptedAssets.isEmpty()) {
                    appendLine("  (none)")
                } else {
                    state.interceptedAssets.forEach { asset ->
                        val sizeStr = if (asset.byteSize > 0L) " | ${BinaryAssetInspector.formatBytes(asset.byteSize)}" else ""
                        val magicStr = if (asset.magicSignature.isNotBlank()) " | ${asset.magicSignature}" else ""
                        appendLine("  [${asset.timestamp}] ${asset.method} (${asset.extension}) ${asset.fileName.ifBlank { asset.url }}$sizeStr$magicStr")
                        appendLine("    URL: ${asset.url}")
                        if (!asset.localRelativePath.isNullOrBlank()) {
                            appendLine("    Local: filesDir/${asset.localRelativePath} | SHA-256: ${asset.sha256Prefix}")
                        }
                        if (asset.structuralSummary.isNotBlank()) {
                            appendLine("    Summary: ${asset.structuralSummary}")
                        }
                    }
                }
                appendLine()

                if (state.wasmHeap.isolatedIndex != null || state.wasmHeap.totalMatches > 0) {
                    appendLine("--- [5] WASM HEAP VALUE-TRACKER MEMORY MAP ---")
                    appendLine("Heap Source    : ${state.wasmHeap.heapSourceLabel.ifBlank { "(unknown)" }}")
                    appendLine("Matches        : ${state.wasmHeap.totalMatches} (HEAP32=${state.wasmHeap.heap32MatchCount}, HEAPF32=${state.wasmHeap.heapF32MatchCount})")
                    state.wasmHeap.isolatedIndex?.let { idx ->
                        val addr = state.wasmHeap.isolatedByteAddress ?: (idx.toLong() * 4L)
                        appendLine("Isolated Slot  : ${state.wasmHeap.isolatedHeapType ?: "HEAP32"}[$idx] (${formatHexAddress(addr)}) = ${state.wasmHeap.isolatedCurrentValue}")
                    }
                    state.wasmHeap.neighborFloats.forEach { nb ->
                        val sign = if (nb.byteOffset >= 0) "+" else ""
                        val dynTag = if (nb.changeCount > 0) " [DYNAMIC ×${nb.changeCount}]" else ""
                        appendLine("  ${sign}${nb.byteOffset}B (${formatHexAddress(nb.byteAddress)}) | F32=${String.format(Locale.US, "%.4f", nb.floatValue)} | I32=${nb.intValue}$dynTag")
                    }
                    appendLine()
                }

                if (state.wasmFloatDelta.candidates.isNotEmpty()) {
                    appendLine("--- [5B] WASM FLOAT HEAP DELTA CANDIDATES (window.HEAPF32) ---")
                    appendLine("Heap Source    : ${state.wasmFloatDelta.heapSourceLabel.ifBlank { "window.HEAPF32" }}")
                    appendLine("Baseline / Hit : ${state.wasmFloatDelta.baselineSampleCount} stable -> ${state.wasmFloatDelta.totalDeltaMatches} moving (round #${state.wasmFloatDelta.scanRound})")
                    state.wasmFloatDelta.candidates.forEach { c ->
                        val pinTag = if (c.isPinned) "[PINNED] " else ""
                        val axisTag = if (c.axisHint.isNotBlank()) "[${c.axisHint}] " else ""
                        appendLine(
                            "  $pinTag${axisTag}HEAPF32[${c.heapIndex}] (${formatHexAddress(c.byteAddress)}) | " +
                                "cur=${String.format(Locale.US, "%.4f", c.currentValue)} | " +
                                "base=${String.format(Locale.US, "%.4f", c.baselineValue)} | " +
                                "Δ=${String.format(Locale.US, "%+.4f", c.delta)} | ticksChanged=${c.changeCount}"
                        )
                    }
                    appendLine()
                }

                appendLine("--- [6] COMPLETE ROLLING LOG HISTORY (${state.logs.size} entries) ---")
                if (state.logs.isEmpty()) {
                    appendLine("  (no log entries)")
                } else {
                    state.logs.forEach { entry ->
                        appendLine("[${entry.timestamp}] [${entry.category.tag}] ${entry.summary}")
                        if (entry.payload.isNotBlank() && entry.payload != entry.summary) {
                            entry.payload.lineSequence().forEach { line ->
                                appendLine("    $line")
                            }
                        }
                    }
                }
                appendLine("========================================================================")
            }
        }

        fun formatHexAddress(byteAddress: Long): String {
            return String.format(Locale.US, "0x%08X", byteAddress.coerceAtLeast(0L))
        }

        fun buildWasmMemoryMapJson(state: DiagnosticsUiState): String {
            val wasm = state.wasmHeap
            val root = JSONObject()
            root.put("application", "WebViewDiagnosticsApp")
            root.put("reportType", "wasm_heap_memory_map")
            root.put("generatedAtIso", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US).format(Date()))
            root.put("activeUrl", state.activeUrl)
            root.put("remoteWebContentsDebuggingEnabled", false)
            root.put("heapSource", wasm.heapSourceLabel.ifBlank { "window.HEAP32 / window.HEAPF32" })
            root.put("heapByteLength", wasm.heapByteLength)
            root.put("initialSearchValue", wasm.initialSearchValue ?: JSONObject.NULL)
            root.put("lastFilterValue", wasm.lastFilterValue ?: JSONObject.NULL)
            root.put("filterRounds", wasm.filterRound)
            root.put("remainingMatches", wasm.totalMatches)
            root.put("heap32MatchCount", wasm.heap32MatchCount)
            root.put("heapF32MatchCount", wasm.heapF32MatchCount)

            val isolatedObj = JSONObject()
            if (wasm.isolatedIndex != null) {
                val byteAddr = wasm.isolatedByteAddress ?: (wasm.isolatedIndex.toLong() * 4L)
                isolatedObj.put("heapType", wasm.isolatedHeapType ?: "HEAP32")
                isolatedObj.put("elementIndex", wasm.isolatedIndex)
                isolatedObj.put("byteAddress", byteAddr)
                isolatedObj.put("hexByteAddress", formatHexAddress(byteAddr))
                isolatedObj.put("currentValue", wasm.isolatedCurrentValue)
                isolatedObj.put("pollTicksObserved", wasm.neighborPollTickCount)
                root.put("isolatedTarget", isolatedObj)
            } else {
                root.put("isolatedTarget", JSONObject.NULL)
            }

            val neighborsArr = JSONArray()
            val dynamicCoordsArr = JSONArray()
            wasm.neighborFloats.forEach { nb ->
                val isDynamic = nb.byteOffset != 0 && nb.changeCount > 0
                val nbJson = JSONObject()
                    .put("byteOffset", nb.byteOffset)
                    .put("offsetLabel", if (nb.byteOffset >= 0) "+${nb.byteOffset}B" else "${nb.byteOffset}B")
                    .put("heapIndex", nb.heapIndex)
                    .put("byteAddress", nb.byteAddress)
                    .put("hexByteAddress", formatHexAddress(nb.byteAddress))
                    .put("float32Value", nb.floatValue)
                    .put("int32Value", nb.intValue)
                    .put("changeCount", nb.changeCount)
                    .put("isTargetSlot", nb.byteOffset == 0)
                    .put("isDynamicCoordinateCandidate", isDynamic)
                neighborsArr.put(nbJson)
                if (isDynamic) {
                    dynamicCoordsArr.put(nbJson)
                }
            }
            root.put("neighboringMemoryMap", neighborsArr)
            root.put("dynamicCoordinateCandidates", dynamicCoordsArr)

            val candidatesArr = JSONArray()
            wasm.survivingCandidates.forEach { c ->
                candidatesArr.put(
                    JSONObject()
                        .put("heapType", c.heapType)
                        .put("index", c.index)
                        .put("byteAddress", c.byteAddress)
                        .put("hexByteAddress", formatHexAddress(c.byteAddress))
                        .put("currentValue", c.currentValue)
                )
            }
            root.put("survivingCandidates", candidatesArr)

            val floatDelta = state.wasmFloatDelta
            val floatDeltaArr = JSONArray()
            floatDelta.candidates.forEach { c ->
                floatDeltaArr.put(
                    JSONObject()
                        .put("heapIndex", c.heapIndex)
                        .put("byteAddress", c.byteAddress)
                        .put("hexByteAddress", formatHexAddress(c.byteAddress))
                        .put("axisHint", c.axisHint)
                        .put("baselineValue", c.baselineValue)
                        .put("currentValue", c.currentValue)
                        .put("delta", c.delta)
                        .put("clusterSize", c.clusterSize)
                        .put("isPinned", c.isPinned)
                        .put("changeCount", c.changeCount)
                )
            }
            root.put(
                "wasmFloatDeltaScanner",
                JSONObject()
                    .put("heapSource", floatDelta.heapSourceLabel.ifBlank { "window.HEAPF32" })
                    .put("baselineSampleCount", floatDelta.baselineSampleCount)
                    .put("scanRound", floatDelta.scanRound)
                    .put("totalDeltaMatches", floatDelta.totalDeltaMatches)
                    .put("candidates", floatDeltaArr)
            )

            return root.toString(2)
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
