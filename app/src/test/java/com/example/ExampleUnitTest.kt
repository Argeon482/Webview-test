package com.example

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Fast local JVM unit tests verifying:
 * - Step 1: Non-blocking Movement Float Delta Scanner (`window.HEAPF32`)
 * - Step 2: ±16 AOB/Float Signature Extractor & Reverse `window.HEAPU32` Pointer Lookup
 * - Step 3: Two-Tier Auto-Relock (Parent Pointer Dereference -> AOB/Float Scan)
 * - Strict read-only invariant (zero WASM heap writes, zero synthetic input dispatch)
 */
class ExampleUnitTest {

    private val heapWriteRegex = Regex("""(hf32|h32|hu32|liveF32|fNow|HEAPF32|HEAP32|HEAPU32)\[[^\]]+]\s*=(?!=)""")

    @Test
    fun verifiedPlayerBaseIndexAndByteAddressAreAccurate() {
        assertEquals(581246, VERIFIED_PLAYER_BASE_INDEX)
        val feed = DirectHeapFeedState()
        assertEquals(581246, feed.baseIndex)
        assertEquals(581246L * 4L, feed.byteAddress)
        assertEquals(LockStatus.UNLOCKED, feed.lockStatus)
    }

    @Test
    fun step1MovementFloatDeltaScriptsUseIsolatedBufferClonePermissiveDeltasAndReadOnly() {
        val baselineScript = WasmHeapScripts.buildCaptureStandingBaselineScript()
        val deltaScript = WasmHeapScripts.buildScanMovementDeltasScript()
        val resetScript = WasmHeapScripts.buildResetFloatScanScript()

        // Step 1A / 2A: True isolated Float32Array clone via buffer.slice() (never aliases live HEAPF32)
        assertTrue(baselineScript.contains("(async function()"))
        assertTrue(baselineScript.contains("window.HEAPF32"))
        assertTrue(baselineScript.contains("hf32.buffer.slice(hf32.byteOffset, hf32.byteOffset + hf32.byteLength)"))
        assertTrue(baselineScript.contains("new Float32Array(slicedBuffer)"))
        assertTrue(baselineScript.contains("clonedHeap.buffer === hf32.buffer"))
        assertTrue(baselineScript.contains("FLOAT_BASELINE_CAPTURED"))

        // Step 1B / 2B: Permissive 0.02..500.0 movement deltas + X/Y/Z Vec3 clustering + 200ms live preview
        assertTrue(deltaScript.contains("(async function()"))
        assertTrue(deltaScript.contains("var MIN_DELTA = 0.02;"))
        assertTrue(deltaScript.contains("var MAX_DELTA = 500.0;"))
        assertTrue(deltaScript.contains("st.baselineHeap"))
        assertTrue(deltaScript.contains("movedNext2 && validMidY"))
        assertTrue(deltaScript.contains("FLOAT_DELTAS_SCANNED"))
        assertTrue(deltaScript.contains("updateFloatDeltaPreview"))

        assertTrue(resetScript.contains("st.baselineHeap = null"))

        listOf(baselineScript, deltaScript, resetScript).forEach { script ->
            assertFalse("Step 1 must never write to WASM heap", heapWriteRegex.containsMatchIn(script))
            assertFalse("Step 1 must never dispatch synthetic input", script.contains("dispatchEvent"))
        }
    }

    @Test
    fun step2PointerAndSignatureScriptExtracts16SlotAobAndReverseScansHeapU32ReadOnly() {
        val analyzeScript = WasmHeapScripts.buildAnalyzePointerAndSignatureScript(VERIFIED_PLAYER_BASE_INDEX)

        assertTrue(analyzeScript.contains("(async function()"))
        assertTrue(analyzeScript.contains("__wvExtractStructSignature(res, 581246, 16)"))
        assertTrue(analyzeScript.contains("window.HEAPU32"))
        assertTrue(analyzeScript.contains("var targetByteAddr = (581246 * 4) >>> 0;"))
        assertTrue(analyzeScript.contains("setTimeout(resolve, 12)"))
        assertTrue(analyzeScript.contains("POINTER_SIGNATURE_ANALYZED"))
        assertTrue(analyzeScript.contains("aobPatternHex"))
        assertTrue(analyzeScript.contains("parentPointers"))

        assertFalse("Step 2 must never write to WASM heap", heapWriteRegex.containsMatchIn(analyzeScript))
        assertFalse("Step 2 must never dispatch synthetic input", analyzeScript.contains("dispatchEvent"))
    }

    @Test
    fun step3AutoRelockAndDirectFeedScriptsSupportPointerDerefAndSignatureFallbackReadOnly() {
        val feedScript = WasmHeapScripts.buildStartDirectFeedScript(VERIFIED_PLAYER_BASE_INDEX)
        val relockScript = WasmHeapScripts.buildAutoRelockScript(
            currentBaseIndex = VERIFIED_PLAYER_BASE_INDEX,
            savedSignatureJson = """{"v":2,"anchors":[{"off":-4,"u32":1065353216}]}""",
            savedPointersJson = """[{"pointerHeapIndex":1024,"fieldOffsetBytes":16}]"""
        )
        val entityScanScript = WasmHeapScripts.buildScanNearbyEntitiesScript(VERIFIED_PLAYER_BASE_INDEX, 50_000)

        assertTrue(feedScript.contains("setInterval(pollTick, 200)"))
        assertTrue(feedScript.contains("updateDirectHeapCoords"))

        // Two-tier relock: Tier 1 O(1) Parent Pointer Dereference -> Tier 2 AOB/Float Signature Scan
        assertTrue(relockScript.contains("Parent Pointer Dereference"))
        assertTrue(relockScript.contains("AOB / Float Signature Scan"))
        assertTrue(relockScript.contains("RELOCK_COMPLETE"))
        assertTrue(relockScript.contains("setTimeout(resolve, 12)"))

        assertTrue(entityScanScript.contains("ENTITIES_SCANNED"))
        assertTrue(entityScanScript.contains("50000"))

        listOf(feedScript, relockScript, entityScanScript).forEach { script ->
            assertFalse("Script must never write to WASM heap", heapWriteRegex.containsMatchIn(script))
            assertFalse("Script must never dispatch synthetic input", script.contains("dispatchEvent"))
        }
    }

    @Test
    fun manualJsEvalEscapesQuotesWithoutAndroidOrgJson() {
        val evalScript = WasmHeapScripts.buildManualEvalScript("window.HEAPF32.slice(581246, 581249)\n// \"ok\"")
        assertTrue(evalScript.contains("MANUAL_EVAL"))
        assertTrue(evalScript.contains("""\n// \"ok\""""))
    }

    @Test
    fun urlValidationAndBinaryInspectorWorkOnPureJvm() {
        val normalized = DiagnosticsViewModel.validateAndNormalizeUrl("example.com/game")
        assertTrue(normalized is UrlValidationResult.Valid)
        assertEquals("https://example.com/game", (normalized as UrlValidationResult.Valid).normalizedUrl)
        assertTrue(DiagnosticsViewModel.validateAndNormalizeUrl("javascript:alert(1)") is UrlValidationResult.Invalid)
        assertNull(DiagnosticsViewModel.extractTrackedAssetExtension("https://cdn.example.com/style.css"))

        val bb = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
        listOf(12.5f, 3.0f, -44.25f, 1.0f, 0.0f, 2.5f).forEach { bb.putFloat(it) }
        val binAudit = BinaryAssetInspector.inspectBytes(bb.array(), ".bin")
        assertEquals("Binary Float32 Buffer", binAudit.magicSignature)
    }
}
