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

    // ============================================================================================
    // WebAssembly Float Heap Delta Scanner (Step 2A / 2B — Float Mode targeting window.HEAPF32)
    // ============================================================================================

    private const val FLOAT_DELTA_RUNTIME = """
        const __fBridge = window.TelemetryBridge;
        const __fd = (window.__wvWasmFloatDelta = window.__wvWasmFloatDelta || {
          scanSeq: 0,
          sourceLabel: '',
          heapByteLength: 0,
          baselineClone: null,
          baselineLength: 0,
          isIsolatedClone: false,
          snapCount: 0,
          candidateIndices: null,
          candidateBaselines: null,
          candidateCount: 0,
          pinnedIndices: [],
          watchedItems: [],
          scanRound: 0,
          pollId: null,
          pollTick: 0
        });

        const __emitFloat = function(obj) {
          try {
            if (__fBridge && typeof __fBridge.logTelemetry === 'function') {
              __fBridge.logTelemetry('WASM', JSON.stringify(obj));
            }
          } catch (_) {}
        };

        const __setFloatStatus = function(status, detail) {
          try {
            if (__fBridge && typeof __fBridge.updateScanStatus === 'function') {
              __fBridge.updateScanStatus(String(status || 'IDLE'), String(detail || ''));
            }
          } catch (_) {}
        };

        const __yieldFloatSlice = function(ms) {
          return new Promise(function(resolve) {
            setTimeout(resolve, ms || 10);
          });
        };

        const __resolveFloatHeap = function() {
          const tryObj = function(obj, prefix) {
            if (!obj || typeof obj !== 'object') return null;
            try {
              if (obj.HEAPF32 instanceof Float32Array && obj.HEAPF32.buffer && obj.HEAPF32.buffer.byteLength >= 4) {
                return {
                  heapF32: obj.HEAPF32,
                  label: prefix + 'HEAPF32',
                  byteLength: obj.HEAPF32.byteLength
                };
              }
              const h32 = obj.HEAP32 || obj.HEAPU8 || obj.HEAP8;
              if (h32 && ArrayBuffer.isView(h32) && h32.buffer && h32.buffer.byteLength >= 4) {
                return {
                  heapF32: new Float32Array(h32.buffer),
                  label: prefix + 'HEAPF32 (buffer view)',
                  byteLength: h32.buffer.byteLength
                };
              }
              const mem = obj.wasmMemory || (obj.asm && obj.asm.memory) || (obj.wasmExports && obj.wasmExports.memory);
              if (typeof WebAssembly !== 'undefined' && mem instanceof WebAssembly.Memory && mem.buffer && mem.buffer.byteLength >= 4) {
                return {
                  heapF32: new Float32Array(mem.buffer),
                  label: prefix + 'wasmMemory Float32Array',
                  byteLength: mem.buffer.byteLength
                };
              }
            } catch (_) {}
            return null;
          };

          const direct = tryObj(window, 'window.');
          if (direct) return direct;
          const mod = tryObj(window.Module, 'window.Module.');
          if (mod) return mod;
          const roots = ['unityInstance', 'game', 'app', 'engine', 'Game', 'App', 'Engine'];
          for (let i = 0; i < roots.length; i++) {
            try {
              const r = window[roots[i]];
              if (!r || typeof r !== 'object') continue;
              const hit = tryObj(r, 'window.' + roots[i] + '.') || (r.Module ? tryObj(r.Module, 'window.' + roots[i] + '.Module.') : null);
              if (hit) return hit;
            } catch (_) {}
          }
          return null;
        };

        const __stopFloatWatch = function() {
          try {
            if (__fd.pollId !== null) {
              clearInterval(__fd.pollId);
              __fd.pollId = null;
            }
          } catch (_) {}
        };

        const __startFloatWatch = function() {
          __stopFloatWatch();
          __fd.pollTick = 0;

          const pushWatchTick = function() {
            try {
              const resolved = __resolveFloatHeap();
              if (!resolved) return;
              const hf32 = resolved.heapF32;
              const len = hf32.length;
              const baseClone = __fd.baselineClone;
              const baseLen = baseClone ? baseClone.length : 0;

              const mergedMap = new Map();
              const pinned = Array.isArray(__fd.pinnedIndices) ? __fd.pinnedIndices : [];
              for (let i = 0; i < pinned.length; i++) {
                const idx = pinned[i] | 0;
                if (idx >= 0 && idx < len) {
                  const staticBase = (baseClone && idx < baseLen && Number.isFinite(baseClone[idx]))
                    ? baseClone[idx]
                    : hf32[idx];
                  mergedMap.set(idx, {
                    heapIndex: idx,
                    baselineValue: staticBase,
                    clusterSize: 1,
                    axisHint: 'PIN',
                    isPinned: true
                  });
                }
              }

              const watched = Array.isArray(__fd.watchedItems) ? __fd.watchedItems : [];
              for (let i = 0; i < watched.length; i++) {
                const item = watched[i];
                const idx = item.heapIndex | 0;
                if (idx < 0 || idx >= len) continue;
                const existing = mergedMap.get(idx);
                mergedMap.set(idx, {
                  heapIndex: idx,
                  baselineValue: item.baselineValue,
                  clusterSize: item.clusterSize || 1,
                  axisHint: item.axisHint || (existing ? existing.axisHint : ''),
                  isPinned: existing ? true : false
                });
              }

              if (mergedMap.size === 0) return;

              __fd.pollTick++;
              const out = [];
              mergedMap.forEach(function(meta, idx) {
                const rawVal = hf32[idx];
                const cur = Number.isFinite(rawVal) ? Number(rawVal.toFixed(4)) : 0;
                const base = Number.isFinite(meta.baselineValue) ? Number(meta.baselineValue.toFixed(4)) : cur;
                const d = Number((cur - base).toFixed(4));
                out.push({
                  heapIndex: idx,
                  byteAddress: idx * 4,
                  baselineValue: base,
                  currentValue: cur,
                  delta: d,
                  clusterSize: meta.clusterSize,
                  axisHint: meta.axisHint,
                  isPinned: meta.isPinned
                });
              });

              const payload = {
                stage: 'WASM_FLOAT_WATCH_TICK',
                heapSourceLabel: resolved.label,
                heapByteLength: resolved.byteLength,
                tick: __fd.pollTick,
                candidates: out
              };
              if (__fBridge && typeof __fBridge.updateWasmFloatCandidates === 'function') {
                __fBridge.updateWasmFloatCandidates(JSON.stringify(payload));
              }
            } catch (_) {}
          };

          pushWatchTick();
          __fd.pollId = setInterval(pushWatchTick, 200);
        };
    """

    /**
     * Step 2A (Float Mode): Creates a true, isolated static clone of the active `window.HEAPF32`
     * buffer (`new Float32Array(hf32.buffer.slice(...))` with a non-blocking indexed copy fallback)
     * so baseline values are completely decoupled from live WASM linear memory and never mutate
     * alongside character movement. Strictly read-only on `window.HEAPF32`.
     */
    fun buildFloatBaselineSnapshotScript(): String = """
        (async function() {
          try {
            $FLOAT_DELTA_RUNTIME
            const resolved = __resolveFloatHeap();
            if (!resolved) {
              __setFloatStatus('ERROR', 'window.HEAPF32 not found');
              __emitFloat({
                stage: 'WASM_FLOAT_ERROR',
                message: 'Could not locate window.HEAPF32 (or Module.HEAPF32) on this page. Switch Step 2 to JS Objects mode if this page does not use WebAssembly.'
              });
              return;
            }

            __stopFloatWatch();
            const mySeq = ++__fd.scanSeq;
            const startMs = performance.now();
            const hf32 = resolved.heapF32;
            const len = hf32.length;

            // Skip low static/guard slots on full WASM heaps while preserving all indices on small test heaps
            const START_INDEX = len > 4096 ? 256 : 0;
            const CHUNK_FLOATS = 262144; // 1 MB per non-blocking async yield slice
            const MAX_ABS = 1000000.0;

            __setFloatStatus('RUNNING', 'Float Baseline: cloning ' + resolved.label + '…');

            // Create a true isolated Float32Array clone whose backing ArrayBuffer is completely separate from live HEAPF32
            let clone = null;
            let cloneMethod = 'buffer.slice';
            try {
              const slicedBuffer = (hf32.byteOffset === 0 && hf32.byteLength === hf32.buffer.byteLength)
                ? hf32.buffer.slice(0)
                : hf32.buffer.slice(hf32.byteOffset, hf32.byteOffset + hf32.byteLength);
              clone = new Float32Array(slicedBuffer);
            } catch (_) {
              clone = null;
            }

            // Fallback: chunked non-blocking indexed copy into a brand-new isolated Float32Array
            if (!clone || clone.buffer === hf32.buffer || clone.length !== len) {
              cloneMethod = 'indexed-copy';
              clone = new Float32Array(len);
              for (let base = 0; base < len; base += CHUNK_FLOATS) {
                if (mySeq !== __fd.scanSeq) return;
                const end = Math.min(base + CHUNK_FLOATS, len);
                for (let i = base; i < end; i++) {
                  clone[i] = hf32[i];
                }
                if (end < len) {
                  await __yieldFloatSlice(8);
                }
              }
            }

            if (mySeq !== __fd.scanSeq) return;
            const isIsolated = (clone.buffer !== hf32.buffer);

            // Non-blocking audit pass to count valid finite float slots in the isolated clone
            let validCount = 0;
            let lastPct = -1;
            for (let base = START_INDEX; base < len; base += CHUNK_FLOATS) {
              if (mySeq !== __fd.scanSeq) return;
              const end = Math.min(base + CHUNK_FLOATS, len);
              for (let i = base; i < end; i++) {
                const v = clone[i];
                if (v === v) {
                  const av = v < 0 ? -v : v;
                  if (av <= MAX_ABS) {
                    validCount++;
                  }
                }
              }
              const pct = Math.min(99, Math.floor(((end - START_INDEX) / Math.max(1, len - START_INDEX)) * 100));
              if (pct >= lastPct + 25) {
                lastPct = pct;
                __setFloatStatus('RUNNING', 'Float Baseline clone ' + pct + '% (' + validCount + ' floats)…');
              }
              if (end < len) {
                await __yieldFloatSlice(8);
              }
            }

            if (mySeq !== __fd.scanSeq) return;

            __fd.sourceLabel = resolved.label;
            __fd.heapByteLength = resolved.byteLength;
            __fd.baselineClone = clone;
            __fd.baselineLength = clone.length;
            __fd.isIsolatedClone = isIsolated;
            __fd.snapCount = validCount;
            __fd.candidateIndices = null;
            __fd.candidateBaselines = null;
            __fd.candidateCount = 0;
            __fd.watchedItems = [];
            __fd.scanRound = 0;

            const durationMs = Number((performance.now() - startMs).toFixed(1));
            __setFloatStatus('COMPLETED', 'Float Baseline cloned: ' + validCount + ' floats (' + durationMs + 'ms)');
            __emitFloat({
              stage: 'WASM_FLOAT_BASELINE_CAPTURED',
              durationMs: durationMs,
              heapSourceLabel: __fd.sourceLabel,
              heapByteLength: __fd.heapByteLength,
              totalHeapFloats: len,
              sampledCount: validCount,
              prunedJitterCount: 0,
              cloneMethod: cloneMethod,
              isIsolatedClone: isIsolated
            });
          } catch (err) {
            try {
              if (window.TelemetryBridge) {
                window.TelemetryBridge.updateScanStatus('ERROR', 'Float baseline failed');
                window.TelemetryBridge.logTelemetry('ERR', 'WASM Float Baseline error: ' + String(err));
              }
            } catch (_) {}
          }
        })();
    """.trimIndent()

    /**
     * Step 2B (Float Mode): Non-blocking movement delta scanner comparing live `window.HEAPF32`
     * against the isolated static `baselineClone` using permissive positional delta thresholds
     * (`0.02 <= |delta| <= 500.0`), grouping adjacent `X, Y, Z` float triplets cleanly, and
     * immediately streaming the top candidates at 200 ms.
     */
    fun buildFloatDeltaScanScript(
        minDelta: Double = 0.02,
        maxDelta: Double = 500.0
    ): String {
        val minLit = if (minDelta.isFinite() && minDelta > 0.0) minDelta.toString() else "0.02"
        val maxLit = if (maxDelta.isFinite() && maxDelta > minDelta) maxDelta.toString() else "500.0"
        return """
        (async function() {
          try {
            $FLOAT_DELTA_RUNTIME
            if (!__fd.baselineClone || __fd.baselineLength <= 0) {
              __setFloatStatus('IDLE', 'Capture Float Baseline first');
              __emitFloat({
                stage: 'WASM_FLOAT_ERROR',
                message: 'Stand still and tap 2A. Capture Float Baseline before scanning for movement deltas.'
              });
              return;
            }

            const resolved = __resolveFloatHeap();
            if (!resolved) {
              __setFloatStatus('ERROR', 'window.HEAPF32 unavailable');
              __emitFloat({
                stage: 'WASM_FLOAT_ERROR',
                message: 'window.HEAPF32 is no longer accessible on this page.'
              });
              return;
            }

            const mySeq = ++__fd.scanSeq;
            const startMs = performance.now();
            const hf32 = resolved.heapF32;
            const liveLen = hf32.length;
            const baseClone = __fd.baselineClone;
            const compareLen = Math.min(liveLen, baseClone.length);
            const START_INDEX = compareLen > 4096 ? 256 : 0;

            const MIN_DELTA = Number($minLit);
            const MAX_DELTA = Number($maxLit);
            const MAX_ABS = 1000000.0;
            const CHUNK_CHECK = 262144;
            const MAX_CANDIDATES = 80000;

            __setFloatStatus('RUNNING', 'Scanning HEAPF32 movement deltas (0.02..500)…');

            let hitIndices = [];
            let hitBaselines = [];
            let hitCurrents = [];
            let hitDeltas = [];
            let totalMatches = 0;
            let comparedCount = 0;

            const scanFullIsolatedClone = async function() {
              hitIndices = [];
              hitBaselines = [];
              hitCurrents = [];
              hitDeltas = [];
              totalMatches = 0;
              comparedCount = Math.max(0, compareLen - START_INDEX);

              for (let base = START_INDEX; base < compareLen; base += CHUNK_CHECK) {
                if (mySeq !== __fd.scanSeq) return false;
                const end = Math.min(base + CHUNK_CHECK, compareLen);
                for (let i = base; i < end; i++) {
                  const cur = hf32[i];
                  if (cur !== cur) continue; // NaN check
                  const prev = baseClone[i];
                  if (prev !== prev) continue;
                  const absCur = cur < 0 ? -cur : cur;
                  const absPrev = prev < 0 ? -prev : prev;
                  if (absCur > MAX_ABS || absPrev > MAX_ABS) continue;
                  const diff = cur - prev;
                  const absDiff = diff < 0 ? -diff : diff;
                  if (absDiff >= MIN_DELTA && absDiff <= MAX_DELTA) {
                    totalMatches++;
                    if (hitIndices.length < MAX_CANDIDATES) {
                      hitIndices.push(i);
                      hitBaselines.push(prev);
                      hitCurrents.push(cur);
                      hitDeltas.push(diff);
                    }
                  }
                }
                if (end < compareLen) {
                  await __yieldFloatSlice(10);
                }
              }
              return true;
            };

            // On round 1 scan the full isolated baseline clone; on subsequent rounds refine surviving candidates
            const canRefine = (__fd.scanRound > 0 && __fd.candidateIndices && __fd.candidateBaselines && __fd.candidateCount > 0);
            if (canRefine) {
              const srcIndices = __fd.candidateIndices;
              const srcBaselines = __fd.candidateBaselines;
              const srcLen = __fd.candidateCount;
              comparedCount = srcLen;

              for (let base = 0; base < srcLen; base += CHUNK_CHECK) {
                if (mySeq !== __fd.scanSeq) return;
                const end = Math.min(base + CHUNK_CHECK, srcLen);
                for (let j = base; j < end; j++) {
                  const idx = srcIndices[j];
                  if (idx < 0 || idx >= liveLen) continue;
                  const cur = hf32[idx];
                  if (cur !== cur) continue;
                  const prev = srcBaselines[j];
                  if (prev !== prev) continue;
                  const absCur = cur < 0 ? -cur : cur;
                  if (absCur > MAX_ABS) continue;
                  const diff = cur - prev;
                  const absDiff = diff < 0 ? -diff : diff;
                  if (absDiff >= MIN_DELTA && absDiff <= MAX_DELTA) {
                    totalMatches++;
                    if (hitIndices.length < MAX_CANDIDATES) {
                      hitIndices.push(idx);
                      hitBaselines.push(prev);
                      hitCurrents.push(cur);
                      hitDeltas.push(diff);
                    }
                  }
                }
                if (end < srcLen) {
                  await __yieldFloatSlice(10);
                }
              }

              // Graceful fallback: if refinement yielded 0 hits, re-compare against the full isolated baseline clone
              if (totalMatches === 0) {
                const ok = await scanFullIsolatedClone();
                if (!ok) return;
              }
            } else {
              const ok = await scanFullIsolatedClone();
              if (!ok) return;
            }

            if (mySeq !== __fd.scanSeq) return;

            // O(1) lookup table for moving indices so contiguous X/Y/Z triplet clustering is fast and exact
            const hitByIndex = new Map();
            for (let i = 0; i < hitIndices.length; i++) {
              hitByIndex.set(hitIndices[i], {
                heapIndex: hitIndices[i],
                byteAddress: hitIndices[i] * 4,
                baselineValue: Number(hitBaselines[i].toFixed(4)),
                currentValue: Number(hitCurrents[i].toFixed(4)),
                delta: Number(hitDeltas[i].toFixed(4))
              });
            }

            // Evaluate contiguous 3-float Vec3 triplets (start, start+1, start+2)
            const evaluatedStarts = new Set();
            const scoredTriplets = [];
            for (let i = 0; i < hitIndices.length; i++) {
              const idx = hitIndices[i];
              for (let off = -2; off <= 0; off++) {
                const start = idx + off;
                if (start < START_INDEX || start + 2 >= compareLen) continue;
                if (evaluatedStarts.has(start)) continue;
                evaluatedStarts.add(start);

                const h0 = hitByIndex.get(start);
                const h1 = hitByIndex.get(start + 1);
                const h2 = hitByIndex.get(start + 2);
                const movedCount = (h0 ? 1 : 0) + (h1 ? 1 : 0) + (h2 ? 1 : 0);
                if (movedCount < 2) continue;

                // Ensure all 3 slots in the triplet hold valid finite floats (handles flat Y elevation cleanly)
                const v0 = hf32[start];
                const v1 = hf32[start + 1];
                const v2 = hf32[start + 2];
                if (!Number.isFinite(v0) || !Number.isFinite(v1) || !Number.isFinite(v2)) continue;
                if (Math.abs(v0) > MAX_ABS || Math.abs(v1) > MAX_ABS || Math.abs(v2) > MAX_ABS) continue;

                let score = 0;
                if (movedCount === 3) {
                  score += 45; // Full 3D X/Y/Z movement
                } else if (h0 && h2 && !h1) {
                  score += 36; // Classic horizontal 3D movement (X & Z moved, Y elevation flat)
                } else {
                  score += 30; // 2D adjacent pair (X & Y or Y & Z moved)
                }

                // Bonus for realistic world-space magnitudes and walk-step deltas
                const sampleHit = h0 || h1 || h2;
                const absSample = Math.abs(sampleHit.currentValue);
                if (absSample >= 0.1 && absSample <= 250000) score += 4;
                const absStep = Math.abs(sampleHit.delta);
                if (absStep >= 0.05 && absStep <= 150) score += 3;

                scoredTriplets.push({
                  start: start,
                  movedCount: movedCount,
                  score: score
                });
              }
            }

            scoredTriplets.sort(function(a, b) {
              if (b.score !== a.score) return b.score - a.score;
              return a.start - b.start;
            });

            // Build top watched list (up to 12 offsets), prioritizing contiguous X, Y, Z triplets first
            const topWatched = [];
            const addedSet = new Set();
            const axisLabels = ['X', 'Y', 'Z'];

            const appendTriplet = function(startIdx, clusterSz, assignAxisLabels) {
              for (let k = 0; k < 3 && topWatched.length < 12; k++) {
                const slotIdx = startIdx + k;
                if (slotIdx < 0 || slotIdx >= compareLen || addedSet.has(slotIdx)) continue;
                const hit = hitByIndex.get(slotIdx);
                const fv = hf32[slotIdx];
                const bv = baseClone[slotIdx];
                if (!Number.isFinite(fv) || Math.abs(fv) > MAX_ABS) continue;
                const baseNum = Number.isFinite(bv) ? Number(bv.toFixed(4)) : Number(fv.toFixed(4));
                const curNum = Number(fv.toFixed(4));
                topWatched.push({
                  heapIndex: slotIdx,
                  byteAddress: slotIdx * 4,
                  baselineValue: hit ? hit.baselineValue : baseNum,
                  currentValue: hit ? hit.currentValue : curNum,
                  delta: hit ? hit.delta : Number((curNum - baseNum).toFixed(4)),
                  clusterSize: clusterSz,
                  axisHint: assignAxisLabels ? axisLabels[k] : ''
                });
                addedSet.add(slotIdx);
              }
            };

            if (scoredTriplets.length > 0) {
              // Primary best X/Y/Z triplet gets explicit X, Y, Z axis hints
              appendTriplet(scoredTriplets[0].start, 3, true);
              // Include up to 2 additional non-overlapping contiguous triplets
              for (let t = 1; t < scoredTriplets.length && topWatched.length < 9; t++) {
                const st = scoredTriplets[t].start;
                if (addedSet.has(st) || addedSet.has(st + 1) || addedSet.has(st + 2)) continue;
                appendTriplet(st, scoredTriplets[t].movedCount, false);
              }
            }

            // Fill remaining slots (up to 12) with highest-scoring individual moving floats
            if (topWatched.length < 12) {
              const singles = [];
              for (let i = 0; i < hitIndices.length; i++) {
                const idx = hitIndices[i];
                if (addedSet.has(idx)) continue;
                let neighbors = 1;
                if (hitByIndex.has(idx - 1)) neighbors++;
                if (hitByIndex.has(idx + 1)) neighbors++;
                if (hitByIndex.has(idx - 2)) neighbors++;
                if (hitByIndex.has(idx + 2)) neighbors++;
                const absCur = Math.abs(hitCurrents[i]);
                const scaleBonus = (absCur >= 0.1 && absCur <= 250000) ? 2.0 : 0.2;
                singles.push({
                  heapIndex: idx,
                  byteAddress: idx * 4,
                  baselineValue: Number(hitBaselines[i].toFixed(4)),
                  currentValue: Number(hitCurrents[i].toFixed(4)),
                  delta: Number(hitDeltas[i].toFixed(4)),
                  clusterSize: neighbors,
                  score: (neighbors * 10) + scaleBonus
                });
              }
              singles.sort(function(a, b) {
                if (b.score !== a.score) return b.score - a.score;
                return a.heapIndex - b.heapIndex;
              });
              for (let i = 0; i < singles.length && topWatched.length < 12; i++) {
                const item = singles[i];
                if (addedSet.has(item.heapIndex)) continue;
                addedSet.add(item.heapIndex);
                const fallbackAxis = (scoredTriplets.length === 0 && topWatched.length < 3)
                  ? axisLabels[topWatched.length]
                  : '';
                topWatched.push({
                  heapIndex: item.heapIndex,
                  byteAddress: item.byteAddress,
                  baselineValue: item.baselineValue,
                  currentValue: item.currentValue,
                  delta: item.delta,
                  clusterSize: item.clusterSize,
                  axisHint: fallbackAxis
                });
              }
            }

            // Persist isolated TypedArray snapshot of surviving candidates for iterative refinement
            const nextCount = hitIndices.length;
            const nextIdxArr = new Int32Array(nextCount);
            const nextBaseArr = new Float32Array(nextCount);
            for (let i = 0; i < nextCount; i++) {
              nextIdxArr[i] = hitIndices[i];
              nextBaseArr[i] = hitCurrents[i];
            }

            __fd.sourceLabel = resolved.label;
            __fd.heapByteLength = resolved.byteLength;
            __fd.candidateIndices = nextIdxArr;
            __fd.candidateBaselines = nextBaseArr;
            __fd.candidateCount = nextCount;
            __fd.watchedItems = topWatched;
            __fd.scanRound = (__fd.scanRound || 0) + 1;

            if (topWatched.length > 0) {
              __startFloatWatch();
            } else {
              __stopFloatWatch();
            }

            const durationMs = Number((performance.now() - startMs).toFixed(1));
            __setFloatStatus(
              totalMatches > 0 ? 'COMPLETED' : 'IDLE',
              totalMatches > 0
                ? ('Float Deltas #' + __fd.scanRound + ': ' + totalMatches + ' matches • Watching top ' + topWatched.length)
                : ('Float Deltas #' + __fd.scanRound + ': 0 matches — walk & retry')
            );
            __emitFloat({
              stage: 'WASM_FLOAT_DELTA_SCANNED',
              durationMs: durationMs,
              heapSourceLabel: resolved.label,
              heapByteLength: resolved.byteLength,
              scanRound: __fd.scanRound,
              comparedCount: comparedCount,
              totalMatches: totalMatches,
              tripletCount: scoredTriplets.length,
              minDelta: MIN_DELTA,
              maxDelta: MAX_DELTA,
              candidates: topWatched
            });
          } catch (err) {
            try {
              if (window.TelemetryBridge) {
                window.TelemetryBridge.updateScanStatus('ERROR', 'Float delta scan failed');
                window.TelemetryBridge.logTelemetry('ERR', 'WASM Float Delta Scan error: ' + String(err));
              }
            } catch (_) {}
          }
        })();
        """.trimIndent()
    }

    /**
     * Synchronizes pinned `HEAPF32` indices into the live 200 ms watcher so pinned coordinates
     * remain visible even across subsequent delta refinement rounds.
     */
    fun buildSyncPinnedFloatsScript(pinnedIndices: Collection<Int>): String {
        val arrLiteral = pinnedIndices.distinct().filter { it >= 0 }.take(24).joinToString(",")
        return """
        (function() {
          try {
            $FLOAT_DELTA_RUNTIME
            __fd.pinnedIndices = [$arrLiteral];
            if ((__fd.pinnedIndices.length > 0 || (__fd.watchedItems && __fd.watchedItems.length > 0)) && __fd.pollId === null) {
              __startFloatWatch();
            }
          } catch (_) {}
        })();
        """.trimIndent()
    }

    /**
     * Stops the 200 ms Live Float Watcher interval cleanly.
     */
    fun buildStopFloatWatchScript(): String = """
        (function() {
          try {
            $FLOAT_DELTA_RUNTIME
            __stopFloatWatch();
            __setFloatStatus('IDLE', 'Live Float Watcher stopped');
          } catch (_) {}
        })();
    """.trimIndent()

    /**
     * Releases the isolated `HEAPF32` baseline clone and stops live watching.
     */
    fun buildResetFloatDeltaScript(): String = """
        (function() {
          try {
            $FLOAT_DELTA_RUNTIME
            __fd.scanSeq++;
            __stopFloatWatch();
            __fd.baselineClone = null;
            __fd.baselineLength = 0;
            __fd.isIsolatedClone = false;
            __fd.snapCount = 0;
            __fd.candidateIndices = null;
            __fd.candidateBaselines = null;
            __fd.candidateCount = 0;
            __fd.pinnedIndices = [];
            __fd.watchedItems = [];
            __fd.scanRound = 0;
            __fd.pollTick = 0;
            __setFloatStatus('IDLE', 'Float Heap Delta Scanner reset');
          } catch (_) {}
        })();
    """.trimIndent()
}
