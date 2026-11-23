package com.example

import org.junit.Assert.*
import org.junit.Test

/**
 * Example local unit test, which will execute on the development machine (host).
 *
 * See [testing documentation](http://d.android.com/tools/testing).
 */
class ExampleUnitTest {
  @Test
  fun addition_isCorrect() {
    assertEquals(4, 2 + 2)
  }

  @Test
  fun step2BaselineAndDeltaScriptsExcludeWasmHeapsAndTypedArrays() {
    val step2a = DiagnosticsScripts.buildCaptureBaselineScript()
    val step2b = DiagnosticsScripts.buildComputeDeltaScript()

    listOf(
      "HEAP8", "HEAPU8", "HEAP16", "HEAPU16", "HEAP32", "HEAPU32",
      "HEAPF32", "HEAPF64", "HEAP64", "HEAPU64", "wasmTableMirror"
    ).forEach { key ->
      assertTrue("Missing exclusion for $key in Step 2A", step2a.contains("'$key'"))
      assertTrue("Missing exclusion for $key in Step 2B", step2b.contains("'$key'"))
    }

    assertTrue(step2a.contains("const MAX_TOTAL_MS = 4.5;"))
    assertTrue(step2a.contains("const MAX_DEPTH = 2;"))
    assertTrue(step2a.contains("__isSafeBaselineObject"))
    assertTrue(step2a.contains("__isExcludedBinaryOrWasmObject"))
    assertTrue(step2b.contains("__isExcludedWasmKey(p)"))
    assertTrue(step2b.contains("__isSafeBaselineObject(parentObj)"))
  }

  @Test
  fun wasmHeapScriptsSupportNonBlockingHeap32HeapF32ScanDeltaFilterAndNeighborInspection() {
    val scanScript = WasmHeapScripts.buildInitialScanScript(4338.0)
    val filterScript = WasmHeapScripts.buildFilterDeltaScript(4120.0)
    val inspectScript = WasmHeapScripts.buildInspectNeighborsScript("HEAP32", 12345)
    val stopScript = WasmHeapScripts.buildStopNeighborPollScript()
    val resetScript = WasmHeapScripts.buildResetTrackerScript()

    // Non-blocking chunked scanning across HEAP32 and HEAPF32
    assertTrue(scanScript.contains("(async function()"))
    assertTrue(scanScript.contains("setTimeout(resolve, 12)"))
    assertTrue(scanScript.contains("obj.HEAP32"))
    assertTrue(scanScript.contains("obj.HEAPF32"))
    assertTrue(scanScript.contains("WASM_HEAP_SCAN_COMPLETE"))
    assertTrue(scanScript.contains("4338.0"))

    // Value-Delta filter narrows stored candidates and auto-starts ±16B neighbor polling
    assertTrue(filterScript.contains("WASM_HEAP_DELTA_FILTERED"))
    assertTrue(filterScript.contains("__startWasmNeighborPoll"))
    assertTrue(filterScript.contains("4120.0"))

    // Neighboring Memory Inspector reads ±4..±16 byte offsets (step -4..+4) every 200ms
    assertTrue(inspectScript.contains("for (let step = -4; step <= 4; step++)"))
    assertTrue(inspectScript.contains("updateWasmNeighbors"))
    assertTrue(inspectScript.contains("setInterval(pushSnapshot, 200)"))
    assertTrue(inspectScript.contains("12345"))

    // Strictly read-only: never writes to heap indices (assignment '=' not followed by '=')
    val heapWriteRegex = Regex("""(h32|hf32|HEAP32|HEAPF32)\[[^\]]+]\s*=(?!=)""")
    assertFalse(heapWriteRegex.containsMatchIn(scanScript))
    assertFalse(heapWriteRegex.containsMatchIn(filterScript))
    assertFalse(heapWriteRegex.containsMatchIn(inspectScript))

    assertTrue(stopScript.contains("__stopWasmNeighborPoll()"))
    assertTrue(resetScript.contains("__wasm.candidates32 = []"))
  }

  @Test
  fun wasmHexAddressFormattingAndTargetInputSanitizationWorkWithoutAndroidRuntime() {
    assertEquals("0x00000000", DiagnosticsViewModel.formatHexAddress(0L))
    assertEquals("0x00008000", DiagnosticsViewModel.formatHexAddress(32768L))
    assertEquals("0x00000000", DiagnosticsViewModel.formatHexAddress(-4L))

    val vm = DiagnosticsViewModel()
    vm.onWasmTargetValueChanged("HP: 4338.5abc")
    assertEquals("4338.5", vm.uiState.value.wasmHeap.targetValueInput)
  }

  @Test
  fun wasmFloatHeapDeltaScriptsAreNonBlockingMemorySafeAndReadOnly() {
    val baselineScript = WasmHeapScripts.buildFloatBaselineSnapshotScript()
    val deltaScript = WasmHeapScripts.buildFloatDeltaScanScript()
    val pinScript = WasmHeapScripts.buildSyncPinnedFloatsScript(listOf(4096, 4097, 4098))
    val stopScript = WasmHeapScripts.buildStopFloatWatchScript()
    val resetScript = WasmHeapScripts.buildResetFloatDeltaScript()

    // Step 2A: true isolated Float32Array baseline clone (decoupled ArrayBuffer + indexed copy fallback)
    assertTrue(baselineScript.contains("(async function()"))
    assertTrue(baselineScript.contains("obj.HEAPF32"))
    assertTrue(baselineScript.contains("hf32.buffer.slice(0)"))
    assertTrue(baselineScript.contains("clone.buffer !== hf32.buffer"))
    assertTrue(baselineScript.contains("new Float32Array(len)"))
    assertTrue(baselineScript.contains("__fd.baselineClone = clone;"))
    assertTrue(baselineScript.contains("const START_INDEX = len > 4096 ? 256 : 0;"))
    assertTrue(baselineScript.contains("WASM_FLOAT_BASELINE_CAPTURED"))

    // Step 2B: permissive default thresholds (0.02..500.0), contiguous Vec3 X/Y/Z clustering, and 200ms Live Float Watcher
    assertTrue(deltaScript.contains("(async function()"))
    assertTrue(deltaScript.contains("Number(0.02)"))
    assertTrue(deltaScript.contains("Number(500.0)"))
    assertTrue(deltaScript.contains("absDiff >= MIN_DELTA && absDiff <= MAX_DELTA"))
    assertTrue(deltaScript.contains("scoredTriplets"))
    assertTrue(deltaScript.contains("WASM_FLOAT_DELTA_SCANNED"))
    assertTrue(deltaScript.contains("updateWasmFloatCandidates"))
    assertTrue(deltaScript.contains("setInterval(pushWatchTick, 200)"))
    assertTrue(deltaScript.contains("['X', 'Y', 'Z']"))

    // Pin sync, stop, and reset
    assertTrue(pinScript.contains("__fd.pinnedIndices = [4096,4097,4098]"))
    assertTrue(stopScript.contains("__stopFloatWatch()"))
    assertTrue(resetScript.contains("__fd.baselineClone = null"))

    // Strictly read-only on live HEAPF32
    val floatHeapWriteRegex = Regex("""(hf32|liveHf32|HEAPF32)\[[^\]]+]\s*=(?!=)""")
    assertFalse(floatHeapWriteRegex.containsMatchIn(baselineScript))
    assertFalse(floatHeapWriteRegex.containsMatchIn(deltaScript))
    assertFalse(floatHeapWriteRegex.containsMatchIn(pinScript))
  }

  @Test
  fun freshReloadClearsStaleWasmFloatBaselineState() {
    val vm = DiagnosticsViewModel()
    vm.onReloadRequested()
    assertFalse(vm.uiState.value.wasmFloatDelta.baselineCaptured)
    assertTrue(vm.uiState.value.wasmFloatDelta.candidates.isEmpty())

    vm.onPageStarted("https://example.com/game")
    assertFalse(vm.uiState.value.wasmFloatDelta.baselineCaptured)
    assertFalse(vm.uiState.value.wasmFloatDelta.isLiveWatching)
    assertEquals(0, vm.uiState.value.wasmFloatDelta.scanRound)
  }
}
