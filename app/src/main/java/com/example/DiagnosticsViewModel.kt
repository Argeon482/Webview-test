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
import java.net.URI
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * One-shot UI / WebView side effects emitted by [DiagnosticsViewModel].
 */
sealed interface DiagnosticsCommand {
    data class LoadUrl(val url: String) : DiagnosticsCommand
    data object Reload : DiagnosticsCommand
    data class EvaluateJs(val script: String) : DiagnosticsCommand
    data class ShowToast(val message: String) : DiagnosticsCommand
    data class ShareFile(val uri: Uri, val mimeType: String, val title: String) : DiagnosticsCommand
}

/**
 * Central MVVM ViewModel coordinating the embedded WebView, 200ms direct `window.HEAPF32`
 * coordinate feed, Step 1 isolated Float32Array Movement Delta Scanner, Step 2 `±16` Pointer &
 * Signature Analyzer, Step 3 persistent `SharedPreferences` Auto-Relock, nearby `±50,000` Entity
 * Struct Scanner, passive Binary Asset Mirror, and JSON / TXT log exports.
 */
class DiagnosticsViewModel(application: Application) : AndroidViewModel(application) {

    private val lockProfileStore = LockProfileStore(application)
    private val nextLogId = AtomicLong(1L)
    private val observedAssetUrls = ConcurrentHashMap.newKeySet<String>()
    private val pinnedEntityIndices = mutableSetOf<Int>()
    private var pendingExportTimeoutJob: Job? = null
    private var pendingAutoRelockJob: Job? = null

    private val _uiState = MutableStateFlow(buildInitialState())
    val uiState: StateFlow<DiagnosticsUiState> = _uiState.asStateFlow()

    private val _commands = MutableSharedFlow<DiagnosticsCommand>(extraBufferCapacity = 32)
    val commands: SharedFlow<DiagnosticsCommand> = _commands.asSharedFlow()

    private fun buildInitialState(): DiagnosticsUiState {
        val saved = lockProfileStore.loadProfile()
        val autoRelock = lockProfileStore.isAutoRelockEnabled()
        val initialBase = saved?.baseIndex ?: VERIFIED_PLAYER_BASE_INDEX
        val initialFeed = DirectHeapFeedState(
            baseIndex = initialBase,
            baseIndexInput = initialBase.toString(),
            lockStatus = LockStatus.UNLOCKED,
            hasSignature = saved != null,
            signatureAnchorCount = saved?.anchorCount ?: 0,
            signatureCapturedAtIndex = saved?.baseIndex,
            signatureJson = saved?.signatureJson,
            autoRelockOnReload = autoRelock,
            statusMessage = if (saved != null) {
                "Loaded saved lock profile for HEAPF32[${saved.baseIndex}] (${saved.anchorCount} anchors)."
            } else {
                "Ready to scan window.HEAPF32 or lock directly onto [$VERIFIED_PLAYER_BASE_INDEX]."
            }
        )
        return DiagnosticsUiState(
            heapFeed = initialFeed,
            savedProfile = saved
        )
    }

    // -------------------------------------------------------------------------
    // URL Navigation & WebView Lifecycle
    // -------------------------------------------------------------------------

    fun onUrlInputChanged(input: String) {
        _uiState.update { it.copy(urlInput = input) }
    }

    fun onLoadUrlRequested() {
        val raw = _uiState.value.urlInput
        when (val result = normalizeAndValidateUrl(raw)) {
            is UrlValidationResult.Valid -> {
                _uiState.update { it.copy(urlInput = result.normalizedUrl) }
                appendLog(LogCategory.SYSTEM, "Loading ${result.normalizedUrl}", result.normalizedUrl)
                _commands.tryEmit(DiagnosticsCommand.LoadUrl(result.normalizedUrl))
            }
            is UrlValidationResult.Invalid -> {
                appendLog(LogCategory.ERROR, "Invalid URL: ${result.reason}", raw)
                _commands.tryEmit(DiagnosticsCommand.ShowToast(result.reason))
            }
        }
    }

    fun onReloadRequested() {
        pendingAutoRelockJob?.cancel()
        _commands.tryEmit(DiagnosticsCommand.EvaluateJs(WasmHeapScripts.buildResetFloatScanScript()))
        _uiState.update { current ->
            current.copy(
                floatScanner = FloatDeltaScannerState(
                    statusMessage = "Page reloading — stand still and capture a fresh isolated baseline once loaded."
                ),
                heapFeed = current.heapFeed.copy(
                    lockStatus = LockStatus.UNLOCKED,
                    isPolling = false,
                    isHeapReady = false,
                    isRelocking = false
                )
            )
        }
        appendLog(LogCategory.SYSTEM, "Reloading WebView and clearing float baseline", _uiState.value.activeUrl)
        _commands.tryEmit(DiagnosticsCommand.Reload)
    }

    fun onToggleDesktopMode() {
        val next = !_uiState.value.isDesktopMode
        _uiState.update { it.copy(isDesktopMode = next) }
        val modeLabel = if (next) "Desktop User-Agent" else "Mobile User-Agent"
        appendLog(LogCategory.SYSTEM, "Switched to $modeLabel", "Reload page to apply updated User-Agent.")
        _commands.tryEmit(DiagnosticsCommand.ShowToast("$modeLabel enabled — tap Reload to apply"))
    }

    fun onPageStarted(url: String) {
        pendingAutoRelockJob?.cancel()
        _uiState.update { current ->
            current.copy(
                activeUrl = url.ifBlank { current.activeUrl },
                urlInput = if (url.startsWith("http", ignoreCase = true)) url else current.urlInput,
                isLoading = true,
                loadProgress = 10,
                floatScanner = FloatDeltaScannerState(
                    statusMessage = "Stand still, tap '1. Capture Standing Baseline', move in-game, stop, then tap '2. Scan Movement Deltas'."
                ),
                heapFeed = current.heapFeed.copy(
                    lockStatus = LockStatus.UNLOCKED,
                    isPolling = false,
                    isHeapReady = false,
                    isRelocking = false
                )
            )
        }
    }

    fun onPageProgressChanged(progress: Int) {
        _uiState.update { current ->
            current.copy(
                loadProgress = progress.coerceIn(0, 100),
                isLoading = progress < 100
            )
        }
    }

    fun onPageFinished(url: String, title: String) {
        _uiState.update { current ->
            current.copy(
                activeUrl = url.ifBlank { current.activeUrl },
                pageTitle = title,
                isLoading = false,
                loadProgress = 100
            )
        }
        if (url.isBlank() || url.equals("about:blank", ignoreCase = true)) return

        appendLog(LogCategory.SYSTEM, "Page loaded: ${title.ifBlank { url }}", url)

        val state = _uiState.value
        val saved = state.savedProfile
        if (saved != null && state.heapFeed.autoRelockOnReload) {
            pendingAutoRelockJob?.cancel()
            pendingAutoRelockJob = viewModelScope.launch {
                delay(1_200L)
                autoRelockCoordinates(isAutomatic = true)
            }
        }
    }

    fun onWebViewError(description: String, failingUrl: String) {
        _uiState.update { it.copy(isLoading = false) }
        appendLog(LogCategory.ERROR, "WebView error: $description", failingUrl)
    }

    // -------------------------------------------------------------------------
    // Direct 200ms WASM Heap Feed & Step 3 Auto-Relock
    // -------------------------------------------------------------------------

    fun startDirectHeapFeed() {
        val base = _uiState.value.heapFeed.baseIndex
        _uiState.update { current ->
            current.copy(
                heapFeed = current.heapFeed.copy(
                    isPolling = true,
                    statusMessage = "Connecting 200ms direct feed to HEAPF32[$base]…"
                )
            )
        }
        _commands.tryEmit(DiagnosticsCommand.EvaluateJs(WasmHeapScripts.buildStartDirectHeapFeedScript(base)))
    }

    fun pauseDirectHeapFeed() {
        _uiState.update { current ->
            current.copy(
                heapFeed = current.heapFeed.copy(
                    isPolling = false,
                    statusMessage = "Direct 200ms feed paused."
                )
            )
        }
        _commands.tryEmit(DiagnosticsCommand.EvaluateJs(WasmHeapScripts.buildPauseDirectHeapFeedScript()))
    }

    fun onToggleAutoRelockOnReload() {
        val next = !_uiState.value.heapFeed.autoRelockOnReload
        lockProfileStore.setAutoRelockEnabled(next)
        _uiState.update { current ->
            val updatedSaved = current.savedProfile?.copy(autoRelockOnReload = next)
            if (updatedSaved != null) {
                lockProfileStore.saveProfile(updatedSaved)
            }
            current.copy(
                savedProfile = updatedSaved,
                heapFeed = current.heapFeed.copy(autoRelockOnReload = next)
            )
        }
        appendLog(
            LogCategory.RELOCK,
            "Auto-Relock on reload ${if (next) "enabled" else "disabled"}",
            "autoRelockOnReload=$next"
        )
    }

    fun autoRelockCoordinates(isAutomatic: Boolean = false) {
        val state = _uiState.value
        val saved = state.savedProfile
        val signatureJson = saved?.signatureJson
            ?: state.pointerAnalysis.signatureJson
            ?: state.heapFeed.signatureJson

        if (signatureJson.isNullOrBlank()) {
            val msg = "No saved struct signature yet. Run Step 1 Scanner & Step 2 Save & Lock Profile first."
            appendLog(LogCategory.RELOCK, "Auto-Relock skipped (no saved signature)", msg)
            if (!isAutomatic) {
                _commands.tryEmit(DiagnosticsCommand.ShowToast(msg))
            }
            return
        }

        val preferredIndex = saved?.baseIndex ?: state.heapFeed.baseIndex
        val pointers = saved?.parentPointers ?: state.pointerAnalysis.parentPointers
        val pointersJson = serializeParentPointers(pointers)

        _uiState.update { current ->
            current.copy(
                scanStatus = AsyncScanStatus.RUNNING,
                scanStatusDetail = if (isAutomatic) "Auto-relocking after reload…" else "Relocking saved signature…",
                heapFeed = current.heapFeed.copy(
                    lockStatus = LockStatus.RELOCKING,
                    isRelocking = true,
                    statusMessage = "Scanning HEAPU32 / HEAPF32 for saved struct signature…"
                )
            )
        }

        appendLog(
            LogCategory.RELOCK,
            if (isAutomatic) "Triggered automatic relock on page load" else "Triggered manual Auto-Relock",
            "preferredIndex=$preferredIndex, savedPointers=${pointers.size}"
        )

        _commands.tryEmit(
            DiagnosticsCommand.EvaluateJs(
                WasmHeapScripts.buildAutoRelockScript(
                    signatureJson = signatureJson,
                    preferredBaseIndex = preferredIndex,
                    parentPointersJson = pointersJson
                )
            )
        )
    }

    // -------------------------------------------------------------------------
    // Step 1: Movement Float Delta Scanner
    // -------------------------------------------------------------------------

    fun captureStandingBaseline() {
        _uiState.update { current ->
            current.copy(
                scanStatus = AsyncScanStatus.RUNNING,
                scanStatusDetail = "Cloning isolated Float32Array baseline…",
                floatScanner = current.floatScanner.copy(
                    isBusy = true,
                    statusMessage = "Cloning isolated window.HEAPF32 snapshot (read-only)…"
                )
            )
        }
        _commands.tryEmit(DiagnosticsCommand.EvaluateJs(WasmHeapScripts.buildCaptureFloatBaselineScript()))
    }

    fun scanMovementDeltas() {
        if (!_uiState.value.floatScanner.baselineCaptured) {
            _commands.tryEmit(DiagnosticsCommand.ShowToast("Capture Standing Baseline first."))
            return
        }
        _uiState.update { current ->
            current.copy(
                scanStatus = AsyncScanStatus.RUNNING,
                scanStatusDetail = "Scanning movement deltas (0.02..500.0)…",
                floatScanner = current.floatScanner.copy(
                    isBusy = true,
                    statusMessage = "Comparing live HEAPF32 against isolated baseline clone…"
                )
            )
        }
        _commands.tryEmit(DiagnosticsCommand.EvaluateJs(WasmHeapScripts.buildScanMovementDeltasScript()))
    }

    fun resetFloatScan() {
        _commands.tryEmit(DiagnosticsCommand.EvaluateJs(WasmHeapScripts.buildResetFloatScanScript()))
        _uiState.update { current ->
            current.copy(
                floatScanner = FloatDeltaScannerState()
            )
        }
        appendLog(LogCategory.SCAN, "Reset float delta scanner", "Cleared isolated baseline clone and candidate list.")
    }

    fun inspectAndLockCandidate(baseIndex: Int) {
        val safeBase = baseIndex.coerceAtLeast(0)
        _uiState.update { current ->
            current.copy(
                activeConsoleTab = ConsoleTab.ANALYZER,
                scanStatus = AsyncScanStatus.RUNNING,
                scanStatusDetail = "Inspecting ±16 block @ [$safeBase]…",
                heapFeed = current.heapFeed.copy(
                    baseIndex = safeBase,
                    baseIndexInput = safeBase.toString(),
                    lockStatus = LockStatus.LOCKED,
                    isPolling = true,
                    statusMessage = "Locked onto window.HEAPF32[$safeBase..${safeBase + 2}]"
                ),
                pointerAnalysis = current.pointerAnalysis.copy(
                    inspectedBaseIndex = safeBase,
                    inspectedByteAddress = safeBase.toLong() * 4L,
                    isAnalyzing = true,
                    errorMessage = null
                )
            )
        }
        appendLog(
            LogCategory.PTR,
            "Inspecting & locking HEAPF32[$safeBase] (${formatHexAddress(safeBase.toLong() * 4L)})",
            "baseIndex=$safeBase"
        )
        _commands.tryEmit(
            DiagnosticsCommand.EvaluateJs(WasmHeapScripts.buildAnalyzePointerSignatureScript(safeBase))
        )
    }

    // -------------------------------------------------------------------------
    // Step 2: Manual Base Index, Pointer & Signature Lock Profile
    // -------------------------------------------------------------------------

    fun onBaseIndexInputChanged(input: String) {
        val digitsOnly = input.filter { it.isDigit() }.take(10)
        _uiState.update { current ->
            current.copy(heapFeed = current.heapFeed.copy(baseIndexInput = digitsOnly))
        }
    }

    fun onApplyBaseIndex() {
        val parsed = _uiState.value.heapFeed.baseIndexInput.trim().toIntOrNull()
        if (parsed == null || parsed < 0) {
            _commands.tryEmit(DiagnosticsCommand.ShowToast("Enter a valid non-negative HEAPF32 index."))
            return
        }
        _uiState.update { current ->
            current.copy(
                heapFeed = current.heapFeed.copy(
                    baseIndex = parsed,
                    baseIndexInput = parsed.toString(),
                    lockStatus = LockStatus.LOCKED,
                    isPolling = true
                )
            )
        }
        _commands.tryEmit(DiagnosticsCommand.EvaluateJs(WasmHeapScripts.buildStartDirectHeapFeedScript(parsed)))
    }

    fun onResetToVerifiedBaseIndex() {
        _uiState.update { current ->
            current.copy(
                heapFeed = current.heapFeed.copy(
                    baseIndex = VERIFIED_PLAYER_BASE_INDEX,
                    baseIndexInput = VERIFIED_PLAYER_BASE_INDEX.toString(),
                    isPolling = true
                )
            )
        }
        _commands.tryEmit(
            DiagnosticsCommand.EvaluateJs(
                WasmHeapScripts.buildStartDirectHeapFeedScript(VERIFIED_PLAYER_BASE_INDEX)
            )
        )
    }

    fun saveAndLockProfile() {
        val state = _uiState.value
        val analysis = state.pointerAnalysis
        val sigJson = analysis.signatureJson ?: state.heapFeed.signatureJson
        if (sigJson.isNullOrBlank()) {
            _commands.tryEmit(DiagnosticsCommand.ShowToast("Analyze a ±16 block first to capture a signature."))
            return
        }
        val baseIdx = analysis.inspectedBaseIndex ?: state.heapFeed.baseIndex
        val host = extractHost(state.activeUrl)
        val profile = SavedLockProfile(
            baseIndex = baseIdx,
            host = host,
            aobPatternHex = analysis.aobPatternHex,
            floatPatternSummary = analysis.floatPatternSummary,
            anchorCount = analysis.anchorCount.coerceAtLeast(state.heapFeed.signatureAnchorCount),
            signatureJson = sigJson,
            parentPointers = analysis.parentPointers,
            autoRelockOnReload = state.heapFeed.autoRelockOnReload,
            savedAtEpochMs = System.currentTimeMillis()
        )
        lockProfileStore.saveProfile(profile)

        _uiState.update { current ->
            current.copy(
                savedProfile = profile,
                heapFeed = current.heapFeed.copy(
                    baseIndex = baseIdx,
                    baseIndexInput = baseIdx.toString(),
                    lockStatus = LockStatus.LOCKED,
                    hasSignature = true,
                    signatureAnchorCount = profile.anchorCount,
                    signatureCapturedAtIndex = baseIdx,
                    signatureJson = sigJson,
                    statusMessage = "Profile saved & locked @ HEAPF32[$baseIdx] (${profile.anchorCount} anchors)."
                )
            )
        }
        appendLog(
            LogCategory.PTR,
            "Saved persistent lock profile @ [$baseIdx] (${profile.anchorCount} anchors, ${profile.parentPointers.size} ptrs)",
            "host=$host\nAOB=${profile.aobPatternHex}"
        )
        _commands.tryEmit(DiagnosticsCommand.ShowToast("Lock profile saved for auto-relock across reloads"))
    }

    fun clearSavedProfile() {
        lockProfileStore.clearProfile()
        _uiState.update { current ->
            current.copy(
                savedProfile = null,
                heapFeed = current.heapFeed.copy(
                    lockStatus = LockStatus.UNLOCKED,
                    hasSignature = false,
                    signatureAnchorCount = 0,
                    signatureCapturedAtIndex = null,
                    signatureJson = null,
                    lastRelockSummary = "",
                    relockConfidencePercent = 0,
                    relockMethodUsed = "",
                    statusMessage = "Cleared saved lock profile."
                )
            )
        }
        appendLog(LogCategory.PTR, "Cleared saved lock profile from SharedPreferences", "")
        _commands.tryEmit(DiagnosticsCommand.ShowToast("Saved lock profile cleared"))
    }

    // -------------------------------------------------------------------------
    // Nearby Entity Struct Scanner
    // -------------------------------------------------------------------------

    fun scanNearbyEntities() {
        val state = _uiState.value
        val baseIdx = state.heapFeed.baseIndex
        val radius = state.entityScanRadius
        _uiState.update { current ->
            current.copy(
                activeConsoleTab = ConsoleTab.ENTITIES,
                isScanningEntities = true,
                scanStatus = AsyncScanStatus.RUNNING,
                scanStatusDetail = "Scanning ±$radius around [$baseIdx]…"
            )
        }
        _commands.tryEmit(
            DiagnosticsCommand.EvaluateJs(WasmHeapScripts.buildScanNearbyEntitiesScript(baseIdx, radius))
        )
    }

    fun onTogglePinEntity(baseIndex: Int) {
        if (!pinnedEntityIndices.add(baseIndex)) {
            pinnedEntityIndices.remove(baseIndex)
        }
        _uiState.update { current ->
            val updated = current.discoveredEntities.map { entity ->
                if (entity.baseIndex == baseIndex) {
                    entity.copy(isPinned = baseIndex in pinnedEntityIndices)
                } else {
                    entity
                }
            }.sortedWith(
                compareByDescending<NearbyEntityStruct> { it.isPinned }
                    .thenByDescending { it.confidenceScore }
                    .thenBy { it.distanceToPlayer }
            )
            current.copy(discoveredEntities = updated)
        }
    }

    // -------------------------------------------------------------------------
    // Passive Binary Asset Interception & Local Inspection
    // -------------------------------------------------------------------------

    fun onResourceRequestObserved(request: WebResourceRequest) {
        val uri = request.url ?: return
        val fullUrl = uri.toString()
        val pathLower = uri.path?.lowercase(Locale.US).orEmpty()
        val ext = matchTrackedAssetExtension(pathLower) ?: return
        if (!observedAssetUrls.add(fullUrl)) return

        val fileName = BinaryAssetInspector.extractFileName(fullUrl, ext)
        val shouldMirror = BinaryAssetInspector.shouldCaptureLocally(ext)
        val initialAsset = InterceptedAsset(
            url = fullUrl,
            extension = ext,
            method = request.method ?: "GET",
            timestamp = nowTimeLabel(),
            isForMainFrame = request.isForMainFrame,
            fileName = fileName,
            captureStatus = if (shouldMirror) AssetCaptureStatus.CACHING else AssetCaptureStatus.OBSERVED
        )

        _uiState.update { current ->
            val nextAssets = (listOf(initialAsset) + current.interceptedAssets).take(MAX_TRACKED_ASSETS)
            current.copy(interceptedAssets = nextAssets)
        }
        appendLog(LogCategory.ASSET, "Observed $ext asset: $fileName", fullUrl)

        if (shouldMirror) {
            val appCtx = getApplication<Application>()
            val ua = if (_uiState.value.isDesktopMode) DESKTOP_USER_AGENT else "WebViewDiagnosticsApp/1.0"
            val referer = _uiState.value.activeUrl
            viewModelScope.launch(Dispatchers.IO) {
                val inspected = BinaryAssetInspector.fetchSaveAndInspect(
                    context = appCtx,
                    asset = initialAsset,
                    userAgent = ua,
                    refererUrl = referer
                )
                withContext(Dispatchers.Main) {
                    _uiState.update { current ->
                        current.copy(
                            interceptedAssets = current.interceptedAssets.map { item ->
                                if (item.url == inspected.url) inspected else item
                            }
                        )
                    }
                    if (inspected.captureStatus == AssetCaptureStatus.CAPTURED) {
                        appendLog(
                            LogCategory.ASSET,
                            "Cached $fileName (${BinaryAssetInspector.formatBytes(inspected.byteSize)})",
                            inspected.structuralSummary
                        )
                    }
                }
            }
        }
    }

    fun onClearAssetCache() {
        val appCtx = getApplication<Application>()
        viewModelScope.launch(Dispatchers.IO) {
            val deleted = BinaryAssetInspector.clearLocalCache(appCtx)
            withContext(Dispatchers.Main) {
                observedAssetUrls.clear()
                _uiState.update { it.copy(interceptedAssets = emptyList()) }
                appendLog(LogCategory.ASSET, "Cleared local asset cache ($deleted files)", "")
                _commands.tryEmit(DiagnosticsCommand.ShowToast("Cleared $deleted cached assets"))
            }
        }
    }

    // -------------------------------------------------------------------------
    // Console Tabs, UI Toggles, Manual JS & Rolling Logs
    // -------------------------------------------------------------------------

    fun onSelectConsoleTab(tab: ConsoleTab) {
        _uiState.update { it.copy(activeConsoleTab = tab) }
    }

    fun onToggleJsDrawer() {
        _uiState.update { it.copy(isJsDrawerOpen = !it.isJsDrawerOpen) }
    }

    fun onToggleTelemetryStreamExpanded() {
        _uiState.update { it.copy(isTelemetryStreamExpanded = !it.isTelemetryStreamExpanded) }
    }

    fun onToggleWebViewExpanded() {
        _uiState.update { it.copy(isWebViewExpanded = !it.isWebViewExpanded) }
    }

    fun onManualJsInputChanged(script: String) {
        _uiState.update { it.copy(manualJsInput = script) }
    }

    fun onExecuteManualScript() {
        val snippet = _uiState.value.manualJsInput.trim()
        if (snippet.isBlank()) {
            _commands.tryEmit(DiagnosticsCommand.ShowToast("Enter a read-only JS expression to evaluate."))
            return
        }
        appendLog(LogCategory.JS, "Evaluating manual expression", snippet)
        _commands.tryEmit(DiagnosticsCommand.EvaluateJs(WasmHeapScripts.buildManualJsEvalScript(snippet)))
    }

    fun onSelectFilter(category: LogCategory) {
        _uiState.update { it.copy(selectedFilter = category) }
    }

    fun onClearLogs() {
        _uiState.update { it.copy(logs = emptyList(), liveStreamHistory = emptyList()) }
    }

    // -------------------------------------------------------------------------
    // Bridge Callbacks (Main Thread)
    // -------------------------------------------------------------------------

    fun onScanStatusChangedFromBridge(status: AsyncScanStatus, detail: String) {
        _uiState.update { current ->
            current.copy(scanStatus = status, scanStatusDetail = detail)
        }
    }

    fun onDirectHeapCoordsUpdatedFromBridge(
        baseIndex: Int,
        x: Double,
        y: Double,
        z: Double,
        heapByteLength: Long,
        heapSource: String
    ) {
        val line = String.format(
            Locale.US,
            "[%s] [%d] X=%+.3f  Y=%+.3f  Z=%+.3f",
            nowTimeLabel(),
            baseIndex,
            x,
            y,
            z
        )
        _uiState.update { current ->
            val updatedHistory = (listOf(line) + current.liveStreamHistory).take(MAX_LIVE_STREAM_LINES)
            val resolvedLockStatus = when (current.heapFeed.lockStatus) {
                LockStatus.RELOCKING -> LockStatus.LOCKED
                else -> current.heapFeed.lockStatus
            }
            current.copy(
                liveStreamHistory = updatedHistory,
                heapFeed = current.heapFeed.copy(
                    baseIndex = baseIndex,
                    heapSourceLabel = heapSource,
                    heapByteLength = heapByteLength,
                    lockStatus = resolvedLockStatus,
                    isPolling = true,
                    isHeapReady = true,
                    x = x,
                    y = y,
                    z = z,
                    tickCount = current.heapFeed.tickCount + 1,
                    lastUpdatedMs = System.currentTimeMillis()
                )
            )
        }
    }

    fun onFloatDeltaPreviewFromBridge(jsonPayload: String) {
        val updatesMap = parsePreviewUpdates(jsonPayload)
        if (updatesMap.isEmpty()) return
        _uiState.update { current ->
            if (current.floatScanner.candidates.isEmpty()) return@update current
            val updatedCandidates = current.floatScanner.candidates.map { candidate ->
                val upd = updatesMap[candidate.heapIndex] ?: return@map candidate
                candidate.copy(
                    currentValue = upd.currentValue,
                    delta = upd.delta,
                    previewY = upd.previewY,
                    previewZ = upd.previewZ,
                    isStillNow = upd.isStillNow,
                    liveChangeCount = upd.liveChangeCount
                )
            }
            current.copy(
                floatScanner = current.floatScanner.copy(
                    isLivePreviewing = true,
                    candidates = updatedCandidates
                )
            )
        }
    }

    fun onTelemetryFromBridge(category: String, payload: String) {
        val parsedCategory = LogCategory.fromRaw(category)
        val trimmed = payload.trim()
        if (trimmed.startsWith("{")) {
            val handled = runCatching { handleStructuredBridgeJson(parsedCategory, JSONObject(trimmed)) }
                .getOrDefault(false)
            if (handled) return
        }
        appendLog(parsedCategory, trimmed.lineSequence().firstOrNull().orEmpty().take(90), trimmed)
    }

    private fun handleStructuredBridgeJson(category: LogCategory, obj: JSONObject): Boolean {
        return when (obj.optString("type")) {
            "FEED_STATUS" -> {
                val active = obj.optBoolean("active", false)
                val ready = obj.optBoolean("heapReady", false)
                val baseIdx = obj.optInt("baseIndex", _uiState.value.heapFeed.baseIndex)
                val msg = obj.optString("message", "")
                val heapSource = obj.optString("heapSource", _uiState.value.heapFeed.heapSourceLabel)
                val byteLen = obj.optLong("heapByteLength", _uiState.value.heapFeed.heapByteLength)
                _uiState.update { current ->
                    current.copy(
                        heapFeed = current.heapFeed.copy(
                            baseIndex = baseIdx,
                            heapSourceLabel = heapSource,
                            heapByteLength = byteLen,
                            isPolling = active,
                            isHeapReady = ready,
                            statusMessage = msg.ifBlank { current.heapFeed.statusMessage }
                        )
                    )
                }
                appendLog(LogCategory.FEED, msg.ifBlank { "Feed state updated" }, obj.toString())
                true
            }

            "FLOAT_BASELINE_CAPTURED" -> {
                val success = obj.optBoolean("success", false)
                if (!success) {
                    val err = obj.optString("error", "Baseline capture failed")
                    _uiState.update { current ->
                        current.copy(
                            floatScanner = current.floatScanner.copy(
                                isBusy = false,
                                baselineCaptured = false,
                                statusMessage = err
                            )
                        )
                    }
                    appendLog(LogCategory.ERROR, "Baseline failed: $err", obj.toString())
                    _commands.tryEmit(DiagnosticsCommand.ShowToast(err))
                } else {
                    val sampled = obj.optInt("sampledCount", 0)
                    val jitter = obj.optInt("prunedJitterCount", 0)
                    val method = obj.optString("cloneMethod", "buffer.slice")
                    val isolated = obj.optBoolean("isIsolatedClone", true)
                    val durationMs = obj.optDouble("durationMs", 0.0)
                    val msg = "Isolated baseline cloned ($sampled floats via $method, ${String.format(Locale.US, "%.0f", durationMs)}ms). Walk 5+ steps, stop, then tap '2. Scan Movement Deltas'."
                    _uiState.update { current ->
                        current.copy(
                            floatScanner = current.floatScanner.copy(
                                isBusy = false,
                                baselineCaptured = true,
                                baselineSampleCount = sampled,
                                prunedJitterCount = jitter,
                                scanRound = 0,
                                totalMatchesFound = 0,
                                isLivePreviewing = false,
                                candidates = emptyList(),
                                statusMessage = msg
                            )
                        )
                    }
                    appendLog(
                        LogCategory.SCAN,
                        "Captured isolated baseline ($sampled floats, isolated=$isolated)",
                        obj.toString()
                    )
                }
                true
            }

            "FLOAT_DELTA_SCANNED" -> {
                val success = obj.optBoolean("success", false)
                if (!success) {
                    val err = obj.optString("error", "Delta scan failed")
                    _uiState.update { current ->
                        current.copy(
                            floatScanner = current.floatScanner.copy(isBusy = false, statusMessage = err)
                        )
                    }
                    appendLog(LogCategory.ERROR, "Movement delta scan error: $err", obj.toString())
                } else {
                    val totalFound = obj.optInt("totalMatchesFound", 0)
                    val round = obj.optInt("scanRound", 1)
                    val durationMs = obj.optDouble("durationMs", 0.0)
                    val livePreview = obj.optBoolean("isLivePreviewing", false)
                    val fallback = obj.optBoolean("usedFallbackFullScan", false)
                    val arr = obj.optJSONArray("candidates") ?: JSONArray()
                    val parsed = buildList {
                        for (i in 0 until arr.length()) {
                            val item = arr.optJSONObject(i) ?: continue
                            val idx = item.optInt("heapIndex", -1)
                            if (idx < 0) continue
                            add(
                                FloatDeltaCandidate(
                                    heapIndex = idx,
                                    byteAddress = item.optLong("byteAddress", idx.toLong() * 4L),
                                    baselineValue = item.optDouble("baselineValue", 0.0),
                                    currentValue = item.optDouble("currentValue", 0.0),
                                    delta = item.optDouble("delta", 0.0),
                                    previewY = item.optDouble("previewY", 0.0),
                                    previewZ = item.optDouble("previewZ", 0.0),
                                    isStillNow = item.optBoolean("isStillNow", true),
                                    clusterSize = item.optInt("clusterSize", 1),
                                    axisHint = item.optString("axisHint", "X"),
                                    liveChangeCount = item.optInt("liveChangeCount", 0)
                                )
                            )
                        }
                    }
                    val statusMsg = if (parsed.isEmpty()) {
                        "0 deltas in range 0.02..500.0. Walk further in-game or capture a fresh Standing Baseline."
                    } else {
                        "Round #$round: ${parsed.size} ranked from $totalFound hits (${String.format(Locale.US, "%.0f", durationMs)}ms" +
                            (if (fallback) ", full-clone fallback" else "") +
                            "). Tap any candidate to inspect ±16 struct & lock."
                    }
                    _uiState.update { current ->
                        current.copy(
                            floatScanner = current.floatScanner.copy(
                                isBusy = false,
                                scanRound = round,
                                totalMatchesFound = totalFound,
                                scanDurationMs = durationMs,
                                isLivePreviewing = livePreview,
                                candidates = parsed,
                                statusMessage = statusMsg
                            )
                        )
                    }
                    appendLog(
                        LogCategory.SCAN,
                        "Delta scan #$round: $totalFound matches (${parsed.size} ranked)",
                        statusMsg
                    )
                }
                true
            }

            "POINTER_SIGNATURE_ANALYZED" -> {
                val success = obj.optBoolean("success", false)
                if (!success) {
                    val err = obj.optString("error", "Signature analysis failed")
                    _uiState.update { current ->
                        current.copy(
                            pointerAnalysis = current.pointerAnalysis.copy(
                                isAnalyzing = false,
                                errorMessage = err
                            )
                        )
                    }
                    appendLog(LogCategory.ERROR, "Pointer/Signature error: $err", obj.toString())
                } else {
                    val baseIdx = obj.optInt("baseIndex", _uiState.value.heapFeed.baseIndex)
                    val byteAddr = obj.optLong("byteAddress", baseIdx.toLong() * 4L)
                    val aobHex = obj.optString("aobPatternHex", "")
                    val floatSummary = obj.optString("floatPatternSummary", "")
                    val anchorCount = obj.optInt("anchorCount", 0)
                    val sigJson = obj.optString("signatureJson", "").ifBlank { null }
                    val durationMs = obj.optDouble("analysisDurationMs", 0.0)

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
                                    targetByteAddress = p.optLong("targetByteAddress", 0L),
                                    fieldOffsetBytes = p.optInt("fieldOffsetBytes", 0),
                                    isExactMatch = p.optBoolean("isExactMatch", false)
                                )
                            )
                        }
                    }

                    val slotArr = obj.optJSONArray("surroundingSlots") ?: JSONArray()
                    val slots = buildList {
                        for (i in 0 until slotArr.length()) {
                            val s = slotArr.optJSONObject(i) ?: continue
                            add(
                                MemorySlotPreview(
                                    offset = s.optInt("offset", 0),
                                    heapIndex = s.optInt("heapIndex", 0),
                                    byteAddress = s.optLong("byteAddress", 0L),
                                    floatValue = s.optDouble("floatValue", 0.0),
                                    uint32Hex = s.optString("uint32Hex", "0x00000000"),
                                    isCoordAxis = s.optBoolean("isCoordAxis", false),
                                    isAnchor = s.optBoolean("isAnchor", false)
                                )
                            )
                        }
                    }

                    _uiState.update { current ->
                        current.copy(
                            pointerAnalysis = PointerSignatureAnalysis(
                                inspectedBaseIndex = baseIdx,
                                inspectedByteAddress = byteAddr,
                                isAnalyzing = false,
                                aobPatternHex = aobHex,
                                floatPatternSummary = floatSummary,
                                anchorCount = anchorCount,
                                signatureJson = sigJson,
                                parentPointers = pointers,
                                surroundingSlots = slots,
                                analysisDurationMs = durationMs,
                                errorMessage = null
                            ),
                            heapFeed = current.heapFeed.copy(
                                baseIndex = baseIdx,
                                baseIndexInput = baseIdx.toString(),
                                lockStatus = LockStatus.LOCKED,
                                isPolling = true,
                                hasSignature = sigJson != null,
                                signatureAnchorCount = anchorCount,
                                signatureCapturedAtIndex = baseIdx,
                                signatureJson = sigJson
                            )
                        )
                    }
                    appendLog(
                        LogCategory.PTR,
                        "Analyzed [$baseIdx]: $anchorCount anchors, ${pointers.size} parent ptrs (${String.format(Locale.US, "%.0f", durationMs)}ms)",
                        "AOB: $aobHex\nFloats: $floatSummary"
                    )
                }
                true
            }

            "AUTO_RELOCK_RESULT" -> {
                val success = obj.optBoolean("success", false)
                val confidence = obj.optInt("confidencePercent", 0)
                val durationMs = obj.optDouble("durationMs", 0.0)
                if (!success) {
                    val err = obj.optString("error", "Auto-relock did not find a match.")
                    _uiState.update { current ->
                        current.copy(
                            heapFeed = current.heapFeed.copy(
                                lockStatus = LockStatus.UNLOCKED,
                                isRelocking = false,
                                relockConfidencePercent = confidence,
                                lastRelockSummary = err,
                                statusMessage = err
                            )
                        )
                    }
                    appendLog(LogCategory.RELOCK, "Auto-Relock missed ($confidence%)", err)
                } else {
                    val matchedBase = obj.optInt("matchedBaseIndex", _uiState.value.heapFeed.baseIndex)
                    val method = obj.optString("methodUsed", "Signature Scan")
                    val x = obj.optDouble("x", 0.0)
                    val y = obj.optDouble("y", 0.0)
                    val z = obj.optDouble("z", 0.0)
                    val byteLen = obj.optLong("heapByteLength", _uiState.value.heapFeed.heapByteLength)
                    val source = obj.optString("heapSource", _uiState.value.heapFeed.heapSourceLabel)
                    val summary = "Relocked [$matchedBase] via $method ($confidence% in ${String.format(Locale.US, "%.0f", durationMs)}ms)"
                    _uiState.update { current ->
                        current.copy(
                            heapFeed = current.heapFeed.copy(
                                baseIndex = matchedBase,
                                baseIndexInput = matchedBase.toString(),
                                heapSourceLabel = source,
                                heapByteLength = byteLen,
                                lockStatus = LockStatus.LOCKED,
                                isPolling = true,
                                isHeapReady = true,
                                isRelocking = false,
                                x = x,
                                y = y,
                                z = z,
                                lastRelockSummary = summary,
                                relockConfidencePercent = confidence,
                                relockMethodUsed = method,
                                statusMessage = summary
                            )
                        )
                    }
                    appendLog(LogCategory.RELOCK, summary, obj.toString())
                    _commands.tryEmit(DiagnosticsCommand.ShowToast("Relocked @ HEAPF32[$matchedBase] ($confidence%)"))
                }
                true
            }

            "NEARBY_ENTITIES_SCANNED" -> {
                val success = obj.optBoolean("success", false)
                if (!success) {
                    val err = obj.optString("error", "Entity scan failed")
                    _uiState.update { it.copy(isScanningEntities = false) }
                    appendLog(LogCategory.ERROR, "Entity scan error: $err", obj.toString())
                } else {
                    val durationMs = obj.optDouble("durationMs", 0.0)
                    val stride = if (obj.isNull("detectedStride")) null else obj.optInt("detectedStride", 0).takeIf { it > 0 }
                    val arr = obj.optJSONArray("entities") ?: JSONArray()
                    val entities = buildList {
                        for (i in 0 until arr.length()) {
                            val e = arr.optJSONObject(i) ?: continue
                            val idx = e.optInt("baseIndex", -1)
                            if (idx < 0) continue
                            add(
                                NearbyEntityStruct(
                                    baseIndex = idx,
                                    byteAddress = e.optLong("byteAddress", idx.toLong() * 4L),
                                    offsetFromPlayer = e.optInt("offsetFromPlayer", 0),
                                    x = e.optDouble("x", 0.0),
                                    y = e.optDouble("y", 0.0),
                                    z = e.optDouble("z", 0.0),
                                    distanceToPlayer = e.optDouble("distanceToPlayer", 0.0),
                                    strideGroup = e.optInt("strideGroup", 0),
                                    confidenceScore = e.optInt("confidenceScore", 0),
                                    isPinned = idx in pinnedEntityIndices
                                )
                            )
                        }
                    }.sortedWith(
                        compareByDescending<NearbyEntityStruct> { it.isPinned }
                            .thenByDescending { it.confidenceScore }
                            .thenBy { it.distanceToPlayer }
                    )

                    _uiState.update { current ->
                        current.copy(
                            isScanningEntities = false,
                            entityScanDurationMs = durationMs,
                            entityDetectedStride = stride,
                            discoveredEntities = entities
                        )
                    }
                    appendLog(
                        LogCategory.ENTITY,
                        "Scanned ${entities.size} nearby structs" + (stride?.let { " (stride=$it)" } ?: ""),
                        "duration=${String.format(Locale.US, "%.0f", durationMs)}ms"
                    )
                }
                true
            }

            "MANUAL_EVAL" -> {
                val success = obj.optBoolean("success", false)
                if (success) {
                    val res = obj.optString("result", "")
                    appendLog(LogCategory.JS, "Eval => ${res.lineSequence().firstOrNull().orEmpty().take(72)}", res)
                } else {
                    val err = obj.optString("error", "Unknown JS eval error")
                    appendLog(LogCategory.ERROR, "Eval error: $err", err)
                }
                true
            }

            else -> {
                appendLog(category, obj.optString("message", obj.toString().take(90)), obj.toString())
                true
            }
        }
    }

    // -------------------------------------------------------------------------
    // Export Reports & Full Log Dumps
    // -------------------------------------------------------------------------

    fun requestExportReport(
        armBridge: () -> Unit,
        disarmBridge: () -> Boolean
    ) {
        if (_uiState.value.isExporting) return
        _uiState.update { it.copy(isExporting = true) }
        armBridge()
        _commands.tryEmit(DiagnosticsCommand.EvaluateJs(WasmHeapScripts.buildCollectViewportMetricsScript()))

        pendingExportTimeoutJob?.cancel()
        pendingExportTimeoutJob = viewModelScope.launch {
            delay(2_500L)
            if (disarmBridge()) {
                val fallbackMetrics = JSONObject().put("note", "WebView did not respond within timeout; exported native state only.").toString()
                onReportPayloadFromBridge(fallbackMetrics)
            }
        }
    }

    fun onReportPayloadFromBridge(jsonContent: String) {
        pendingExportTimeoutJob?.cancel()
        val snapshot = _uiState.value
        val appCtx = getApplication<Application>()

        viewModelScope.launch(Dispatchers.IO) {
            val fullReport = buildDiagnosticReportJson(snapshot, jsonContent)
            val outcome = ReportExporter.exportJsonToDownloads(
                context = appCtx,
                jsonContent = fullReport,
                filePrefix = "webview_diagnostics"
            )
            withContext(Dispatchers.Main) {
                _uiState.update { it.copy(isExporting = false, lastExport = outcome) }
                if (outcome.success) {
                    appendLog(LogCategory.SYSTEM, "Exported report: ${outcome.displayPath}", outcome.fileName)
                    _commands.tryEmit(
                        DiagnosticsCommand.ShowToast(
                            appCtx.getString(R.string.toast_report_saved, outcome.displayPath)
                        )
                    )
                } else {
                    val err = outcome.errorMessage ?: "Export error"
                    appendLog(LogCategory.ERROR, "Report export failed", err)
                    _commands.tryEmit(
                        DiagnosticsCommand.ShowToast(appCtx.getString(R.string.toast_report_failed, err))
                    )
                }
            }
        }
    }

    fun onShareLastReport() {
        val export = _uiState.value.lastExport ?: return
        val uri = export.contentUri ?: return
        val title = getApplication<Application>().getString(R.string.share_report_chooser_title)
        _commands.tryEmit(DiagnosticsCommand.ShareFile(uri, export.mimeType, title))
    }

    fun exportFullLogDump(asJson: Boolean) {
        if (_uiState.value.isExportingLogDump) return
        val snapshot = _uiState.value
        val appCtx = getApplication<Application>()
        _uiState.update { it.copy(isExportingLogDump = true) }

        viewModelScope.launch(Dispatchers.IO) {
            val outcome = if (asJson) {
                val jsonDump = buildDiagnosticReportJson(snapshot, null)
                ReportExporter.exportJsonToDownloads(
                    context = appCtx,
                    jsonContent = jsonDump,
                    filePrefix = "webview_log_dump"
                )
            } else {
                val textDump = buildFullLogDumpText(snapshot)
                ReportExporter.exportTextLogDumpToDownloads(
                    context = appCtx,
                    textContent = textDump
                )
            }

            withContext(Dispatchers.Main) {
                _uiState.update { it.copy(isExportingLogDump = false, lastLogDumpExport = outcome) }
                if (outcome.success) {
                    appendLog(LogCategory.SYSTEM, "Exported log dump: ${outcome.displayPath}", outcome.fileName)
                    _commands.tryEmit(
                        DiagnosticsCommand.ShowToast(
                            appCtx.getString(R.string.toast_log_dump_saved, outcome.displayPath)
                        )
                    )
                } else {
                    val err = outcome.errorMessage ?: "Export error"
                    appendLog(LogCategory.ERROR, "Log dump export failed", err)
                    _commands.tryEmit(
                        DiagnosticsCommand.ShowToast(appCtx.getString(R.string.toast_report_failed, err))
                    )
                }
            }
        }
    }

    fun onShareLastLogDump() {
        val export = _uiState.value.lastLogDumpExport ?: return
        val uri = export.contentUri ?: return
        val title = getApplication<Application>().getString(R.string.share_report_chooser_title)
        _commands.tryEmit(DiagnosticsCommand.ShareFile(uri, export.mimeType, title))
    }

    // -------------------------------------------------------------------------
    // Internal Helpers
    // -------------------------------------------------------------------------

    private data class CandidatePreviewUpdate(
        val currentValue: Double,
        val delta: Double,
        val previewY: Double,
        val previewZ: Double,
        val isStillNow: Boolean,
        val liveChangeCount: Int
    )

    private fun parsePreviewUpdates(jsonPayload: String): Map<Int, CandidatePreviewUpdate> {
        return runCatching {
            val root = JSONObject(jsonPayload)
            val arr = root.optJSONArray("updates") ?: return emptyMap()
            val map = HashMap<Int, CandidatePreviewUpdate>(arr.length())
            for (i in 0 until arr.length()) {
                val u = arr.optJSONObject(i) ?: continue
                val idx = u.optInt("heapIndex", -1)
                if (idx < 0) continue
                map[idx] = CandidatePreviewUpdate(
                    currentValue = u.optDouble("currentValue", 0.0),
                    delta = u.optDouble("delta", 0.0),
                    previewY = u.optDouble("previewY", 0.0),
                    previewZ = u.optDouble("previewZ", 0.0),
                    isStillNow = u.optBoolean("isStillNow", true),
                    liveChangeCount = u.optInt("liveChangeCount", 0)
                )
            }
            map
        }.getOrDefault(emptyMap())
    }

    private fun serializeParentPointers(pointers: List<ParentPointerMatch>): String {
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

    private fun buildDiagnosticReportJson(
        state: DiagnosticsUiState,
        webViewMetricsJson: String?
    ): String {
        val root = JSONObject()
        root.put("exportedAtEpochMs", System.currentTimeMillis())
        root.put("activeUrl", state.activeUrl)
        root.put("pageTitle", state.pageTitle)
        root.put("desktopMode", state.isDesktopMode)

        if (!webViewMetricsJson.isNullOrBlank()) {
            runCatching { root.put("webViewMetrics", JSONObject(webViewMetricsJson)) }
        }

        val feed = state.heapFeed
        root.put(
            "directHeapFeed",
            JSONObject().apply {
                put("baseIndex", feed.baseIndex)
                put("byteAddressHex", formatHexAddress(feed.byteAddress))
                put("heapSource", feed.heapSourceLabel)
                put("heapByteLength", feed.heapByteLength)
                put("lockStatus", feed.lockStatus.name)
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
            "pointerSignature",
            JSONObject().apply {
                put("inspectedBaseIndex", analysis.inspectedBaseIndex)
                put("aobPatternHex", analysis.aobPatternHex)
                put("floatPatternSummary", analysis.floatPatternSummary)
                put("anchorCount", analysis.anchorCount)
                put("parentPointerCount", analysis.parentPointers.size)
            }
        )

        val entitiesArr = JSONArray()
        state.discoveredEntities.forEach { e ->
            entitiesArr.put(
                JSONObject().apply {
                    put("baseIndex", e.baseIndex)
                    put("byteAddressHex", formatHexAddress(e.byteAddress))
                    put("offsetFromPlayer", e.offsetFromPlayer)
                    put("x", e.x)
                    put("y", e.y)
                    put("z", e.z)
                    put("distanceToPlayer", e.distanceToPlayer)
                    put("strideGroup", e.strideGroup)
                    put("isPinned", e.isPinned)
                }
            )
        }
        root.put("nearbyEntities", entitiesArr)

        val assetsArr = JSONArray()
        state.interceptedAssets.forEach { a ->
            assetsArr.put(
                JSONObject().apply {
                    put("url", a.url)
                    put("fileName", a.fileName)
                    put("extension", a.extension)
                    put("captureStatus", a.captureStatus.name)
                    put("byteSize", a.byteSize)
                    put("sha256Prefix", a.sha256Prefix)
                    put("magicSignature", a.magicSignature)
                    put("structuralSummary", a.structuralSummary)
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

    private fun appendLog(category: LogCategory, summary: String, payload: String) {
        val entry = TelemetryLogEntry(
            id = nextLogId.getAndIncrement(),
            timestamp = nowTimeLabel(),
            epochMs = System.currentTimeMillis(),
            category = category,
            summary = summary.ifBlank { category.label },
            payload = payload
        )
        _uiState.update { current ->
            val updated = (listOf(entry) + current.logs).take(MAX_LOG_ENTRIES)
            current.copy(logs = updated)
        }
    }

    private fun extractHost(url: String): String {
        return runCatching { URI(url).host.orEmpty() }.getOrDefault("")
    }

    private fun nowTimeLabel(): String {
        return SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
    }

    companion object {
        private const val MAX_LOG_ENTRIES = 350
        private const val MAX_TRACKED_ASSETS = 120
        private const val MAX_LIVE_STREAM_LINES = 60

        const val DESKTOP_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

        private val TRACKED_ASSET_EXTENSIONS = listOf(
            ".wasm", ".bin", ".json", ".glb", ".gltf", ".data", ".mem", ".pak", ".pck", ".atlas"
        )

        fun createShareIntent(uri: Uri, mimeType: String, title: String): Intent {
            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                type = mimeType
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            return Intent.createChooser(sendIntent, title).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }

        fun normalizeAndValidateUrl(rawInput: String): UrlValidationResult {
            val trimmed = rawInput.trim()
            if (trimmed.isEmpty()) {
                return UrlValidationResult.Invalid("Please enter a target URL.")
            }
            if (trimmed.any { it.isWhitespace() || it.isISOControl() }) {
                return UrlValidationResult.Invalid("URL cannot contain spaces or control characters.")
            }

            val lower = trimmed.lowercase(Locale.US)
            if (lower.startsWith("javascript:") ||
                lower.startsWith("file:") ||
                lower.startsWith("content:") ||
                lower.startsWith("data:")
            ) {
                return UrlValidationResult.Invalid("Blocked scheme. Only http:// and https:// URLs are permitted.")
            }

            val withScheme = if (lower.startsWith("http://") || lower.startsWith("https://")) {
                trimmed
            } else {
                "https://$trimmed"
            }

            return try {
                val parsed = URI(withScheme)
                val scheme = parsed.scheme?.lowercase(Locale.US)
                val host = parsed.host
                if ((scheme != "https" && scheme != "http") || host.isNullOrBlank()) {
                    UrlValidationResult.Invalid("Enter a valid http:// or https:// host.")
                } else {
                    UrlValidationResult.Valid(parsed.toASCIIString())
                }
            } catch (_: Exception) {
                UrlValidationResult.Invalid("Malformed URL syntax.")
            }
        }

        fun isAllowedNavigationUri(uri: Uri): Boolean {
            val scheme = uri.scheme?.lowercase(Locale.US) ?: return false
            return (scheme == "https" || scheme == "http") && !uri.host.isNullOrBlank()
        }

        fun matchTrackedAssetExtension(pathLower: String): String? {
            val clean = pathLower.substringBefore('?').substringBefore('#').trim()
            if (clean.isEmpty()) return null
            return TRACKED_ASSET_EXTENSIONS.firstOrNull { ext -> clean.endsWith(ext) }
        }

        fun formatHexAddress(byteAddress: Long): String {
            val safe = byteAddress.coerceAtLeast(0L)
            return "0x${safe.toString(16).uppercase(Locale.US)}"
        }

        fun buildFullLogDumpText(state: DiagnosticsUiState): String {
            val feed = state.heapFeed
            return buildString {
                appendLine("=== WebViewDiagnosticsApp Full Telemetry Dump ===")
                appendLine("Active URL      : ${state.activeUrl}")
                appendLine("Page Title      : ${state.pageTitle}")
                appendLine("Lock Status     : ${feed.lockStatus.label}")
                appendLine("HEAPF32 Base    : [${feed.baseIndex}] (${formatHexAddress(feed.byteAddress)})")
                appendLine(
                    String.format(
                        Locale.US,
                        "Live Coords     : X=%.3f  Y=%.3f  Z=%.3f (ticks=%d)",
                        feed.x,
                        feed.y,
                        feed.z,
                        feed.tickCount
                    )
                )
                if (state.pointerAnalysis.aobPatternHex.isNotBlank()) {
                    appendLine("AOB Signature   : ${state.pointerAnalysis.aobPatternHex}")
                    appendLine("Parent Pointers : ${state.pointerAnalysis.parentPointers.size}")
                }
                appendLine("Nearby Entities : ${state.discoveredEntities.size}")
                appendLine("Tracked Assets  : ${state.interceptedAssets.size} (cached=${state.cachedAssetCount})")
                appendLine("Log Entries     : ${state.logs.size}")
                appendLine()
                appendLine("--- Rolling Telemetry Logs ---")
                state.logs.forEach { entry ->
                    appendLine("[${entry.timestamp}] [${entry.category.tag}] ${entry.summary}")
                    if (entry.payload.isNotBlank() && entry.payload != entry.summary) {
                        appendLine("    ${entry.payload.replace("\n", "\n    ")}")
                    }
                }
            }.trimEnd()
        }
    }
}
