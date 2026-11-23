package com.example

import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
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
        assertEquals("WebViewDiagnosticsApp", context.getString(R.string.app_name))
    }

    @Test
    fun `lock profile store persists signature and parent pointers in SharedPreferences`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = LockProfileStore(context)
        store.clearProfile()
        assertNull(store.loadProfile())

        val profile = SavedLockProfile(
            baseIndex = 581246,
            host = "example.com",
            aobPatternHex = "[-4]:3F800000 [+3]:00000001",
            floatPatternSummary = "[-4]=1.0",
            anchorCount = 8,
            signatureJson = """{"v":2,"anchors":[{"off":-4,"u32":1065353216}]}""",
            parentPointers = listOf(
                ParentPointerMatch(
                    pointerHeapIndex = 4096,
                    pointerByteAddress = 16384L,
                    targetByteAddress = 581246L * 4L,
                    fieldOffsetBytes = 0,
                    isExactMatch = true
                )
            ),
            autoRelockOnReload = true,
            savedAtEpochMs = 1700000000000L
        )
        store.saveProfile(profile)

        val restored = store.loadProfile()
        assertNotNull(restored)
        assertEquals(581246, restored!!.baseIndex)
        assertEquals(8, restored.anchorCount)
        assertEquals(1, restored.parentPointers.size)
        assertEquals(4096, restored.parentPointers.first().pointerHeapIndex)
        assertTrue(restored.autoRelockOnReload)

        store.clearProfile()
        assertNull(store.loadProfile())
    }

    @Test
    fun `telemetry bridge forwards direct 200ms coords and live delta previews on main thread`() {
        val coords = mutableListOf<Triple<Int, Double, String>>()
        val previews = mutableListOf<String>()
        val exported = mutableListOf<String>()

        val bridge = TelemetryBridge(
            onTelemetryReceived = { _, _ -> },
            onReportPayloadReceived = { exported.add(it) },
            onDirectHeapCoordsUpdated = { idx, x, _, _, _, src -> coords.add(Triple(idx, x, src)) },
            onFloatDeltaPreviewUpdated = { previews.add(it) }
        )

        bridge.exportReport("""{"blocked":true}""")
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(exported.isEmpty())

        bridge.armExport()
        bridge.exportReport("""{"ok":true}""")
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, exported.size)

        bridge.updateDirectHeapCoords(581246, 128.5, 4.25, -92.75, 67_108_864L, "window.HEAPF32")
        bridge.updateFloatDeltaPreview("""{"updates":[{"heapIndex":581246,"currentValue":129.0}]}""")
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, coords.size)
        assertEquals(128.5, coords.first().second, 0.001)
        assertEquals(1, previews.size)
    }

    @Test
    fun `viewmodel runs full step 1 scan step 2 pointer analysis profile save and step 3 relock`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        LockProfileStore(app).clearProfile()
        val vm = DiagnosticsViewModel(app)

        // Step 1A + 1B: Baseline & Movement Delta candidates
        vm.onTelemetryFromBridge(
            "SCAN",
            """{"type":"FLOAT_BASELINE_CAPTURED","sampledCount":18000,"prunedJitterCount":92,"durationMs":24.0}"""
        )
        assertTrue(vm.uiState.value.floatScanner.baselineCaptured)

        vm.onTelemetryFromBridge(
            "SCAN",
            """{"type":"FLOAT_DELTAS_SCANNED","scanRound":1,"totalMatches":4,"stillCount":3,"durationMs":11.2,"candidates":[{"heapIndex":581246,"byteAddress":2324984,"baselineValue":100.0,"currentValue":104.5,"delta":4.5,"previewY":12.0,"previewZ":-40.0,"isStillNow":true,"clusterSize":3,"axisHint":"X"}]}"""
        )
        assertEquals(1, vm.uiState.value.floatScanner.candidates.size)

        // Step 2: Pointer & Signature analysis + Save & Lock Profile into SharedPreferences
        vm.onTelemetryFromBridge(
            "PTR",
            """{"type":"POINTER_SIGNATURE_ANALYZED","baseIndex":581246,"byteAddress":2324984,"aobPatternHex":"[-4]:3F800000 [+3]:00000001","floatPatternSummary":"[-4]=1.0","anchorCount":6,"signatureJson":"{\"v\":2,\"anchors\":[{\"off\":-4,\"u32\":1065353216}]}","parentPointers":[{"pointerHeapIndex":2048,"pointerByteAddress":8192,"targetByteAddress":2324984,"fieldOffsetBytes":0,"isExactMatch":true}],"surroundingSlots":[],"durationMs":31.5}"""
        )
        assertEquals(LockStatus.LOCKED, vm.uiState.value.heapFeed.lockStatus)
        assertEquals(1, vm.uiState.value.pointerAnalysis.parentPointers.size)

        vm.saveAndLockProfile()
        assertNotNull(vm.uiState.value.savedProfile)

        // Step 3: Relock after reload restores LOCKED state on relocated index
        vm.onPageStarted("https://example.com/reloaded")
        assertEquals(LockStatus.UNLOCKED, vm.uiState.value.heapFeed.lockStatus)
        assertNotNull(vm.uiState.value.savedProfile)

        vm.onTelemetryFromBridge(
            "RELOCK",
            """{"type":"RELOCK_COMPLETE","oldBaseIndex":581246,"newBaseIndex":600120,"confidencePercent":96,"matchedAnchors":6,"totalAnchors":6,"method":"Parent Pointer Dereference","x":105.25,"y":12.0,"z":-39.5,"durationMs":0.6,"heapByteLength":67108864,"heapSource":"window.HEAPF32","note":"Instant pointer relock -> HEAPF32[600120] (96% match)"}"""
        )
        assertEquals(LockStatus.LOCKED, vm.uiState.value.heapFeed.lockStatus)
        assertEquals(600120, vm.uiState.value.heapFeed.baseIndex)
        assertEquals(96, vm.uiState.value.heapFeed.relockConfidencePercent)

        LockProfileStore(app).clearProfile()
    }
}
