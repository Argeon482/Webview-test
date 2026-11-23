package com.example

/**
 * Pure read-only JavaScript inspection routines for WebAssembly `window.HEAPF32` & `window.HEAPU32`
 * scanning, pointer/AOB signature analysis, and persistent coordinate relocking.
 *
 * Strictly observational:
 * - Zero memory writes to `HEAPF32` / `HEAP32` / `HEAPU32` / `HEAPU8`
 * - Zero touch, keyboard, mouse, or navigation dispatching
 * - Zero `org.json` dependencies so script generation stays 100% testable on pure JVM
 */
object WasmHeapScripts {

    const val DEFAULT_POLL_INTERVAL_MS = 200
    const val DEFAULT_ENTITY_SCAN_RADIUS = 50_000

    internal fun quoteJsString(value: String): String {
        val sb = StringBuilder(value.length + 16)
        sb.append('"')
        for (ch in value) {
            when (ch) {
                '\\' -> sb.append("\\\\")
                '"' -> sb.append("\\\"")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                '\u2028' -> sb.append("\\u2028")
                '\u2029' -> sb.append("\\u2029")
                else -> {
                    if (ch.code < 0x20) {
                        sb.append("\\u")
                        sb.append(ch.code.toString(16).padStart(4, '0'))
                    } else {
                        sb.append(ch)
                    }
                }
            }
        }
        sb.append('"')
        return sb.toString()
    }

    /**
     * Shared read-only helper resolving synchronized `Float32Array` (`hf32`), `Int32Array` (`h32`),
     * and `Uint32Array` (`hu32`) views over the active WebAssembly linear memory buffer.
     */
    private const val HEAP_RESOLVER_JS = """
function __wvResolveWasmHeap() {
  try {
    var fromBuffer = function(buf, byteOffset, byteLength, label) {
      if (!buf || byteLength < 64) return null;
      var alignedOffset = (byteOffset || 0);
      var alignedCount = Math.floor(byteLength / 4);
      if ((alignedOffset % 4) !== 0 || alignedCount < 16) return null;
      return {
        hf32: new Float32Array(buf, alignedOffset, alignedCount),
        h32: new Int32Array(buf, alignedOffset, alignedCount),
        hu32: new Uint32Array(buf, alignedOffset, alignedCount),
        length: alignedCount,
        byteLength: alignedCount * 4,
        label: label
      };
    };

    var makeFromF32 = function(f32, label) {
      if (!f32 || !(f32 instanceof Float32Array) || f32.length < 16) return null;
      var i32 = null;
      var u32 = null;
      try {
        if ((f32.byteOffset % 4) === 0) {
          i32 = new Int32Array(f32.buffer, f32.byteOffset, f32.length);
          u32 = new Uint32Array(f32.buffer, f32.byteOffset, f32.length);
        }
      } catch (_e) {}
      return {
        hf32: f32,
        h32: i32,
        hu32: u32,
        length: f32.length,
        byteLength: f32.byteLength,
        label: label
      };
    };

    if (window.HEAPF32 instanceof Float32Array && window.HEAPF32.length >= 16) {
      return makeFromF32(window.HEAPF32, 'window.HEAPF32');
    }
    if (window.Module && window.Module.HEAPF32 instanceof Float32Array && window.Module.HEAPF32.length >= 16) {
      return makeFromF32(window.Module.HEAPF32, 'window.Module.HEAPF32');
    }
    if (window.HEAPU32 instanceof Uint32Array && window.HEAPU32.length >= 16) {
      return fromBuffer(window.HEAPU32.buffer, window.HEAPU32.byteOffset, window.HEAPU32.byteLength, 'window.HEAPU32->Float32');
    }
    if (window.HEAP32 instanceof Int32Array && window.HEAP32.length >= 16) {
      return fromBuffer(window.HEAP32.buffer, window.HEAP32.byteOffset, window.HEAP32.byteLength, 'window.HEAP32->Float32');
    }
    if (window.Module && window.Module.HEAPU32 instanceof Uint32Array && window.Module.HEAPU32.length >= 16) {
      return fromBuffer(window.Module.HEAPU32.buffer, window.Module.HEAPU32.byteOffset, window.Module.HEAPU32.byteLength, 'window.Module.HEAPU32->Float32');
    }
    if (window.Module && window.Module.HEAP32 instanceof Int32Array && window.Module.HEAP32.length >= 16) {
      return fromBuffer(window.Module.HEAP32.buffer, window.Module.HEAP32.byteOffset, window.Module.HEAP32.byteLength, 'window.Module.HEAP32->Float32');
    }
    if (window.HEAPU8 instanceof Uint8Array && window.HEAPU8.byteLength >= 64) {
      return fromBuffer(window.HEAPU8.buffer, window.HEAPU8.byteOffset, window.HEAPU8.byteLength, 'window.HEAPU8->Float32');
    }
    if (window.Module && window.Module.HEAPU8 instanceof Uint8Array && window.Module.HEAPU8.byteLength >= 64) {
      return fromBuffer(window.Module.HEAPU8.buffer, window.Module.HEAPU8.byteOffset, window.Module.HEAPU8.byteLength, 'window.Module.HEAPU8->Float32');
    }
    if (window.wasmMemory && window.wasmMemory.buffer) {
      return fromBuffer(window.wasmMemory.buffer, 0, window.wasmMemory.buffer.byteLength, 'window.wasmMemory');
    }
    if (window.Module && window.Module.wasmMemory && window.Module.wasmMemory.buffer) {
      return fromBuffer(window.Module.wasmMemory.buffer, 0, window.Module.wasmMemory.buffer.byteLength, 'window.Module.wasmMemory');
    }
    var roots = ['game', 'app', 'engine', 'unityInstance', 'Unity', 'instance'];
    for (var r = 0; r < roots.length; r++) {
      var rootName = roots[r];
      var obj = window[rootName];
      if (!obj || typeof obj !== 'object') continue;
      if (obj.HEAPF32 instanceof Float32Array && obj.HEAPF32.length >= 16) {
        return makeFromF32(obj.HEAPF32, 'window.' + rootName + '.HEAPF32');
      }
      if (obj.Module && obj.Module.HEAPF32 instanceof Float32Array && obj.Module.HEAPF32.length >= 16) {
        return makeFromF32(obj.Module.HEAPF32, 'window.' + rootName + '.Module.HEAPF32');
      }
    }
  } catch (_err) {}
  return null;
}

function __wvIsPlausibleCoord(v) {
  if (typeof v !== 'number' || !Number.isFinite(v)) return false;
  var av = Math.abs(v);
  if (av > 1000000) return false;
  if (av > 0 && av < 1e-6) return false;
  return true;
}

function __wvHexU32(u) {
  var h = ((u >>> 0).toString(16)).toUpperCase();
  while (h.length < 8) h = '0' + h;
  return h;
}

function __wvEnsureState(defaultBase) {
  return window.__wvDiagState || (window.__wvDiagState = {
    feedTimerId: null,
    deltaPreviewTimerId: null,
    baseIndex: defaultBase,
    signature: null,
    parentPointers: [],
    relockToken: 0,
    entityScanToken: 0,
    floatScanToken: 0,
    analyzeToken: 0,
    notifiedMissing: false,
    baselineHeap: null,
    baselineLength: 0,
    jitterMask: null,
    prevMovedMap: null,
    deltaCandidates: [],
    scanRound: 0
  });
}

function __wvExtractStructSignature(res, baseIndex, radius) {
  var hf32 = res.hf32;
  var hu32 = res.hu32;
  var len = res.length;
  var rad = radius || 16;
  if (baseIndex < 0 || baseIndex + 2 >= len) return null;

  var anchors = [];
  var zeroOffsets = [];
  var slots = [];

  for (var off = -rad; off <= rad; off++) {
    var idx = baseIndex + off;
    if (idx < 0 || idx >= len) continue;
    var uVal = hu32 ? (hu32[idx] >>> 0) : 0;
    var fVal = hf32[idx];
    var isCoord = (off === 0 || off === 1 || off === 2);
    slots.push({
      offset: off,
      heapIndex: idx,
      byteAddress: idx * 4,
      floatValue: Number.isFinite(fVal) ? (Math.round(fVal * 10000) / 10000) : 0,
      uint32Hex: '0x' + __wvHexU32(uVal),
      isCoordAxis: isCoord,
      isAnchor: false
    });

    if (isCoord) continue;
    if (uVal === 0) {
      if (zeroOffsets.length < 10) zeroOffsets.push(off);
      continue;
    }

    var isCleanFloat = Number.isFinite(fVal) && Math.abs(fVal) >= 1e-3 && Math.abs(fVal) <= 10000;
    var isHeaderWord = (uVal > 0 && uVal <= 0x00FFFFFF) || ((uVal & 0xFFFF) === 0 && uVal > 0);
    var pri = 1;
    if (isHeaderWord) pri = 3;
    else if (isCleanFloat) pri = 2;

    anchors.push({
      off: off,
      u32: uVal,
      hex: __wvHexU32(uVal),
      fval: isCleanFloat ? (Math.round(fVal * 10000) / 10000) : null,
      pri: pri
    });
  }

  anchors.sort(function(a, b) {
    if (b.pri !== a.pri) return b.pri - a.pri;
    return Math.abs(a.off) - Math.abs(b.off);
  });
  var selected = anchors.slice(0, 14);
  var anchorOffsetMap = Object.create(null);
  for (var s = 0; s < selected.length; s++) {
    anchorOffsetMap[selected[s].off] = true;
  }
  for (var k = 0; k < slots.length; k++) {
    if (anchorOffsetMap[slots[k].offset]) slots[k].isAnchor = true;
  }

  // Build readable AOB pattern string & float pattern summary sorted by memory offset
  var ordered = selected.slice().sort(function(a, b) { return a.off - b.off; });
  var aobParts = [];
  var floatParts = [];
  for (var p = 0; p < ordered.length; p++) {
    var item = ordered[p];
    var sign = item.off >= 0 ? ('+' + item.off) : String(item.off);
    aobParts.push('[' + sign + ']:' + item.hex);
    if (item.fval !== null && floatParts.length < 6) {
      floatParts.push('[' + sign + ']=' + item.fval);
    }
  }

  var sigObj = {
    v: 2,
    capturedAtIndex: baseIndex,
    capturedCoords: [hf32[baseIndex], hf32[baseIndex + 1], hf32[baseIndex + 2]],
    anchors: selected,
    zeroOffsets: zeroOffsets.slice(0, 6)
  };

  return {
    signature: sigObj,
    aobPatternHex: aobParts.join(' '),
    floatPatternSummary: floatParts.length > 0 ? floatParts.join(', ') : 'Uint32 header anchors only',
    slots: slots
  };
}
"""

    // =========================================================================
    // Permanent 200ms Direct Coordinate Feed
    // =========================================================================

    fun buildStartDirectFeedScript(
        baseIndex: Int = VERIFIED_PLAYER_BASE_INDEX,
        savedSignatureJson: String? = null
    ): String {
        val safeBase = baseIndex.coerceAtLeast(0)
        val sigLiteral = if (savedSignatureJson.isNullOrBlank()) "null" else quoteJsString(savedSignatureJson)
        return """
(function() {
  $HEAP_RESOLVER_JS
  try {
    var st = __wvEnsureState($safeBase);
    if (st.feedTimerId !== null) {
      clearInterval(st.feedTimerId);
      st.feedTimerId = null;
    }
    st.baseIndex = $safeBase;
    st.notifiedMissing = false;

    var persistedSigStr = $sigLiteral;
    if (persistedSigStr) {
      try {
        var parsedSig = JSON.parse(persistedSigStr);
        if (parsedSig && Array.isArray(parsedSig.anchors)) {
          st.signature = parsedSig;
        }
      } catch (_sigErr) {}
    }

    var pollTick = function() {
      var res = __wvResolveWasmHeap();
      var idx = st.baseIndex;
      if (!res || idx < 0 || idx + 2 >= res.length) {
        if (!st.notifiedMissing) {
          st.notifiedMissing = true;
          if (window.TelemetryBridge) {
            window.TelemetryBridge.logTelemetry('FEED', JSON.stringify({
              type: 'FEED_STATUS',
              active: true,
              heapReady: false,
              baseIndex: idx,
              message: res
                ? ('Index ' + idx + ' out of bounds (heap len ' + res.length + ')')
                : 'Waiting for window.HEAPF32 initialization...'
            }));
          }
        }
        return;
      }

      st.notifiedMissing = false;
      var x = res.hf32[idx];
      var y = res.hf32[idx + 1];
      var z = res.hf32[idx + 2];
      if (!Number.isFinite(x) || !Number.isFinite(y) || !Number.isFinite(z)) return;

      if (window.TelemetryBridge && window.TelemetryBridge.updateDirectHeapCoords) {
        window.TelemetryBridge.updateDirectHeapCoords(idx, x, y, z, res.byteLength, res.label);
      }
    };

    pollTick();
    st.feedTimerId = setInterval(pollTick, $DEFAULT_POLL_INTERVAL_MS);

    if (window.TelemetryBridge) {
      window.TelemetryBridge.updateScanStatus('COMPLETED', 'LIVE 200ms @ HEAPF32[' + $safeBase + '..' + ($safeBase + 2) + ']');
      window.TelemetryBridge.logTelemetry('FEED', JSON.stringify({
        type: 'FEED_STATUS',
        active: true,
        heapReady: !!__wvResolveWasmHeap(),
        baseIndex: $safeBase,
        message: 'Direct 200ms polling active on window.HEAPF32[' + $safeBase + ', ' + ($safeBase + 1) + ', ' + ($safeBase + 2) + ']'
      }));
    }
  } catch (e) {
    if (window.TelemetryBridge) {
      window.TelemetryBridge.logTelemetry('ERR', 'Direct feed startup error: ' + String(e));
    }
  }
})();
""".trimIndent()
    }

    fun buildStopDirectFeedScript(): String = """
(function() {
  try {
    var st = window.__wvDiagState;
    if (st && st.feedTimerId !== null) {
      clearInterval(st.feedTimerId);
      st.feedTimerId = null;
    }
    if (window.TelemetryBridge) {
      window.TelemetryBridge.updateScanStatus('IDLE', 'Direct feed paused');
      window.TelemetryBridge.logTelemetry('FEED', JSON.stringify({
        type: 'FEED_STATUS',
        active: false,
        heapReady: true,
        baseIndex: st ? st.baseIndex : $VERIFIED_PLAYER_BASE_INDEX,
        message: 'Direct 200ms HEAPF32 feed paused.'
      }));
    }
  } catch (e) {
    if (window.TelemetryBridge) {
      window.TelemetryBridge.logTelemetry('ERR', 'Feed pause error: ' + String(e));
    }
  }
})();
""".trimIndent()

    // =========================================================================
    // Step 1: Movement Float Delta Scanner (window.HEAPF32)
    // =========================================================================

    /**
     * Step 1A / 2A ("Capture Standing Baseline"):
     * Creates a TRUE isolated clone of the active `window.HEAPF32` memory buffer via
     * `new Float32Array(hf32.buffer.slice(...))` (with manual indexed-loop fallback) so baseline
     * values NEVER alias or update live alongside character movement.
     */
    fun buildCaptureStandingBaselineScript(): String = """
(async function() {
  $HEAP_RESOLVER_JS
  try {
    var st = __wvEnsureState($VERIFIED_PLAYER_BASE_INDEX);
    var token = ++st.floatScanToken;
    if (st.deltaPreviewTimerId !== null) {
      clearInterval(st.deltaPreviewTimerId);
      st.deltaPreviewTimerId = null;
    }

    var res = __wvResolveWasmHeap();
    if (!res) {
      if (window.TelemetryBridge) {
        window.TelemetryBridge.updateScanStatus('ERROR', 'window.HEAPF32 not found');
        window.TelemetryBridge.logTelemetry('SCAN', JSON.stringify({
          type: 'FLOAT_BASELINE_ERROR',
          reason: 'window.HEAPF32 is not initialized on this page yet.'
        }));
      }
      return;
    }

    var hf32 = res.hf32;
    var len = res.length;
    var startTime = performance.now();

    if (window.TelemetryBridge) {
      window.TelemetryBridge.updateScanStatus('RUNNING', 'Cloning isolated Float32Array baseline...');
    }

    // Create a true, isolated Float32Array clone (never aliases live window.HEAPF32.buffer)
    var clonedHeap = null;
    try {
      var slicedBuffer = hf32.buffer.slice(hf32.byteOffset, hf32.byteOffset + hf32.byteLength);
      clonedHeap = new Float32Array(slicedBuffer);
    } catch (_sliceErr) {
      clonedHeap = new Float32Array(len);
      for (var c = 0; c < len; c++) {
        clonedHeap[c] = hf32[c];
      }
    }

    // Safety invariant: guarantee the clone does not share the live ArrayBuffer
    if (clonedHeap.buffer === hf32.buffer) {
      var manualClone = new Float32Array(len);
      for (var mc = 0; mc < len; mc++) {
        manualClone[mc] = hf32[mc];
      }
      clonedHeap = manualClone;
    }

    // Brief 100ms idle observation to flag high-frequency animated timers (> 0.05 jump while idle)
    // without mutating the isolated clone or dropping 0.0 / subtle server sync ticks.
    await new Promise(function(resolve) { setTimeout(resolve, 100); });
    if (st.floatScanToken !== token) return;

    var liveRes = __wvResolveWasmHeap();
    var liveF32 = liveRes ? liveRes.hf32 : hf32;
    var jitterMask = new Uint8Array(len);
    var startIndex = len > 4096 ? 64 : 0;
    var chunkSize = 524288;
    var validFloatCount = 0;
    var prunedJitter = 0;

    for (var s = startIndex; s < len; s += chunkSize) {
      if (st.floatScanToken !== token) return;
      var e = Math.min(len, s + chunkSize);
      for (var i = s; i < e; i++) {
        var bv = clonedHeap[i];
        if (!Number.isFinite(bv) || Math.abs(bv) > 100000) continue;
        validFloatCount++;
        if (i < liveF32.length) {
          var lv = liveF32[i];
          if (Number.isFinite(lv) && Math.abs(lv - bv) > 0.05) {
            jitterMask[i] = 1;
            prunedJitter++;
          }
        }
      }
      await new Promise(function(resolve) { setTimeout(resolve, 8); });
    }

    if (st.floatScanToken !== token) return;

    st.baselineHeap = clonedHeap;
    st.baselineLength = len;
    st.jitterMask = jitterMask;
    st.prevMovedMap = null;
    st.deltaCandidates = [];
    st.scanRound = 0;

    var durationMs = Math.round((performance.now() - startTime) * 10) / 10;
    if (window.TelemetryBridge) {
      window.TelemetryBridge.updateScanStatus('COMPLETED', 'Cloned ' + len + ' floats (' + durationMs + 'ms)');
      window.TelemetryBridge.logTelemetry('SCAN', JSON.stringify({
        type: 'FLOAT_BASELINE_CAPTURED',
        sampledCount: validFloatCount,
        clonedLength: len,
        prunedJitterCount: prunedJitter,
        isolatedClone: clonedHeap.buffer !== hf32.buffer,
        durationMs: durationMs,
        heapByteLength: res.byteLength,
        heapSource: res.label
      }));
    }
  } catch (e) {
    if (window.TelemetryBridge) {
      window.TelemetryBridge.updateScanStatus('ERROR', 'Baseline error');
      window.TelemetryBridge.logTelemetry('ERR', 'Float baseline error: ' + String(e));
    }
  }
})();
""".trimIndent()

    /**
     * Step 1B / 2B ("Scan Movement Deltas"):
     * Compares live `window.HEAPF32` against the isolated `baselineHeap` clone using permissive
     * movement thresholds (`0.02` to `500.0`), clusters adjacent `X, Y, Z` floats (including
     * flat-ground `X + Z` movement with constant `Y`), and streams live 200ms previews immediately.
     */
    fun buildScanMovementDeltasScript(
        minDelta: Double = 0.02,
        maxDelta: Double = 500.0
    ): String = """
(async function() {
  $HEAP_RESOLVER_JS
  try {
    var st = __wvEnsureState($VERIFIED_PLAYER_BASE_INDEX);
    var token = ++st.floatScanToken;

    if (!st.baselineHeap || st.baselineLength === 0) {
      if (window.TelemetryBridge) {
        window.TelemetryBridge.updateScanStatus('ERROR', 'Capture baseline first');
        window.TelemetryBridge.logTelemetry('SCAN', JSON.stringify({
          type: 'FLOAT_DELTA_ERROR',
          reason: 'Tap "1. Capture Standing Baseline" while standing still first.'
        }));
      }
      return;
    }

    var res = __wvResolveWasmHeap();
    if (!res) {
      if (window.TelemetryBridge) {
        window.TelemetryBridge.updateScanStatus('ERROR', 'window.HEAPF32 not found');
        window.TelemetryBridge.logTelemetry('SCAN', JSON.stringify({
          type: 'FLOAT_DELTA_ERROR',
          reason: 'window.HEAPF32 is not available.'
        }));
      }
      return;
    }

    var hf32 = res.hf32;
    var baseHeap = st.baselineHeap;
    var jitterMask = st.jitterMask;
    var scanLen = Math.min(res.length, st.baselineLength);
    var startIndex = scanLen > 4096 ? 64 : 0;
    var startTime = performance.now();
    var MIN_DELTA = $minDelta;
    var MAX_DELTA = $maxDelta;
    var chunkSize = 262144;
    var totalChunks = Math.max(1, Math.ceil((scanLen - startIndex) / chunkSize));

    if (window.TelemetryBridge) {
      window.TelemetryBridge.updateScanStatus('RUNNING', 'Scanning float deltas (' + MIN_DELTA + '..' + MAX_DELTA + ')...');
    }

    var hits = [];
    var movedSet = Object.create(null);
    var chunkNo = 0;

    for (var s = startIndex; s < scanLen; s += chunkSize) {
      if (st.floatScanToken !== token) return;
      chunkNo++;
      var e = Math.min(scanLen, s + chunkSize);
      for (var i = s; i < e; i++) {
        if (jitterMask && jitterMask[i] === 1) continue;
        var base = baseHeap[i];
        var cur = hf32[i];
        if (!Number.isFinite(base) || !Number.isFinite(cur)) continue;
        if (Math.abs(base) > 100000 || Math.abs(cur) > 100000) continue;

        var diff = cur - base;
        var absDiff = Math.abs(diff);
        if (absDiff >= MIN_DELTA && absDiff <= MAX_DELTA) {
          hits.push({
            idx: i,
            base: base,
            cur: cur,
            delta: diff
          });
          movedSet[i] = true;
          if (hits.length >= 30000) break;
        }
      }
      if (hits.length >= 30000) break;
      if (window.TelemetryBridge && (chunkNo % 4 === 0 || chunkNo === totalChunks)) {
        window.TelemetryBridge.updateScanStatus(
          'RUNNING',
          'Delta slice ' + chunkNo + '/' + totalChunks + ' (' + hits.length + ' hits)'
        );
      }
      await new Promise(function(resolve) { setTimeout(resolve, 10); });
    }

    if (st.floatScanToken !== token) return;

    // Soft 80ms stability check: NEVER discards moving/server-ticking coords; used strictly as ranking bonus
    await new Promise(function(resolve) { setTimeout(resolve, 80); });
    if (st.floatScanToken !== token) return;

    var liveRes = __wvResolveWasmHeap();
    var liveF32 = liveRes ? liveRes.hf32 : hf32;
    var stillCount = 0;
    var prevMap = st.prevMovedMap;
    var nextMovedMap = Object.create(null);

    // Group adjacent X, Y, Z floats (supports both full 3D movement & flat-ground X+Z movement)
    var enriched = [];
    for (var j = 0; j < hits.length; j++) {
      var item = hits[j];
      var idx = item.idx;
      var nowVal = idx < liveF32.length ? liveF32[idx] : item.cur;
      if (!Number.isFinite(nowVal)) nowVal = item.cur;
      var isStillNow = Math.abs(nowVal - item.cur) <= 0.02;
      if (isStillNow) stillCount++;

      var movedPrev2 = !!movedSet[idx - 2];
      var movedPrev1 = !!movedSet[idx - 1];
      var movedNext1 = !!movedSet[idx + 1];
      var movedNext2 = !!movedSet[idx + 2];

      var py = (idx + 1 < liveF32.length && Number.isFinite(liveF32[idx + 1])) ? liveF32[idx + 1] : 0;
      var pz = (idx + 2 < liveF32.length && Number.isFinite(liveF32[idx + 2])) ? liveF32[idx + 2] : 0;
      var validMidY = Math.abs(py) <= 100000;

      // Determine cluster size & axis role:
      // - Contiguous [X, Y, Z] all moved -> clusterSize = 3
      // - Flat-ground [X, static Y, Z] where idx & idx+2 moved -> clusterSize = 3, axisHint = 'X'
      // - Adjacent 2-axis pair [X, Y] -> clusterSize = 2
      var cluster = 1;
      var axis = 'X';
      if (!movedPrev1 && movedNext1 && movedNext2) {
        cluster = 3;
        axis = 'X';
      } else if (!movedPrev1 && !movedPrev2 && movedNext2 && validMidY) {
        cluster = 3;
        axis = 'X';
      } else if (movedPrev1 && movedNext1) {
        cluster = 3;
        axis = 'Y';
      } else if (movedPrev1 && movedPrev2) {
        cluster = 3;
        axis = 'Z';
      } else if (!movedPrev1 && movedPrev2 && !movedNext1) {
        cluster = 3;
        axis = 'Z';
      } else if (!movedPrev1 && movedNext1) {
        cluster = 2;
        axis = 'X';
      } else if (movedPrev1 && !movedNext1) {
        cluster = 2;
        axis = 'Y';
      }

      var repeatedHit = !!(prevMap && prevMap[idx] !== undefined && Math.abs(nowVal - prevMap[idx]) >= MIN_DELTA);
      nextMovedMap[idx] = nowVal;

      enriched.push({
        heapIndex: idx,
        byteAddress: idx * 4,
        baselineValue: Math.round(item.base * 1000) / 1000,
        currentValue: Math.round(nowVal * 1000) / 1000,
        delta: Math.round((nowVal - item.base) * 1000) / 1000,
        previewY: Math.round(py * 1000) / 1000,
        previewZ: Math.round(pz * 1000) / 1000,
        isStillNow: isStillNow,
        clusterSize: cluster,
        axisHint: axis,
        repeatedHit: repeatedHit
      });
    }

    // Rank Vec3 X-heads, multi-round confirmed movement, stopped coords, and realistic walking deltas first
    enriched.sort(function(a, b) {
      if (a.repeatedHit !== b.repeatedHit) return a.repeatedHit ? -1 : 1;
      var aVec3Head = (a.clusterSize >= 3 && a.axisHint === 'X') ? 1 : 0;
      var bVec3Head = (b.clusterSize >= 3 && b.axisHint === 'X') ? 1 : 0;
      if (bVec3Head !== aVec3Head) return bVec3Head - aVec3Head;
      var aPairHead = (a.clusterSize >= 2 && a.axisHint === 'X') ? 1 : 0;
      var bPairHead = (b.clusterSize >= 2 && b.axisHint === 'X') ? 1 : 0;
      if (bPairHead !== aPairHead) return bPairHead - aPairHead;
      if (b.clusterSize !== a.clusterSize) return b.clusterSize - a.clusterSize;
      if (a.isStillNow !== b.isStillNow) return a.isStillNow ? -1 : 1;
      return Math.abs(a.delta) - Math.abs(b.delta);
    });

    var topCandidates = enriched.slice(0, 80);
    st.prevMovedMap = nextMovedMap;
    st.deltaCandidates = topCandidates;
    st.scanRound = (st.scanRound || 0) + 1;

    // Immediately start 200ms live preview loop for displayed candidates
    if (st.deltaPreviewTimerId !== null) {
      clearInterval(st.deltaPreviewTimerId);
      st.deltaPreviewTimerId = null;
    }
    if (topCandidates.length > 0) {
      st.deltaPreviewTimerId = setInterval(function() {
        var rNow = __wvResolveWasmHeap();
        if (!rNow || !st.deltaCandidates || st.deltaCandidates.length === 0) return;
        var fNow = rNow.hf32;
        var updates = [];
        for (var u = 0; u < st.deltaCandidates.length; u++) {
          var c = st.deltaCandidates[u];
          if (c.heapIndex + 2 >= fNow.length) continue;
          var vx = fNow[c.heapIndex];
          var vy = fNow[c.heapIndex + 1];
          var vz = fNow[c.heapIndex + 2];
          if (!Number.isFinite(vx)) continue;
          updates.push({
            heapIndex: c.heapIndex,
            currentValue: Math.round(vx * 1000) / 1000,
            previewY: Number.isFinite(vy) ? (Math.round(vy * 1000) / 1000) : 0,
            previewZ: Number.isFinite(vz) ? (Math.round(vz * 1000) / 1000) : 0
          });
        }
        if (window.TelemetryBridge && window.TelemetryBridge.updateFloatDeltaPreview) {
          window.TelemetryBridge.updateFloatDeltaPreview(JSON.stringify({ updates: updates }));
        }
      }, $DEFAULT_POLL_INTERVAL_MS);
    }

    var durationMs = Math.round((performance.now() - startTime) * 10) / 10;
    if (window.TelemetryBridge) {
      window.TelemetryBridge.updateScanStatus('COMPLETED', 'Isolated ' + hits.length + ' movement deltas');
      window.TelemetryBridge.logTelemetry('SCAN', JSON.stringify({
        type: 'FLOAT_DELTAS_SCANNED',
        scanRound: st.scanRound,
        totalMatches: hits.length,
        stillCount: stillCount,
        durationMs: durationMs,
        candidates: topCandidates
      }));
    }
  } catch (e) {
    if (window.TelemetryBridge) {
      window.TelemetryBridge.updateScanStatus('ERROR', 'Delta scan error');
      window.TelemetryBridge.logTelemetry('ERR', 'Float delta scan error: ' + String(e));
    }
  }
})();
""".trimIndent()

    fun buildResetFloatScanScript(): String = """
(function() {
  try {
    var st = window.__wvDiagState;
    if (st) {
      st.floatScanToken++;
      if (st.deltaPreviewTimerId !== null) {
        clearInterval(st.deltaPreviewTimerId);
        st.deltaPreviewTimerId = null;
      }
      st.baselineHeap = null;
      st.baselineLength = 0;
      st.jitterMask = null;
      st.prevMovedMap = null;
      st.deltaCandidates = [];
      st.scanRound = 0;
    }
    if (window.TelemetryBridge) {
      window.TelemetryBridge.updateScanStatus('IDLE', 'Float delta scan reset');
    }
  } catch (_e) {}
})();
""".trimIndent()

    // =========================================================================
    // Step 2: Pointer & Memory Signature Analyzer (±16 block + HEAPU32 reverse pointer scan)
    // =========================================================================

    fun buildAnalyzePointerAndSignatureScript(baseIndex: Int): String {
        val safeBase = baseIndex.coerceAtLeast(0)
        return """
(async function() {
  $HEAP_RESOLVER_JS
  try {
    var st = __wvEnsureState($safeBase);
    var token = ++st.analyzeToken;
    var res = __wvResolveWasmHeap();
    if (!res) {
      if (window.TelemetryBridge) {
        window.TelemetryBridge.updateScanStatus('ERROR', 'window.HEAPF32 not found');
        window.TelemetryBridge.logTelemetry('PTR', JSON.stringify({
          type: 'POINTER_SIGNATURE_ERROR',
          reason: 'window.HEAPF32 / window.HEAPU32 is not initialized on this page.'
        }));
      }
      return;
    }

    if ($safeBase + 2 >= res.length) {
      if (window.TelemetryBridge) {
        window.TelemetryBridge.updateScanStatus('ERROR', 'Base index out of bounds');
        window.TelemetryBridge.logTelemetry('PTR', JSON.stringify({
          type: 'POINTER_SIGNATURE_ERROR',
          reason: 'Index ' + $safeBase + ' exceeds heap length (' + res.length + ').'
        }));
      }
      return;
    }

    var startTime = performance.now();
    if (window.TelemetryBridge) {
      window.TelemetryBridge.updateScanStatus('RUNNING', 'Analyzing ±16 block & scanning HEAPU32 pointers...');
    }

    // 1. Extract ±16 surrounding HEAPF32 / HEAPU32 memory block & AOB/Float signature
    var extracted = __wvExtractStructSignature(res, $safeBase, 16);
    if (!extracted) {
      if (window.TelemetryBridge) {
        window.TelemetryBridge.logTelemetry('PTR', JSON.stringify({
          type: 'POINTER_SIGNATURE_ERROR',
          reason: 'Unable to extract ±16 memory signature around index ' + $safeBase
        }));
      }
      return;
    }

    // 2. Non-blocking chunked Reverse Pointer Lookup across window.HEAPU32
    var hu32 = res.hu32;
    var targetByteAddr = ($safeBase * 4) >>> 0;
    var minStructBase = targetByteAddr >= 64 ? (targetByteAddr - 64) : 0;
    var exactPointers = [];
    var structBasePointers = [];

    if (hu32 && targetByteAddr > 0) {
      var len = hu32.length;
      var chunkSize = 262144;
      var totalChunks = Math.max(1, Math.ceil(len / chunkSize));
      var chunkNo = 0;

      for (var s = 0; s < len; s += chunkSize) {
        if (st.analyzeToken !== token) return;
        chunkNo++;
        var e = Math.min(len, s + chunkSize);
        for (var p = s; p < e; p++) {
          var val = hu32[p];
          if (val === targetByteAddr) {
            if (exactPointers.length < 16) {
              exactPointers.push({
                pointerHeapIndex: p,
                pointerByteAddress: p * 4,
                targetByteAddress: val,
                fieldOffsetBytes: 0,
                isExactMatch: true
              });
            }
          } else if (val >= minStructBase && val < targetByteAddr && ((targetByteAddr - val) % 4) === 0) {
            if (structBasePointers.length < 16) {
              structBasePointers.push({
                pointerHeapIndex: p,
                pointerByteAddress: p * 4,
                targetByteAddress: val,
                fieldOffsetBytes: targetByteAddr - val,
                isExactMatch: false
              });
            }
          }
        }
        if (window.TelemetryBridge && (chunkNo % 4 === 0 || chunkNo === totalChunks)) {
          window.TelemetryBridge.updateScanStatus(
            'RUNNING',
            'Reverse HEAPU32 pointer scan ' + chunkNo + '/' + totalChunks + '...'
          );
        }
        await new Promise(function(resolve) { setTimeout(resolve, 12); });
      }
    }

    if (st.analyzeToken !== token) return;

    structBasePointers.sort(function(a, b) { return a.fieldOffsetBytes - b.fieldOffsetBytes; });
    var parentPointers = exactPointers.concat(structBasePointers).slice(0, 24);

    // Update live state immediately so the user sees LOCKED coords right away
    st.baseIndex = $safeBase;
    st.signature = extracted.signature;
    st.parentPointers = parentPointers;

    var durationMs = Math.round((performance.now() - startTime) * 10) / 10;
    var cx = res.hf32[$safeBase];
    var cy = res.hf32[$safeBase + 1];
    var cz = res.hf32[$safeBase + 2];

    if (window.TelemetryBridge) {
      if (window.TelemetryBridge.updateDirectHeapCoords && Number.isFinite(cx) && Number.isFinite(cy) && Number.isFinite(cz)) {
        window.TelemetryBridge.updateDirectHeapCoords($safeBase, cx, cy, cz, res.byteLength, res.label);
      }
      window.TelemetryBridge.updateScanStatus(
        'COMPLETED',
        'Analyzed ' + extracted.signature.anchors.length + ' anchors, ' + parentPointers.length + ' ptrs'
      );
      window.TelemetryBridge.logTelemetry('PTR', JSON.stringify({
        type: 'POINTER_SIGNATURE_ANALYZED',
        baseIndex: $safeBase,
        byteAddress: targetByteAddr,
        aobPatternHex: extracted.aobPatternHex,
        floatPatternSummary: extracted.floatPatternSummary,
        anchorCount: extracted.signature.anchors.length,
        signatureJson: JSON.stringify(extracted.signature),
        parentPointers: parentPointers,
        surroundingSlots: extracted.slots,
        durationMs: durationMs,
        heapByteLength: res.byteLength,
        heapSource: res.label
      }));
    }
  } catch (e) {
    if (window.TelemetryBridge) {
      window.TelemetryBridge.updateScanStatus('ERROR', 'Pointer analysis error');
      window.TelemetryBridge.logTelemetry('ERR', 'Pointer/Signature error: ' + String(e));
    }
  }
})();
""".trimIndent()
    }

    // =========================================================================
    // Step 3: One-Tap & Automatic Relock Across Reloads (Pointer Deref -> AOB/Float Scan)
    // =========================================================================

    fun buildAutoRelockScript(
        currentBaseIndex: Int = VERIFIED_PLAYER_BASE_INDEX,
        savedSignatureJson: String? = null,
        savedPointersJson: String? = null
    ): String {
        val safeBase = currentBaseIndex.coerceAtLeast(0)
        val sigLiteral = if (savedSignatureJson.isNullOrBlank()) "null" else quoteJsString(savedSignatureJson)
        val ptrLiteral = if (savedPointersJson.isNullOrBlank()) "null" else quoteJsString(savedPointersJson)
        return """
(async function() {
  $HEAP_RESOLVER_JS
  try {
    var st = __wvEnsureState($safeBase);
    var token = ++st.relockToken;

    var persistedSigStr = $sigLiteral;
    if (persistedSigStr) {
      try {
        var parsedSig = JSON.parse(persistedSigStr);
        if (parsedSig && Array.isArray(parsedSig.anchors) && parsedSig.anchors.length > 0) {
          st.signature = parsedSig;
        }
      } catch (_pe) {}
    }

    var persistedPtrsStr = $ptrLiteral;
    if (persistedPtrsStr) {
      try {
        var parsedPtrs = JSON.parse(persistedPtrsStr);
        if (Array.isArray(parsedPtrs)) {
          st.parentPointers = parsedPtrs;
        }
      } catch (_pp) {}
    }

    var res = __wvResolveWasmHeap();
    if (!res) {
      if (window.TelemetryBridge) {
        window.TelemetryBridge.updateScanStatus('ERROR', 'window.HEAPF32 not found');
        window.TelemetryBridge.logTelemetry('RELOCK', JSON.stringify({
          type: 'RELOCK_FAILED',
          reason: 'window.HEAPF32 is not initialized on this page yet.'
        }));
      }
      return;
    }

    // If no signature exists yet, capture from current base index immediately
    if (!st.signature || !Array.isArray(st.signature.anchors) || st.signature.anchors.length === 0) {
      if ($safeBase + 2 < res.length) {
        var fresh = __wvExtractStructSignature(res, $safeBase, 16);
        if (fresh && fresh.signature.anchors.length > 0) {
          st.signature = fresh.signature;
          var fx = res.hf32[$safeBase], fy = res.hf32[$safeBase + 1], fz = res.hf32[$safeBase + 2];
          if (window.TelemetryBridge) {
            window.TelemetryBridge.logTelemetry('RELOCK', JSON.stringify({
              type: 'RELOCK_COMPLETE',
              oldBaseIndex: $safeBase,
              newBaseIndex: $safeBase,
              confidencePercent: 100,
              matchedAnchors: fresh.signature.anchors.length,
              totalAnchors: fresh.signature.anchors.length,
              method: 'Baseline Signature Lock',
              x: Number.isFinite(fx) ? fx : 0,
              y: Number.isFinite(fy) ? fy : 0,
              z: Number.isFinite(fz) ? fz : 0,
              durationMs: 1,
              heapByteLength: res.byteLength,
              heapSource: res.label,
              signatureJson: JSON.stringify(fresh.signature),
              aobPatternHex: fresh.aobPatternHex,
              note: 'Captured signature and locked onto HEAPF32[' + $safeBase + ']'
            }));
          }
          return;
        }
      }
      if (window.TelemetryBridge) {
        window.TelemetryBridge.updateScanStatus('ERROR', 'No saved profile');
        window.TelemetryBridge.logTelemetry('RELOCK', JSON.stringify({
          type: 'RELOCK_FAILED',
          reason: 'No saved signature profile. Use Step 1 & Step 2 to inspect and lock a coordinate first.'
        }));
      }
      return;
    }

    var sig = st.signature;
    var anchors = sig.anchors;
    var zeroOffsets = sig.zeroOffsets || [];
    var totalWeight = anchors.length * 2 + zeroOffsets.length;
    var hf32 = res.hf32;
    var hu32 = res.hu32;
    var len = res.length;
    var startTime = performance.now();

    var scoreCandidate = function(idx) {
      if (idx < 16 || idx + 18 >= len) return null;
      var x = hf32[idx], y = hf32[idx + 1], z = hf32[idx + 2];
      if (!__wvIsPlausibleCoord(x) || !__wvIsPlausibleCoord(y) || !__wvIsPlausibleCoord(z)) return null;
      var score = 0;
      var matched = 0;
      for (var a = 0; a < anchors.length; a++) {
        var an = anchors[a];
        var pos = idx + an.off;
        if (pos < 0 || pos >= len) continue;
        var expectedU32 = (an.u32 !== undefined ? an.u32 : an.bits) >>> 0;
        if (hu32 && (hu32[pos] >>> 0) === expectedU32) {
          score += 2;
          matched++;
        } else if (an.fval !== null && an.fval !== undefined) {
          var fv = hf32[pos];
          if (Number.isFinite(fv) && Math.abs(fv - an.fval) <= 0.005) {
            score += 1;
            matched++;
          }
        }
      }
      for (var zIdx = 0; zIdx < zeroOffsets.length; zIdx++) {
        var zPos = idx + zeroOffsets[zIdx];
        if (zPos >= 0 && zPos < len && hu32 && hu32[zPos] === 0) {
          score += 1;
        }
      }
      var conf = totalWeight > 0 ? Math.min(100, Math.round((score * 100) / totalWeight)) : 0;
      return { score: score, matched: matched, confidence: conf };
    };

    // Tier 1: O(1) Reverse Parent Pointer Dereference check
    var savedPtrs = st.parentPointers || [];
    if (hu32 && savedPtrs.length > 0) {
      for (var pi = 0; pi < savedPtrs.length; pi++) {
        var ptr = savedPtrs[pi];
        var pIdx = ptr.pointerHeapIndex;
        if (pIdx >= 0 && pIdx < len) {
          var pointedBaseAddr = hu32[pIdx] >>> 0;
          var fieldOff = ptr.fieldOffsetBytes || 0;
          var resolvedByteAddr = pointedBaseAddr + fieldOff;
          if (resolvedByteAddr > 0 && (resolvedByteAddr % 4) === 0) {
            var candidateIdx = resolvedByteAddr >>> 2;
            var ptrEval = scoreCandidate(candidateIdx);
            if (ptrEval && ptrEval.confidence >= 60) {
              st.baseIndex = candidateIdx;
              var px = hf32[candidateIdx], py = hf32[candidateIdx + 1], pz = hf32[candidateIdx + 2];
              var ptrDur = Math.round((performance.now() - startTime) * 10) / 10;
              if (window.TelemetryBridge) {
                if (window.TelemetryBridge.updateDirectHeapCoords) {
                  window.TelemetryBridge.updateDirectHeapCoords(candidateIdx, px, py, pz, res.byteLength, res.label);
                }
                window.TelemetryBridge.updateScanStatus('COMPLETED', 'Pointer Relock @ ' + candidateIdx + ' (' + ptrEval.confidence + '%)');
                window.TelemetryBridge.logTelemetry('RELOCK', JSON.stringify({
                  type: 'RELOCK_COMPLETE',
                  oldBaseIndex: $safeBase,
                  newBaseIndex: candidateIdx,
                  confidencePercent: ptrEval.confidence,
                  matchedAnchors: ptrEval.matched,
                  totalAnchors: anchors.length,
                  method: 'Parent Pointer Dereference (HEAPU32[' + pIdx + ']+0x' + fieldOff.toString(16).toUpperCase() + ')',
                  x: px,
                  y: py,
                  z: pz,
                  durationMs: ptrDur,
                  heapByteLength: res.byteLength,
                  heapSource: res.label,
                  note: 'Instant pointer relock -> HEAPF32[' + candidateIdx + '] (' + ptrEval.confidence + '% match)'
                }));
              }
              return;
            }
          }
        }
      }
    }

    // Tier 2: Full non-blocking chunked AOB / Float Signature Scan across window.HEAPU32 / HEAPF32
    var primary = anchors[0];
    var primaryU32 = (primary.u32 !== undefined ? primary.u32 : primary.bits) >>> 0;
    var chunkSize = 262144;
    var minIdx = 18;
    var maxIdx = len - 20;
    var totalChunks = Math.max(1, Math.ceil((maxIdx - minIdx) / chunkSize));

    var bestIdx = -1;
    var bestScore = -1;
    var bestMatched = 0;
    var bestConf = 0;

    if (window.TelemetryBridge) {
      window.TelemetryBridge.updateScanStatus('RUNNING', 'Auto-Relock scanning AOB/Float signature...');
    }

    var curEval = scoreCandidate($safeBase);
    if (curEval && curEval.confidence >= 55) {
      bestIdx = $safeBase;
      bestScore = curEval.score;
      bestMatched = curEval.matched;
      bestConf = curEval.confidence;
    }

    var chunkNo = 0;
    for (var start = minIdx; start < maxIdx; start += chunkSize) {
      if (st.relockToken !== token) return;
      chunkNo++;
      var end = Math.min(maxIdx, start + chunkSize);

      for (var i = start; i < end; i++) {
        if (hu32 && (hu32[i + primary.off] >>> 0) !== primaryU32) continue;
        var ev = scoreCandidate(i);
        if (!ev) continue;
        if (ev.score > bestScore ||
            (ev.score === bestScore && bestIdx !== -1 && Math.abs(i - $safeBase) < Math.abs(bestIdx - $safeBase))) {
          bestScore = ev.score;
          bestMatched = ev.matched;
          bestConf = ev.confidence;
          bestIdx = i;
        }
      }

      if (window.TelemetryBridge && (chunkNo % 4 === 0 || chunkNo === totalChunks)) {
        window.TelemetryBridge.updateScanStatus('RUNNING', 'Auto-Relock slice ' + chunkNo + '/' + totalChunks + '...');
      }
      await new Promise(function(resolve) { setTimeout(resolve, 12); });
    }

    if (st.relockToken !== token) return;
    var durationMs = Math.round((performance.now() - startTime) * 10) / 10;

    if (bestIdx === -1 || bestConf < 35) {
      if (window.TelemetryBridge) {
        window.TelemetryBridge.updateScanStatus('ERROR', 'Auto-Relock match not found');
        window.TelemetryBridge.logTelemetry('RELOCK', JSON.stringify({
          type: 'RELOCK_FAILED',
          reason: 'Signature not matched above 35% confidence (best ' + bestConf + '%, ' + durationMs + 'ms).'
        }));
      }
      return;
    }

    st.baseIndex = bestIdx;
    var nx = hf32[bestIdx], ny = hf32[bestIdx + 1], nz = hf32[bestIdx + 2];
    if (window.TelemetryBridge) {
      if (window.TelemetryBridge.updateDirectHeapCoords) {
        window.TelemetryBridge.updateDirectHeapCoords(bestIdx, nx, ny, nz, res.byteLength, res.label);
      }
      window.TelemetryBridge.updateScanStatus('COMPLETED', 'Relocked @ HEAPF32[' + bestIdx + '] (' + bestConf + '%)');
      window.TelemetryBridge.logTelemetry('RELOCK', JSON.stringify({
        type: 'RELOCK_COMPLETE',
        oldBaseIndex: $safeBase,
        newBaseIndex: bestIdx,
        confidencePercent: bestConf,
        matchedAnchors: bestMatched,
        totalAnchors: anchors.length,
        method: 'AOB / Float Signature Scan',
        x: nx,
        y: ny,
        z: nz,
        durationMs: durationMs,
        heapByteLength: res.byteLength,
        heapSource: res.label,
        note: bestIdx === $safeBase
          ? ('Verified HEAPF32[' + bestIdx + '] intact (' + bestConf + '% match)')
          : ('Relocated ' + $safeBase + ' -> ' + bestIdx + ' (' + bestConf + '% signature match)')
      }));
    }
  } catch (e) {
    if (window.TelemetryBridge) {
      window.TelemetryBridge.updateScanStatus('ERROR', 'Relock error');
      window.TelemetryBridge.logTelemetry('ERR', 'Auto-relock error: ' + String(e));
    }
  }
})();
""".trimIndent()
    }

    // =========================================================================
    // Nearby Entity Struct Scanner (±50,000), Manual JS & Viewport Metrics
    // =========================================================================

    fun buildScanNearbyEntitiesScript(
        playerBaseIndex: Int = VERIFIED_PLAYER_BASE_INDEX,
        searchRadius: Int = DEFAULT_ENTITY_SCAN_RADIUS
    ): String {
        val safeBase = playerBaseIndex.coerceAtLeast(0)
        val safeRadius = searchRadius.coerceIn(500, 250_000)
        return """
(async function() {
  $HEAP_RESOLVER_JS
  try {
    var st = __wvEnsureState($safeBase);
    var token = ++st.entityScanToken;

    var res = __wvResolveWasmHeap();
    if (!res) {
      if (window.TelemetryBridge) {
        window.TelemetryBridge.updateScanStatus('ERROR', 'window.HEAPF32 not ready');
        window.TelemetryBridge.logTelemetry('ENTITY', JSON.stringify({
          type: 'ENTITIES_SCANNED',
          error: 'window.HEAPF32 is not initialized on this page.',
          entities: []
        }));
      }
      return;
    }

    var hf32 = res.hf32;
    var len = res.length;
    var pIdx = $safeBase;
    if (pIdx + 2 >= len) {
      if (window.TelemetryBridge) {
        window.TelemetryBridge.updateScanStatus('ERROR', 'Player index out of bounds');
        window.TelemetryBridge.logTelemetry('ENTITY', JSON.stringify({
          type: 'ENTITIES_SCANNED',
          error: 'Player base index ' + pIdx + ' exceeds heap length ' + len,
          entities: []
        }));
      }
      return;
    }

    var px = hf32[pIdx], py = hf32[pIdx + 1], pz = hf32[pIdx + 2];
    var hasPlayerOrigin = __wvIsPlausibleCoord(px) && __wvIsPlausibleCoord(py) && __wvIsPlausibleCoord(pz) &&
      (Math.abs(px) + Math.abs(py) + Math.abs(pz) > 0.05);

    var startIdx = Math.max(0, pIdx - $safeRadius);
    var endIdx = Math.min(len - 3, pIdx + $safeRadius);
    var chunkSize = 16384;
    var totalChunks = Math.max(1, Math.ceil((endIdx - startIdx) / chunkSize));
    var startTime = performance.now();

    if (window.TelemetryBridge) {
      window.TelemetryBridge.updateScanStatus('RUNNING', 'Scanning ±$safeRadius around ' + pIdx + '...');
    }

    var rawMatches = [];
    var chunkCount = 0;

    var isCleanWorldFloat = function(v) {
      if (typeof v !== 'number' || !Number.isFinite(v)) return false;
      var av = Math.abs(v);
      if (av > 100000) return false;
      if (av > 0 && av < 1e-4) return false;
      return true;
    };

    for (var s = startIdx; s < endIdx; s += chunkSize) {
      if (st.entityScanToken !== token) return;
      chunkCount++;
      var e = Math.min(endIdx, s + chunkSize);

      for (var i = s; i < e; i++) {
        if (Math.abs(i - pIdx) < 4) continue;
        var ex = hf32[i];
        if (!isCleanWorldFloat(ex)) continue;
        var ey = hf32[i + 1];
        if (!isCleanWorldFloat(ey)) continue;
        var ez = hf32[i + 2];
        if (!isCleanWorldFloat(ez)) continue;

        var mag = Math.abs(ex) + Math.abs(ey) + Math.abs(ez);
        if (mag < 0.25) continue;
        if (ex === ey && ey === ez) continue;
        if ((ex === 0 || Math.abs(ex) === 1) && (ey === 0 || Math.abs(ey) === 1) && (ez === 0 || Math.abs(ez) === 1)) continue;

        var dist = 0;
        if (hasPlayerOrigin) {
          var dx = ex - px, dy = ey - py, dz = ez - pz;
          if (Math.abs(dy) > 150) continue;
          if (Math.abs(dx) > 1500 || Math.abs(dz) > 1500) continue;
          dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
          if (dist < 0.5 || dist > 1800) continue;
        } else {
          dist = Math.sqrt(ex * ex + ey * ey + ez * ez);
          if (dist < 1 || dist > 50000) continue;
        }

        rawMatches.push({
          idx: i,
          offset: i - pIdx,
          x: Math.round(ex * 1000) / 1000,
          y: Math.round(ey * 1000) / 1000,
          z: Math.round(ez * 1000) / 1000,
          dist: Math.round(dist * 100) / 100
        });
        i += 2;
        if (rawMatches.length >= 4000) break;
      }

      if (rawMatches.length >= 4000) break;
      if (window.TelemetryBridge && (chunkCount % 2 === 0 || chunkCount === totalChunks)) {
        window.TelemetryBridge.updateScanStatus('RUNNING', 'Entity scan ' + chunkCount + '/' + totalChunks + ' (' + rawMatches.length + ' hits)');
      }
      await new Promise(function(resolve) { setTimeout(resolve, 10); });
    }

    if (st.entityScanToken !== token) return;

    var strideCounts = Object.create(null);
    for (var m = 1; m < rawMatches.length; m++) {
      var delta = rawMatches[m].idx - rawMatches[m - 1].idx;
      if (delta >= 4 && delta <= 2048) {
        strideCounts[delta] = (strideCounts[delta] || 0) + 1;
      }
    }

    var dominantStride = 0, dominantCount = 0;
    var strideKeys = Object.keys(strideCounts);
    for (var k = 0; k < strideKeys.length; k++) {
      var sk = parseInt(strideKeys[k], 10);
      if (strideCounts[sk] > dominantCount) {
        dominantCount = strideCounts[sk];
        dominantStride = sk;
      }
    }
    if (dominantCount < 2) dominantStride = 0;

    var scored = [];
    for (var j = 0; j < rawMatches.length; j++) {
      var item = rawMatches[j];
      var conf = 50;
      var strideGroup = 0;
      if (dominantStride > 0) {
        var prevDelta = j > 0 ? (item.idx - rawMatches[j - 1].idx) : -1;
        var nextDelta = j + 1 < rawMatches.length ? (rawMatches[j + 1].idx - item.idx) : -1;
        if (prevDelta === dominantStride || nextDelta === dominantStride) {
          conf += 35;
          strideGroup = dominantStride;
        } else if (Math.abs(item.offset) % dominantStride === 0) {
          conf += 25;
          strideGroup = dominantStride;
        }
      }
      if (hasPlayerOrigin && item.dist <= 250) conf += 15;
      else if (hasPlayerOrigin && item.dist <= 600) conf += 8;

      scored.push({
        baseIndex: item.idx,
        byteAddress: item.idx * 4,
        offsetFromPlayer: item.offset,
        x: item.x,
        y: item.y,
        z: item.z,
        distanceToPlayer: item.dist,
        strideGroup: strideGroup,
        confidenceScore: Math.min(100, conf)
      });
    }

    scored.sort(function(a, b) {
      if (b.confidenceScore !== a.confidenceScore) return b.confidenceScore - a.confidenceScore;
      return a.distanceToPlayer - b.distanceToPlayer;
    });

    var topEntities = scored.slice(0, 80);
    var durationMs = Math.round((performance.now() - startTime) * 10) / 10;

    if (window.TelemetryBridge) {
      window.TelemetryBridge.updateScanStatus('COMPLETED', 'Found ' + topEntities.length + ' entities (' + durationMs + 'ms)');
      window.TelemetryBridge.logTelemetry('ENTITY', JSON.stringify({
        type: 'ENTITIES_SCANNED',
        playerBaseIndex: pIdx,
        searchRadius: $safeRadius,
        totalCandidates: rawMatches.length,
        dominantStride: dominantStride,
        durationMs: durationMs,
        entities: topEntities
      }));
    }
  } catch (e) {
    if (window.TelemetryBridge) {
      window.TelemetryBridge.updateScanStatus('ERROR', 'Entity scan failed');
      window.TelemetryBridge.logTelemetry('ERR', 'Entity struct scan error: ' + String(e));
    }
  }
})();
""".trimIndent()
    }

    fun buildManualEvalScript(userCode: String): String {
        val quoted = quoteJsString(userCode)
        return """
(function() {
  try {
    var code = $quoted;
    var result = (0, eval)(code);
    var formatted;
    if (result === undefined) {
      formatted = 'undefined';
    } else if (result === null) {
      formatted = 'null';
    } else if (typeof result === 'object') {
      try {
        var seen = new WeakSet();
        formatted = JSON.stringify(result, function(_k, v) {
          if (typeof v === 'object' && v !== null) {
            if (ArrayBuffer.isView(v)) return v.constructor.name + '(' + v.length + ')';
            if (seen.has(v)) return '[Circular]';
            seen.add(v);
          }
          return v;
        }, 2);
      } catch (_je) {
        formatted = String(result);
      }
    } else {
      formatted = String(result);
    }
    if (formatted && formatted.length > 4000) {
      formatted = formatted.slice(0, 4000) + '... [truncated]';
    }
    if (window.TelemetryBridge) {
      window.TelemetryBridge.logTelemetry('JS', JSON.stringify({
        type: 'MANUAL_EVAL',
        expression: code,
        result: formatted
      }));
    }
  } catch (e) {
    if (window.TelemetryBridge) {
      window.TelemetryBridge.logTelemetry('ERR', 'Manual JS Error: ' + String(e));
    }
  }
})();
""".trimIndent()
    }

    fun buildCollectViewportMetricsScript(baseIndex: Int = VERIFIED_PLAYER_BASE_INDEX): String {
        val safeBase = baseIndex.coerceAtLeast(0)
        return """
(function() {
  $HEAP_RESOLVER_JS
  try {
    var res = __wvResolveWasmHeap();
    var heapSnapshot = null;
    if (res && $safeBase + 2 < res.length) {
      heapSnapshot = {
        source: res.label,
        byteLength: res.byteLength,
        floatLength: res.length,
        baseIndex: $safeBase,
        byteAddressHex: '0x' + ($safeBase * 4).toString(16).toUpperCase(),
        x: res.hf32[$safeBase],
        y: res.hf32[$safeBase + 1],
        z: res.hf32[$safeBase + 2]
      };
    }
    var payload = {
      url: String(window.location && window.location.href || ''),
      title: String(document.title || ''),
      userAgent: String(navigator.userAgent || ''),
      viewport: {
        innerWidth: window.innerWidth || 0,
        innerHeight: window.innerHeight || 0,
        devicePixelRatio: window.devicePixelRatio || 1
      },
      heapSnapshot: heapSnapshot,
      capturedAtIso: new Date().toISOString()
    };
    if (window.TelemetryBridge && window.TelemetryBridge.exportReport) {
      window.TelemetryBridge.exportReport(JSON.stringify(payload));
    }
  } catch (e) {
    if (window.TelemetryBridge && window.TelemetryBridge.exportReport) {
      window.TelemetryBridge.exportReport(JSON.stringify({ error: String(e) }));
    }
  }
})();
""".trimIndent()
    }
}
