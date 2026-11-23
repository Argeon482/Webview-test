package com.example

import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun `telemetry bridge guards exportReport and forwards direct 200ms HEAPF32 coordinates`() {
        val logged = mutableListOf<Pair<String, String>>()
        val exported = mutableListOf<String>()
        val statuses = mutableListOf<Pair<AsyncScanStatus, String>>()
        val coordUpdates = mutableListOf<Triple<Int, Double, String>>()

        val bridge = TelemetryBridge(
            onTelemetryReceived = { cat, payload -> logged.add(cat to payload) },
            onReportPayloadReceived = { json -> exported.add(json) },
            onScanStatusChanged = { status, detail -> statuses.add(status to detail) },
            onDirectHeapCoordsUpdated = { baseIdx, x, _, _, _, src ->
                coordUpdates.add(Triple(baseIdx, x, src))
            }
        )

        // Unsolicited export must be blocked
        bridge.exportReport("""{"injected":true}""")
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(exported.isEmpty())
        assertEquals("ERR", logged.first().first)

        // Armed export succeeds
        bridge.armExport()
        bridge.exportReport("""{"url":"https://example.com"}""")
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, exported.size)

        // Scan status sanitization strips newlines
        bridge.updateScanStatus("COMPLETED", "Relocked @ 581246\n98%")
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(AsyncScanStatus.COMPLETED, statuses.first().first)
        assertEquals("Relocked @ 581246 98%", statuses.first().second)

        // NaN coordinates are ignored; valid coordinates update cleanly
        bridge.updateDirectHeapCoords(581246, Double.NaN, 1.0, 2.0, 67_108_864L, "window.HEAPF32")
        bridge.updateDirectHeapCoords(581246, 128.5, 4.25, -92.75, 67_108_864L, "window.HEAPF32")
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, coordUpdates.size)
        assertEquals(581246, coordUpdates.first().first)
        assertEquals(128.5, coordUpdates.first().second, 0.001)
        assertEquals("window.HEAPF32", coordUpdates.first().third)
    }

    @Test
    fun `viewmodel updates live coords persists relock signature across reload and stores nearby entities`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val vm = DiagnosticsViewModel(app)

        // 1. Live 200ms direct coordinate update
        vm.onDirectHeapCoordsUpdatedFromBridge(
            baseIndex = VERIFIED_PLAYER_BASE_INDEX,
            x = 240.125,
            y = 12.5,
            z = -84.75,
            heapByteLength = 67_108_864L,
            heapSource = "window.HEAPF32"
        )
        val feedAfterTick = vm.uiState.value.heapFeed
        assertEquals(581246, feedAfterTick.baseIndex)
        assertEquals(240.125, feedAfterTick.x, 0.001)
        assertEquals(12.5, feedAfterTick.y, 0.001)
        assertEquals(-84.75, feedAfterTick.z, 0.001)
        assertTrue(feedAfterTick.isHeapReady)
        assertEquals(1, vm.uiState.value.liveStreamHistory.size)

        // 2. Signature captured & preserved across page reload
        vm.onTelemetryFromBridge(
            "RELOCK",
            """{"type":"SIGNATURE_CAPTURED","auto":true,"baseIndex":581246,"anchorCount":12,"zeroCount":4,"signatureJson":"{\"v\":1,\"anchors\":[{\"off\":-4,\"bits\":1065353216}]}"}"""
        )
        assertTrue(vm.uiState.value.heapFeed.hasSignature)
        assertEquals(12, vm.uiState.value.heapFeed.signatureAnchorCount)

        vm.onPageStarted("https://example.com/zone2")
        assertTrue("Signature must persist across reload/zone change", vm.uiState.value.heapFeed.hasSignature)
        assertFalse(vm.uiState.value.heapFeed.isHeapReady)

        // 3. Auto-Relock relocates base index after zone change
        vm.onTelemetryFromBridge(
            "RELOCK",
            """{"type":"RELOCK_COMPLETE","oldBaseIndex":581246,"newBaseIndex":612004,"confidencePercent":94,"matchedAnchors":11,"totalAnchors":12,"x":10.5,"y":2.0,"z":-5.25,"durationMs":34.5,"heapByteLength":67108864,"heapSource":"window.HEAPF32","note":"Relocated player base 581246 -> 612004 (94% match)"}"""
        )
        assertEquals(612004, vm.uiState.value.heapFeed.baseIndex)
        assertEquals(94, vm.uiState.value.heapFeed.relockConfidencePercent)

        // 4. Nearby ±50,000 entity scan populates Entities tab and supports pinning
        vm.onTelemetryFromBridge(
            "ENTITY",
            """{"type":"ENTITIES_SCANNED","playerBaseIndex":612004,"searchRadius":50000,"totalCandidates":5,"dominantStride":32,"durationMs":19.4,"entities":[{"baseIndex":612036,"byteAddress":2448144,"offsetFromPlayer":32,"x":15.5,"y":2.0,"z":-8.0,"distanceToPlayer":5.7,"strideGroup":32,"confidenceScore":95},{"baseIndex":612068,"byteAddress":2448272,"offsetFromPlayer":64,"x":40.0,"y":2.0,"z":10.0,"distanceToPlayer":33.2,"strideGroup":32,"confidenceScore":95}]}"""
        )
        assertEquals(2, vm.uiState.value.discoveredEntities.size)
        assertEquals(32, vm.uiState.value.entityDetectedStride)

        vm.onTogglePinEntity(612068)
        assertEquals(612068, vm.uiState.value.discoveredEntities.first().baseIndex)
        assertTrue(vm.uiState.value.discoveredEntities.first().isPinned)
    }
}
