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

    private val profileStore = LockProfileStore(application)
    private val _uiState = MutableStateFlow(DiagnosticsUiState())
    val uiState: StateFlow<DiagnosticsUiState> = _uiState.asStateFlow()

    private val _commands = MutableSharedFlow<DiagnosticsCommand>(extraBufferCapacity = 32)
    val commands: SharedFlow<DiagnosticsCommand> = _commands.asSharedFlow()

    private val nextLogId = AtomicLong(1L)
    private var exportFallbackJob: Job? = null
    private var autoRelockDelayJob: Job? = null
    private val inFlightAssetUrls = HashSet<String>()

    init {
        val persisted = profileStore.loadProfile()
        val autoRelockPref = profileStore.isAutoRelockEnabled()

        if (persisted != null) {
            _uiState.update { state ->
                state.copy(
                    savedProfile = persisted,
                    heapFeed = state.heapFeed.copy(
                        baseIndex = persisted.baseIndex,
                        baseIndexInput = persisted.baseIndex.toString(),
                        hasSignature = true,
                        signatureAnchorCount = persisted.anchorCount,
                        signatureCapturedAtIndex = persisted.baseIndex,
                        signatureJson = persisted.signatureJson,
                        autoRelockOnReload = autoRelockPref,
                        lastRelockSummary = "Loaded saved profile (${persisted.anchorCount} anchors, ${persisted.parentPointers.size} ptrs)"
                    ),
                    pointerAnalysis = state.pointerAnalysis.copy(
                        inspectedBaseIndex = persisted.baseIndex,
                        inspectedByteAddress = persisted.baseIndex.toLong() * 4L,
                        aobPatternHex = persisted.aobPatternHex,
                        floatPatternSummary = persisted.floatPatternSummary,
                        anchorCount = persisted.anchorCount,
                        signatureJson = persisted.signatureJson,
                        parentPointers = persisted.parentPointers
                    )
                )
            }
            appendLog(
                category = LogCategory.RELOCK,
                summary = "Restored saved lock profile from SharedPreferences (HEAPF32[${persisted.baseIndex}])",
                payload = "Anchors: ${persisted.anchorCount} | Parent Pointers: ${persisted.parentPointers.size} | Auto-Relock: $autoRelockPref"
            )
        } else {
            _uiState.update { state ->
                state.copy(heapFeed = state.heapFeed.copy(autoRelockOnReload = autoRelockPref))
            }
            appendLog(
                category = LogCategory.SYSTEM,
                summary = "WASM Memory Scanner & Persistent Lock ready (remote debugging OFF)",
                payload = "Default target: window.HEAPF32[$VERIFIED_PLAYER_BASE_INDEX..${VERIFIED_PLAYER_BASE_INDEX + 2}] @ 200ms"
            )
        }
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
                appendLog(LogCategory.ERROR, "Blocked invalid URL", validation.reason)
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
                appendLog(LogCategory.SYSTEM, "Loading target URL", validation.normalizedUrl)
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
        appendLog(
            category = LogCategory.SYSTEM,
            summary = "Reloading target page (saved profile preserved)",
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
        autoRelockDelayJob?.cancel()
        _uiState.update { state ->
            state.copy(
                activeUrl = url,
                urlInput = if (url == "about:blank") state.urlInput else url,
                isLoading = true,
                loadProgress = 10,
                heapFeed = state.heapFeed.copy(
                    isHeapReady = false,
                    lockStatus = LockStatus.UNLOCKED,
                    statusMessage = "Page loading… preparing 200ms HEAPF32 feed"
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
            appendLog(LogCategory.SYSTEM, "Page loaded: ${title.ifBlank { url }}", url)
            startDirectHeapFeed()

            // Automatic Relock on reload if a saved signature/pointer profile exists
            val stateNow = _uiState.value
            if (stateNow.heapFeed.autoRelockOnReload &&
                (stateNow.savedProfile != null || stateNow.heapFeed.hasSignature)
            ) {
                autoRelockDelayJob?.cancel()
                autoRelockDelayJob = viewModelScope.launch {
                    delay(1_400L) // allow WASM module & linear memory heap to finish booting
                    autoRelockCoordinates(isAutomatic = true)
                }
            }
        }
    }

    fun onWebViewError(description: String, failingUrl: String) {
        appendLog(LogCategory.ERROR, "WebView load error: $description", failingUrl)
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
    // Step 1: Movement Float Delta Scanner (window.HEAPF32)
    // -------------------------------------------------------------------------

    fun captureStandingBaseline() {
        if (!ensurePageLoadedForInspection()) return
        _uiState.update { state ->
            state.copy(
                scanStatus = AsyncScanStatus.RUNNING,
                scanStatusDetail = "Capturing standing float baseline…",
                floatScanner = state.floatScanner.copy(
                    isBusy = true,
                    isLivePreviewing = false,
                    statusMessage = "Snapshotting static floats in window.HEAPF32 (stand completely still)…"
                )
            )
        }
        appendLog(
            category = LogCategory.SCAN,
            summary = "Step 1A: Capturing standing float baseline",
            payload = "Non-blocking chunked snapshot of window.HEAPF32 + 120ms jitter prune"
        )
        _commands.tryEmit(DiagnosticsCommand.EvaluateJs(WasmHeapScripts.buildCaptureStandingBaselineScript()))
    }

    fun scanMovementDeltas() {
        if (!ensurePageLoadedForInspection()) return
        if (!_uiState.value.floatScanner.baselineCaptured) {
            _commands.tryEmit(DiagnosticsCommand.ShowToast("Tap '1. Capture Standing Baseline' first"))
            return
        }
        _uiState.update { state ->
            state.copy(
                scanStatus = AsyncScanStatus.RUNNING,
                scanStatusDetail = "Scanning movement float deltas…",
                floatScanner = state.floatScanner.copy(
                    isBusy = true,
                    statusMessage = "Filtering window.HEAPF32 for floats that shifted & stopped…"
                )
            )
        }
        appendLog(
            category = LogCategory.SCAN,
            summary = "Step 1B: Scanning movement float deltas",
            payload = "Comparing current window.HEAPF32 against baseline with 120ms stillness filter"
        )
        _commands.tryEmit(DiagnosticsCommand.EvaluateJs(WasmHeapScripts.buildScanMovementDeltasScript()))
    }

    fun resetFloatScan() {
        _uiState.update { state ->
            state.copy(floatScanner = FloatDeltaScannerState())
        }
        if (_uiState.value.activeUrl != "about:blank") {
            _commands.tryEmit(DiagnosticsCommand.EvaluateJs(WasmHeapScripts.buildResetFloatScanScript()))
        }
        appendLog(LogCategory.SCAN, "Reset Movement Float Delta Scanner", "Cleared baseline and candidates.")
    }

    fun onFloatDeltaPreviewFromBridge(jsonPayload: String) {
        val obj = runCatching { JSONObject(jsonPayload) }.getOrNull() ?: return
        val updatesArr = obj.optJSONArray("updates") ?: return
        if (updatesArr.length() == 0) return

        val updateMap = HashMap<Int, Triple<Double, Double, Double>>(updatesArr.length())
        for (i in 0 until updatesArr.length()) {
            val item = updatesArr.optJSONObject(i) ?: continue
            val idx = item.optInt("heapIndex", -1)
            if (idx < 0) continue
            updateMap[idx] = Triple(
                item.optDouble("currentValue", 0.0),
                item.optDouble("previewY", 0.0),
                item.optDouble("previewZ", 0.0)
            )
        }
        if (updateMap.isEmpty()) return

        _uiState.update { state ->
            val updatedList = state.floatScanner.candidates.map { cand ->
                val live = updateMap[cand.heapIndex] ?: return@map cand
                val changed = kotlin.math.abs(live.first - cand.currentValue) > 0.001
                cand.copy(
                    currentValue = live.first,
                    delta = live.first - cand.baselineValue,
                    previewY = live.second,
                    previewZ = live.third,
                    liveChangeCount = if (changed) cand.liveChangeCount + 1 else cand.liveChangeCount
                )
            }
            state.copy(
                floatScanner = state.floatScanner.copy(
                    isLivePreviewing = true,
                    candidates = updatedList
                )
            )
        }
    }

    // -------------------------------------------------------------------------
    // Step 2: Pointer & Memory Signature Analyzer + Persistent SharedPreferences Lock
    // -------------------------------------------------------------------------

    fun inspectAndLockCandidate(baseIndex: Int) {
        if (!ensurePageLoadedForInspection()) return
        val safeBase = baseIndex.coerceAtLeast(0)
        _uiState.update { state ->
            state.copy(
                activeConsoleTab = ConsoleTab.ANALYZER,
                scanStatus = AsyncScanStatus.RUNNING,
                scanStatusDetail = "Analyzing ±16 block & HEAPU32 pointers for [$safeBase]…",
                heapFeed = state.heapFeed.copy(
                    baseIndex = safeBase,
                    baseIndexInput = safeBase.toString(),
                    lockStatus = LockStatus.LOCKED
                ),
                pointerAnalysis = state.pointerAnalysis.copy(
                    inspectedBaseIndex = safeBase,
                    inspectedByteAddress = safeBase.toLong() * 4L,
                    isAnalyzing = true,
                    errorMessage = null
                )
            )
        }
        appendLog(
            category = LogCategory.PTR,
            summary = "Step 2: Analyzing ±16 struct signature & reverse HEAPU32 pointers for [$safeBase]",
            payload = "Byte address: 0x${(safeBase.toLong() * 4L).toString(16).uppercase(Locale.US)}"
        )
        _commands.tryEmit(
            DiagnosticsCommand.EvaluateJs(
                WasmHeapScripts.buildAnalyzePointerAndSignatureScript(safeBase)
            )
        )
        // Lock the 200ms top Live Telemetry Bar onto this candidate immediately
        startDirectHeapFeed()
    }

    fun saveAndLockProfile() {
        val state = _uiState.value
        val analysis = state.pointerAnalysis
        val sigJson = analysis.signatureJson ?: state.heapFeed.signatureJson
        val targetIndex = analysis.inspectedBaseIndex ?: state.heapFeed.baseIndex

        if (sigJson.isNullOrBlank()) {
            _commands.tryEmit(DiagnosticsCommand.ShowToast("Analyze a candidate index first before saving"))
            return
        }

        val host = extractHost(state.activeUrl)
        val profile = SavedLockProfile(
            baseIndex = targetIndex,
            host = host,
            aobPatternHex = analysis.aobPatternHex,
            floatPatternSummary = analysis.floatPatternSummary,
            anchorCount = analysis.anchorCount.takeIf { it > 0 } ?: state.heapFeed.signatureAnchorCount,
            signatureJson = sigJson,
            parentPointers = analysis.parentPointers,
            autoRelockOnReload = state.heapFeed.autoRelockOnReload,
            savedAtEpochMs = System.currentTimeMillis()
        )

        profileStore.saveProfile(profile)

        _uiState.update { current ->
            current.copy(
                savedProfile = profile,
                heapFeed = current.heapFeed.copy(
                    baseIndex = targetIndex,
                    baseIndexInput = targetIndex.toString(),
                    lockStatus = LockStatus.LOCKED,
                    hasSignature = true,
                    signatureAnchorCount = profile.anchorCount,
                    signatureCapturedAtIndex = targetIndex,
                    signatureJson = sigJson,
                    lastRelockSummary = "Profile saved to SharedPreferences (${profile.anchorCount} anchors, ${profile.parentPointers.size} ptrs)"
                )
            )
        }

        appendLog(
            category = LogCategory.PTR,
            summary = "Saved & Locked Profile to SharedPreferences (HEAPF32[$targetIndex])",
            payload = "AOB: ${profile.aobPatternHex.take(120)} | Parent Pointers: ${profile.parentPointers.size}"
        )
        _commands.tryEmit(
            DiagnosticsCommand.ShowToast("Profile saved & locked @ HEAPF32[$targetIndex]")
        )
        if (state.activeUrl != "about:blank") {
            startDirectHeapFeed()
        }
    }

    fun clearSavedProfile() {
        profileStore.clearProfile()
        _uiState.update { state ->
            state.copy(
                savedProfile = null,
                heapFeed = state.heapFeed.copy(
                    lockStatus = LockStatus.UNLOCKED,
                    hasSignature = false,
                    signatureAnchorCount = 0,
                    signatureCapturedAtIndex = null,
                    signatureJson = null,
                    lastRelockSummary = "Saved lock profile cleared"
                )
            )
        }
        appendLog(LogCategory.RELOCK, "Cleared saved lock profile from SharedPreferences", "")
        _commands.tryEmit(DiagnosticsCommand.ShowToast("Saved lock profile cleared"))
    }

    // -------------------------------------------------------------------------
    // Step 3: One-Tap & Automatic Relock Across Reloads + Direct Feed Controls
    // -------------------------------------------------------------------------

    fun onToggleAutoRelockOnReload(enabled: Boolean) {
        profileStore.setAutoRelockEnabled(enabled)
        _uiState.update { state ->
            state.copy(
                savedProfile = state.savedProfile?.copy(autoRelockOnReload = enabled),
                heapFeed = state.heapFeed.copy(autoRelockOnReload = enabled)
            )
        }
    }

    fun autoRelockCoordinates(isAutomatic: Boolean = false) {
        if (!ensurePageLoadedForInspection()) return
        val state = _uiState.value
        val feed = state.heapFeed
        val sigJson = state.savedProfile?.signatureJson ?: feed.signatureJson
        val pointers = state.savedProfile?.parentPointers ?: state.pointerAnalysis.parentPointers
        val pointersJson = serializePointersJson(pointers)

        _uiState.update { current ->
            current.copy(
                scanStatus = AsyncScanStatus.RUNNING,
                scanStatusDetail = if (isAutomatic) "Auto-Relock on reload…" else "Relocking saved profile…",
                heapFeed = current.heapFeed.copy(
                    isRelocking = true,
                    lockStatus = LockStatus.RELOCKING,
                    statusMessage = "Running Pointer Deref -> AOB/Float signature relock…"
                )
            )
        }
        appendLog(
            category = LogCategory.RELOCK,
            summary = if (isAutomatic) "Automatic reload relock started" else "One-tap Auto-Relock triggered",
            payload = "Base=${feed.baseIndex} | Anchors=${feed.signatureAnchorCount} | Saved Pointers=${pointers.size}"
        )
        _commands.tryEmit(
            DiagnosticsCommand.EvaluateJs(
                WasmHeapScripts.buildAutoRelockScript(
                    currentBaseIndex = feed.baseIndex,
                    savedSignatureJson = sigJson,
                    savedPointersJson = pointersJson
                )
            )
        )
    }

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
                    statusMessage = "Switched base index to window.HEAPF32[$parsed..${parsed + 2}]"
                )
            )
        }
        if (_uiState.value.activeUrl != "about:blank") {
            startDirectHeapFeed()
        }
    }

    fun onResetToVerifiedBaseIndex() {
        _uiState.update { state ->
            state.copy(
                heapFeed = state.heapFeed.copy(
                    baseIndex = VERIFIED_PLAYER_BASE_INDEX,
                    baseIndexInput = VERIFIED_PLAYER_BASE_INDEX.toString()
                )
            )
        }
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
                    savedSignatureJson = _uiState.value.savedProfile?.signatureJson ?: feed.signatureJson
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
                scanStatusDetail = "Scanning ±$radius around ${feed.baseIndex}…",
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
    // Bridge Callbacks (Direct 200ms Coords, Status, Structured Telemetry)
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
            val resolvedLock = when {
                prevFeed.isRelocking -> LockStatus.RELOCKING
                prevFeed.lockStatus == LockStatus.LOCKED || state.savedProfile != null -> LockStatus.LOCKED
                else -> prevFeed.lockStatus
            }
            state.copy(
                heapFeed = prevFeed.copy(
                    baseIndex = baseIndex,
                    baseIndexInput = if (prevFeed.baseIndex != baseIndex) baseIndex.toString() else prevFeed.baseIndexInput,
                    heapSourceLabel = heapSource,
                    heapByteLength = heapByteLength,
                    lockStatus = resolvedLock,
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
            val finished = status == AsyncScanStatus.COMPLETED || status == AsyncScanStatus.ERROR
            state.copy(
                scanStatus = status,
                scanStatusDetail = detail,
                isScanningEntities = if (finished) false else state.isScanningEntities,
                floatScanner = if (finished) state.floatScanner.copy(isBusy = false) else state.floatScanner,
                pointerAnalysis = if (finished) state.pointerAnalysis.copy(isAnalyzing = false) else state.pointerAnalysis,
                heapFeed = if (finished) state.heapFeed.copy(isRelocking = false) else state.heapFeed
            )
        }
    }

    fun onTelemetryFromBridge(rawCategory: String, payload: String) {
        val category = LogCategory.fromRaw(rawCategory)
        val trimmed = payload.trim()
        if (trimmed.startsWith("{")) {
            if (tryHandleStructuredTelemetry(trimmed)) return
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
                if (message.isNotBlank()) appendLog(LogCategory.FEED, message, jsonStr)
                true
            }

            "FLOAT_BASELINE_CAPTURED" -> {
                val sampled = obj.optInt("sampledCount", 0)
                val pruned = obj.optInt("prunedJitterCount", 0)
                val durationMs = obj.optDouble("durationMs", 0.0)
                _uiState.update { state ->
                    state.copy(
                        scanStatus = AsyncScanStatus.COMPLETED,
                        scanStatusDetail = "Baseline captured ($sampled static floats)",
                        floatScanner = state.floatScanner.copy(
                            isBusy = false,
                            baselineCaptured = true,
                            baselineSampleCount = sampled,
                            prunedJitterCount = pruned,
                            scanDurationMs = durationMs,
                            candidates = emptyList(),
                            isLivePreviewing = false,
                            statusMessage = "Baseline ready ($sampled static floats, $pruned timers pruned). Walk a few steps, stop, then tap '2. Scan Movement Deltas'."
                        )
                    )
                }
                appendLog(
                    category = LogCategory.SCAN,
                    summary = "Baseline captured: $sampled static floats ($pruned jitter pruned) in ${durationMs}ms",
                    payload = jsonStr
                )
                true
            }

            "FLOAT_BASELINE_ERROR", "FLOAT_DELTA_ERROR" -> {
                val reason = obj.optString("reason", "Float scan failed.")
                _uiState.update { state ->
                    state.copy(
                        scanStatus = AsyncScanStatus.ERROR,
                        scanStatusDetail = reason,
                        floatScanner = state.floatScanner.copy(isBusy = false, statusMessage = reason)
                    )
                }
                appendLog(LogCategory.ERROR, "Float scanner error", reason)
                _commands.tryEmit(DiagnosticsCommand.ShowToast(reason))
                true
            }

            "FLOAT_DELTAS_SCANNED" -> {
                val round = obj.optInt("scanRound", 1)
                val totalMatches = obj.optInt("totalMatches", 0)
                val stillCount = obj.optInt("stillCount", 0)
                val durationMs = obj.optDouble("durationMs", 0.0)
                val arr = obj.optJSONArray("candidates") ?: JSONArray()

                val parsed = buildList {
                    for (i in 0 until arr.length()) {
                        val c = arr.optJSONObject(i) ?: continue
                        val idx = c.optInt("heapIndex", -1)
                        if (idx < 0) continue
                        add(
                            FloatDeltaCandidate(
                                heapIndex = idx,
                                byteAddress = c.optLong("byteAddress", idx.toLong() * 4L),
                                baselineValue = c.optDouble("baselineValue", 0.0),
                                currentValue = c.optDouble("currentValue", 0.0),
                                delta = c.optDouble("delta", 0.0),
                                previewY = c.optDouble("previewY", 0.0),
                                previewZ = c.optDouble("previewZ", 0.0),
                                isStillNow = c.optBoolean("isStillNow", true),
                                clusterSize = c.optInt("clusterSize", 1),
                                axisHint = c.optString("axisHint", "X")
                            )
                        )
                    }
                }

                _uiState.update { state ->
                    state.copy(
                        scanStatus = AsyncScanStatus.COMPLETED,
                        scanStatusDetail = "Round #$round: $totalMatches movement deltas",
                        floatScanner = state.floatScanner.copy(
                            isBusy = false,
                            scanRound = round,
                            totalMatchesFound = totalMatches,
                            scanDurationMs = durationMs,
                            isLivePreviewing = parsed.isNotEmpty(),
                            candidates = parsed,
                            statusMessage = if (parsed.isNotEmpty()) {
                                "Round #$round: $totalMatches matches ($stillCount stopped). Showing top ${parsed.size} live — tap 'Inspect & Lock' on your player X coordinate."
                            } else {
                                "No movement deltas found. Move further in-game and scan again, or Reset Scan."
                            }
                        )
                    )
                }
                appendLog(
                    category = LogCategory.SCAN,
                    summary = "Delta Scan #$round: $totalMatches hits ($stillCount stopped) in ${durationMs}ms",
                    payload = "Top ${parsed.size} live candidates streaming at 200ms"
                )
                true
            }

            "POINTER_SIGNATURE_ANALYZED" -> {
                val baseIdx = obj.optInt("baseIndex", _uiState.value.heapFeed.baseIndex)
                val byteAddr = obj.optLong("byteAddress", baseIdx.toLong() * 4L)
                val aobHex = obj.optString("aobPatternHex", "")
                val floatSummary = obj.optString("floatPatternSummary", "")
                val anchorCount = obj.optInt("anchorCount", 0)
                val sigJson = obj.optString("signatureJson", "")
                val durationMs = obj.optDouble("durationMs", 0.0)

                val ptrArr = obj.optJSONArray("parentPointers") ?: JSONArray()
                val pointers = buildList {
                    for (i in 0 until ptrArr.length()) {
                        val p = ptrArr.optJSONObject(i) ?: continue
                        val pIdx = p.optInt("pointerHeapIndex", -1)
                        if (pIdx < 0) continue
                        add(
                            ParentPointerMatch(
                                pointerHeapIndex = pIdx,
                                pointerByteAddress = p.optLong("pointerByteAddress", pIdx.toLong() * 4L),
                                targetByteAddress = p.optLong("targetByteAddress", byteAddr),
                                fieldOffsetBytes = p.optInt("fieldOffsetBytes", 0),
                                isExactMatch = p.optBoolean("isExactMatch", false)
                            )
                        )
                    }
                }

                val slotsArr = obj.optJSONArray("surroundingSlots") ?: JSONArray()
                val slots = buildList {
                    for (i in 0 until slotsArr.length()) {
                        val s = slotsArr.optJSONObject(i) ?: continue
                        val hIdx = s.optInt("heapIndex", -1)
                        if (hIdx < 0) continue
                        add(
                            MemorySlotPreview(
                                offset = s.optInt("offset", 0),
                                heapIndex = hIdx,
                                byteAddress = s.optLong("byteAddress", hIdx.toLong() * 4L),
                                floatValue = s.optDouble("floatValue", 0.0),
                                uint32Hex = s.optString("uint32Hex", "0x00000000"),
                                isCoordAxis = s.optBoolean("isCoordAxis", false),
                                isAnchor = s.optBoolean("isAnchor", false)
                            )
                        )
                    }
                }

                _uiState.update { state ->
                    state.copy(
                        scanStatus = AsyncScanStatus.COMPLETED,
                        scanStatusDetail = "Analyzed [$baseIdx]: $anchorCount anchors, ${pointers.size} ptrs",
                        heapFeed = state.heapFeed.copy(
                            baseIndex = baseIdx,
                            baseIndexInput = baseIdx.toString(),
                            lockStatus = LockStatus.LOCKED,
                            hasSignature = sigJson.isNotBlank(),
                            signatureAnchorCount = anchorCount,
                            signatureCapturedAtIndex = baseIdx,
                            signatureJson = sigJson.ifBlank { state.heapFeed.signatureJson }
                        ),
                        pointerAnalysis = PointerSignatureAnalysis(
                            inspectedBaseIndex = baseIdx,
                            inspectedByteAddress = byteAddr,
                            isAnalyzing = false,
                            aobPatternHex = aobHex,
                            floatPatternSummary = floatSummary,
                            anchorCount = anchorCount,
                            signatureJson = sigJson.ifBlank { null },
                            parentPointers = pointers,
                            surroundingSlots = slots,
                            analysisDurationMs = durationMs,
                            errorMessage = null
                        )
                    )
                }
                appendLog(
                    category = LogCategory.PTR,
                    summary = "Analyzed [$baseIdx]: $anchorCount AOB/Float anchors & ${pointers.size} HEAPU32 parent pointers (${durationMs}ms)",
                    payload = "AOB: $aobHex\nFloats: $floatSummary"
                )
                true
            }

            "POINTER_SIGNATURE_ERROR" -> {
                val reason = obj.optString("reason", "Pointer & Signature analysis failed.")
                _uiState.update { state ->
                    state.copy(
                        scanStatus = AsyncScanStatus.ERROR,
                        scanStatusDetail = reason,
                        pointerAnalysis = state.pointerAnalysis.copy(
                            isAnalyzing = false,
                            errorMessage = reason
                        )
                    )
                }
                appendLog(LogCategory.ERROR, "Pointer/Signature error", reason)
                _commands.tryEmit(DiagnosticsCommand.ShowToast(reason))
                true
            }

            "RELOCK_COMPLETE" -> {
                val oldIdx = obj.optInt("oldBaseIndex", _uiState.value.heapFeed.baseIndex)
                val newIdx = obj.optInt("newBaseIndex", oldIdx)
                val confidence = obj.optInt("confidencePercent", 0)
                val matched = obj.optInt("matchedAnchors", 0)
                val total = obj.optInt("totalAnchors", 0)
                val method = obj.optString("method", "Signature Scan")
                val nx = obj.optDouble("x", _uiState.value.heapFeed.x)
                val ny = obj.optDouble("y", _uiState.value.heapFeed.y)
                val nz = obj.optDouble("z", _uiState.value.heapFeed.z)
                val durationMs = obj.optDouble("durationMs", 0.0)
                val heapBytes = obj.optLong("heapByteLength", _uiState.value.heapFeed.heapByteLength)
                val heapSource = obj.optString("heapSource", _uiState.value.heapFeed.heapSourceLabel)
                val note = obj.optString("note", "Relocked @ $newIdx ($confidence%)")
                val freshSigJson = obj.optString("signatureJson", "").ifBlank { null }

                // Update persisted baseIndex if a profile is saved
                _uiState.value.savedProfile?.let { existing ->
                    if (existing.baseIndex != newIdx) {
                        val updatedProfile = existing.copy(baseIndex = newIdx)
                        profileStore.saveProfile(updatedProfile)
                        _uiState.update { it.copy(savedProfile = updatedProfile) }
                    }
                }

                _uiState.update { state ->
                    state.copy(
                        scanStatus = AsyncScanStatus.COMPLETED,
                        scanStatusDetail = "LOCKED @ HEAPF32[$newIdx] ($confidence%)",
                        heapFeed = state.heapFeed.copy(
                            baseIndex = newIdx,
                            baseIndexInput = newIdx.toString(),
                            x = if (nx.isFinite()) nx else state.heapFeed.x,
                            y = if (ny.isFinite()) ny else state.heapFeed.y,
                            z = if (nz.isFinite()) nz else state.heapFeed.z,
                            heapByteLength = heapBytes,
                            heapSourceLabel = heapSource,
                            lockStatus = LockStatus.LOCKED,
                            isRelocking = false,
                            isHeapReady = true,
                            hasSignature = freshSigJson != null || state.heapFeed.hasSignature,
                            signatureAnchorCount = if (total > 0) total else state.heapFeed.signatureAnchorCount,
                            signatureJson = freshSigJson ?: state.heapFeed.signatureJson,
                            relockConfidencePercent = confidence,
                            relockMethodUsed = method,
                            lastRelockSummary = "$note • $method • $matched/$total anchors • ${String.format(Locale.US, "%.0f", durationMs)}ms",
                            statusMessage = "LOCKED • $heapSource[$newIdx..${newIdx + 2}] ($confidence%)"
                        )
                    )
                }
                appendLog(
                    category = LogCategory.RELOCK,
                    summary = note,
                    payload = "method=$method | old=$oldIdx -> new=$newIdx | confidence=$confidence% ($matched/$total) | (${fmtCoord(nx)}, ${fmtCoord(ny)}, ${fmtCoord(nz)}) | ${durationMs}ms"
                )
                _commands.tryEmit(DiagnosticsCommand.ShowToast("LOCKED @ HEAPF32[$newIdx] ($confidence%)"))
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
                            lockStatus = LockStatus.UNLOCKED,
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
                        state.copy(isScanningEntities = false, scanStatus = AsyncScanStatus.ERROR, scanStatusDetail = error)
                    }
                    appendLog(LogCategory.ERROR, "Entity scan error", error)
                    _commands.tryEmit(DiagnosticsCommand.ShowToast(error))
                    return true
                }

                val arr = obj.optJSONArray("entities") ?: JSONArray()
                val durationMs = obj.optDouble("durationMs", 0.0)
                val dominantStride = obj.optInt("dominantStride", 0).takeIf { it > 0 }
                val totalCandidates = obj.optInt("totalCandidates", arr.length())
                val pinnedIndices = _uiState.value.discoveredEntities.filter { it.isPinned }.map { it.baseIndex }.toSet()

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
                appendLog(LogCategory.JS, "JS> ${expr.take(72)}", res)
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
            if (state.interceptedAssets.any { it.url == url }) {
                state
            } else {
                isNewUrl = true
                state.copy(interceptedAssets = (listOf(observed) + state.interceptedAssets).take(MAX_ASSETS))
            }
        }

        if (isNewUrl) {
            appendLog(LogCategory.ASSET, "Observed $matchedExt asset ($fileName)", "$method $url")
            if (shouldMirror) {
                val shouldLaunch = synchronized(inFlightAssetUrls) { inFlightAssetUrls.add(url) }
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
                            synchronized(inFlightAssetUrls) { inFlightAssetUrls.remove(url) }
                        }
                    }
                }
            }
        }
    }

    fun onClearAssetCache() {
        viewModelScope.launch(Dispatchers.IO) {
            val removed = BinaryAssetInspector.clearLocalCache(getApplication())
            _uiState.update { state -> state.copy(interceptedAssets = emptyList()) }
            appendLog(LogCategory.ASSET, "Cleared local binary asset cache ($removed files deleted)", "")
            _commands.tryEmit(DiagnosticsCommand.ShowToast("Cleared $removed cached binary files"))
        }
    }

    // -------------------------------------------------------------------------
    // Manual JS Console & Log Filters
    // -------------------------------------------------------------------------

    fun onToggleJsDrawer() {
        _uiState.update { it.copy(isJsDrawerOpen = !it.isJsDrawerOpen) }
    }

    fun onManualJsInputChanged(value: String) {
        _uiState.update { it.copy(manualJsInput = value) }
    }

    fun onExecuteManualScript() {
        val code = _uiState.value.manualJsInput.trim()
        if (code.isEmpty() || !ensurePageLoadedForInspection()) return
        _commands.tryEmit(DiagnosticsCommand.EvaluateJs(WasmHeapScripts.buildManualEvalScript(code)))
    }

    fun onSelectFilter(category: LogCategory) {
        _uiState.update { it.copy(selectedFilter = category) }
    }

    fun onClearLogs() {
        _uiState.update { it.copy(logs = emptyList(), liveStreamHistory = emptyList()) }
    }

    // -------------------------------------------------------------------------
    // Structured Report & Log Dump Exports
    // -------------------------------------------------------------------------

    fun requestExportReport(armBridge: () -> Unit, disarmBridge: () -> Boolean) {
        if (_uiState.value.isExporting) return
        _uiState.update { it.copy(isExporting = true) }

        if (_uiState.value.activeUrl == "about:blank") {
            writeCombinedJsonReport(null)
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
            if (disarmBridge()) writeCombinedJsonReport(null)
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
                    filePrefix = "webview_wasm_diagnostics"
                )
            }
            _uiState.update { it.copy(isExporting = false, lastExport = outcome) }
            if (outcome.success) {
                appendLog(LogCategory.SYSTEM, "Exported JSON report: ${outcome.fileName}", outcome.displayPath)
                _commands.tryEmit(DiagnosticsCommand.ShowToast("Report saved to ${outcome.displayPath}"))
            } else {
                appendLog(LogCategory.ERROR, "Report export failed", outcome.errorMessage.orEmpty())
                _commands.tryEmit(DiagnosticsCommand.ShowToast("Export failed: ${outcome.errorMessage}"))
            }
        }
    }

    fun onShareLastReport() {
        val last = _uiState.value.lastExport
        if (last?.success == true && last.contentUri != null) {
            _commands.tryEmit(
                DiagnosticsCommand.ShareFile(last.contentUri, last.mimeType, "Share Report (${last.fileName})")
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
                    ReportExporter.exportJsonToDownloads(
                        context = getApplication(),
                        jsonContent = buildCombinedReportJson(snapshot, null),
                        filePrefix = "webview_log_dump"
                    )
                } else {
                    ReportExporter.exportTextLogDumpToDownloads(
                        context = getApplication(),
                        textContent = buildPlainTextLogDump(snapshot)
                    )
                }
            }
            _uiState.update { it.copy(isExportingLogDump = false, lastLogDumpExport = outcome) }
            if (outcome.success) {
                appendLog(LogCategory.SYSTEM, "Exported log dump: ${outcome.fileName}", outcome.displayPath)
                _commands.tryEmit(DiagnosticsCommand.ShowToast("Log dump saved to ${outcome.displayPath}"))
            } else {
                appendLog(LogCategory.ERROR, "Log dump export failed", outcome.errorMessage.orEmpty())
                _commands.tryEmit(DiagnosticsCommand.ShowToast("Export failed: ${outcome.errorMessage}"))
            }
        }
    }

    fun onShareLastLogDump() {
        val last = _uiState.value.lastLogDumpExport
        if (last?.success == true && last.contentUri != null) {
            _commands.tryEmit(
                DiagnosticsCommand.ShareFile(last.contentUri, last.mimeType, "Share Log Dump (${last.fileName})")
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

    private fun serializePointersJson(pointers: List<ParentPointerMatch>): String? {
        if (pointers.isEmpty()) return null
        val arr = JSONArray()
        pointers.forEach { ptr ->
            arr.put(
                JSONObject().apply {
                    put("pointerHeapIndex", ptr.pointerHeapIndex)
                    put("pointerByteAddress", ptr.pointerByteAddress)
                    put("targetByteAddress", ptr.targetByteAddress)
                    put("fieldOffsetBytes", ptr.fieldOffsetBytes)
                    put("isExactMatch", ptr.isExactMatch)
                }
            )
        }
        return arr.toString()
    }

    private fun extractHost(url: String): String {
        if (!url.contains("://")) return ""
        return url.substringAfter("://").substringBefore('/').substringBefore('?')
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
        root.put(
            "liveTelemetryFeed",
            JSONObject().apply {
                put("lockStatus", feed.lockStatus.name)
                put("baseIndex", feed.baseIndex)
                put("byteAddressHex", "0x${feed.byteAddress.toString(16).uppercase(Locale.US)}")
                put("heapSource", feed.heapSourceLabel)
                put("x", feed.x)
                put("y", feed.y)
                put("z", feed.z)
                put("tickCount", feed.tickCount)
                put("autoRelockOnReload", feed.autoRelockOnReload)
                put("relockConfidencePercent", feed.relockConfidencePercent)
                put("relockMethodUsed", feed.relockMethodUsed)
            }
        )

        val analysis = state.pointerAnalysis
        root.put(
            "pointerAndSignature",
            JSONObject().apply {
                put("inspectedBaseIndex", analysis.inspectedBaseIndex ?: JSONObject.NULL)
                put("aobPatternHex", analysis.aobPatternHex)
                put("floatPatternSummary", analysis.floatPatternSummary)
                put("anchorCount", analysis.anchorCount)
                put("isSavedInSharedPreferences", state.savedProfile != null)
                val ptrArr = JSONArray()
                analysis.parentPointers.forEach { ptr ->
                    ptrArr.put(
                        JSONObject().apply {
                            put("pointerHeapIndex", ptr.pointerHeapIndex)
                            put("pointerByteAddressHex", "0x${ptr.pointerByteAddress.toString(16).uppercase(Locale.US)}")
                            put("targetByteAddressHex", "0x${ptr.targetByteAddress.toString(16).uppercase(Locale.US)}")
                            put("fieldOffsetBytes", ptr.fieldOffsetBytes)
                            put("isExactMatch", ptr.isExactMatch)
                        }
                    )
                }
                put("parentPointers", ptrArr)
            }
        )

        if (!webViewMetricsJson.isNullOrBlank()) {
            runCatching { JSONObject(webViewMetricsJson) }.getOrNull()?.let {
                root.put("webViewMetrics", it)
            }
        }

        val entitiesArr = JSONArray()
        state.discoveredEntities.forEach { e ->
            entitiesArr.put(
                JSONObject().apply {
                    put("baseIndex", e.baseIndex)
                    put("byteAddressHex", "0x${e.byteAddress.toString(16).uppercase(Locale.US)}")
                    put("offsetFromPlayer", e.offsetFromPlayer)
                    put("x", e.x)
                    put("y", e.y)
                    put("z", e.z)
                    put("distanceToPlayer", e.distanceToPlayer)
                    put("strideGroup", e.strideGroup)
                }
            )
        }
        root.put("discoveredEntities", entitiesArr)

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
        appendLine("WebViewDiagnosticsApp - WASM Memory & Pointer Lock Dump")
        appendLine("============================================================")
        appendLine("Target URL   : ${state.activeUrl}")
        appendLine("Lock Status  : ${feed.lockStatus.label}")
        appendLine("Base Index   : ${feed.baseIndex} (0x${feed.byteAddress.toString(16).uppercase(Locale.US)})")
        appendLine("Live Coords  : X=${fmtCoord(feed.x)}  Y=${fmtCoord(feed.y)}  Z=${fmtCoord(feed.z)}")
        appendLine("AOB Pattern  : ${state.pointerAnalysis.aobPatternHex.ifBlank { "(not analyzed)" }}")
        appendLine("Parent Ptrs  : ${state.pointerAnalysis.parentPointers.size}")
        appendLine("Saved Prefs  : ${if (state.savedProfile != null) "YES" else "NO"}")
        appendLine()
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
            if (trimmed.isEmpty()) return UrlValidationResult.Invalid("Please enter a target URL.")
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
