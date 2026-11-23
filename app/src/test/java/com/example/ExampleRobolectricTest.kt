package com.example

import android.app.Application
import android.content.Context
import android.net.Uri
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
    fun `navigation uri guard allows https and rejects content or file schemes`() {
        assertTrue(DiagnosticsViewModel.isAllowedNavigationUri(Uri.parse("https://threejs.org/examples/")))
        assertFalse(DiagnosticsViewModel.isAllowedNavigationUri(Uri.parse("content://com.example/secret")))
        assertFalse(DiagnosticsViewModel.isAllowedNavigationUri(Uri.parse("file:///etc/hosts")))
    }

    @Test
    fun `telemetry bridge guards exportReport and forwards direct heap coords and float previews`() {
        val logged = mutableListOf<Pair<String, String>>()
        val exported = mutableListOf<String>()
        val statuses = mutableListOf<Pair<AsyncScanStatus, String>>()
        val directCoords = mutableListOf<Triple<Int, Double, String>>()
        val previews = mutableListOf<String>()

        val bridge = TelemetryBridge(
            onTelemetryReceived = { cat, payload -> logged.add(cat to payload) },
            onReportPayloadReceived = { json -> exported.add(json) },
            onScanStatusChanged = { status, detail -> statuses.add(status to detail) },
            onDirectHeapCoordsUpdated = { baseIdx, x, _, _, _, src ->
                directCoords.add(Triple(baseIdx, x, src))
            },
            onFloatDeltaPreviewUpdated = { json -> previews.add(json) }
        )

        // Unarmed export is rejected
        bridge.exportReport("""{"injected":true}""")
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(exported.isEmpty())
        assertEquals("ERR", logged.first().first)

        // Armed export succeeds
        bridge.armExport()
        bridge.exportReport("""{"url":"https://example.com"}""")
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, exported.size)

        // Status sanitization collapses newlines
        bridge.updateScanStatus("COMPLETED", "Relocked\n[581246]")
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(AsyncScanStatus.COMPLETED, statuses.first().first)
        assertEquals("Relocked [581246]", statuses.first().second)

        // NaN coordinates are ignored; finite coordinates are forwarded
        bridge.updateDirectHeapCoords(581246, Double.NaN, 0.0, 0.0, 67108864L, "window.HEAPF32")
        bridge.updateDirectHeapCoords(581246, 12.5, 3.0, -44.25, 67108864L, "window.HEAPF32")
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, directCoords.size)
        assertEquals(581246, directCoords.first().first)
        assertEquals(12.5, directCoords.first().second, 0.001)

        // Float preview JSON is forwarded
        bridge.updateFloatDeltaPreview("""{"updates":[{"heapIndex":581246,"currentValue":14.0}]}""")
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, previews.size)
        assertTrue(previews.first().contains("581246"))
    }

    @Test
    fun `lock profile store persists and clears saved lock profile cleanly`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = LockProfileStore(context)
        store.clearProfile()
        assertNull(store.loadProfile())

        val profile = SavedLockProfile(
            baseIndex = 581246,
            host = "example.com",
            aobPatternHex = "3F800000 ?? ?? ?? 40000000",
            floatPatternSummary = "-4:1.00  +4:2.00",
            anchorCount = 6,
            signatureJson = """{"version":1,"anchors":[{"offset":-4,"u32":1065353216}]}""",
            parentPointers = listOf(
                ParentPointerMatch(
                    pointerHeapIndex = 1024,
                    pointerByteAddress = 4096L,
                    targetByteAddress = 2324984L,
                    fieldOffsetBytes = 0,
                    isExactMatch = true
                )
            ),
            autoRelockOnReload = true,
            savedAtEpochMs = 1700000000000L
        )
        store.saveProfile(profile)

        val loaded = store.loadProfile()
        assertNotNull(loaded)
        assertEquals(581246, loaded!!.baseIndex)
        assertEquals("example.com", loaded.host)
        assertEquals(6, loaded.anchorCount)
        assertEquals(1, loaded.parentPointers.size)
        assertTrue(loaded.autoRelockOnReload)

        store.clearProfile()
        assertNull(store.loadProfile())
    }

    @Test
    fun `viewmodel processes float delta scanner pointer analyzer auto relock and reload reset`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        LockProfileStore(app).clearProfile()
        val vm = DiagnosticsViewModel(app)

        // 1. Capture baseline
        vm.onTelemetryFromBridge(
            "SCAN",
            """{"type":"FLOAT_BASELINE_CAPTURED","success":true,"sampledCount":18500,"prunedJitterCount":92,"cloneMethod":"buffer.slice","isIsolatedClone":true,"durationMs":11.4}"""
        )
        assertTrue(vm.uiState.value.floatScanner.baselineCaptured)
        assertEquals(18500, vm.uiState.value.floatScanner.baselineSampleCount)

        // 2. Movement delta scan returns ranked candidate
        vm.onTelemetryFromBridge(
            "SCAN",
            """{"type":"FLOAT_DELTA_SCANNED","success":true,"scanRound":1,"totalMatchesFound":4,"durationMs":4.2,"isLivePreviewing":true,"candidates":[{"heapIndex":581246,"byteAddress":2324984,"baselineValue":100.0,"currentValue":106.5,"delta":6.5,"previewY":1.0,"previewZ":-20.25,"isStillNow":true,"clusterSize":3,"axisHint":"X/Y/Z"}]}"""
        )
        assertEquals(1, vm.uiState.value.floatScanner.candidates.size)
        assertTrue(vm.uiState.value.floatScanner.isLivePreviewing)

        // 3. Live preview tick updates candidate in place
        vm.onFloatDeltaPreviewFromBridge(
            """{"updates":[{"heapIndex":581246,"currentValue":108.0,"delta":8.0,"previewY":1.0,"previewZ":-19.5,"isStillNow":false,"liveChangeCount":2}]}"""
        )
        val updatedCand = vm.uiState.value.floatScanner.candidates.first()
        assertEquals(108.0, updatedCand.currentValue, 0.001)
        assertEquals(2, updatedCand.liveChangeCount)

        // 4. Pointer & signature analysis populates Step 2 card and enables Save & Lock Profile
        vm.onTelemetryFromBridge(
            "PTR",
            """{"type":"POINTER_SIGNATURE_ANALYZED","success":true,"baseIndex":581246,"byteAddress":2324984,"aobPatternHex":"3F800000 ?? ?? ??","floatPatternSummary":"-4:1.00","anchorCount":5,"signatureJson":"{\"version\":1,\"anchors\":[{\"offset\":-4,\"u32\":1065353216}]}","parentPointers":[{"pointerHeapIndex":2048,"pointerByteAddress":8192,"targetByteAddress":2324984,"fieldOffsetBytes":0,"isExactMatch":true}],"surroundingSlots":[{"offset":0,"heapIndex":581246,"byteAddress":2324984,"floatValue":108.0,"uint32Hex":"0x42D80000","isCoordAxis":true,"isAnchor":false}],"analysisDurationMs":6.5}"""
        )
        assertEquals(5, vm.uiState.value.pointerAnalysis.anchorCount)
        assertEquals(1, vm.uiState.value.pointerAnalysis.parentPointers.size)

        vm.saveAndLockProfile()
        assertNotNull(vm.uiState.value.savedProfile)
        assertEquals(LockStatus.LOCKED, vm.uiState.value.heapFeed.lockStatus)

        // 5. Fresh reload clears stale baseline & candidates while keeping saved lock profile
        vm.onReloadRequested()
        assertFalse(vm.uiState.value.floatScanner.baselineCaptured)
        assertTrue(vm.uiState.value.floatScanner.candidates.isEmpty())
        assertEquals(LockStatus.UNLOCKED, vm.uiState.value.heapFeed.lockStatus)
        assertNotNull(vm.uiState.value.savedProfile)

        // 6. Auto-Relock restores LOCKED state on shifted base index
        vm.onTelemetryFromBridge(
            "RELOCK",
            """{"type":"AUTO_RELOCK_RESULT","success":true,"matchedBaseIndex":582010,"byteAddress":2328040,"confidencePercent":100,"methodUsed":"HEAPU32 Parent Pointer","durationMs":3.1,"x":110.25,"y":1.0,"z":-18.0,"heapByteLength":67108864,"heapSource":"window.HEAPF32"}"""
        )
        assertEquals(LockStatus.LOCKED, vm.uiState.value.heapFeed.lockStatus)
        assertEquals(582010, vm.uiState.value.heapFeed.baseIndex)
        assertEquals(100, vm.uiState.value.heapFeed.relockConfidencePercent)
    }

    @Test
    fun `binary asset inspector decodes wasm json and float32 bin headers via ByteBuffer`() {
        val wasmBytes = byteArrayOf(
            0x00, 0x61, 0x73, 0x6D,
            0x01, 0x00, 0x00, 0x00,
            0x01, 0x01, 0x00
        )
        val wasmAudit = BinaryAssetInspector.inspectBytes(wasmBytes, ".wasm")
        assertEquals("WASM v1", wasmAudit.magicSignature)
        assertTrue(wasmAudit.structuralDetails.contains("Type(1 B)"))
        assertTrue(wasmAudit.hexHeaderPreview.startsWith("00 61 73 6D 01 00 00 00"))

        val jsonBytes = """{"scene":"dungeon","entities":[1,2,3],"version":4}""".toByteArray(Charsets.UTF_8)
        val jsonAudit = BinaryAssetInspector.inspectBytes(jsonBytes, ".json", "application/json")
        assertEquals("JSON Object (3 keys)", jsonAudit.magicSignature)
        assertTrue(jsonAudit.structuralSummary.contains("scene"))

        val bb = java.nio.ByteBuffer.allocate(24).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        listOf(12.5f, 3.0f, -44.25f, 1.0f, 0.0f, 2.5f).forEach { bb.putFloat(it) }
        val binAudit = BinaryAssetInspector.inspectBytes(bb.array(), ".bin")
        assertEquals("Binary Float32 Buffer", binAudit.magicSignature)
        assertTrue(binAudit.structuralDetails.contains("12.50, 3.00, -44.25"))

        val safeName = BinaryAssetInspector.extractFileName("https://cdn.example.com/../../evil?x=1", ".bin")
        assertFalse(safeName.contains("/"))
        assertFalse(safeName.contains("\\"))
    }
}
