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
}
