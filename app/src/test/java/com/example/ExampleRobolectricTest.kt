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
    fun `diagnostic scripts store direct liveRef with 200ms polling and anchor step 3 to parent`() {
        val step2b = DiagnosticsScripts.buildComputeDeltaScript()
        val step3 = DiagnosticsScripts.buildScanEntitiesScript("window.game.player.position")

        assertTrue(step2b.contains("targetObject:"))
        assertTrue(step2b.contains("__LIVE_POLL_INTERVAL_MS = 200"))
        assertTrue(step2b.contains("setInterval(pushNow, __LIVE_POLL_INTERVAL_MS)"))
        assertTrue(step3.contains("window.game.player.position"))
        assertTrue(step3.contains("for (let climb = 1; climb <= 3; climb++)"))
    }
}
