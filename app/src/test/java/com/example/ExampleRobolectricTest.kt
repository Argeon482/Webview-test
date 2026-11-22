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
    fun `telemetry bridge guards exportReport and forwards scan status and live player coords`() {
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

        // Unsolicited export must be rejected
        bridge.exportReport("""{"injected":true}""")
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(exported.isEmpty())
        assertEquals("ERR", logged.first().first)

        // Armed user-initiated export must succeed
        bridge.armExport()
        bridge.exportReport("""{"url":"https://example.com"}""")
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, exported.size)

        // Scan status sanitization
        bridge.updateScanStatus("COMPLETED", "Step 1: 12 globals\nin 1.1ms")
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(AsyncScanStatus.COMPLETED, statuses.first().first)
        assertEquals("Step 1: 12 globals in 1.1ms", statuses.first().second)

        // Live player coords validation (rejects NaN, accepts finite values)
        bridge.updatePlayerCoords("window.game.player.pos", Double.NaN, 0.0, 0.0)
        bridge.updatePlayerCoords("window.game.player.pos", 12.5, 3.0, -44.25)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, coords.size)
        assertEquals("window.game.player.pos", coords.first().path)
        assertEquals(12.5, coords.first().x, 0.001)
        assertEquals(-44.25, coords.first().z, 0.001)
    }

    @Test
    fun `step 1 filters standard web APIs and step 3 anchors to player path`() {
        val step1 = DiagnosticsScripts.buildInspectGlobalScopeScript()
        val step3 = DiagnosticsScripts.buildScanEntitiesScript("window.game.player.position")

        assertTrue(step1.contains("__filterCustomWindowKeys"))
        assertTrue(step1.contains("HTML|SVG|WebGL|webkit"))
        assertTrue(step3.contains("window.game.player.position"))
        assertTrue(step3.contains("for (let climb = 1; climb <= 3; climb++)"))
    }
}
