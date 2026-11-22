package com.example

import android.content.Context
import android.net.Uri
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

    @Test
    fun `read app name string from context`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("WebViewDiagnosticsApp", appName)
    }

    @Test
    fun `url normalization prepends https and blocks disallowed schemes`() {
        val bareDomain = DiagnosticsViewModel.normalizeAndValidateUrl("example.com/demo")
        assertTrue(bareDomain is UrlValidationResult.Valid)
        assertEquals("https://example.com/demo", (bareDomain as UrlValidationResult.Valid).normalizedUrl)

        val blank = DiagnosticsViewModel.normalizeAndValidateUrl("")
        assertEquals("about:blank", (blank as UrlValidationResult.Valid).normalizedUrl)

        val jsScheme = DiagnosticsViewModel.normalizeAndValidateUrl("javascript:alert(1)")
        assertTrue(jsScheme is UrlValidationResult.Invalid)

        val fileScheme = DiagnosticsViewModel.normalizeAndValidateUrl("file:///etc/hosts")
        assertTrue(fileScheme is UrlValidationResult.Invalid)

        assertTrue(DiagnosticsViewModel.isAllowedNavigationUri(Uri.parse("https://threejs.org/examples/")))
        assertFalse(DiagnosticsViewModel.isAllowedNavigationUri(Uri.parse("content://com.example/secret")))
    }

    @Test
    fun `asset request matcher identifies wasm json bin and chunk files`() {
        assertEquals(".wasm", DiagnosticsViewModel.matchTrackedAssetExtension("/build/engine.wasm"))
        assertEquals(".json", DiagnosticsViewModel.matchTrackedAssetExtension("/assets/scene.json?v=2"))
        assertEquals(".bin", DiagnosticsViewModel.matchTrackedAssetExtension("/models/mesh.bin"))
        assertEquals("chunk", DiagnosticsViewModel.matchTrackedAssetExtension("/static/js/412.chunk.js"))
        assertNull(DiagnosticsViewModel.matchTrackedAssetExtension("/styles/main.css"))
    }

    @Test
    fun `staging buffer accumulates across multiple scans without overwriting and preserves pins`() {
        val scan1 = DiagnosticsViewModel.mergeIntoStagingBuffer(
            existing = emptyList(),
            incoming = listOf("window.game" to "GameEngine (12 keys)", "window.scene" to "Scene (5 keys)"),
            sourceStep = "Step 1",
            scanNumber = 1,
            timestamp = "12:00:01.000"
        )
        assertEquals(2, scan1.size)

        // Pin window.game
        val withPin = scan1.map { if (it.path == "window.game") it.copy(isPinned = true) else it }

        // Second scan discovers new player path and re-observes window.game
        val scan2 = DiagnosticsViewModel.mergeIntoStagingBuffer(
            existing = withPin,
            incoming = listOf("window.game" to "GameEngine (14 keys)", "window.game.player.pos" to "Δ +4.20"),
            sourceStep = "Step 2B",
            scanNumber = 2,
            timestamp = "12:00:08.500"
        )

        assertEquals(3, scan2.size)
        val pinnedGame = scan2.first()
        assertEquals("window.game", pinnedGame.path)
        assertTrue(pinnedGame.isPinned)
        assertEquals(2, pinnedGame.seenCount)
        assertTrue(scan2.any { it.path == "window.scene" })
        assertTrue(scan2.any { it.path == "window.game.player.pos" })
    }

    @Test
    fun `telemetry bridge guards exportReport and forwards scan status and live 200ms player coords`() {
        val logged = mutableListOf<Pair<String, String>>()
        val exported = mutableListOf<String>()
        val statuses = mutableListOf<Pair<AsyncScanStatus, String>>()
        val coords = mutableListOf<PlayerCoordinates>()

        val bridge = TelemetryBridge(
            onTelemetryReceived = { cat, payload -> logged.add(cat to payload) },
            onReportPayloadReceived = { json -> exported.add(json) },
            onScanStatusChanged = { status, detail -> statuses.add(status to detail) },
            onPlayerCoordsUpdated = { path, x, y, z -> coords.add(PlayerCoordinates(path, x, y, z)) }
        )

        bridge.exportReport("""{"injected":true}""")
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(exported.isEmpty())
        assertEquals("ERR", logged.first().first)

        bridge.armExport()
        bridge.exportReport("""{"url":"https://example.com"}""")
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, exported.size)

        bridge.updateScanStatus("COMPLETED", "Step 1: 12 globals\nin 1.1ms")
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(AsyncScanStatus.COMPLETED, statuses.first().first)
        assertEquals("Step 1: 12 globals in 1.1ms", statuses.first().second)

        bridge.updatePlayerCoords("window.game.player.pos", Double.NaN, 0.0, 0.0)
        bridge.updatePlayerCoords("window.game.player.pos", 12.5, 3.0, -44.25)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, coords.size)
        assertEquals("window.game.player.pos", coords.first().path)
        assertEquals(12.5, coords.first().x, 0.001)
    }

    @Test
    fun `diagnostic scripts support 100-item batched scanning with pause resume and direct liveRef`() {
        val step1 = DiagnosticsScripts.buildInspectGlobalScopeScript()
        val pauseScript = DiagnosticsScripts.buildPauseScopeScanScript()
        val resumeScript = DiagnosticsScripts.buildResumeScopeScanScript()
        val step2b = DiagnosticsScripts.buildComputeDeltaScript()
        val step3 = DiagnosticsScripts.buildScanEntitiesScript("window.game.player.position")

        // Step 1 strict built-in blacklist, 100-item batching, staggered async delays, and pause/resume
        assertTrue(step1.contains("const BATCH_SIZE = 100;"))
        assertTrue(step1.contains("Scanning batch "))
        assertTrue(step1.contains("GLOBAL_SCOPE_BATCH"))
        assertTrue(step1.contains("WebSocket"))
        assertTrue(step1.contains("Iterator"))
        assertTrue(step1.contains("DataTransfer"))
        assertTrue(step1.contains("[native code]"))
        assertTrue(pauseScript.contains("job.paused = true"))
        assertTrue(resumeScript.contains("job.paused = false"))

        // Step 2B direct nested reference (`parentObject[propName]` + `refObject`) and 200ms polling
        assertTrue(step2b.contains("parentObject:"))
        assertTrue(step2b.contains("propName:"))
        assertTrue(step2b.contains("refObject:"))
        assertTrue(step2b.contains("ref.parentObject[ref.propName]"))
        assertTrue(step2b.contains("__LIVE_POLL_INTERVAL_MS = 200"))
        assertTrue(step2b.contains("setInterval(pushNow, __LIVE_POLL_INTERVAL_MS)"))

        // Step 3 parent-climbing visual entity discovery
        assertTrue(step3.contains("window.game.player.position"))
        assertTrue(step3.contains("for (let climb = 1; climb <= 3; climb++)"))
    }

    @Test
    fun `staging keyword search filters by multi-token query and telemetry bridge reports batch progress`() {
        val items = listOf(
            CandidateStagingItem("window.game.player.pos", "Step 2B", "Vec3(12, 0, -5)", 2, 2, true, "12:00:01.000"),
            CandidateStagingItem("window.game.actors", "Step 1 B1", "Array (8 keys)", 1, 1, false, "12:00:00.500"),
            CandidateStagingItem("window.uiTheme", "Step 1 B2", "Object (3 keys)", 1, 1, false, "12:00:00.800")
        )

        val posMatches = DiagnosticsViewModel.filterStagingBuffer(items, showPinnedOnly = false, rawQuery = "pos")
        assertEquals(1, posMatches.size)
        assertEquals("window.game.player.pos", posMatches.first().path)

        val actorMatches = DiagnosticsViewModel.filterStagingBuffer(items, showPinnedOnly = false, rawQuery = "actor")
        assertEquals(1, actorMatches.size)
        assertEquals("window.game.actors", actorMatches.first().path)

        val pinnedActorMatches = DiagnosticsViewModel.filterStagingBuffer(items, showPinnedOnly = true, rawQuery = "actor")
        assertTrue(pinnedActorMatches.isEmpty())

        val progressEvents = mutableListOf<ScopeBatchProgress>()
        val bridge = TelemetryBridge(
            onTelemetryReceived = { _, _ -> },
            onReportPayloadReceived = {},
            onBatchProgressChanged = { progressEvents.add(it) }
        )
        bridge.updateBatchProgress(
            currentBatch = 2,
            totalBatches = 10,
            itemStart = 101,
            itemEnd = 200,
            totalItems = 950,
            foundCount = 4,
            isActive = true,
            isPaused = false
        )
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, progressEvents.size)
        assertEquals("Scanning batch 2/10 (items 101–200)…", progressEvents.first().statusText)
    }

    @Test
    fun `full log dump text includes staged variables entities assets and rolling log history`() {
        val state = DiagnosticsUiState(
            activeUrl = "https://example.com/game",
            pageTitle = "Demo World",
            scanCounter = 2,
            discoveredPlayerPath = "window.game.player.pos",
            playerPropertyKeys = "x, y, z",
            livePlayerCoords = PlayerCoordinates("window.game.player.pos", 10.5, 2.0, -4.25),
            stagingBuffer = listOf(
                CandidateStagingItem("window.game.player.pos", "Step 2B", "Live ref", 2, 2, true, "12:01:00.000")
            ),
            discoveredEntityArrayPath = "window.game.entities",
            discoveredEntities = listOf(
                DiscoveredEntitySample("Goblin", "e1", "window.game.entities[0]", 15.0, 2.0, -1.0, 5.5, 320.0, 180.0, true, listOf("x", "y", "z"))
            ),
            interceptedAssets = listOf(
                InterceptedAsset("https://example.com/engine.wasm", ".wasm", "GET", "12:00:10.000", false)
            ),
            logs = listOf(
                TelemetryLogEntry(1L, "12:00:11.000", 1700000000000L, LogCategory.SCOPE, "Batch 1/10 complete", "{\"ok\":true}")
            )
        )

        val dump = DiagnosticsViewModel.buildFullLogDumpText(state)
        assertTrue(dump.contains("WebViewDiagnosticsApp — Complete Diagnostic Log Dump"))
        assertTrue(dump.contains("[PINNED] window.game.player.pos"))
        assertTrue(dump.contains("Goblin #e1 (IN VIEW)"))
        assertTrue(dump.contains("https://example.com/engine.wasm"))
        assertTrue(dump.contains("Batch 1/10 complete"))
    }

    @Test
    fun `binary asset inspector decodes wasm json and float32 bin headers via ByteBuffer`() {
        // 1. Minimal WASM binary: \0asm + version 1 + Type section (id=1, size=1, body=0x00)
        val wasmBytes = byteArrayOf(
            0x00, 0x61, 0x73, 0x6D,
            0x01, 0x00, 0x00, 0x00,
            0x01, 0x01, 0x00
        )
        val wasmAudit = BinaryAssetInspector.inspectBytes(wasmBytes, ".wasm")
        assertEquals("WASM v1", wasmAudit.magicSignature)
        assertTrue(wasmAudit.structuralDetails.contains("Type(1 B)"))
        assertTrue(wasmAudit.hexHeaderPreview.startsWith("00 61 73 6D 01 00 00 00"))

        // 2. JSON asset inspection
        val jsonBytes = """{"scene":"dungeon","entities":[1,2,3],"version":4}""".toByteArray(Charsets.UTF_8)
        val jsonAudit = BinaryAssetInspector.inspectBytes(jsonBytes, ".json", "application/json")
        assertEquals("JSON Object (3 keys)", jsonAudit.magicSignature)
        assertTrue(jsonAudit.structuralSummary.contains("scene"))

        // 3. Little-endian Float32 .bin buffer inspection
        val bb = java.nio.ByteBuffer.allocate(24).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        listOf(12.5f, 3.0f, -44.25f, 1.0f, 0.0f, 2.5f).forEach { bb.putFloat(it) }
        val binAudit = BinaryAssetInspector.inspectBytes(bb.array(), ".bin")
        assertEquals("Binary Float32 Buffer", binAudit.magicSignature)
        assertTrue(binAudit.structuralDetails.contains("12.50, 3.00, -44.25"))

        // 4. Filename sanitization prevents path traversal
        val safeName = BinaryAssetInspector.extractFileName("https://cdn.example.com/../../evil?x=1", ".bin")
        assertFalse(safeName.contains("/"))
        assertFalse(safeName.contains("\\"))
    }
}
