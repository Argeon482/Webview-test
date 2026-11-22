package com.example

import android.content.Context
import android.net.Uri
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
import android.os.Looper

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
    fun `telemetry bridge blocks unsolicited exportReport and honors armed export`() {
        val logged = mutableListOf<Pair<String, String>>()
        val exported = mutableListOf<String>()

        val bridge = TelemetryBridge(
            onTelemetryReceived = { cat, payload -> logged.add(cat to payload) },
            onReportPayloadReceived = { json -> exported.add(json) }
        )

        // Unsolicited page call must be rejected
        bridge.exportReport("""{"injected":true}""")
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(exported.isEmpty())
        assertEquals(1, logged.size)
        assertEquals("ERR", logged.first().first)

        // Armed user-initiated export must succeed
        bridge.armExport()
        bridge.exportReport("""{"url":"https://example.com"}""")
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, exported.size)
        assertTrue(exported.first().contains("example.com"))
    }
}
