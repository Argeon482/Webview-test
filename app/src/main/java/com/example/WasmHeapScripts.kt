package com.example

/**
 * Targeted WebAssembly Heap Value-Tracker JavaScript routines.
 *
 * Strictly observational and read-only:
 * - Never writes to WebAssembly linear memory.
 * - Uses chunked async yields (`await new Promise(r => setTimeout(r, 12))`) across large
 *   `HEAP32` / `HEAPF32` views so the browser render thread and WebSocket loop never freeze.
 * - Supports initial exact-value scanning (e.g. HP = 4338), instant delta filtering after
 *   in-game HP changes, and live 200 ms inspection of neighboring floats (±4 to ±16 bytes)
 *   around the isolated memory address.
 */
object WasmHeapScripts {

    private const val WASM_RUNTIME = """
        const __bridge = window.TelemetryBridge;
        const __wasm = (window.__wvWasmTracker = window.__wvWasmTracker || {
          scanSeq: 0,
          sourceLabel: '',
          heapByteLength: 0,
          candidates32: [],
          candidatesF32: [],
          totalMatches32: 0,
          totalMatchesF32: 0,
          filterRound: 0,
          isolated: null,
          pollId: null,
          pollTick: 0
        });

        const __emitWasm = function(obj) {
          try {
            if (__bridge && typeof __bridge.logTelemetry === 'function') {
              __bridge.logTelemetry('WASM', JSON.stringify(obj));
            }
          } catch (_) {}
        };

        const __setWasmStatus = function(status, detail) {
          try {
            if (__bridge && typeof __bridge.updateScanStatus === 'function') {
              __bridge.updateScanStatus(String(status || 'IDLE'), String(detail || ''));
            }
          } catch (_) {}
        };

        const __yieldWasmChunk = function() {
          return new Promise(function(resolve) {
            setTimeout(resolve, 12);
          });
        };

        /**
         * Safely resolves live Int32Array (HEAP32) and Float32Array (HEAPF32) views over the
         * page's WebAssembly linear memory, checking window globals first and Emscripten Module
         * containers as automatic fallback. Handles detached buffers after memory.grow().
         */
        const __resolveWasmHeaps = function() {
          let h32 = null;
          let hf32 = null;
          let label = '';

          const tryContainer = function(obj, prefix) {
            if (!obj || typeof obj !== 'object') return false;
            try {
              const c32 = obj.HEAP32;
              const cf32 = obj.HEAPF32;
              if (c32 instanceof Int32Array && c32.buffer && c32.buffer.byteLength > 0) {
                h32 = c32;
                hf32 = (cf32 instanceof Float32Array && cf32.buffer === c32.buffer)
                  ? cf32
                  : new Float32Array(c32.buffer);
                label = prefix + 'HEAP32 / ' + prefix + 'HEAPF32';
                return true;
              }
              if (cf32 instanceof Float32Array && cf32.buffer && cf32.buffer.byteLength > 0) {
                hf32 = cf32;
                h32 = new Int32Array(cf32.buffer);
                label = prefix + 'HEAPF32 (derived HEAP32)';
                return true;
              }
              const h8 = obj.HEAPU8 || obj.HEAP8;
              if (h8 && ArrayBuffer.isView(h8) && h8.buffer && h8.buffer.byteLength >= 4) {
                h32 = new Int32Array(h8.buffer);
                hf32 = new Float32Array(h8.buffer);
                label = prefix + 'HEAPU8 buffer (Int32/Float32 views)';
                return true;
              }
              const mem = obj.wasmMemory || (obj.asm && obj.asm.memory) || (obj.wasmExports && obj.wasmExports.memory);
              if (typeof WebAssembly !== 'undefined' && mem instanceof WebAssembly.Memory && mem.buffer && mem.buffer.byteLength >= 4) {
                h32 = new Int32Array(mem.buffer);
                hf32 = new Float32Array(mem.buffer);
                label = prefix + 'wasmMemory.buffer';
                return true;
              }
            } catch (_) {}
            return false;
          };

          if (tryContainer(window, 'window.')) {
            return { heap32: h32, heapF32: hf32, label: label, byteLength: h32.buffer.byteLength };
          }
          if (tryContainer(window.Module, 'window.Module.')) {
            return { heap32: h32, heapF32: hf32, label: label, byteLength: h32.buffer.byteLength };
          }
          const fallbackRoots = ['unityInstance', 'game', 'app', 'engine', 'Game', 'App', 'Engine'];
          for (let i = 0; i < fallbackRoots.length; i++) {
            const rk = fallbackRoots[i];
            try {
              const root = window[rk];
              if (!root || typeof root !== 'object') continue;
              if (tryContainer(root, 'window.' + rk + '.')) {
                return { heap32: h32, heapF32: hf32, label: label, byteLength: h32.buffer.byteLength };
              }
              if (root.Module && tryContainer(root.Module, 'window.' + rk + '.Module.')) {
                return { heap32: h32, heapF32: hf32, label: label, byteLength: h32.buffer.byteLength };
              }
            } catch (_) {}
          }
          return null;
        };

        const __stopWasmNeighborPoll = function() {
          try {
            if (__wasm.pollId !== null) {
              clearInterval(__wasm.pollId);
              __wasm.pollId = null;
            }
          } catch (_) {}
        };

        /**
         * Reads neighboring ±4 to ±16 byte slots (offsets -16, -12, -8, -4, 0, +4, +8, +12, +16)
         * around the isolated 32-bit element index from both HEAPF32 and HEAP32, and pushes
         * a live snapshot every 200 ms through TelemetryBridge.updateWasmNeighbors().
         */
        const __startWasmNeighborPoll = function(heapType, targetIndex) {
          __stopWasmNeighborPoll();
          __wasm.isolated = { heapType: heapType, index: targetIndex };
          __wasm.pollTick = 0;

          const pushSnapshot = function() {
            try {
              const heaps = __resolveWasmHeaps();
              if (!heaps) return;
              const h32 = heaps.heap32;
              const hf32 = heaps.heapF32;
              const len = Math.min(h32.length, hf32.length);
              if (targetIndex < 0 || targetIndex >= len) return;

              __wasm.pollTick++;
              const neighbors = [];
              for (let step = -4; step <= 4; step++) {
                const idx = targetIndex + step;
                if (idx < 0 || idx >= len) continue;
                const fv = hf32[idx];
                const iv = h32[idx];
                neighbors.push({
                  byteOffset: step * 4,
                  heapIndex: idx,
                  byteAddress: idx * 4,
                  floatValue: Number.isFinite(fv) ? Number(fv.toFixed(5)) : 0,
                  intValue: iv
                });
              }

              const curVal = heapType === 'HEAPF32'
                ? (Number.isFinite(hf32[targetIndex]) ? hf32[targetIndex].toFixed(3) : 'NaN')
                : String(h32[targetIndex]);

              const payload = {
                stage: 'WASM_NEIGHBORS_TICK',
                heapSourceLabel: heaps.label,
                heapByteLength: heaps.byteLength,
                heapType: heapType,
                isolatedIndex: targetIndex,
                isolatedByteAddress: targetIndex * 4,
                currentValue: curVal,
                tick: __wasm.pollTick,
                neighbors: neighbors
              };

              if (__bridge && typeof __bridge.updateWasmNeighbors === 'function') {
                __bridge.updateWasmNeighbors(JSON.stringify(payload));
              }
            } catch (_) {}
          };

          pushSnapshot();
          __wasm.pollId = setInterval(pushSnapshot, 200);
        };
    """

    /**
     * Step 1: Non-blocking chunked scan across `window.HEAP32` and `window.HEAPF32`
     * (with automatic `window.Module` fallback) to find all memory indices matching
     * the exact entered stat/HP value.
     */
    fun buildInitialScanScript(targetValue: Double): String {
        val valueLiteral = if (targetValue.isFinite()) targetValue.toString() else "0"
        return """
        (async function() {
          try {
            $WASM_RUNTIME
            const targetVal = Number($valueLiteral);
            if (!Number.isFinite(targetVal)) {
              __emitWasm({ stage: 'WASM_HEAP_ERROR', message: 'Enter a valid numeric stat value (e.g. 4338).' });
              return;
            }

            const heaps = __resolveWasmHeaps();
            if (!heaps) {
              __setWasmStatus('ERROR', 'No WASM HEAP32/HEAPF32 found');
              __emitWasm({
                stage: 'WASM_HEAP_ERROR',
                message: 'Could not locate window.HEAP32, window.HEAPF32, or window.Module.HEAP32 on this page.'
              });
              return;
            }

            __stopWasmNeighborPoll();
            const mySeq = ++__wasm.scanSeq;
            const startMs = performance.now();
            const h32 = heaps.heap32;
            const hf32 = heaps.heapF32;
            const len32 = h32.length;
            const lenF32 = hf32.length;
            const isExactInt = Number.isInteger(targetVal) && targetVal >= -2147483648 && targetVal <= 2147483647;
            const targetInt = isExactInt ? (targetVal | 0) : 0;
            const EPS = 0.001;
            const CHUNK_ITEMS = 262144; // 1 MB per async slice
            const MAX_STORED = 120000;

            const matches32 = [];
            const matchesF32 = [];
            let totalCount32 = 0;
            let totalCountF32 = 0;

            __wasm.sourceLabel = heaps.label;
            __wasm.heapByteLength = heaps.byteLength;
            __wasm.filterRound = 0;
            __wasm.isolated = null;

            const totalElements = Math.max(len32, lenF32);
            __setWasmStatus('RUNNING', 'WASM Scan: 0% (' + heaps.label + ')…');

            let lastProgressPct = -1;
            for (let base = 0; base < totalElements; base += CHUNK_ITEMS) {
              if (mySeq !== __wasm.scanSeq) return;
              const end = Math.min(base + CHUNK_ITEMS, totalElements);

              if (isExactInt && base < len32) {
                const end32 = Math.min(end, len32);
                for (let i = base; i < end32; i++) {
                  if (h32[i] === targetInt) {
                    totalCount32++;
                    if (matches32.length < MAX_STORED) matches32.push(i);
                  }
                }
              }

              if (base < lenF32) {
                const endF = Math.min(end, lenF32);
                for (let i = base; i < endF; i++) {
                  const fv = hf32[i];
                  if (fv === targetVal || (Number.isFinite(fv) && Math.abs(fv - targetVal) < EPS)) {
                    totalCountF32++;
                    if (matchesF32.length < MAX_STORED) matchesF32.push(i);
                  }
                }
              }

              const pct = Math.min(99, Math.floor((end / Math.max(1, totalElements)) * 100));
              if (pct >= lastProgressPct + 10) {
                lastProgressPct = pct;
                __setWasmStatus('RUNNING', 'WASM Scan ' + pct + '% (' + (totalCount32 + totalCountF32) + ' matches)…');
              }

              if (end < totalElements) {
                await __yieldWasmChunk();
              }
            }

            if (mySeq !== __wasm.scanSeq) return;

            __wasm.candidates32 = matches32;
            __wasm.candidatesF32 = matchesF32;
            __wasm.totalMatches32 = totalCount32;
            __wasm.totalMatchesF32 = totalCountF32;

            const totalMatches = totalCount32 + totalCountF32;
            const durationMs = Number((performance.now() - startMs).toFixed(1));
            const samples = [];
            for (let i = 0; i < matches32.length && samples.length < 10; i++) {
              const idx = matches32[i];
              samples.push({ heapType: 'HEAP32', index: idx, byteAddress: idx * 4, currentValue: String(h32[idx]) });
            }
            for (let i = 0; i < matchesF32.length && samples.length < 12; i++) {
              const idx = matchesF32[i];
              samples.push({ heapType: 'HEAPF32', index: idx, byteAddress: idx * 4, currentValue: hf32[idx].toFixed(3) });
            }

            let autoIsolated = null;
            if (totalMatches === 1 && samples.length === 1) {
              autoIsolated = samples[0];
              __startWasmNeighborPoll(autoIsolated.heapType, autoIsolated.index);
            }

            __setWasmStatus(
              'COMPLETED',
              'WASM Scan: ' + totalMatches + ' matches (' + durationMs + 'ms)'
            );
            __emitWasm({
              stage: 'WASM_HEAP_SCAN_COMPLETE',
              durationMs: durationMs,
              heapSourceLabel: heaps.label,
              heapByteLength: heaps.byteLength,
              targetValue: targetVal,
              totalMatches: totalMatches,
              heap32MatchCount: totalCount32,
              heapF32MatchCount: totalCountF32,
              cappedStored: (totalCount32 > MAX_STORED) || (totalCountF32 > MAX_STORED),
              samples: samples,
              autoIsolated: autoIsolated
            });
          } catch (err) {
            try {
              if (window.TelemetryBridge) {
                window.TelemetryBridge.updateScanStatus('ERROR', 'WASM scan failed');
                window.TelemetryBridge.logTelemetry('ERR', 'WASM Heap Scan error: ' + String(err));
              }
            } catch (_) {}
          }
        })();
        """.trimIndent()
    }

    /**
     * Step 2: Instant Value-Delta Filter (Change Detection).
     * Narrows down the stored matching indices to only those whose current value in
     * `HEAP32` / `HEAPF32` updated to match the new entered HP/stat value. Automatically
     * starts the live ±4..±16 byte Neighboring Memory Inspector as soon as an address is isolated.
     */
    fun buildFilterDeltaScript(newTargetValue: Double): String {
        val valueLiteral = if (newTargetValue.isFinite()) newTargetValue.toString() else "0"
        return """
        (function() {
          try {
            $WASM_RUNTIME
            const newVal = Number($valueLiteral);
            if (!Number.isFinite(newVal)) {
              __emitWasm({ stage: 'WASM_HEAP_ERROR', message: 'Enter a valid updated HP value before filtering.' });
              return;
            }

            if ((!__wasm.candidates32 || __wasm.candidates32.length === 0) &&
                (!__wasm.candidatesF32 || __wasm.candidatesF32.length === 0)) {
              __emitWasm({
                stage: 'WASM_HEAP_ERROR',
                message: 'Run Step 1 (Scan HEAP32 / F32) with your initial HP value first.'
              });
              return;
            }

            const heaps = __resolveWasmHeaps();
            if (!heaps) {
              __emitWasm({ stage: 'WASM_HEAP_ERROR', message: 'WASM heap is no longer accessible.' });
              return;
            }

            const startMs = performance.now();
            const h32 = heaps.heap32;
            const hf32 = heaps.heapF32;
            const len32 = h32.length;
            const lenF32 = hf32.length;
            const isExactInt = Number.isInteger(newVal) && newVal >= -2147483648 && newVal <= 2147483647;
            const targetInt = isExactInt ? (newVal | 0) : 0;
            const EPS = 0.001;

            const prevTotal = __wasm.candidates32.length + __wasm.candidatesF32.length;
            const next32 = [];
            const nextF32 = [];

            if (isExactInt) {
              for (let i = 0; i < __wasm.candidates32.length; i++) {
                const idx = __wasm.candidates32[i];
                if (idx >= 0 && idx < len32 && h32[idx] === targetInt) {
                  next32.push(idx);
                }
              }
            }

            for (let i = 0; i < __wasm.candidatesF32.length; i++) {
              const idx = __wasm.candidatesF32[i];
              if (idx >= 0 && idx < lenF32) {
                const fv = hf32[idx];
                if (fv === newVal || (Number.isFinite(fv) && Math.abs(fv - newVal) < EPS)) {
                  nextF32.push(idx);
                }
              }
            }

            __wasm.candidates32 = next32;
            __wasm.candidatesF32 = nextF32;
            __wasm.totalMatches32 = next32.length;
            __wasm.totalMatchesF32 = nextF32.length;
            __wasm.filterRound = (__wasm.filterRound || 0) + 1;

            const samples = [];
            for (let i = 0; i < next32.length && samples.length < 12; i++) {
              const idx = next32[i];
              samples.push({ heapType: 'HEAP32', index: idx, byteAddress: idx * 4, currentValue: String(h32[idx]) });
            }
            for (let i = 0; i < nextF32.length && samples.length < 16; i++) {
              const idx = nextF32[i];
              samples.push({ heapType: 'HEAPF32', index: idx, byteAddress: idx * 4, currentValue: hf32[idx].toFixed(3) });
            }

            const remaining = next32.length + nextF32.length;
            const durationMs = Number((performance.now() - startMs).toFixed(2));

            // Automatically start Neighboring Memory Inspector when narrowed to 1 address (or <= 2 identical-offset views)
            let autoIsolated = null;
            if (samples.length === 1) {
              autoIsolated = samples[0];
              __startWasmNeighborPoll(autoIsolated.heapType, autoIsolated.index);
            } else if (remaining > 0 && remaining <= 2 && next32.length === 1 && nextF32.length === 1 && next32[0] === nextF32[0]) {
              autoIsolated = samples[0];
              __startWasmNeighborPoll(autoIsolated.heapType, autoIsolated.index);
            }

            __setWasmStatus(
              remaining > 0 ? 'COMPLETED' : 'IDLE',
              remaining === 1
                ? ('Isolated ' + autoIsolated.heapType + '[' + autoIsolated.index + '] — polling ±16B')
                : ('Filter #' + __wasm.filterRound + ': ' + remaining + ' remaining')
            );

            __emitWasm({
              stage: 'WASM_HEAP_DELTA_FILTERED',
              durationMs: durationMs,
              heapSourceLabel: heaps.label,
              heapByteLength: heaps.byteLength,
              filterRound: __wasm.filterRound,
              newValue: newVal,
              previousCount: prevTotal,
              totalMatches: remaining,
              heap32MatchCount: next32.length,
              heapF32MatchCount: nextF32.length,
              samples: samples,
              autoIsolated: autoIsolated
            });
          } catch (err) {
            try {
              if (window.TelemetryBridge) {
                window.TelemetryBridge.updateScanStatus('ERROR', 'WASM filter failed');
                window.TelemetryBridge.logTelemetry('ERR', 'WASM Filter error: ' + String(err));
              }
            } catch (_) {}
          }
        })();
        """.trimIndent()
    }

    /**
     * Step 3: Manually lock onto any surviving WASM candidate address and begin live 200 ms
     * polling of its neighboring floats (±4 to ±16 bytes).
     */
    fun buildInspectNeighborsScript(heapType: String, index: Int): String {
        val safeType = if (heapType == "HEAPF32") "'HEAPF32'" else "'HEAP32'"
        val safeIdx = index.coerceAtLeast(0)
        return """
        (function() {
          try {
            $WASM_RUNTIME
            const heaps = __resolveWasmHeaps();
            if (!heaps) {
              __emitWasm({ stage: 'WASM_HEAP_ERROR', message: 'WASM heap is not available on this page.' });
              return;
            }
            __startWasmNeighborPoll($safeType, $safeIdx);
            __setWasmStatus('COMPLETED', 'Polling ±16B around ' + $safeType + '[' + $safeIdx + ']');
          } catch (err) {
            try {
              if (window.TelemetryBridge) {
                window.TelemetryBridge.logTelemetry('ERR', 'WASM Neighbor Inspector error: ' + String(err));
              }
            } catch (_) {}
          }
        })();
        """.trimIndent()
    }

    /**
     * Stops the live 200 ms neighboring memory polling loop cleanly.
     */
    fun buildStopNeighborPollScript(): String = """
        (function() {
          try {
            $WASM_RUNTIME
            __stopWasmNeighborPoll();
            __setWasmStatus('IDLE', 'WASM neighbor polling stopped');
          } catch (_) {}
        })();
    """.trimIndent()

    /**
     * Stops polling and clears all stored candidate arrays in the WebView.
     */
    fun buildResetTrackerScript(): String = """
        (function() {
          try {
            $WASM_RUNTIME
            __wasm.scanSeq++;
            __stopWasmNeighborPoll();
            __wasm.candidates32 = [];
            __wasm.candidatesF32 = [];
            __wasm.totalMatches32 = 0;
            __wasm.totalMatchesF32 = 0;
            __wasm.filterRound = 0;
            __wasm.isolated = null;
            __wasm.pollTick = 0;
            __setWasmStatus('IDLE', 'WASM Heap Tracker reset');
          } catch (_) {}
        })();
    """.trimIndent()
}
