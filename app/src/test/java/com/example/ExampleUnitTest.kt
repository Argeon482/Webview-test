package com.example

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Fast local JVM unit tests verifying verified `window.HEAPF32[581246..581248]` direct polling,
 * Auto-Relock signature scanning, ±50,000 nearby entity struct scanning, strict read-only safety,
 * URL validation, and binary asset inspection.
 */
class ExampleUnitTest {

    @Test
    fun verifiedPlayerBaseIndexAndByteAddressAreAccurate() {
        assertEquals(581246, VERIFIED_PLAYER_BASE_INDEX)
        val feed = DirectHeapFeedState()
        assertEquals(581246, feed.baseIndex)
        assertEquals(581246L * 4L, feed.byteAddress)
    }

    @Test
    fun directFeedScriptPollsVerifiedHeapF32IndicesAt200msAndIsStrictlyReadOnly() {
        val startScript = WasmHeapScripts.buildStartDirectFeedScript(
            baseIndex = VERIFIED_PLAYER_BASE_INDEX,
            savedSignatureJson = """{"v":1,"anchors":[{"off":-4,"bits":1065353216}]}"""
        )
        val stopScript = WasmHeapScripts.buildStopDirectFeedScript()

        // Targets verified player indices & 200ms direct polling loop
        assertTrue(startScript.contains("window.HEAPF32"))
        assertTrue(startScript.contains("581246"))
        assertTrue(startScript.contains("setInterval(pollTick, 200)"))
        assertTrue(startScript.contains("updateDirectHeapCoords"))
        assertTrue(startScript.contains("SIGNATURE_CAPTURED"))
        assertTrue(stopScript.contains("clearInterval(st.timerId)"))

        // Strictly read-only: zero writes to HEAPF32 / HEAP32 / hf32 / h32
        val heapWriteRegex = Regex("""(hf32|h32|HEAPF32|HEAP32)\[[^\]]+]\s*=(?!=)""")
        assertFalse("Direct feed must be strictly read-only", heapWriteRegex.containsMatchIn(startScript))
        assertFalse("Stop script must be strictly read-only", heapWriteRegex.containsMatchIn(stopScript))
    }

    @Test
    fun autoRelockAndNearbyEntityScriptsAreNonBlockingChunkedAndStrictlyReadOnly() {
        val captureSigScript = WasmHeapScripts.buildCaptureSignatureScript(VERIFIED_PLAYER_BASE_INDEX)
        val relockScript = WasmHeapScripts.buildAutoRelockScript(
            currentBaseIndex = VERIFIED_PLAYER_BASE_INDEX,
            savedSignatureJson = """{"v":1,"anchors":[{"off":-2,"bits":1065353216}]}"""
        )
        val entityScanScript = WasmHeapScripts.buildScanNearbyEntitiesScript(
            playerBaseIndex = VERIFIED_PLAYER_BASE_INDEX,
            searchRadius = 50_000
        )

        // Auto-Relock: async chunked rescan with yielding & signature matching
        assertTrue(captureSigScript.contains("__wvBuildSignature"))
        assertTrue(captureSigScript.contains("SIGNATURE_CAPTURED"))
        assertTrue(relockScript.contains("(async function()"))
        assertTrue(relockScript.contains("setTimeout(resolve, 12)"))
        assertTrue(relockScript.contains("RELOCK_COMPLETE"))
        assertTrue(relockScript.contains("updateDirectHeapCoords"))

        // Nearby Entity Scanner: ±50,000 radius, async slice yielding, stride detection
        assertTrue(entityScanScript.contains("(async function()"))
        assertTrue(entityScanScript.contains("50000"))
        assertTrue(entityScanScript.contains("setTimeout(resolve, 10)"))
        assertTrue(entityScanScript.contains("ENTITIES_SCANNED"))
        assertTrue(entityScanScript.contains("dominantStride"))

        // Strictly read-only: zero heap writes & zero input/touch dispatching
        val heapWriteRegex = Regex("""(hf32|h32|HEAPF32|HEAP32)\[[^\]]+]\s*=(?!=)""")
        listOf(captureSigScript, relockScript, entityScanScript).forEach { script ->
            assertFalse("Script must never write to WASM heap", heapWriteRegex.containsMatchIn(script))
            assertFalse("Script must never dispatch synthetic input events", script.contains("dispatchEvent"))
        }
    }

    @Test
    fun manualJsEvalEscapesQuotesAndNewlinesWithoutAndroidOrgJson() {
        val evalScript = WasmHeapScripts.buildManualEvalScript("window.HEAPF32.slice(581246, 581249)\n// \"check\"")
        assertTrue(evalScript.contains("MANUAL_EVAL"))
        assertTrue(evalScript.contains("""\\n""").not()) // single escaped \n in JS string literal
        assertTrue(evalScript.contains("""\n// \"check\""""))
    }

    @Test
    fun urlValidationAndAssetExtensionMatchingWorkCleanly() {
        val normalized = DiagnosticsViewModel.validateAndNormalizeUrl("example.com/game")
        assertTrue(normalized is UrlValidationResult.Valid)
        assertEquals("https://example.com/game", (normalized as UrlValidationResult.Valid).normalizedUrl)

        assertTrue(DiagnosticsViewModel.validateAndNormalizeUrl("javascript:alert(1)") is UrlValidationResult.Invalid)
        assertTrue(DiagnosticsViewModel.validateAndNormalizeUrl("file:///etc/passwd") is UrlValidationResult.Invalid)
        assertTrue(DiagnosticsViewModel.validateAndNormalizeUrl("   ") is UrlValidationResult.Invalid)

        assertEquals(".wasm", DiagnosticsViewModel.extractTrackedAssetExtension("https://cdn.example.com/app.wasm?v=3"))
        assertEquals(".bin", DiagnosticsViewModel.extractTrackedAssetExtension("https://cdn.example.com/mesh.bin"))
        assertEquals(".json", DiagnosticsViewModel.extractTrackedAssetExtension("https://cdn.example.com/map.json#top"))
        assertNull(DiagnosticsViewModel.extractTrackedAssetExtension("https://cdn.example.com/style.css"))
    }

    @Test
    fun binaryAssetInspectorDecodesWasmAndFloat32BuffersOnPureJvm() {
        val wasmBytes = byteArrayOf(
            0x00, 0x61, 0x73, 0x6D,
            0x01, 0x00, 0x00, 0x00,
            0x01, 0x01, 0x00
        )
        val wasmAudit = BinaryAssetInspector.inspectBytes(wasmBytes, ".wasm")
        assertEquals("WASM v1", wasmAudit.magicSignature)
        assertTrue(wasmAudit.structuralDetails.contains("Type(1 B)"))

        val bb = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
        listOf(12.5f, 3.0f, -44.25f, 1.0f, 0.0f, 2.5f).forEach { bb.putFloat(it) }
        val binAudit = BinaryAssetInspector.inspectBytes(bb.array(), ".bin")
        assertEquals("Binary Float32 Buffer", binAudit.magicSignature)
        assertTrue(binAudit.structuralDetails.contains("12.50, 3.00, -44.25"))
    }
}
