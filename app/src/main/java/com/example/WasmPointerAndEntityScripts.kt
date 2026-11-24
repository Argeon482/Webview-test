package com.example

/**
 * Strictly read-only WebAssembly Pointer Live Watcher (200ms), Post-Reload Auto-Verifier,
 * and Step 3 WASM Nearby-Entity Struct Scanner.
 *
 * Security & observational guarantees:
 * - Never writes to WebAssembly linear memory (`HEAPF32`, `HEAPU32`, `HEAP32`).
 * - Never dispatches synthetic touch, pointer, or keyboard events.
 * - Uses non-blocking 1 MB async slices (`setTimeout(resolve, 12)`) during full-heap entity scans
 *   so the browser render loop and WebSocket traffic never stall.
 */
object WasmPointerAndEntityScripts {

    private const val SHARED_HEAP_RESOLVER = """
        const __peBridge = window.TelemetryBridge;
        const __peResolveHeaps = function() {
          const tryObj = function(obj, prefix) {
            if (!obj || typeof obj !== 'object') return null;
            try {
              if (obj.HEAPF32 instanceof Float32Array && obj.HEAPF32.buffer && obj.HEAPF32.buffer.byteLength >= 4) {
                const buf = obj.HEAPF32.buffer;
                return {
                  heapF32: obj.HEAPF32,
                  heapU32: new Uint32Array(buf),
                  heap32: new Int32Array(buf),
                  heap16: new Int16Array(buf),
                  heapU8: new Uint8Array(buf),
                  label: prefix + 'HEAPF32',
                  byteLength: buf.byteLength
                };
              }
              const h32 = obj.HEAP32 || obj.HEAPU8 || obj.HEAP8;
              if (h32 && ArrayBuffer.isView(h32) && h32.buffer && h32.buffer.byteLength >= 4) {
                const buf = h32.buffer;
                return {
                  heapF32: new Float32Array(buf),
                  heapU32: new Uint32Array(buf),
                  heap32: new Int32Array(buf),
                  heap16: new Int16Array(buf),
                  heapU8: new Uint8Array(buf),
                  label: prefix + 'HEAPF32 (buffer view)',
                  byteLength: buf.byteLength
                };
              }
              const mem = obj.wasmMemory || (obj.asm && obj.asm.memory) || (obj.wasmExports && obj.wasmExports.memory);
              if (typeof WebAssembly !== 'undefined' && mem instanceof WebAssembly.Memory && mem.buffer && mem.buffer.byteLength >= 4) {
                const buf = mem.buffer;
                return {
                  heapF32: new Float32Array(buf),
                  heapU32: new Uint32Array(buf),
                  heap32: new Int32Array(buf),
                  heap16: new Int16Array(buf),
                  heapU8: new Uint8Array(buf),
                  label: prefix + 'wasmMemory Float32Array',
                  byteLength: buf.byteLength
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

        const __peEmit = function(cat, obj) {
          try {
            if (__peBridge && typeof __peBridge.logTelemetry === 'function') {
              __peBridge.logTelemetry(cat, JSON.stringify(obj));
            }
          } catch (_) {}
        };

        const __peStatus = function(status, detail) {
          try {
            if (__peBridge && typeof __peBridge.updateScanStatus === 'function') {
              __peBridge.updateScanStatus(String(status || 'IDLE'), String(detail || ''));
            }
          } catch (_) {}
        };

        const __peYield = function(ms) {
          return new Promise(function(resolve) { setTimeout(resolve, ms || 12); });
        };

        const __peFormatHex = function(n) {
          return '0x' + (n >>> 0).toString(16).toUpperCase().padStart(8, '0');
        };

        const __peReadUtf8 = function(u8, startAddr, maxLen) {
          if (!u8 || startAddr <= 0 || startAddr >= u8.length) return null;
          const limit = Math.min(u8.length, startAddr + (maxLen || 48));
          const bytes = [];
          let letters = 0;
          for (let p = startAddr; p < limit; p++) {
            const b = u8[p];
            if (b === 0) break;
            if (b >= 32 && b <= 126) {
              bytes.push(b);
              if ((b >= 65 && b <= 90) || (b >= 97 && b <= 122)) letters++;
            } else if (b >= 0xC2 && b <= 0xF4 && p + 1 < limit) {
              bytes.push(b);
              letters++;
            } else if (b >= 0x80 && b <= 0xBF && bytes.length > 0) {
              bytes.push(b);
            } else {
              return null;
            }
          }
          if (bytes.length < 2 || bytes.length > 44 || letters < 1) return null;
          try {
            const txt = (typeof TextDecoder !== 'undefined')
              ? new TextDecoder('utf-8', { fatal: false }).decode(new Uint8Array(bytes))
              : String.fromCharCode.apply(null, bytes);
            const cleaned = txt.trim();
            return (cleaned.length >= 2 && cleaned.length <= 44) ? cleaned : null;
          } catch (_) {
            return null;
          }
        };

        const __peReadSchemaField = function(coordAddr, desc, heaps) {
          if (!desc || coordAddr <= 0) return null;
          const byteLen = heaps.byteLength;
          let addr = coordAddr + (desc.relOffsetBytes | 0);
          if (addr <= 0 || addr >= byteLen) return null;
          if (desc.subOffsetBytes !== null && desc.subOffsetBytes !== undefined) {
            if ((addr & 3) !== 0 || (addr >>> 2) >= heaps.heapU32.length) return null;
            const ptr = heaps.heapU32[addr >>> 2] >>> 0;
            if (ptr < 1024 || ptr >= byteLen) return null;
            addr = ptr + (desc.subOffsetBytes | 0);
            if (addr <= 0 || addr >= byteLen) return null;
          }
          const vt = desc.valueType || 'INT32';
          if (vt === 'UINT8') {
            return heaps.heapU8[addr] >>> 0;
          }
          if (vt === 'INT32') {
            if ((addr & 3) !== 0 || (addr >>> 2) >= heaps.heap32.length) return null;
            const v = heaps.heap32[addr >>> 2];
            return (v >= 0 && v <= 100000000) ? v : null;
          }
          if (vt === 'INT16') {
            if ((addr & 1) !== 0 || (addr >>> 1) >= heaps.heap16.length) return null;
            const v = heaps.heap16[addr >>> 1];
            return (v >= 0 && v <= 32767) ? v : null;
          }
          if (vt === 'FLOAT32') {
            if ((addr & 3) !== 0 || (addr >>> 2) >= heaps.heapF32.length) return null;
            const fv = heaps.heapF32[addr >>> 2];
            return (Number.isFinite(fv) && fv >= 0 && fv <= 100000000) ? Math.round(fv) : null;
          }
          if (vt === 'INLINE_UTF8') {
            return __peReadUtf8(heaps.heapU8, addr, 48);
          }
          if (vt === 'CHAR_PTR') {
            if ((addr & 3) !== 0 || (addr >>> 2) >= heaps.heapU32.length) return null;
            const strPtr = heaps.heapU32[addr >>> 2] >>> 0;
            if (strPtr < 1024 || strPtr >= byteLen) return null;
            return __peReadUtf8(heaps.heapU8, strPtr, 48);
          }
          if (vt === 'STD_STRING') {
            const b0 = heaps.heapU8[addr];
            if ((b0 & 1) === 0) {
              const ssoLen = b0 >>> 1;
              if (ssoLen >= 2 && ssoLen <= 22 && addr + 1 + ssoLen < byteLen) {
                const s1 = __peReadUtf8(heaps.heapU8, addr + 1, ssoLen + 1);
                if (s1) return s1;
              }
            }
            const slots = [8, 0, 4];
            for (let s = 0; s < slots.length; s++) {
              const sa = addr + slots[s];
              if ((sa & 3) !== 0 || (sa >>> 2) >= heaps.heapU32.length) continue;
              const p = heaps.heapU32[sa >>> 2] >>> 0;
              if (p >= 1024 && p < byteLen) {
                const t = __peReadUtf8(heaps.heapU8, p, 48);
                if (t) return t;
              }
            }
            return __peReadUtf8(heaps.heapU8, addr, 48);
          }
          return null;
        };

        const __peReadEntitySchema = function(coordAddr, heaps) {
          const schema = window.__wvStructSchema;
          if (!schema || typeof schema !== 'object' || coordAddr <= 0) return null;
          const out = {};
          let any = false;
          const fields = ['hp', 'maxHp', 'mp', 'maxMp', 'fp', 'maxFp', 'name', 'aggro'];
          for (let i = 0; i < fields.length; i++) {
            const k = fields[i];
            if (schema[k]) {
              const val = __peReadSchemaField(coordAddr, schema[k], heaps);
              if (val !== null && val !== undefined) {
                out[k] = val;
                any = true;
              }
            }
          }
          return any ? out : null;
        };

        const __peDerefChain = function(chain, heaps) {
          const hu32 = heaps.heapU32;
          const hf32 = heaps.heapF32;
          const byteLen = heaps.byteLength;
          const lenU32 = hu32.length;
          const lenF32 = hf32.length;
          let addr = (chain.basePointerAddress >>> 0);
          const offs = Array.isArray(chain.offsets) ? chain.offsets : [];

          for (let step = 0; step < offs.length; step++) {
            const uIdx = addr >>> 2;
            if (addr === 0 || (addr & 3) !== 0 || uIdx >= lenU32) {
              return { status: 'STALE', isVerifiedLive: false, addr: 0, x: 0, y: 0, z: 0 };
            }
            const deref = hu32[uIdx] >>> 0;
            const nextAddr = deref + (offs[step] | 0);
            if (deref === 0 || nextAddr <= 0 || nextAddr >= byteLen || (nextAddr & 3) !== 0) {
              return { status: 'STALE', isVerifiedLive: false, addr: 0, x: 0, y: 0, z: 0 };
            }
            addr = nextAddr;
          }

          const fIdx = addr >>> 2;
          if (fIdx < 0 || fIdx >= lenF32) {
            return { status: 'STALE', isVerifiedLive: false, addr: 0, x: 0, y: 0, z: 0 };
          }
          const rawX = hf32[fIdx];
          const rawY = (fIdx + 1 < lenF32) ? hf32[fIdx + 1] : 0;
          const rawZ = (fIdx + 2 < lenF32) ? hf32[fIdx + 2] : 0;
          if (!Number.isFinite(rawX) || Math.abs(rawX) > 500000 ||
              !Number.isFinite(rawY) || Math.abs(rawY) > 500000 ||
              !Number.isFinite(rawZ) || Math.abs(rawZ) > 500000) {
            return { status: 'STALE', isVerifiedLive: false, addr: 0, x: 0, y: 0, z: 0 };
          }

          const x = Number(rawX.toFixed(4));
          const y = Number(rawY.toFixed(4));
          const z = Number(rawZ.toFixed(4));
          const isZero = Math.abs(x) < 0.00001 && Math.abs(y) < 0.00001 && Math.abs(z) < 0.00001;
          return {
            status: isZero ? 'ZERO' : 'LIVE',
            isVerifiedLive: !isZero,
            addr: addr,
            x: x,
            y: y,
            z: z
          };
        };

        const __ptrWatch = (window.__wvWasmPtrWatch = window.__wvWasmPtrWatch || {
          autoSeq: 0,
          pollId: null,
          tick: 0,
          chains: [],
          hudPointerId: null,
          tracker: {}
        });

        const __peStopPtrWatch = function() {
          try {
            if (__ptrWatch.pollId !== null) {
              clearInterval(__ptrWatch.pollId);
              __ptrWatch.pollId = null;
            }
          } catch (_) {}
        };

        const __peStartPtrWatch = function(inputChains, hudId) {
          __peStopPtrWatch();
          __ptrWatch.chains = Array.isArray(inputChains) ? inputChains : [];
          __ptrWatch.hudPointerId = hudId || null;
          __ptrWatch.tick = 0;
          const prevTracker = __ptrWatch.tracker || {};
          const nextTracker = {};
          for (let i = 0; i < __ptrWatch.chains.length; i++) {
            const c = __ptrWatch.chains[i];
            if (prevTracker[c.id]) {
              nextTracker[c.id] = prevTracker[c.id];
            }
          }
          __ptrWatch.tracker = nextTracker;

          const pushPtrTick = function() {
            try {
              const heaps = __peResolveHeaps();
              if (!heaps || __ptrWatch.chains.length === 0) return;

              __ptrWatch.tick++;
              const results = [];
              let movingCount = 0;
              let liveCount = 0;
              let zeroCount = 0;
              let staleCount = 0;
              let hudResolved = null;

              for (let i = 0; i < __ptrWatch.chains.length; i++) {
                const c = __ptrWatch.chains[i];
                const d = __peDerefChain(c, heaps);
                let tr = __ptrWatch.tracker[c.id];
                let status = d.status;
                let changedFromPrev = false;
                let lastDeltaMag = 0;
                let changeCount = tr ? (tr.changeCount || 0) : 0;

                if (d.status === 'LIVE') {
                  if (!tr || !tr.hasBaseline) {
                    tr = {
                      hasBaseline: true,
                      baseX: d.x,
                      baseY: d.y,
                      baseZ: d.z,
                      prevX: d.x,
                      prevY: d.y,
                      prevZ: d.z,
                      everMoved: false,
                      changeCount: 0
                    };
                    __ptrWatch.tracker[c.id] = tr;
                  } else {
                    const stepDx = d.x - tr.prevX;
                    const stepDy = d.y - tr.prevY;
                    const stepDz = d.z - tr.prevZ;
                    const stepMag = Math.sqrt(stepDx * stepDx + stepDy * stepDy + stepDz * stepDz);
                    if (stepMag >= 0.002) {
                      changedFromPrev = true;
                      lastDeltaMag = Number(stepMag.toFixed(4));
                      tr.changeCount = (tr.changeCount || 0) + 1;
                      tr.everMoved = true;
                    }
                    const totalDx = d.x - tr.baseX;
                    const totalDy = d.y - tr.baseY;
                    const totalDz = d.z - tr.baseZ;
                    const totalMag = Math.sqrt(totalDx * totalDx + totalDy * totalDy + totalDz * totalDz);
                    if (totalMag >= 0.005) {
                      tr.everMoved = true;
                    }
                    tr.prevX = d.x;
                    tr.prevY = d.y;
                    tr.prevZ = d.z;
                    changeCount = tr.changeCount || 0;
                  }
                  if (tr.everMoved) {
                    status = 'MOVING';
                    movingCount++;
                  } else {
                    liveCount++;
                  }
                } else if (d.status === 'ZERO') {
                  zeroCount++;
                } else {
                  staleCount++;
                }

                const entry = {
                  id: c.id,
                  status: status,
                  isVerifiedLive: (status === 'MOVING' || status === 'LIVE'),
                  resolvedTargetAddress: d.addr,
                  resolvedX: d.x,
                  resolvedY: d.y,
                  resolvedZ: d.z,
                  changedFromPrev: changedFromPrev,
                  changeCount: changeCount,
                  lastDeltaMagnitude: lastDeltaMag
                };
                results.push(entry);

                if (__ptrWatch.hudPointerId && c.id === __ptrWatch.hudPointerId && entry.isVerifiedLive) {
                  hudResolved = {
                    id: c.id,
                    expression: c.expression || c.id,
                    addr: d.addr,
                    x: d.x,
                    y: d.y,
                    z: d.z
                  };
                }
              }

              if (hudResolved && __peBridge && typeof __peBridge.updatePlayerCoords === 'function') {
                __peBridge.updatePlayerCoords(
                  'WASM_PTR: ' + hudResolved.expression,
                  hudResolved.x,
                  hudResolved.y,
                  hudResolved.z
                );
              }

              let vitalsAnchorAddr = hudResolved ? hudResolved.addr : 0;
              if (vitalsAnchorAddr <= 0) {
                for (let ri = 0; ri < results.length; ri++) {
                  if (results[ri].isVerifiedLive && results[ri].resolvedTargetAddress > 0) {
                    vitalsAnchorAddr = results[ri].resolvedTargetAddress;
                    break;
                  }
                }
              }
              const playerVitals = vitalsAnchorAddr > 0
                ? __peReadEntitySchema(vitalsAnchorAddr, heaps)
                : null;

              const payload = {
                stage: 'WASM_POINTER_WATCH_TICK',
                tick: __ptrWatch.tick,
                heapSourceLabel: heaps.label,
                heapByteLength: heaps.byteLength,
                movingCount: movingCount,
                liveCount: liveCount,
                zeroCount: zeroCount,
                staleCount: staleCount,
                activeHudPointerId: __ptrWatch.hudPointerId,
                hudTargetAddress: hudResolved ? hudResolved.addr : 0,
                playerVitals: playerVitals,
                results: results
              };

              if (__peBridge && typeof __peBridge.updateWasmPointers === 'function') {
                __peBridge.updateWasmPointers(JSON.stringify(payload));
              }
            } catch (_) {}
          };

          pushPtrTick();
          __ptrWatch.pollId = setInterval(pushPtrTick, 200);
        };
    """

    /**
     * Starts continuous read-only 200ms live pointer verification across all provided chains,
     * streaming `MOVING / LIVE / ZERO / STALE` classifications and optional HUD coordinates.
     */
    fun buildStartPointerWatchScript(
        chainsJsonArray: String,
        hudPointerId: String? = null
    ): String {
        val hudLiteral = if (hudPointerId.isNullOrBlank()) "null" else quoteJsString(hudPointerId)
        return """
        (function() {
          try {
            $SHARED_HEAP_RESOLVER
            const heaps = __peResolveHeaps();
            if (!heaps) {
              __peEmit('WASM', { stage: 'WASM_POINTER_ERROR', message: 'WASM heap unavailable for live pointer watch.' });
              return;
            }
            const chains = $chainsJsonArray;
            if (!Array.isArray(chains) || chains.length === 0) {
              __peStopPtrWatch();
              return;
            }
            __peStartPtrWatch(chains, $hudLiteral);
            __peStatus('COMPLETED', 'Watching ' + chains.length + ' pointer chains live (200ms)');
          } catch (err) {
            try {
              if (window.TelemetryBridge) {
                window.TelemetryBridge.logTelemetry('ERR', 'Start Pointer Watch error: ' + String(err));
              }
            } catch (_) {}
          }
        })();
        """.trimIndent()
    }

    /**
     * Updates which pointer chain (if any) streams directly to the top live coordinates HUD
     * without resetting existing movement counters.
     */
    fun buildSetHudPointerScript(hudPointerId: String?): String {
        val hudLiteral = if (hudPointerId.isNullOrBlank()) "null" else quoteJsString(hudPointerId)
        return """
        (function() {
          try {
            if (window.__wvWasmPtrWatch) {
              window.__wvWasmPtrWatch.hudPointerId = $hudLiteral;
            }
          } catch (_) {}
        })();
        """.trimIndent()
    }

    /**
     * Cleanly stops the 200ms live pointer watch interval.
     */
    fun buildStopPointerWatchScript(): String = """
        (function() {
          try {
            if (window.__wvWasmPtrWatch) {
              window.__wvWasmPtrWatch.autoSeq++;
              if (window.__wvWasmPtrWatch.pollId !== null) {
                clearInterval(window.__wvWasmPtrWatch.pollId);
                window.__wvWasmPtrWatch.pollId = null;
              }
            }
            if (window.TelemetryBridge && typeof window.TelemetryBridge.updateScanStatus === 'function') {
              window.TelemetryBridge.updateScanStatus('IDLE', 'Live pointer watch paused');
            }
          } catch (_) {}
        })();
    """.trimIndent()

    /**
     * Non-blocking post-reload auto-verifier: gently waits for the WASM heap to finish booting
     * (polling every 800ms up to [maxWaitMs]), verifies saved per-site pointer chains, and
     * immediately starts 200ms Live Watch once at least one chain resolves non-zero.
     */
    fun buildAutoVerifySavedPointersScript(
        chainsJsonArray: String,
        hudPointerId: String? = null,
        maxWaitMs: Int = 20_000
    ): String {
        val hudLiteral = if (hudPointerId.isNullOrBlank()) "null" else quoteJsString(hudPointerId)
        val safeMaxWait = maxWaitMs.coerceIn(2_000, 30_000)
        return """
        (async function() {
          try {
            $SHARED_HEAP_RESOLVER
            const chains = $chainsJsonArray;
            if (!Array.isArray(chains) || chains.length === 0) return;

            const myAutoSeq = ++__ptrWatch.autoSeq;
            const deadline = performance.now() + $safeMaxWait;
            let resolvedHeaps = null;
            let bestSnapshot = null;

            while (performance.now() <= deadline) {
              if (myAutoSeq !== __ptrWatch.autoSeq) return;
              const heaps = __peResolveHeaps();
              if (heaps && heaps.byteLength >= 4096) {
                resolvedHeaps = heaps;
                let nonZeroCount = 0;
                let zeroCount = 0;
                const snapshot = [];
                for (let i = 0; i < chains.length; i++) {
                  const d = __peDerefChain(chains[i], heaps);
                  if (d.status === 'LIVE') nonZeroCount++;
                  else if (d.status === 'ZERO') zeroCount++;
                  snapshot.push({
                    id: chains[i].id,
                    status: d.status,
                    isVerifiedLive: d.isVerifiedLive,
                    resolvedTargetAddress: d.addr,
                    resolvedX: d.x,
                    resolvedY: d.y,
                    resolvedZ: d.z
                  });
                }
                bestSnapshot = { nonZeroCount: nonZeroCount, zeroCount: zeroCount, results: snapshot };
                if (nonZeroCount > 0) break;
              }
              await __peYield(800);
            }

            if (myAutoSeq !== __ptrWatch.autoSeq) return;

            if (!resolvedHeaps || !bestSnapshot) {
              __peEmit('WASM', {
                stage: 'WASM_POINTER_AUTO_VERIFY_DONE',
                heapFound: false,
                nonZeroCount: 0,
                zeroCount: 0,
                totalCount: chains.length,
                results: []
              });
              return;
            }

            let autoHudId = $hudLiteral;
            if (!autoHudId) {
              for (let i = 0; i < bestSnapshot.results.length; i++) {
                if (bestSnapshot.results[i].status === 'LIVE') {
                  autoHudId = bestSnapshot.results[i].id;
                  break;
                }
              }
            }

            __peEmit('WASM', {
              stage: 'WASM_POINTER_AUTO_VERIFY_DONE',
              heapFound: true,
              heapSourceLabel: resolvedHeaps.label,
              heapByteLength: resolvedHeaps.byteLength,
              nonZeroCount: bestSnapshot.nonZeroCount,
              zeroCount: bestSnapshot.zeroCount,
              totalCount: chains.length,
              autoHudPointerId: autoHudId,
              results: bestSnapshot.results
            });

            __peStartPtrWatch(chains, autoHudId);
          } catch (err) {
            try {
              if (window.TelemetryBridge) {
                window.TelemetryBridge.logTelemetry('ERR', 'Auto-verify saved pointers error: ' + String(err));
              }
            } catch (_) {}
          }
        })();
        """.trimIndent()
    }

    /**
     * Step 3 (WASM Mode): Strictly read-only Anti-Thrash Spatial Entity Scanner over 100% of `window.HEAPF32`.
     * - Pass 1 scans 100% of `HEAPF32` in non-blocking 1 MB async slices (`setTimeout(resolve, 12)`)
     *   into compact TypedArrays (up to 200,000 coords, no premature 2,000-hit cutoff), starting from
     *   `minRadius = 0.0` so mobs you are standing directly on top of are NEVER excluded.
     * - 3-Sample Stability Check (`t=0`, `t=half`, `t=dwellMs`, default 360ms):
     *   KEEPS both standing/idle mobs AND smoothly walking mobs, while automatically REJECTING erratic
     *   per-frame reused/scratchpad buffers that jump unrealistically (`60u -> 80u -> 65u` in milliseconds)
     *   or bounce back-and-forth, as well as camera/player clones that mirror player motion.
     * - Alignment & heap-pool aware spatial deduplication picks the real gameplay struct per mob,
     *   and sorts all results strictly closest-to-furthest so any mob you stand on top of rises to Row #1.
     */
    fun buildScanWasmNearbyEntitiesScript(
        playerByteAddress: Long,
        playerX: Double,
        playerY: Double,
        playerZ: Double,
        structCoordOffset: Int = 0,
        minRadius: Double = 0.0,
        maxRadius: Double = 250.0,
        maxYDelta: Double = 45.0,
        dwellMs: Int = 360,
        movingOnly: Boolean = false
    ): String {
        val safePlayerAddr = playerByteAddress.coerceAtLeast(0L)
        val safePx = if (playerX.isFinite()) playerX.toString() else "0.0"
        val safePy = if (playerY.isFinite()) playerY.toString() else "0.0"
        val safePz = if (playerZ.isFinite()) playerZ.toString() else "0.0"
        val safeStructOff = structCoordOffset.coerceIn(0, 1024)
        val safeMinR = minRadius.coerceIn(0.0, 50.0)
        val safeMaxR = maxRadius.coerceIn(10.0, 2000.0)
        val safeMaxY = maxYDelta.coerceIn(5.0, 500.0)
        val safeDwellMs = dwellMs.coerceIn(240, 5000)
        val movingOnlyLiteral = if (movingOnly) "true" else "false"

        return """
        (async function() {
          try {
            $SHARED_HEAP_RESOLVER
            const __entState = (window.__wvWasmEntities = window.__wvWasmEntities || {
              scanSeq: 0,
              pollId: null,
              tick: 0,
              playerByteAddr: 0,
              structOffset: 0,
              tracked: []
            });

            const heaps = __peResolveHeaps();
            if (!heaps) {
              __peStatus('ERROR', 'WASM heap unavailable for entity scan');
              __peEmit('ENTITY', {
                stage: 'WASM_ENTITIES_DISCOVERED',
                error: 'Could not locate window.HEAPF32 on this page.',
                entities: []
              });
              return;
            }

            const mySeq = ++__entState.scanSeq;
            const startMs = performance.now();
            const hf32 = heaps.heapF32;
            const h32 = heaps.heap32;
            const lenF32 = hf32.length;

            const readLivePlayer = function(fallbackAddr, fallbackX, fallbackY, fallbackZ) {
              let addr = fallbackAddr;
              let rx = fallbackX;
              let ry = fallbackY;
              let rz = fallbackZ;
              if (__ptrWatch && __ptrWatch.hudPointerId && Array.isArray(__ptrWatch.chains)) {
                for (let i = 0; i < __ptrWatch.chains.length; i++) {
                  if (__ptrWatch.chains[i].id === __ptrWatch.hudPointerId) {
                    const d = __peDerefChain(__ptrWatch.chains[i], heaps);
                    if (d.isVerifiedLive && d.addr > 0) {
                      return { addr: d.addr, x: d.x, y: d.y, z: d.z };
                    }
                    break;
                  }
                }
              }
              if (addr > 0 && ((addr >>> 2) + 2) < lenF32) {
                const pIdx = addr >>> 2;
                if (Number.isFinite(hf32[pIdx]) && Number.isFinite(hf32[pIdx + 1]) && Number.isFinite(hf32[pIdx + 2])) {
                  rx = hf32[pIdx];
                  ry = hf32[pIdx + 1];
                  rz = hf32[pIdx + 2];
                }
              }
              return { addr: addr, x: rx, y: ry, z: rz };
            };

            const p0 = readLivePlayer($safePlayerAddr, Number($safePx), Number($safePy), Number($safePz));
            const playerAddr0 = p0.addr;
            const px0 = p0.x;
            const py0 = p0.y;
            const pz0 = p0.z;
            const playerFloatIdx = playerAddr0 >>> 2;

            const STRUCT_OFF = $safeStructOff;
            const MIN_R = Number($safeMinR);
            const MAX_R = Number($safeMaxR);
            const MIN_R2 = MIN_R * MIN_R;
            const MAX_R2 = MAX_R * MAX_R;
            const MAX_Y = Number($safeMaxY);
            const DWELL_MS = $safeDwellMs;
            const MOVING_ONLY = $movingOnlyLiteral;
            const START_IDX = lenF32 > 4096 ? 256 : 0;
            const CHUNK_FLOATS = 262144; // 1 MB per non-blocking slice
            const MAX_PASS1_SNAP = 200000;

            const snapIdx = new Int32Array(MAX_PASS1_SNAP);
            const snapX0 = new Float32Array(MAX_PASS1_SNAP);
            const snapY0 = new Float32Array(MAX_PASS1_SNAP);
            const snapZ0 = new Float32Array(MAX_PASS1_SNAP);
            let snapCount = 0;

            __peStatus('RUNNING', 'Step 3 WASM: scanning 100% of HEAPF32…');

            for (let base = START_IDX; base < lenF32 - 2 && snapCount < MAX_PASS1_SNAP; base += CHUNK_FLOATS) {
              if (mySeq !== __entState.scanSeq) return;
              const end = Math.min(base + CHUNK_FLOATS, lenF32 - 2);
              for (let i = base; i < end && snapCount < MAX_PASS1_SNAP; i++) {
                // Skip player's own immediate struct vicinity only (allow mobs right next to / under player)
                if (playerFloatIdx > 0 && Math.abs(i - playerFloatIdx) <= 24) continue;

                const vx = hf32[i];
                if (vx !== vx) continue;
                const dx = vx - px0;
                if (dx < -MAX_R || dx > MAX_R) continue;

                const vy = hf32[i + 1];
                if (vy !== vy) continue;
                const dy = vy - py0;
                if (dy < -MAX_Y || dy > MAX_Y) continue;

                const vz = hf32[i + 2];
                if (vz !== vz) continue;
                const dz = vz - pz0;
                if (dz < -MAX_R || dz > MAX_R) continue;

                const dist2 = dx * dx + dy * dy + dz * dz;
                if (dist2 < MIN_R2 || dist2 > MAX_R2) continue;

                // Reject degenerate origin-plane floats
                if (Math.abs(vx) < 0.001 && Math.abs(vz) < 0.001) continue;

                snapIdx[snapCount] = i;
                snapX0[snapCount] = vx;
                snapY0[snapCount] = vy;
                snapZ0[snapCount] = vz;
                snapCount++;
                // Advance past this Vec3 so overlapping (Y, Z, next) slots aren't double-counted
                i += 2;
              }
              if (end < lenF32 - 2 && snapCount < MAX_PASS1_SNAP) {
                await __peYield(12);
              }
            }

            if (mySeq !== __entState.scanSeq) return;

            // 3-Sample Anti-Thrash Stability Check (t=0, t=half, t=dwellMs):
            // Keeps BOTH standing mobs AND smoothly walking mobs, while dropping erratic
            // scratchpad/reused-buffer floats (e.g. 60u -> 80u -> 65u in milliseconds).
            const halfDwell = Math.round(DWELL_MS / 2);
            const remDwell = Math.max(120, DWELL_MS - halfDwell);
            __peStatus(
              'RUNNING',
              'Step 3 WASM: filtering jittery buffers across ' + snapCount + ' coords…'
            );

            await __peYield(halfDwell);
            if (mySeq !== __entState.scanSeq) return;

            const p1 = readLivePlayer(playerAddr0, px0, py0, pz0);
            const pdx1 = p1.x - px0;
            const pdy1 = p1.y - py0;
            const pdz1 = p1.z - pz0;
            const pStep1 = Math.sqrt(pdx1 * pdx1 + pdy1 * pdy1 + pdz1 * pdz1);

            const snapX1 = new Float32Array(snapCount);
            const snapY1 = new Float32Array(snapCount);
            const snapZ1 = new Float32Array(snapCount);
            for (let k = 0; k < snapCount; k++) {
              const idx = snapIdx[k];
              if (idx + 2 < lenF32) {
                snapX1[k] = hf32[idx];
                snapY1[k] = hf32[idx + 1];
                snapZ1[k] = hf32[idx + 2];
              }
            }

            await __peYield(remDwell);
            if (mySeq !== __entState.scanSeq) return;

            const p2 = readLivePlayer(playerAddr0, px0, py0, pz0);
            const playerAddr = p2.addr;
            const px = p2.x;
            const py = p2.y;
            const pz = p2.z;
            const pdx2 = px - p1.x;
            const pdy2 = py - p1.y;
            const pdz2 = pz - p1.z;
            const pStep2 = Math.sqrt(pdx2 * pdx2 + pdy2 * pdy2 + pdz2 * pdz2);
            const pTotal = Math.sqrt((px - px0) * (px - px0) + (py - py0) * (py - py0) + (pz - pz0) * (pz - pz0));

            // Realistic speed cap (~36 u/s max for a single half-interval, never 20u-in-180ms teleports)
            const MAX_STEP_PER_HALF = Math.max(6.5, (halfDwell / 180.0) * 6.5);
            const MAX_VERT_STEP = 4.5;
            const playerPhase16 = playerAddr > 0 ? (playerAddr & 15) : -1;

            let erraticDroppedCount = 0;
            let staticFilteredCount = 0;
            const validHits = [];

            for (let k = 0; k < snapCount; k++) {
              const idx = snapIdx[k];
              if (idx + 2 >= lenF32) continue;
              const x0 = snapX0[k], y0 = snapY0[k], z0 = snapZ0[k];
              const x1 = snapX1[k], y1 = snapY1[k], z1 = snapZ1[k];
              const x2 = hf32[idx], y2 = hf32[idx + 1], z2 = hf32[idx + 2];
              if (!Number.isFinite(x1) || !Number.isFinite(y1) || !Number.isFinite(z1) ||
                  !Number.isFinite(x2) || !Number.isFinite(y2) || !Number.isFinite(z2)) {
                erraticDroppedCount++;
                continue;
              }
              if (Math.abs(x2) < 0.001 && Math.abs(z2) < 0.001) {
                erraticDroppedCount++;
                continue;
              }

              const v1x = x1 - x0, v1y = y1 - y0, v1z = z1 - z0;
              const v2x = x2 - x1, v2y = y2 - y1, v2z = z2 - z1;
              const step1 = Math.sqrt(v1x * v1x + v1y * v1y + v1z * v1z);
              const step2 = Math.sqrt(v2x * v2x + v2y * v2y + v2z * v2z);
              const totalMove = Math.sqrt((x2 - x0) * (x2 - x0) + (y2 - y0) * (y2 - y0) + (z2 - z0) * (z2 - z0));

              // 1) Reject unrealistic per-interval jumps & vertical teleports (scratchpad/reused buffers)
              if (step1 > MAX_STEP_PER_HALF || step2 > MAX_STEP_PER_HALF ||
                  Math.abs(v1y) > MAX_VERT_STEP || Math.abs(v2y) > MAX_VERT_STEP) {
                erraticDroppedCount++;
                continue;
              }

              // 2) Reject ping-pong / back-and-forth jitter (e.g. 60u -> 80u -> 65u)
              const dotXZ = v1x * v2x + v1z * v2z;
              if (step1 > 0.75 && step2 > 0.75 && dotXZ < -0.25 * step1 * step2) {
                erraticDroppedCount++;
                continue;
              }

              // 3) Reject player-mirrored camera/physics/shadow clones
              if (pTotal >= 0.12) {
                const mirror1 = Math.sqrt((v1x - pdx1) * (v1x - pdx1) + (v1y - pdy1) * (v1y - pdy1) + (v1z - pdz1) * (v1z - pdz1));
                const mirror2 = Math.sqrt((v2x - pdx2) * (v2x - pdx2) + (v2y - pdy2) * (v2y - pdy2) + (v2z - pdz2) * (v2z - pdz2));
                if ((pStep1 >= 0.05 && mirror1 < 0.12 && (pStep2 < 0.05 || mirror2 < 0.12)) ||
                    (pStep2 >= 0.05 && mirror2 < 0.12 && (pStep1 < 0.05 || mirror1 < 0.12))) {
                  erraticDroppedCount++;
                  continue;
                }
              }

              const dx = x2 - px;
              const dy = y2 - py;
              const dz = z2 - pz;
              if (Math.abs(dy) > MAX_Y) continue;
              const dist2 = dx * dx + dy * dy + dz * dz;
              if (dist2 < MIN_R2 || dist2 > MAX_R2) continue;
              const dist = Math.sqrt(dist2);

              // Reject exact-zero-distance player clone (but keep mobs standing 0.03u..4.0u next to/under player!)
              if (dist < 0.03) {
                erraticDroppedCount++;
                continue;
              }

              const isSmoothWalking = (totalMove >= 0.04 && (step1 <= 0.35 || step2 <= 0.35 || dotXZ >= -0.1 * step1 * step2));
              if (MOVING_ONLY && !isSmoothWalking) {
                staticFilteredCount++;
                continue;
              }

              const byteAddr = idx * 4;
              const structBase = (STRUCT_OFF > 0 && byteAddr >= STRUCT_OFF) ? (byteAddr - STRUCT_OFF) : byteAddr;
              const matchesOffsetMod = (STRUCT_OFF > 0 && ((byteAddr - STRUCT_OFF) & 15) === 0);
              const matchesPlayerPhase = (playerPhase16 >= 0 && (byteAddr & 15) === playerPhase16);
              const heapProximity = playerAddr > 0 ? Math.abs(byteAddr - playerAddr) : byteAddr;
              const sameHeapPool = (playerAddr > 0 && heapProximity <= 8388608); // within ±8 MB of player struct

              // Score structural authenticity for spatial clone deduplication (prefer real CMover struct over mesh copy)
              let structScore = 0;
              if (matchesOffsetMod) structScore += 400;
              if (matchesPlayerPhase) structScore += 300;
              if (sameHeapPool) structScore += 200;
              if (isSmoothWalking) structScore += 150;
              if (Math.abs(dy) <= 10.0) structScore += 80;

              validHits.push({
                heapIndex: idx,
                byteAddr: byteAddr,
                structBase: structBase,
                matchesOffsetMod: matchesOffsetMod,
                matchesPlayerPhase: matchesPlayerPhase,
                heapProximity: heapProximity,
                structScore: structScore,
                x: x2,
                y: y2,
                z: z2,
                dx: dx,
                dy: dy,
                dz: dz,
                dist: dist,
                selfMoveDelta: totalMove,
                isSmoothWalking: isSmoothWalking
              });
            }

            // Alignment & heap-pool aware spatial deduplication:
            // Sort by structural score first so if a mob has both a gameplay struct and a render mesh copy
            // at the exact same (X,Y,Z), we keep the real gameplay struct.
            validHits.sort(function(a, b) {
              if (a.structScore !== b.structScore) return b.structScore - a.structScore;
              if (a.heapProximity !== b.heapProximity) return a.heapProximity - b.heapProximity;
              return a.dist - b.dist;
            });

            // Collapse spatial clones within 0.35u; retain up to 160 distinct candidates before final closest-60 cut
            const deduped = [];
            for (let i = 0; i < validHits.length && deduped.length < 160; i++) {
              const cand = validHits[i];
              let isClone = false;
              for (let j = 0; j < deduped.length; j++) {
                const ex = deduped[j];
                const ddx = cand.x - ex.x;
                const ddy = cand.y - ex.y;
                const ddz = cand.z - ex.z;
                if ((ddx * ddx + ddy * ddy + ddz * ddz) < 0.1225) { // 0.35u squared
                  isClone = true;
                  break;
                }
              }
              if (!isClone) {
                deduped.push(cand);
              }
            }

            // Final presentation order: strictly closest to furthest (standing-on-mob <= 4.0u automatically at top!)
            deduped.sort(function(a, b) {
              if (Math.abs(a.dist - b.dist) > 0.05) return a.dist - b.dist;
              if (a.structScore !== b.structScore) return b.structScore - a.structScore;
              return a.heapIndex - b.heapIndex;
            });

            const finalList = deduped.slice(0, 60);
            const vw = window.innerWidth || 0;
            const vh = window.innerHeight || 0;
            const entities = [];
            const tracked = [];

            for (let i = 0; i < finalList.length; i++) {
              const e = finalList[i];
              const baseIdx = e.structBase >>> 2;
              let candidateId = '';
              if (STRUCT_OFF > 0 && baseIdx >= 0 && baseIdx + 2 < h32.length) {
                const rawId = h32[baseIdx] >>> 0;
                if (rawId > 0 && rawId < 100000000) {
                  candidateId = String(rawId);
                }
              }
              const hexCoord = __peFormatHex(e.byteAddr);
              const hexStruct = __peFormatHex(e.structBase);
              const relTag = 'Δ(' + e.dx.toFixed(1) + ', ' + e.dy.toFixed(1) + ', ' + e.dz.toFixed(1) + ')';
              const entityName = 'WASM Entity #' + (i + 1);
              const entityPath = 'HEAPF32[' + e.heapIndex + '] @ ' + hexCoord;
              const decoded = __peReadEntitySchema(e.byteAddr, heaps) || {};
              const activeTargetCoord = (window.__wvTargetScan && window.__wvTargetScan.lastResolvedCoordAddr > 0)
                ? window.__wvTargetScan.lastResolvedCoordAddr : 0;
              const activeTargetStruct = (window.__wvTargetScan && window.__wvTargetScan.lastResolvedAddr > 0)
                ? window.__wvTargetScan.lastResolvedAddr : 0;
              const isActiveTgt = (activeTargetCoord > 0 && Math.abs(e.byteAddr - activeTargetCoord) <= 64) ||
                (activeTargetStruct > 0 && (e.structBase === activeTargetStruct || Math.abs(e.byteAddr - activeTargetStruct) <= 1024));
              const aggroRaw = (typeof decoded.aggro === 'number') ? decoded.aggro : null;

              entities.push({
                name: entityName,
                id: candidateId || hexStruct,
                path: entityPath,
                coordByteAddress: e.byteAddr,
                heapIndex: e.heapIndex,
                resolvedName: decoded.name || null,
                hp: (typeof decoded.hp === 'number') ? decoded.hp : null,
                maxHp: (typeof decoded.maxHp === 'number') ? decoded.maxHp : null,
                mp: (typeof decoded.mp === 'number') ? decoded.mp : null,
                maxMp: (typeof decoded.maxMp === 'number') ? decoded.maxMp : null,
                fp: (typeof decoded.fp === 'number') ? decoded.fp : null,
                maxFp: (typeof decoded.maxFp === 'number') ? decoded.maxFp : null,
                aggroRawValue: aggroRaw,
                isAggro: (aggroRaw !== null) ? (aggroRaw !== 0) : null,
                isActiveGameTarget: isActiveTgt,
                worldX: Number(e.x.toFixed(3)),
                worldY: Number(e.y.toFixed(3)),
                worldZ: Number(e.z.toFixed(3)),
                deltaX: Number(e.dx.toFixed(3)),
                deltaZ: Number(e.dz.toFixed(3)),
                distanceToPlayer: Number(e.dist.toFixed(2)),
                distanceDelta: null,
                selfMoveDelta: Number(e.selfMoveDelta.toFixed(3)),
                isConfirmedMoving: e.isSmoothWalking,
                screenX: 0,
                screenY: 0,
                inViewport: e.dist <= 65.0,
                sampleKeys: [
                  'WASM_STRUCT',
                  e.isSmoothWalking ? 'WALKING' : 'STABLE',
                  'struct=' + hexStruct,
                  relTag,
                  STRUCT_OFF > 0 ? ('off=+0x' + STRUCT_OFF.toString(16).toUpperCase()) : 'spatial-vec3'
                ]
              });
              tracked.push({
                name: entityName,
                id: candidateId || hexStruct,
                path: entityPath,
                heapIndex: e.heapIndex,
                byteAddr: e.byteAddr,
                structBase: e.structBase,
                prevX: e.x,
                prevY: e.y,
                prevZ: e.z,
                prevVx: 0,
                prevVz: 0,
                prevMove: 0,
                prevDist: e.dist,
                isSmoothWalking: e.isSmoothWalking,
                erraticStrikes: 0,
                dropped: false
              });
            }

            __entState.playerByteAddr = playerAddr;
            __entState.structOffset = STRUCT_OFF;
            __entState.tracked = tracked;

            const durationMs = Number((performance.now() - startMs).toFixed(1));
            __peStatus(
              entities.length > 0 ? 'COMPLETED' : 'IDLE',
              'Step 3 WASM: ' + entities.length + ' stable nearby (' + erraticDroppedCount + ' jittery dropped)'
            );
            __peEmit('ENTITY', {
              stage: 'WASM_ENTITIES_DISCOVERED',
              twoPass: true,
              movingOnly: MOVING_ONLY,
              dwellMs: DWELL_MS,
              durationMs: durationMs,
              heapSourceLabel: heaps.label,
              playerByteAddress: playerAddr,
              playerCoords: { x: Number(px.toFixed(3)), y: Number(py.toFixed(3)), z: Number(pz.toFixed(3)) },
              structCoordOffset: STRUCT_OFF,
              pass1CandidateCount: snapCount,
              rawCandidateCount: validHits.length,
              staticFilteredCount: staticFilteredCount,
              erraticDroppedCount: erraticDroppedCount,
              viewportWidth: vw,
              viewportHeight: vh,
              projectionMode: MOVING_ONLY
                ? ('WASM Smooth Walkers (' + (DWELL_MS / 1000).toFixed(1) + 's)')
                : 'WASM Anti-Thrash Spatial (0..250u)',
              arrayPath: heaps.label + ' near ' + __peFormatHex(playerAddr),
              entities: entities
            });
          } catch (err) {
            try {
              if (window.TelemetryBridge) {
                window.TelemetryBridge.updateScanStatus('ERROR', 'WASM entity scan failed');
                window.TelemetryBridge.logTelemetry('ERR', 'WASM Entity Scan error: ' + String(err));
              }
            } catch (_) {}
          }
        })();
        """.trimIndent()
    }

    /**
     * Starts a read-only 500ms live position refresh loop for the discovered WASM nearby entities,
     * recomputing world `(X, Y, Z)`, relative `(ΔX, ΔZ)` compass vector, live distance trend (`distanceDelta`),
     * entity self-movement (`selfMoveDelta`), continuously auto-pruning erratic scratchpad buffers that
     * teleport or ping-pong (`60u -> 80u -> 65u`), and sorting strictly closest-to-furthest every tick
     * so any mob you stand on top of floats straight to the top of the list.
     */
    fun buildStartWasmEntitiesLiveRefreshScript(): String = """
        (function() {
          try {
            $SHARED_HEAP_RESOLVER
            const __entState = window.__wvWasmEntities;
            if (!__entState || !Array.isArray(__entState.tracked) || __entState.tracked.length === 0) {
              return;
            }
            if (__entState.pollId !== null) {
              clearInterval(__entState.pollId);
              __entState.pollId = null;
            }
            __entState.tick = 0;

            const pushEntityTick = function() {
              try {
                const heaps = __peResolveHeaps();
                if (!heaps) return;
                const hf32 = heaps.heapF32;
                const lenF32 = hf32.length;

                let px = 0, py = 0, pz = 0;
                let hasPlayer = false;
                if (__ptrWatch && __ptrWatch.hudPointerId && Array.isArray(__ptrWatch.chains)) {
                  for (let i = 0; i < __ptrWatch.chains.length; i++) {
                    if (__ptrWatch.chains[i].id === __ptrWatch.hudPointerId) {
                      const d = __peDerefChain(__ptrWatch.chains[i], heaps);
                      if (d.isVerifiedLive) {
                        px = d.x; py = d.y; pz = d.z;
                        hasPlayer = true;
                      }
                      break;
                    }
                  }
                }
                if (!hasPlayer && __entState.playerByteAddr > 0) {
                  const pIdx = __entState.playerByteAddr >>> 2;
                  if (pIdx + 2 < lenF32 && Number.isFinite(hf32[pIdx])) {
                    px = hf32[pIdx];
                    py = hf32[pIdx + 1];
                    pz = hf32[pIdx + 2];
                    hasPlayer = true;
                  }
                }

                __entState.tick++;
                const out = [];
                for (let i = 0; i < __entState.tracked.length; i++) {
                  const t = __entState.tracked[i];
                  if (t.dropped) continue;
                  const idx = t.heapIndex | 0;
                  if (idx < 0 || idx + 2 >= lenF32) continue;
                  const x = hf32[idx];
                  const y = hf32[idx + 1];
                  const z = hf32[idx + 2];
                  if (!Number.isFinite(x) || !Number.isFinite(y) || !Number.isFinite(z)) continue;
                  if (Math.abs(x) > 500000 || Math.abs(y) > 500000 || Math.abs(z) > 500000) continue;

                  const hasPrev = (typeof t.prevX === 'number');
                  const vx = hasPrev ? (x - t.prevX) : 0;
                  const vy = hasPrev ? (y - t.prevY) : 0;
                  const vz = hasPrev ? (z - t.prevZ) : 0;
                  const selfMove = hasPrev ? Math.sqrt(vx * vx + vy * vy + vz * vz) : 0;

                  // Continuous Live Anti-Thrash Guard: prune slots that teleport or ping-pong wildly
                  if (hasPrev) {
                    if (selfMove > 18.0 || Math.abs(vy) > 10.0) {
                      t.erraticStrikes = (t.erraticStrikes || 0) + 2;
                    } else if (selfMove > 2.5 && (t.prevMove || 0) > 2.5) {
                      const dot = vx * (t.prevVx || 0) + vz * (t.prevVz || 0);
                      if (dot < -0.35 * selfMove * t.prevMove) {
                        t.erraticStrikes = (t.erraticStrikes || 0) + 1;
                      } else if (t.erraticStrikes > 0) {
                        t.erraticStrikes--;
                      }
                    } else if (t.erraticStrikes > 0) {
                      t.erraticStrikes--;
                    }
                    if (t.erraticStrikes >= 2) {
                      t.dropped = true;
                      continue;
                    }
                  }

                  const dx = hasPlayer ? (x - px) : 0;
                  const dy = hasPlayer ? (y - py) : 0;
                  const dz = hasPlayer ? (z - pz) : 0;
                  const dist = hasPlayer ? Math.sqrt(dx * dx + dy * dy + dz * dz) : 0;
                  const distDelta = (hasPlayer && typeof t.prevDist === 'number')
                    ? Number((dist - t.prevDist).toFixed(3))
                    : null;

                  if (selfMove >= 0.04 && selfMove <= 14.0) {
                    t.isSmoothWalking = true;
                  }
                  t.prevVx = vx;
                  t.prevVz = vz;
                  t.prevMove = selfMove;
                  t.prevX = x;
                  t.prevY = y;
                  t.prevZ = z;
                  if (hasPlayer) t.prevDist = dist;

                  const decoded = __peReadEntitySchema(t.byteAddr, heaps) || {};
                  const activeTargetCoord = (window.__wvTargetScan && window.__wvTargetScan.lastResolvedCoordAddr > 0)
                    ? window.__wvTargetScan.lastResolvedCoordAddr : 0;
                  const activeTargetStruct = (window.__wvTargetScan && window.__wvTargetScan.lastResolvedAddr > 0)
                    ? window.__wvTargetScan.lastResolvedAddr : 0;
                  const isActiveTgt = (activeTargetCoord > 0 && Math.abs(t.byteAddr - activeTargetCoord) <= 64) ||
                    (activeTargetStruct > 0 && (t.structBase === activeTargetStruct || Math.abs(t.byteAddr - activeTargetStruct) <= 1024));
                  const aggroRaw = (typeof decoded.aggro === 'number') ? decoded.aggro : null;
                  out.push({
                    name: t.name,
                    id: t.id,
                    path: t.path,
                    coordByteAddress: t.byteAddr,
                    heapIndex: t.heapIndex,
                    resolvedName: decoded.name || null,
                    hp: (typeof decoded.hp === 'number') ? decoded.hp : null,
                    maxHp: (typeof decoded.maxHp === 'number') ? decoded.maxHp : null,
                    mp: (typeof decoded.mp === 'number') ? decoded.mp : null,
                    maxMp: (typeof decoded.maxMp === 'number') ? decoded.maxMp : null,
                    fp: (typeof decoded.fp === 'number') ? decoded.fp : null,
                    maxFp: (typeof decoded.maxFp === 'number') ? decoded.maxFp : null,
                    aggroRawValue: aggroRaw,
                    isAggro: (aggroRaw !== null) ? (aggroRaw !== 0) : null,
                    isActiveGameTarget: isActiveTgt,
                    worldX: Number(x.toFixed(3)),
                    worldY: Number(y.toFixed(3)),
                    worldZ: Number(z.toFixed(3)),
                    deltaX: hasPlayer ? Number(dx.toFixed(3)) : null,
                    deltaZ: hasPlayer ? Number(dz.toFixed(3)) : null,
                    distanceToPlayer: Number(dist.toFixed(2)),
                    distanceDelta: distDelta,
                    selfMoveDelta: Number(selfMove.toFixed(3)),
                    isConfirmedMoving: !!t.isSmoothWalking,
                    screenX: 0,
                    screenY: 0,
                    inViewport: dist <= 65.0,
                    sampleKeys: [
                      'WASM_STRUCT',
                      t.isSmoothWalking ? 'WALKING' : 'STABLE',
                      'struct=' + __peFormatHex(t.structBase),
                      'Δ(' + dx.toFixed(1) + ', ' + dy.toFixed(1) + ', ' + dz.toFixed(1) + ')'
                    ]
                  });
                }

                out.sort(function(a, b) { return a.distanceToPlayer - b.distanceToPlayer; });

                if (__peBridge && typeof __peBridge.updateWasmEntities === 'function') {
                  __peBridge.updateWasmEntities(JSON.stringify({
                    stage: 'WASM_ENTITIES_TICK',
                    tick: __entState.tick,
                    entities: out
                  }));
                }
              } catch (_) {}
            };

            pushEntityTick();
            __entState.pollId = setInterval(pushEntityTick, 500);
          } catch (_) {}
        })();
    """.trimIndent()

    /**
     * Cleanly stops the 500ms live WASM entity position refresh loop.
     */
    fun buildStopWasmEntitiesLiveRefreshScript(): String = """
        (function() {
          try {
            if (window.__wvWasmEntities && window.__wvWasmEntities.pollId !== null) {
              clearInterval(window.__wvWasmEntities.pollId);
              window.__wvWasmEntities.pollId = null;
            }
          } catch (_) {}
        })();
    """.trimIndent()

    private fun quoteJsString(raw: String): String {
        val escaped = raw
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
        return "\"$escaped\""
    }
}
