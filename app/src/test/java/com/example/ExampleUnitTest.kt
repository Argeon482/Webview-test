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
}
