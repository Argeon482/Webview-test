package com.example

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure JVM unit tests verifying read-only WASM JavaScript generators, isolated baseline cloning,
 * permissive movement delta thresholds, pointer/signature analysis, auto-relock, and ViewModel
 * formatting/validation helpers.
 */
class ExampleUnitTest {

    @Test
    fun directFeedAndFloatDeltaScriptsAreIsolatedNonBlockingAndStrictlyReadOnly() {
        val startFeed = WasmHeapScripts.buildStartDirectHeapFeedScript(VERIFIED_PLAYER_BASE_INDEX)
        val pauseFeed = WasmHeapScripts.buildPauseDirectHeapFeedScript()
        val baselineScript = WasmHeapScripts.buildCaptureFloatBaselineScript()
        val deltaScript = WasmHeapScripts.buildScanMovementDeltasScript()
        val resetScript = WasmHeapScripts.buildResetFloatScanScript()

        // Direct 200ms feed
        assertTrue(startFeed.contains("581246"))
        assertTrue(startFeed.contains("setInterval(pushSample, 200)"))
        assertTrue(startFeed.contains("updateDirectHeapCoords"))
        assertTrue(pauseFeed.contains("clearInterval(root.feedTimerId)"))

        // Step 1A: true isolated Float32Array baseline clone + active jitter pruning via NaN sentinel
        assertTrue(baselineScript.contains("(async function()"))
        assertTrue(baselineScript.contains("hf32.buffer.slice(0)"))
        assertTrue(baselineScript.contains("isolatedClone.buffer !== hf32.buffer"))
        assertTrue(baselineScript.contains("new Float32Array(len)"))
        assertTrue(baselineScript.contains("const START_INDEX = len > 4096 ? 256 : 0;"))
        assertTrue(baselineScript.contains("isolatedClone[i] = NaN;"))
        assertTrue(baselineScript.contains("FLOAT_BASELINE_CAPTURED"))

        // Step 1B: iterative narrowing, post-move stillness pruning, per-round baseline update, no full-heap reset
        assertTrue(deltaScript.contains("(async function()"))
        assertTrue(deltaScript.contains("const MIN_DELTA = 0.02;"))
        assertTrue(deltaScript.contains("const MAX_DELTA = 500.0;"))
        assertTrue(deltaScript.contains("absDiff >= MIN_DELTA && absDiff <= MAX_DELTA"))
        assertTrue(deltaScript.contains("const filteredMatches = stillMatches.length > 0 ? stillMatches : rawMatches;"))
        assertTrue(deltaScript.contains("base[idx] = liveF32[idx];"))
        assertTrue(deltaScript.contains("preservedDueToZeroDelta: true"))
        assertTrue(deltaScript.contains("FLOAT_DELTA_SCANNED"))
        assertTrue(deltaScript.contains("updateFloatDeltaPreview"))
        assertTrue(deltaScript.contains("X/Y/Z"))
        assertTrue(deltaScript.contains("X/_/Z"))

        // Reset cleans baseline clone and preview timer
        assertTrue(resetScript.contains("root.baselineClone = null"))
        assertTrue(resetScript.contains("clearInterval(root.previewTimerId)"))

        // Strictly read-only: never writes to live WASM heap arrays
        val heapWriteRegex = Regex("""(hf32|hu32|liveF32|HEAPF32|HEAPU32)\[[^\]]+]\s*=(?!=)""")
        assertFalse(heapWriteRegex.containsMatchIn(startFeed))
        assertFalse(heapWriteRegex.containsMatchIn(baselineScript))
        assertFalse(heapWriteRegex.containsMatchIn(deltaScript))
    }

    @Test
    fun pointerSignatureAutoRelockAndNearbyEntityScriptsAreValidAndReadOnly() {
        val ptrScript = WasmHeapScripts.buildAnalyzePointerSignatureScript(581246)
        val relockScript = WasmHeapScripts.buildAutoRelockScript(
            signatureJson = """{"version":1,"anchors":[{"offset":-4,"u32":1065353216,"f32":1.0}]}""",
            preferredBaseIndex = 581246,
            parentPointersJson = """[{"pointerHeapIndex":1024,"fieldOffsetBytes":16}]"""
        )
        val entityScript = WasmHeapScripts.buildScanNearbyEntitiesScript(581246, 50_000)

        // Step 2: ±16 memory inspection & reverse HEAPU32 parent pointer scan
        assertTrue(ptrScript.contains("for (let off = -16; off <= 16; off++)"))
        assertTrue(ptrScript.contains("POINTER_SIGNATURE_ANALYZED"))
        assertTrue(ptrScript.contains("minStructAddr"))

        // Step 3: 3-pass relock (direct verify, parent pointer dereference, chunked signature scan)
        assertTrue(relockScript.contains("Direct Index Verify"))
        assertTrue(relockScript.contains("HEAPU32 Parent Pointer"))
        assertTrue(relockScript.contains("AOB Struct Signature Scan"))
        assertTrue(relockScript.contains("AUTO_RELOCK_RESULT"))

        // Nearby ±50,000 entity struct scanner
        assertTrue(entityScript.contains("NEARBY_ENTITIES_SCANNED"))
        assertTrue(entityScript.contains("50000"))

        val heapWriteRegex = Regex("""(hf32|hu32|HEAPF32|HEAPU32)\[[^\]]+]\s*=(?!=)""")
        assertFalse(heapWriteRegex.containsMatchIn(ptrScript))
        assertFalse(heapWriteRegex.containsMatchIn(relockScript))
        assertFalse(heapWriteRegex.containsMatchIn(entityScript))
    }

    @Test
    fun urlValidationAssetMatchingHexFormattingAndLogDumpWorkOnPureJvm() {
        val valid = DiagnosticsViewModel.normalizeAndValidateUrl("example.com/game")
        assertTrue(valid is UrlValidationResult.Valid)
        assertEquals("https://example.com/game", (valid as UrlValidationResult.Valid).normalizedUrl)

        assertTrue(DiagnosticsViewModel.normalizeAndValidateUrl("") is UrlValidationResult.Invalid)
        assertTrue(DiagnosticsViewModel.normalizeAndValidateUrl("javascript:alert(1)") is UrlValidationResult.Invalid)
        assertTrue(DiagnosticsViewModel.normalizeAndValidateUrl("file:///etc/hosts") is UrlValidationResult.Invalid)

        assertEquals(".wasm", DiagnosticsViewModel.matchTrackedAssetExtension("/build/engine.wasm"))
        assertEquals(".json", DiagnosticsViewModel.matchTrackedAssetExtension("/assets/scene.json?v=2"))
        assertEquals(".bin", DiagnosticsViewModel.matchTrackedAssetExtension("/models/mesh.bin"))
        assertNull(DiagnosticsViewModel.matchTrackedAssetExtension("/styles/main.css"))

        assertEquals("0x0", DiagnosticsViewModel.formatHexAddress(0L))
        assertEquals("0x2379F8", DiagnosticsViewModel.formatHexAddress(581246L * 4L))
        assertEquals("0x0", DiagnosticsViewModel.formatHexAddress(-8L))

        val sampleState = DiagnosticsUiState(
            activeUrl = "https://example.com/game",
            pageTitle = "Demo World",
            heapFeed = DirectHeapFeedState(
                baseIndex = 581246,
                lockStatus = LockStatus.LOCKED,
                x = 12.5,
                y = 3.0,
                z = -44.25,
                tickCount = 7
            ),
            logs = listOf(
                TelemetryLogEntry(1L, "12:00:01.000", 1700000000000L, LogCategory.RELOCK, "Relocked [581246]", "ok")
            )
        )
        val dump = DiagnosticsViewModel.buildFullLogDumpText(sampleState)
        assertTrue(dump.contains("WebViewDiagnosticsApp Full Telemetry Dump"))
        assertTrue(dump.contains("[581246] (0x2379F8)"))
        assertTrue(dump.contains("X=12.500  Y=3.000  Z=-44.250"))
        assertTrue(dump.contains("Relocked [581246]"))
    }
}
