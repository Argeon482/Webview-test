package com.example

/**
 * Pure read-only JavaScript inspection routines for WebAssembly `window.HEAPF32` telemetry.
 *
 * Strictly observational:
 * - Zero memory writes to `HEAPF32` / `HEAP32` / `HEAPU8`
 * - Zero touch, keyboard, mouse, or navigation dispatching
 * - Zero `org.json` dependencies so script generation stays 100% testable on pure JVM
 */
object WasmHeapScripts {

    const val DEFAULT_POLL_INTERVAL_MS = 200
    const val DEFAULT_ENTITY_SCAN_RADIUS = 50_000

    /**
     * Escapes arbitrary Kotlin text into a double-quoted JS string literal using pure Kotlin
     * (avoids Android's stub `org.json.JSONObject` in pure JVM unit tests).
     */
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
     * Shared read-only helper resolving `window.HEAPF32` (primary target) with clean fallbacks
     * to `window.Module.HEAPF32`, `HEAP32`, `HEAPU8`, or `wasmMemory.buffer`.
     */
    private const val HEAP_RESOLVER_JS = """
function __wvResolveFloatHeap() {
  try {
    var makePair = function(f32, label) {
      if (!f32 || !(f32 instanceof Float32Array) || f32.length < 16) return null;
      var i32 = null;
      try {
        if ((f32.byteOffset % 4) === 0) {
          i32 = new Int32Array(f32.buffer, f32.byteOffset, f32.length);
        }
      } catch (_e) {}
      return {
        hf32: f32,
        h32: i32,
        length: f32.length,
        byteLength: f32.byteLength,
        label: label
      };
    };

    var fromBuffer = function(buf, byteOffset, byteLength, label) {
      if (!buf || byteLength < 64) return null;
      var alignedOffset = (byteOffset || 0);
      var alignedCount = Math.floor(byteLength / 4);
      if ((alignedOffset % 4) !== 0 || alignedCount < 16) return null;
      return {
        hf32: new Float32Array(buf, alignedOffset, alignedCount),
        h32: new Int32Array(buf, alignedOffset, alignedCount),
        length: alignedCount,
        byteLength: alignedCount * 4,
        label: label
      };
    };

    // 1. Primary verified target: window.HEAPF32
    if (window.HEAPF32 instanceof Float32Array && window.HEAPF32.length >= 16) {
      return makePair(window.HEAPF32, 'window.HEAPF32');
    }
    // 2. Emscripten Module.HEAPF32
    if (window.Module && window.Module.HEAPF32 instanceof Float32Array && window.Module.HEAPF32.length >= 16) {
      return makePair(window.Module.HEAPF32, 'window.Module.HEAPF32');
    }
    // 3. Companion HEAP32 / HEAPU8 views on window or Module
    if (window.HEAP32 instanceof Int32Array && window.HEAP32.length >= 16) {
      return fromBuffer(window.HEAP32.buffer, window.HEAP32.byteOffset, window.HEAP32.byteLength, 'window.HEAP32->Float32');
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
    // 4. WebAssembly.Memory fallback
    if (window.wasmMemory && window.wasmMemory.buffer) {
      return fromBuffer(window.wasmMemory.buffer, 0, window.wasmMemory.buffer.byteLength, 'window.wasmMemory');
    }
    if (window.Module && window.Module.wasmMemory && window.Module.wasmMemory.buffer) {
      return fromBuffer(window.Module.wasmMemory.buffer, 0, window.Module.wasmMemory.buffer.byteLength, 'window.Module.wasmMemory');
    }
    // 5. Common engine roots
    var roots = ['game', 'app', 'engine', 'unityInstance', 'Unity', 'instance'];
    for (var r = 0; r < roots.length; r++) {
      var rootName = roots[r];
      var obj = window[rootName];
      if (!obj || typeof obj !== 'object') continue;
      if (obj.HEAPF32 instanceof Float32Array && obj.HEAPF32.length >= 16) {
        return makePair(obj.HEAPF32, 'window.' + rootName + '.HEAPF32');
      }
      if (obj.Module && obj.Module.HEAPF32 instanceof Float32Array && obj.Module.HEAPF32.length >= 16) {
        return makePair(obj.Module.HEAPF32, 'window.' + rootName + '.Module.HEAPF32');
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

function __wvBuildSignature(res, baseIndex) {
  var hf32 = res.hf32;
  var h32 = res.h32;
  var len = res.length;
  if (baseIndex < 0 || baseIndex + 2 >= len) return null;

  var anchors = [];
  var zeroOffsets = [];
  var minOff = -24;
  var maxOff = 24;

  for (var off = minOff; off <= maxOff; off++) {
    if (off === 0 || off === 1 || off === 2) continue;
    var idx = baseIndex + off;
    if (idx < 0 || idx >= len) continue;
    var iVal = h32 ? h32[idx] : 0;
    var fVal = hf32[idx];
    if (iVal === 0) {
      if (zeroOffsets.length < 10) zeroOffsets.push(off);
      continue;
    }
    var priority = 1;
    // Prioritize clean float constants (e.g., 1.0, -1.0, scale/bounds) and small integer struct tags
    var isCleanFloat = Number.isFinite(fVal) && Math.abs(fVal) >= 1e-3 && Math.abs(fVal) <= 10000;
    var isSmallInt = Math.abs(iVal) > 0 && Math.abs(iVal) <= 65535;
    if (isSmallInt) priority = 3;
    else if (isCleanFloat) priority = 2;
    anchors.push({
      off: off,
      bits: iVal,
      fval: isCleanFloat ? Math.round(fVal * 10000) / 10000 : null,
      pri: priority
    });
  }

  anchors.sort(function(a, b) {
    if (b.pri !== a.pri) return b.pri - a.pri;
    return Math.abs(a.off) - Math.abs(b.off);
  });
  var selected = anchors.slice(0, 14);
  return {
    v: 1,
    capturedAtIndex: baseIndex,
    capturedCoords: [hf32[baseIndex], hf32[baseIndex + 1], hf32[baseIndex + 2]],
    anchors: selected,
    zeroOffsets: zeroOffsets.slice(0, 6)
  };
}
"""

    /**
     * Starts a high-performance 200ms direct polling loop reading `window.HEAPF32[baseIndex]` (X),
     * `window.HEAPF32[baseIndex + 1]` (Y), and `window.HEAPF32[baseIndex + 2]` (Z).
     * Automatically captures an auto-relock signature once valid coordinates are detected.
     */
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
    var st = window.__wvDiagFeed || (window.__wvDiagFeed = {
      timerId: null,
      baseIndex: $safeBase,
      signature: null,
      relockToken: 0,
      entityScanToken: 0,
      notifiedMissing: false
    });

    if (st.timerId !== null) {
      clearInterval(st.timerId);
      st.timerId = null;
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
      var res = __wvResolveFloatHeap();
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

      // Auto-capture surrounding signature once non-zero finite coordinates appear
      if (!st.signature && (__wvIsPlausibleCoord(x) && __wvIsPlausibleCoord(y) && __wvIsPlausibleCoord(z)) &&
          (Math.abs(x) + Math.abs(y) + Math.abs(z) > 0.01)) {
        var autoSig = __wvBuildSignature(res, idx);
        if (autoSig && autoSig.anchors.length >= 2) {
          st.signature = autoSig;
          if (window.TelemetryBridge) {
            window.TelemetryBridge.logTelemetry('RELOCK', JSON.stringify({
              type: 'SIGNATURE_CAPTURED',
              auto: true,
              baseIndex: idx,
              anchorCount: autoSig.anchors.length,
              zeroCount: autoSig.zeroOffsets.length,
              signatureJson: JSON.stringify(autoSig)
            }));
          }
        }
      }
    };

    pollTick();
    st.timerId = setInterval(pollTick, $DEFAULT_POLL_INTERVAL_MS);

    if (window.TelemetryBridge) {
      window.TelemetryBridge.updateScanStatus('COMPLETED', 'LIVE 200ms @ HEAPF32[' + $safeBase + '..' + ($safeBase + 2) + ']');
      window.TelemetryBridge.logTelemetry('FEED', JSON.stringify({
        type: 'FEED_STATUS',
        active: true,
        heapReady: !!__wvResolveFloatHeap(),
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

    /**
     * Pauses the 200ms direct `window.HEAPF32` polling loop cleanly.
     */
    fun buildStopDirectFeedScript(): String = """
(function() {
  try {
    var st = window.__wvDiagFeed;
    if (st && st.timerId !== null) {
      clearInterval(st.timerId);
      st.timerId = null;
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

    /**
     * Captures the surrounding static Float32/Int32 signature around `baseIndex` for Auto-Relock
     * across page reloads or zone transitions.
     */
    fun buildCaptureSignatureScript(baseIndex: Int = VERIFIED_PLAYER_BASE_INDEX): String {
        val safeBase = baseIndex.coerceAtLeast(0)
        return """
(function() {
  $HEAP_RESOLVER_JS
  try {
    var res = __wvResolveFloatHeap();
    if (!res) {
      if (window.TelemetryBridge) {
        window.TelemetryBridge.logTelemetry('RELOCK', JSON.stringify({
          type: 'RELOCK_FAILED',
          reason: 'window.HEAPF32 is not initialized on this page yet.'
        }));
      }
      return;
    }
    if ($safeBase + 2 >= res.length) {
      if (window.TelemetryBridge) {
        window.TelemetryBridge.logTelemetry('RELOCK', JSON.stringify({
          type: 'RELOCK_FAILED',
          reason: 'Base index ' + $safeBase + ' exceeds heap length (' + res.length + ').'
        }));
      }
      return;
    }
    var sig = __wvBuildSignature(res, $safeBase);
    if (!sig || sig.anchors.length === 0) {
      if (window.TelemetryBridge) {
        window.TelemetryBridge.logTelemetry('RELOCK', JSON.stringify({
          type: 'RELOCK_FAILED',
          reason: 'No non-zero surrounding signature words found around index ' + $safeBase + '.'
        }));
      }
      return;
    }
    var st = window.__wvDiagFeed || (window.__wvDiagFeed = {
      timerId: null, baseIndex: $safeBase, signature: null, relockToken: 0, entityScanToken: 0
    });
    st.signature = sig;
    if (window.TelemetryBridge) {
      window.TelemetryBridge.logTelemetry('RELOCK', JSON.stringify({
        type: 'SIGNATURE_CAPTURED',
        auto: false,
        baseIndex: $safeBase,
        anchorCount: sig.anchors.length,
        zeroCount: sig.zeroOffsets.length,
        signatureJson: JSON.stringify(sig)
      }));
    }
  } catch (e) {
    if (window.TelemetryBridge) {
      window.TelemetryBridge.logTelemetry('ERR', 'Capture signature error: ' + String(e));
    }
  }
})();
""".trimIndent()
    }

    /**
     * Non-blocking async chunked Auto-Relock scanner.
     * Uses the captured surrounding float/int32 signature to relocate the player's base index
     * across `window.HEAPF32` after a page reload or zone transition, then resumes 200ms polling.
     */
    fun buildAutoRelockScript(
        currentBaseIndex: Int = VERIFIED_PLAYER_BASE_INDEX,
        savedSignatureJson: String? = null
    ): String {
        val safeBase = currentBaseIndex.coerceAtLeast(0)
        val sigLiteral = if (savedSignatureJson.isNullOrBlank()) "null" else quoteJsString(savedSignatureJson)
        return """
(async function() {
  $HEAP_RESOLVER_JS
  try {
    var st = window.__wvDiagFeed || (window.__wvDiagFeed = {
      timerId: null, baseIndex: $safeBase, signature: null, relockToken: 0, entityScanToken: 0
    });
    var token = ++st.relockToken;

    var persistedSigStr = $sigLiteral;
    if (persistedSigStr) {
      try {
        var parsed = JSON.parse(persistedSigStr);
        if (parsed && Array.isArray(parsed.anchors) && parsed.anchors.length > 0) {
          st.signature = parsed;
        }
      } catch (_pe) {}
    }

    var res = __wvResolveFloatHeap();
    if (!res) {
      if (window.TelemetryBridge) {
        window.TelemetryBridge.updateScanStatus('ERROR', 'window.HEAPF32 not found');
        window.TelemetryBridge.logTelemetry('RELOCK', JSON.stringify({
          type: 'RELOCK_FAILED',
          reason: 'window.HEAPF32 is not available on this page.'
        }));
      }
      return;
    }

    // If no signature exists yet, capture immediately from the current/verified base index
    if (!st.signature || !Array.isArray(st.signature.anchors) || st.signature.anchors.length === 0) {
      if ($safeBase + 2 < res.length) {
        var freshSig = __wvBuildSignature(res, $safeBase);
        if (freshSig && freshSig.anchors.length > 0) {
          st.signature = freshSig;
          var cx = res.hf32[$safeBase], cy = res.hf32[$safeBase + 1], cz = res.hf32[$safeBase + 2];
          if (window.TelemetryBridge) {
            window.TelemetryBridge.logTelemetry('RELOCK', JSON.stringify({
              type: 'SIGNATURE_CAPTURED',
              auto: false,
              baseIndex: $safeBase,
              anchorCount: freshSig.anchors.length,
              zeroCount: freshSig.zeroOffsets.length,
              signatureJson: JSON.stringify(freshSig)
            }));
            window.TelemetryBridge.logTelemetry('RELOCK', JSON.stringify({
              type: 'RELOCK_COMPLETE',
              oldBaseIndex: $safeBase,
              newBaseIndex: $safeBase,
              confidencePercent: 100,
              matchedAnchors: freshSig.anchors.length,
              totalAnchors: freshSig.anchors.length,
              x: Number.isFinite(cx) ? cx : 0,
              y: Number.isFinite(cy) ? cy : 0,
              z: Number.isFinite(cz) ? cz : 0,
              durationMs: 1,
              heapByteLength: res.byteLength,
              heapSource: res.label,
              note: 'Captured baseline signature and locked onto index ' + $safeBase
            }));
            window.TelemetryBridge.updateScanStatus('COMPLETED', 'Signature locked @ ' + $safeBase);
          }
          return;
        }
      }
      if (window.TelemetryBridge) {
        window.TelemetryBridge.updateScanStatus('ERROR', 'No relock signature');
        window.TelemetryBridge.logTelemetry('RELOCK', JSON.stringify({
          type: 'RELOCK_FAILED',
          reason: 'No signature captured yet. Load the scene with valid coordinates first.'
        }));
      }
      return;
    }

    var sig = st.signature;
    var anchors = sig.anchors;
    var zeroOffsets = sig.zeroOffsets || [];
    var totalWeight = anchors.length * 2 + zeroOffsets.length;
    var primary = anchors[0];
    var hf32 = res.hf32;
    var h32 = res.h32;
    var len = res.length;

    var startTime = performance.now();
    var chunkSize = 262144; // 1 MB per non-blocking slice
    var minIdx = 26;
    var maxIdx = len - 28;
    var totalChunks = Math.max(1, Math.ceil((maxIdx - minIdx) / chunkSize));

    var bestIdx = -1;
    var bestScore = -1;
    var bestMatched = 0;

    if (window.TelemetryBridge) {
      window.TelemetryBridge.updateScanStatus('RUNNING', 'Relocking HEAPF32 signature...');
    }

    // First fast-check the current base index so stationary relocks prefer exact continuity
    var scoreCandidate = function(idx) {
      var x = hf32[idx], y = hf32[idx + 1], z = hf32[idx + 2];
      if (!__wvIsPlausibleCoord(x) || !__wvIsPlausibleCoord(y) || !__wvIsPlausibleCoord(z)) return null;
      var score = 0;
      var matched = 0;
      for (var a = 0; a < anchors.length; a++) {
        var an = anchors[a];
        var pos = idx + an.off;
        if (pos < 0 || pos >= len) continue;
        if (h32 && h32[pos] === an.bits) {
          score += 2;
          matched++;
        } else if (an.fval !== null) {
          var fv = hf32[pos];
          if (Number.isFinite(fv) && Math.abs(fv - an.fval) <= 0.005) {
            score += 1;
            matched++;
          }
        }
      }
      for (var zIdx = 0; zIdx < zeroOffsets.length; zIdx++) {
        var zPos = idx + zeroOffsets[zIdx];
        if (zPos >= 0 && zPos < len && h32 && h32[zPos] === 0) {
          score += 1;
        }
      }
      return { score: score, matched: matched };
    };

    if ($safeBase >= minIdx && $safeBase < maxIdx) {
      var curEval = scoreCandidate($safeBase);
      if (curEval && curEval.matched >= Math.max(2, Math.ceil(anchors.length * 0.5))) {
        bestIdx = $safeBase;
        bestScore = curEval.score;
        bestMatched = curEval.matched;
      }
    }

    var chunkIdx = 0;
    for (var start = minIdx; start < maxIdx; start += chunkSize) {
      if (st.relockToken !== token) return;
      chunkIdx++;
      var end = Math.min(maxIdx, start + chunkSize);

      for (var i = start; i < end; i++) {
        // Fast pre-filter using primary anchor bit word
        if (h32) {
          var pPos = i + primary.off;
          if (h32[pPos] !== primary.bits) continue;
        }
        var ev = scoreCandidate(i);
        if (!ev) continue;
        if (ev.score > bestScore ||
            (ev.score === bestScore && bestIdx !== -1 && Math.abs(i - $safeBase) < Math.abs(bestIdx - $safeBase))) {
          bestScore = ev.score;
          bestMatched = ev.matched;
          bestIdx = i;
        }
      }

      if (window.TelemetryBridge && (chunkIdx % 4 === 0 || chunkIdx === totalChunks)) {
        window.TelemetryBridge.updateScanStatus(
          'RUNNING',
          'Relocking slice ' + chunkIdx + '/' + totalChunks + '...'
        );
      }
      await new Promise(function(resolve) { setTimeout(resolve, 12); });
    }

    if (st.relockToken !== token) return;
    var durationMs = Math.round((performance.now() - startTime) * 10) / 10;
    var confidence = totalWeight > 0 ? Math.min(100, Math.round((bestScore * 100) / totalWeight)) : 0;

    if (bestIdx === -1 || confidence < 35) {
      if (window.TelemetryBridge) {
        window.TelemetryBridge.updateScanStatus('ERROR', 'Relock match not found');
        window.TelemetryBridge.logTelemetry('RELOCK', JSON.stringify({
          type: 'RELOCK_FAILED',
          reason: 'No heap region matched signature above 35% confidence (best ' + confidence + '%, ' + durationMs + 'ms).'
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
      window.TelemetryBridge.updateScanStatus('COMPLETED', 'Relocked @ HEAPF32[' + bestIdx + '] (' + confidence + '%)');
      window.TelemetryBridge.logTelemetry('RELOCK', JSON.stringify({
        type: 'RELOCK_COMPLETE',
        oldBaseIndex: $safeBase,
        newBaseIndex: bestIdx,
        confidencePercent: confidence,
        matchedAnchors: bestMatched,
        totalAnchors: anchors.length,
        x: nx,
        y: ny,
        z: nz,
        durationMs: durationMs,
        heapByteLength: res.byteLength,
        heapSource: res.label,
        note: bestIdx === $safeBase
          ? ('Verified base index ' + bestIdx + ' intact (' + confidence + '% signature match)')
          : ('Relocated player base ' + $safeBase + ' -> ' + bestIdx + ' (' + confidence + '% match)')
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

    /**
     * Non-blocking async Nearby Entity Struct Scanner.
     * Searches `window.HEAPF32` within `±searchRadius` (default ±50,000 indices) of the player's
     * base index for repeating `(X, Y, Z)` float triplets matching valid world coordinate bounds.
     */
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
    var st = window.__wvDiagFeed || (window.__wvDiagFeed = {
      timerId: null, baseIndex: $safeBase, signature: null, relockToken: 0, entityScanToken: 0
    });
    var token = ++st.entityScanToken;

    var res = __wvResolveFloatHeap();
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

    var px = hf32[pIdx];
    var py = hf32[pIdx + 1];
    var pz = hf32[pIdx + 2];
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
        // Exclude the player's own triplet window
        if (Math.abs(i - pIdx) < 4) continue;

        var ex = hf32[i];
        if (!isCleanWorldFloat(ex)) continue;
        var ey = hf32[i + 1];
        if (!isCleanWorldFloat(ey)) continue;
        var ez = hf32[i + 2];
        if (!isCleanWorldFloat(ez)) continue;

        // Skip zeroed buffers or unit vectors like (1, 1, 1) / (0, 1, 0)
        var mag = Math.abs(ex) + Math.abs(ey) + Math.abs(ez);
        if (mag < 0.25) continue;
        if (ex === ey && ey === ez) continue;
        if ((ex === 0 || Math.abs(ex) === 1) && (ey === 0 || Math.abs(ey) === 1) && (ez === 0 || Math.abs(ez) === 1)) continue;

        var dist = 0;
        if (hasPlayerOrigin) {
          var dx = ex - px;
          var dy = ey - py;
          var dz = ez - pz;
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

        // Advance past this triplet to prevent shifted overlap [i+1, i+2, i+3]
        i += 2;
        if (rawMatches.length >= 4000) break;
      }

      if (rawMatches.length >= 4000) break;
      if (window.TelemetryBridge && (chunkCount % 2 === 0 || chunkCount === totalChunks)) {
        window.TelemetryBridge.updateScanStatus(
          'RUNNING',
          'Entity scan slice ' + chunkCount + '/' + totalChunks + ' (' + rawMatches.length + ' hits)'
        );
      }
      await new Promise(function(resolve) { setTimeout(resolve, 10); });
    }

    if (st.entityScanToken !== token) return;

    // Detect repeating struct stride via delta histogram across adjacent candidates
    var strideCounts = Object.create(null);
    for (var m = 1; m < rawMatches.length; m++) {
      var delta = rawMatches[m].idx - rawMatches[m - 1].idx;
      if (delta >= 4 && delta <= 2048) {
        strideCounts[delta] = (strideCounts[delta] || 0) + 1;
      }
    }

    var dominantStride = 0;
    var dominantCount = 0;
    var strideKeys = Object.keys(strideCounts);
    for (var k = 0; k < strideKeys.length; k++) {
      var sk = parseInt(strideKeys[k], 10);
      var sc = strideCounts[sk];
      if (sc > dominantCount) {
        dominantCount = sc;
        dominantStride = sk;
      }
    }
    if (dominantCount < 2) dominantStride = 0;

    // Score each entity candidate: repeating stride alignment + proximity to player
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
      window.TelemetryBridge.updateScanStatus(
        'COMPLETED',
        'Found ' + topEntities.length + ' entities (' + durationMs + 'ms)'
      );
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

    /**
     * Evaluates a user-entered read-only JavaScript inspection expression and streams the result
     * back through [TelemetryBridge.logTelemetry].
     */
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

    /**
     * Collects read-only viewport, memory, and current `window.HEAPF32` snapshot metrics for
     * user-initiated report export via [TelemetryBridge.exportReport].
     */
    fun buildCollectViewportMetricsScript(baseIndex: Int = VERIFIED_PLAYER_BASE_INDEX): String {
        val safeBase = baseIndex.coerceAtLeast(0)
        return """
(function() {
  $HEAP_RESOLVER_JS
  try {
    var res = __wvResolveFloatHeap();
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
