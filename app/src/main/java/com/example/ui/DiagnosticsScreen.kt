package com.example.ui

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.UnfoldLess
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.DiagnosticsCommand
import com.example.DiagnosticsUiState
import com.example.DiagnosticsViewModel
import com.example.LogCategory
import com.example.R
import com.example.TelemetryBridge
import com.example.ui.theme.AssetAmber
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.ObsidianBg
import com.example.ui.theme.ObsidianBorder
import com.example.ui.theme.ObsidianSurface
import com.example.ui.theme.ObsidianSurfaceElevated
import com.example.ui.theme.OnElectricCyan
import com.example.ui.theme.SignalEmerald
import com.example.ui.theme.TelemetryViolet
import kotlinx.coroutines.flow.collectLatest
import java.util.Locale

/**
 * Main mobile-first adaptive diagnostics interface.
 *
 * Layout hierarchy:
 * 1. Compact URL Navigation Bar (top)
 * 2. Prominent Top Live Coordinate HUD reading `window.HEAPF32[581246..581248]` @ 200ms
 * 3. Scrollable Parent Body (portrait) or Side-by-Side Split (wide >= 840dp):
 *    - Interactive WebView Card (expandable height toggle)
 *    - Permanent High-Contrast Primary Action Bar (`Relock Coordinates`, `Scan Entities`, `Export Report`)
 *    - Tabbed Console Deck (`Controls`, `Entities`, `Assets`, `Logs` + Manual JS Drawer)
 * 4. Fixed, Collapsible Live Telemetry Stream Card anchored at the bottom (isolated from buttons)
 */
@Composable
fun DiagnosticsScreen(
    viewModel: DiagnosticsViewModel = viewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    var webViewRef by remember { mutableStateOf<WebView?>(null) }

    val telemetryBridge = remember(viewModel) {
        TelemetryBridge(
            onTelemetryReceived = { category, payload ->
                viewModel.onTelemetryFromBridge(category, payload)
            },
            onReportPayloadReceived = { jsonContent ->
                viewModel.onReportPayloadFromBridge(jsonContent)
            },
            onScanStatusChanged = { status, detail ->
                viewModel.onScanStatusChangedFromBridge(status, detail)
            },
            onDirectHeapCoordsUpdated = { baseIndex, x, y, z, heapByteLength, heapSource ->
                viewModel.onDirectHeapCoordsUpdatedFromBridge(
                    baseIndex = baseIndex,
                    x = x,
                    y = y,
                    z = z,
                    heapByteLength = heapByteLength,
                    heapSource = heapSource
                )
            }
        )
    }

    LaunchedEffect(viewModel, context) {
        viewModel.commands.collectLatest { command ->
            when (command) {
                is DiagnosticsCommand.LoadUrl -> {
                    webViewRef?.loadUrl(command.url)
                }

                DiagnosticsCommand.Reload -> {
                    webViewRef?.reload()
                }

                is DiagnosticsCommand.EvaluateJs -> {
                    webViewRef?.evaluateJavascript(command.script, null)
                }

                is DiagnosticsCommand.ShowToast -> {
                    Toast.makeText(context, command.message, Toast.LENGTH_SHORT).show()
                }

                is DiagnosticsCommand.ShareFile -> {
                    val intent = DiagnosticsViewModel.createShareIntent(
                        uri = command.uri,
                        mimeType = command.mimeType,
                        title = command.title
                    )
                    runCatching { context.startActivity(intent) }
                        .onFailure {
                            Toast.makeText(
                                context,
                                "Unable to open share sheet: ${it.localizedMessage}",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                }
            }
        }
    }

    BackHandler(enabled = state.isJsDrawerOpen || state.isTelemetryStreamExpanded) {
        when {
            state.isJsDrawerOpen -> viewModel.onToggleJsDrawer()
            state.isTelemetryStreamExpanded -> viewModel.onToggleTelemetryStreamExpanded()
        }
    }

    val filteredLogs = remember(state.logs, state.selectedFilter) {
        if (state.selectedFilter == LogCategory.ALL) {
            state.logs
        } else {
            state.logs.filter { it.category == state.selectedFilter }
        }
    }

    val copyToClipboard: (String) -> Unit = remember(context) {
        { text ->
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            clipboard?.setPrimaryClip(ClipData.newPlainText("WebViewTelemetry", text))
            Toast.makeText(context, context.getString(R.string.toast_log_copied), Toast.LENGTH_SHORT).show()
        }
    }

    Scaffold(
        containerColor = ObsidianBg,
        contentWindowInsets = WindowInsets.safeDrawing,
        bottomBar = {
            CollapsibleLiveTelemetryStreamCard(
                state = state,
                onToggleExpanded = viewModel::onToggleTelemetryStreamExpanded,
                modifier = Modifier.navigationBarsPadding()
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(ObsidianBg)
        ) {
            // 1. Compact URL Navigation Bar
            CompactUrlNavigationBar(
                urlInput = state.urlInput,
                isDesktopMode = state.isDesktopMode,
                isLoading = state.isLoading,
                loadProgress = state.loadProgress,
                onUrlChanged = viewModel::onUrlInputChanged,
                onLoadRequested = {
                    focusManager.clearFocus()
                    viewModel.onLoadUrlRequested()
                },
                onReloadRequested = viewModel::onReloadRequested,
                onToggleDesktopMode = viewModel::onToggleDesktopMode
            )

            // 2. Prominent Top Live Coordinate HUD (HEAPF32[581246..581248])
            TopLiveCoordinateHud(
                state = state,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
            )

            // 3. Adaptive Main Body (Scrollable Parent on Portrait, Side-by-Side Split on Wide)
            BoxWithConstraints(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                val isWideLayout = maxWidth >= 840.dp

                if (isWideLayout) {
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        DiagnosticWebViewCard(
                            state = state,
                            telemetryBridge = telemetryBridge,
                            onWebViewReady = { webViewRef = it },
                            onPageStarted = viewModel::onPageStarted,
                            onPageProgressChanged = viewModel::onPageProgressChanged,
                            onPageFinished = viewModel::onPageFinished,
                            onWebViewError = viewModel::onWebViewError,
                            onResourceObserved = viewModel::onResourceRequestObserved,
                            onConsoleLog = { msg -> viewModel.onTelemetryFromBridge(LogCategory.JS.tag, msg) },
                            onToggleExpand = viewModel::onToggleWebViewExpanded,
                            showHeightToggle = false,
                            modifier = Modifier
                                .weight(1.1f)
                                .fillMaxHeight()
                        )

                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            PrimaryActionButtonsBar(
                                state = state,
                                onRelockCoordinates = viewModel::relockCoordinates,
                                onScanEntities = viewModel::scanNearbyEntities,
                                onExportReport = {
                                    viewModel.requestExportReport(
                                        armBridge = telemetryBridge::armExport,
                                        disarmBridge = telemetryBridge::disarmExport
                                    )
                                },
                                onShareLastReport = viewModel::onShareLastReport
                            )

                            TelemetryConsolePanel(
                                state = state,
                                filteredLogs = filteredLogs,
                                onSelectConsoleTab = viewModel::onSelectConsoleTab,
                                onBaseIndexInputChanged = viewModel::onBaseIndexInputChanged,
                                onApplyBaseIndex = viewModel::onApplyBaseIndex,
                                onResetVerifiedIndex = viewModel::onResetToVerifiedBaseIndex,
                                onStartFeed = viewModel::startDirectHeapFeed,
                                onPauseFeed = viewModel::pauseDirectHeapFeed,
                                onCaptureSignature = viewModel::captureRelockSignature,
                                onTogglePinEntity = viewModel::onTogglePinEntity,
                                onClearAssetCache = viewModel::onClearAssetCache,
                                onToggleJsDrawer = viewModel::onToggleJsDrawer,
                                onManualJsInputChanged = viewModel::onManualJsInputChanged,
                                onExecuteManualScript = viewModel::onExecuteManualScript,
                                onSelectFilter = viewModel::onSelectFilter,
                                onClearLogs = viewModel::onClearLogs,
                                onExportLogDumpTxt = { viewModel.exportFullLogDump(asJson = false) },
                                onExportLogDumpJson = { viewModel.exportFullLogDump(asJson = true) },
                                onShareLastLogDump = viewModel::onShareLastLogDump,
                                onCopyText = copyToClipboard,
                                tabListHeight = 380.dp
                            )

                            Spacer(Modifier.height(12.dp))
                        }
                    }
                } else {
                    // Portrait / Mobile Scrollable Parent Container:
                    // Guarantees buttons and tabs are NEVER clipped or pushed off-screen.
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 12.dp, vertical = 4.dp)
                            .testTag("main_scrollable_container"),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        val webViewHeight = if (state.isWebViewExpanded) 430.dp else 280.dp
                        DiagnosticWebViewCard(
                            state = state,
                            telemetryBridge = telemetryBridge,
                            onWebViewReady = { webViewRef = it },
                            onPageStarted = viewModel::onPageStarted,
                            onPageProgressChanged = viewModel::onPageProgressChanged,
                            onPageFinished = viewModel::onPageFinished,
                            onWebViewError = viewModel::onWebViewError,
                            onResourceObserved = viewModel::onResourceRequestObserved,
                            onConsoleLog = { msg -> viewModel.onTelemetryFromBridge(LogCategory.JS.tag, msg) },
                            onToggleExpand = viewModel::onToggleWebViewExpanded,
                            showHeightToggle = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(webViewHeight)
                        )

                        PrimaryActionButtonsBar(
                            state = state,
                            onRelockCoordinates = viewModel::relockCoordinates,
                            onScanEntities = viewModel::scanNearbyEntities,
                            onExportReport = {
                                viewModel.requestExportReport(
                                    armBridge = telemetryBridge::armExport,
                                    disarmBridge = telemetryBridge::disarmExport
                                )
                            },
                            onShareLastReport = viewModel::onShareLastReport
                        )

                        TelemetryConsolePanel(
                            state = state,
                            filteredLogs = filteredLogs,
                            onSelectConsoleTab = viewModel::onSelectConsoleTab,
                            onBaseIndexInputChanged = viewModel::onBaseIndexInputChanged,
                            onApplyBaseIndex = viewModel::onApplyBaseIndex,
                            onResetVerifiedIndex = viewModel::onResetToVerifiedBaseIndex,
                            onStartFeed = viewModel::startDirectHeapFeed,
                            onPauseFeed = viewModel::pauseDirectHeapFeed,
                            onCaptureSignature = viewModel::captureRelockSignature,
                            onTogglePinEntity = viewModel::onTogglePinEntity,
                            onClearAssetCache = viewModel::onClearAssetCache,
                            onToggleJsDrawer = viewModel::onToggleJsDrawer,
                            onManualJsInputChanged = viewModel::onManualJsInputChanged,
                            onExecuteManualScript = viewModel::onExecuteManualScript,
                            onSelectFilter = viewModel::onSelectFilter,
                            onClearLogs = viewModel::onClearLogs,
                            onExportLogDumpTxt = { viewModel.exportFullLogDump(asJson = false) },
                            onExportLogDumpJson = { viewModel.exportFullLogDump(asJson = true) },
                            onShareLastLogDump = viewModel::onShareLastLogDump,
                            onCopyText = copyToClipboard,
                            tabListHeight = 360.dp
                        )

                        Spacer(Modifier.height(16.dp))
                    }
                }
            }
        }
    }
}

/**
 * Prominent Top Live Coordinate HUD displaying verified `window.HEAPF32[581246..581248]` (X, Y, Z)
 * coordinates clearly at the top of the interface with live polling badge and hex address.
 */
@Composable
private fun TopLiveCoordinateHud(
    state: DiagnosticsUiState,
    modifier: Modifier = Modifier
) {
    val feed = state.heapFeed
    val hexAddress = remember(feed.byteAddress) {
        "0x${feed.byteAddress.toString(16).uppercase(Locale.US)}"
    }
    val statusColor = when {
        feed.isRelocking -> ElectricCyan
        feed.isPolling && feed.isHeapReady -> SignalEmerald
        else -> AssetAmber
    }
    val statusText = when {
        feed.isRelocking -> stringResource(R.string.hud_status_relocking)
        feed.isPolling && feed.isHeapReady -> stringResource(R.string.hud_status_live)
        else -> stringResource(R.string.hud_status_standby)
    }

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = ObsidianSurface,
        border = BorderStroke(1.2.dp, statusColor.copy(alpha = 0.65f)),
        modifier = modifier
            .fillMaxWidth()
            .testTag("top_live_coordinate_hud")
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.hud_title_live_coords),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = statusColor
                    )
                    Text(
                        text = "${feed.heapSourceLabel}[${feed.baseIndex}..${feed.baseIndex + 2}] • $hexAddress",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = JetBrainsMonoFontFamily,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = statusColor.copy(alpha = 0.18f),
                    border = BorderStroke(1.dp, statusColor.copy(alpha = 0.7f))
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        if (feed.isRelocking) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(10.dp),
                                strokeWidth = 1.6.dp,
                                color = statusColor
                            )
                        } else {
                            Box(
                                modifier = Modifier
                                    .size(7.dp)
                                    .clip(CircleShape)
                                    .background(statusColor)
                            )
                        }
                        Text(
                            text = statusText,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = statusColor,
                            fontSize = 10.sp
                        )
                    }
                }
            }

            // Clean 3-column Coordinate Readout (X, Y, Z)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                HudCoordinateTile(
                    axis = "X",
                    index = feed.baseIndex,
                    value = feed.x,
                    accent = SignalEmerald,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("hud_coord_x")
                )
                HudCoordinateTile(
                    axis = "Y",
                    index = feed.baseIndex + 1,
                    value = feed.y,
                    accent = ElectricCyan,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("hud_coord_y")
                )
                HudCoordinateTile(
                    axis = "Z",
                    index = feed.baseIndex + 2,
                    value = feed.z,
                    accent = TelemetryViolet,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("hud_coord_z")
                )
            }
        }
    }
}

@Composable
private fun HudCoordinateTile(
    axis: String,
    index: Int,
    value: Double,
    accent: Color,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(ObsidianBg)
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = axis,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = accent
            )
            Text(
                text = "[$index]",
                style = MaterialTheme.typography.labelSmall,
                fontFamily = JetBrainsMonoFontFamily,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 9.sp
            )
        }
        Text(
            text = String.format(Locale.US, "%.3f", value),
            style = MaterialTheme.typography.titleSmall,
            fontFamily = JetBrainsMonoFontFamily,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun CompactUrlNavigationBar(
    urlInput: String,
    isDesktopMode: Boolean,
    isLoading: Boolean,
    loadProgress: Int,
    onUrlChanged: (String) -> Unit,
    onLoadRequested: () -> Unit,
    onReloadRequested: () -> Unit,
    onToggleDesktopMode: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(ObsidianSurface)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            OutlinedTextField(
                value = urlInput,
                onValueChange = onUrlChanged,
                singleLine = true,
                placeholder = {
                    Text(
                        text = stringResource(R.string.url_input_hint),
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                textStyle = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = JetBrainsMonoFontFamily,
                    color = MaterialTheme.colorScheme.onSurface
                ),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Go
                ),
                keyboardActions = KeyboardActions(onGo = { onLoadRequested() }),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = ElectricCyan,
                    unfocusedBorderColor = ObsidianBorder,
                    focusedContainerColor = ObsidianBg,
                    unfocusedContainerColor = ObsidianBg
                ),
                modifier = Modifier
                    .weight(1f)
                    .testTag("url_input_field")
            )

            Button(
                onClick = onLoadRequested,
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = ElectricCyan,
                    contentColor = OnElectricCyan
                ),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
                modifier = Modifier
                    .heightIn(min = 50.dp)
                    .testTag("load_url_button")
            ) {
                Text(
                    text = stringResource(R.string.action_load),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold
                )
            }

            IconButton(
                onClick = onReloadRequested,
                modifier = Modifier
                    .size(48.dp)
                    .testTag("reload_button")
            ) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = stringResource(R.string.cd_reload_page),
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(20.dp)
                )
            }

            IconButton(
                onClick = onToggleDesktopMode,
                modifier = Modifier
                    .size(48.dp)
                    .testTag("toggle_ua_button")
            ) {
                Icon(
                    imageVector = if (isDesktopMode) Icons.Default.Computer else Icons.Default.PhoneAndroid,
                    contentDescription = stringResource(R.string.cd_toggle_desktop_ua),
                    tint = if (isDesktopMode) ElectricCyan else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }
        }

        if (isLoading) {
            LinearProgressIndicator(
                progress = { (loadProgress.coerceIn(5, 100)) / 100f },
                color = ElectricCyan,
                trackColor = ObsidianBg,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp)
            )
        } else {
            HorizontalDivider(color = ObsidianBorder, thickness = 1.dp)
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun DiagnosticWebViewCard(
    state: DiagnosticsUiState,
    telemetryBridge: TelemetryBridge,
    onWebViewReady: (WebView) -> Unit,
    onPageStarted: (String) -> Unit,
    onPageProgressChanged: (Int) -> Unit,
    onPageFinished: (String, String) -> Unit,
    onWebViewError: (String, String) -> Unit,
    onResourceObserved: (WebResourceRequest) -> Unit,
    onConsoleLog: (String) -> Unit,
    onToggleExpand: () -> Unit,
    showHeightToggle: Boolean,
    modifier: Modifier = Modifier
) {
    var createdWebView by remember { mutableStateOf<WebView?>(null) }

    DisposableEffect(Unit) {
        onDispose {
            createdWebView?.let { wv ->
                wv.removeJavascriptInterface(TelemetryBridge.BRIDGE_NAME)
                wv.stopLoading()
                wv.destroy()
            }
            createdWebView = null
        }
    }

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = ObsidianSurface,
        border = BorderStroke(1.2.dp, ObsidianBorder),
        modifier = modifier
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Card Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(ObsidianSurfaceElevated)
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = state.pageTitle.ifBlank { "Embedded WebView Viewport" },
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = if (state.scanStatusDetail.isNotBlank()) state.scanStatusDetail else state.activeUrl,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = JetBrainsMonoFontFamily,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        fontSize = 10.sp
                    )
                }

                if (showHeightToggle) {
                    IconButton(
                        onClick = onToggleExpand,
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("toggle_webview_height_button")
                    ) {
                        Icon(
                            imageVector = if (state.isWebViewExpanded) Icons.Default.UnfoldLess else Icons.Default.OpenInFull,
                            contentDescription = stringResource(R.string.cd_toggle_webview_height),
                            tint = ElectricCyan,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            HorizontalDivider(color = ObsidianBorder, thickness = 1.dp)

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(Color.Black)
            ) {
                AndroidView(
                    factory = { ctx ->
                        WebView(ctx).apply {
                            layoutParams = ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT
                            )
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.databaseEnabled = true
                            settings.useWideViewPort = true
                            settings.loadWithOverviewMode = true
                            settings.builtInZoomControls = true
                            settings.displayZoomControls = false
                            settings.allowFileAccess = false
                            settings.allowContentAccess = false
                            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                            settings.userAgentString = if (state.isDesktopMode) {
                                DiagnosticsViewModel.DESKTOP_USER_AGENT
                            } else {
                                WebSettings.getDefaultUserAgent(ctx)
                            }

                            addJavascriptInterface(telemetryBridge, TelemetryBridge.BRIDGE_NAME)

                            webViewClient = object : WebViewClient() {
                                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                    super.onPageStarted(view, url, favicon)
                                    onPageStarted(url.orEmpty())
                                }

                                override fun onPageFinished(view: WebView?, url: String?) {
                                    super.onPageFinished(view, url)
                                    onPageFinished(url.orEmpty(), view?.title.orEmpty())
                                }

                                override fun onReceivedError(
                                    view: WebView?,
                                    request: WebResourceRequest?,
                                    error: WebResourceError?
                                ) {
                                    super.onReceivedError(view, request, error)
                                    if (request?.isForMainFrame == true) {
                                        onWebViewError(
                                            error?.description?.toString() ?: "Unknown load error",
                                            request.url?.toString().orEmpty()
                                        )
                                    }
                                }

                                override fun shouldInterceptRequest(
                                    view: WebView?,
                                    request: WebResourceRequest?
                                ): WebResourceResponse? {
                                    if (request != null) {
                                        onResourceObserved(request)
                                    }
                                    return super.shouldInterceptRequest(view, request)
                                }
                            }

                            webChromeClient = object : WebChromeClient() {
                                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                    super.onProgressChanged(view, newProgress)
                                    onPageProgressChanged(newProgress)
                                }

                                override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                                    if (consoleMessage != null &&
                                        consoleMessage.messageLevel() == ConsoleMessage.MessageLevel.ERROR
                                    ) {
                                        onConsoleLog("ConsoleError: ${consoleMessage.message()}")
                                    }
                                    return super.onConsoleMessage(consoleMessage)
                                }
                            }

                            createdWebView = this
                            onWebViewReady(this)
                        }
                    },
                    update = { webView ->
                        val desiredUa = if (state.isDesktopMode) {
                            DiagnosticsViewModel.DESKTOP_USER_AGENT
                        } else {
                            WebSettings.getDefaultUserAgent(webView.context)
                        }
                        if (webView.settings.userAgentString != desiredUa) {
                            webView.settings.userAgentString = desiredUa
                        }
                    },
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("diagnostic_webview")
                )
            }
        }
    }
}
