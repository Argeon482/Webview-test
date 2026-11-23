package com.example

/**
 * Strictly observational, read-only JavaScript generators for WebAssembly heap inspection
 * inside the embedded diagnostic WebView.
 *
 * Design guarantees:
 * - Zero heap mutation: never writes to `window.HEAPF32`, `window.HEAPU32`, or game objects.
 * - Zero input synthesis or automated navigation: strictly gathers telemetry for manual audit.
 * - True isolated baseline cloning: Step 1 uses `new Float32Array(hf32.buffer.slice(0))` with an
 *   indexed copy fallback so baseline values never alias the live WebAssembly heap buffer.
 * - Non-blocking execution: large scans yield between chunks via `setTimeout(..., 0)` so the
 *   browser render thread and network loop stay responsive.
 * - Pure Kotlin (no `org.json` dependency) so JVM unit tests verify script contracts directly.
 */
object WasmHeapScripts {

    const val DEFAULT_MIN_MOVEMENT_DELTA = 0.02
    const val DEFAULT_MAX_MOVEMENT_DELTA = 500.0
    const val DEFAULT_ENTITY_SCAN_RADIUS = 50_000

    /**
     * Shared read-only runtime installed lazily on `window.__wvWasmDiag`.
     * Resolves active WASM Float32 / Uint32 heap views across Emscripten, Unity WebGL, and
     * custom game engine globals without modifying any heap memory.
     */
    private const val RUNTIME_BOOTSTRAP_JS = """
        const root = window.__wvWasmDiag = window.__wvWasmDiag || {
            feedTimerId: null,
            previewTimerId: null,
            activeBaseIndex: 581246,
            baselineClone: null,
            baselineLen: 0,
            baselineSource: '',
            candidateIndices: [],
            scanRound: 0,
            scanGeneration: 0,
            previewCandidates: []
        };

        function resolveWasmHeaps() {
            const directF32 = window.HEAPF32;
            const directU32 = window.HEAPU32;
            if (directF32 instanceof Float32Array && directF32.length > 0 && directF32.buffer && directF32.buffer.byteLength > 0) {
                const u32 = (directU32 instanceof Uint32Array && directU32.buffer === directF32.buffer)
                    ? directU32
                    : new Uint32Array(directF32.buffer);
                return { hf32: directF32, hu32: u32, source: 'window.HEAPF32' };
            }
            const containers = [
                ['window.Module', window.Module],
                ['window.unityInstance.Module', window.unityInstance && window.unityInstance.Module],
                ['window.gameInstance.Module', window.gameInstance && window.gameInstance.Module],
                ['window.game', window.game],
                ['window.app', window.app],
                ['window.engine', window.engine]
            ];
            for (let i = 0; i < containers.length; i++) {
                const label = containers[i][0];
                const obj = containers[i][1];
                if (!obj || typeof obj !== 'object') continue;
                try {
                    if (obj.HEAPF32 instanceof Float32Array && obj.HEAPF32.length > 0 && obj.HEAPF32.buffer.byteLength > 0) {
                        const f32 = obj.HEAPF32;
                        const u32 = (obj.HEAPU32 instanceof Uint32Array && obj.HEAPU32.buffer === f32.buffer)
                            ? obj.HEAPU32
                            : new Uint32Array(f32.buffer);
                        return { hf32: f32, hu32: u32, source: label + '.HEAPF32' };
                    }
                    const memBuf = (obj.wasmMemory && obj.wasmMemory.buffer)
                        || (obj.asm && obj.asm.memory && obj.asm.memory.buffer)
                        || (obj.memory && obj.memory.buffer);
                    if (memBuf instanceof ArrayBuffer && memBuf.byteLength >= 4096) {
                        return {
                            hf32: new Float32Array(memBuf),
                            hu32: new Uint32Array(memBuf),
                            source: label + '.wasmMemory'
                        };
                    }
                } catch (_e) {}
            }
            if (window.wasmMemory && window.wasmMemory.buffer instanceof ArrayBuffer && window.wasmMemory.buffer.byteLength >= 4096) {
                const buf = window.wasmMemory.buffer;
                return { hf32: new Float32Array(buf), hu32: new Uint32Array(buf), source: 'window.wasmMemory' };
            }
            return null;
        }

        function emitLog(category, payloadObj) {
            try {
                if (window.TelemetryBridge && typeof window.TelemetryBridge.logTelemetry === 'function') {
                    window.TelemetryBridge.logTelemetry(category, JSON.stringify(payloadObj));
                }
            } catch (_e) {}
        }

        function emitStatus(status, detail) {
            try {
                if (window.TelemetryBridge && typeof window.TelemetryBridge.updateScanStatus === 'function') {
                    window.TelemetryBridge.updateScanStatus(status, String(detail || ''));
                }
            } catch (_e) {}
        }

        function yieldFrame() {
            return new Promise(function(resolve) { setTimeout(resolve, 0); });
        }

        function isPlausibleWorldFloat(v) {
            return Number.isFinite(v) && Math.abs(v) <= 500000.0;
        }

        function hexU32(val) {
            return '0x' + ((val >>> 0).toString(16).toUpperCase().padStart(8, '0'));
        }

        function startDirectFeedInternal(targetBaseIndex) {
            if (root.feedTimerId !== null) {
                clearInterval(root.feedTimerId);
                root.feedTimerId = null;
            }
            root.activeBaseIndex = Math.max(0, targetBaseIndex | 0);
            function pushSample() {
                const heaps = resolveWasmHeaps();
                if (!heaps) return false;
                const hf32 = heaps.hf32;
                const idx = root.activeBaseIndex;
                if (idx < 0 || idx + 2 >= hf32.length) return false;
                const x = Number(hf32[idx]);
                const y = Number(hf32[idx + 1]);
                const z = Number(hf32[idx + 2]);
                if (!Number.isFinite(x) || !Number.isFinite(y) || !Number.isFinite(z)) return false;
                if (window.TelemetryBridge && typeof window.TelemetryBridge.updateDirectHeapCoords === 'function') {
                    window.TelemetryBridge.updateDirectHeapCoords(idx, x, y, z, hf32.byteLength, heaps.source);
                }
                return true;
            }
            const ok = pushSample();
            root.feedTimerId = setInterval(pushSample, 200);
            return ok;
        }
    """

    /**
     * Starts a read-only 200ms direct coordinate feed reading `HEAPF32[baseIndex..baseIndex+2]`.
     */
    fun buildStartDirectHeapFeedScript(baseIndex: Int): String {
        val safeBase = baseIndex.coerceAtLeast(0)
        return """
            (function() {
                try {
                    $RUNTIME_BOOTSTRAP_JS
                    const heaps = resolveWasmHeaps();
                    if (!heaps) {
                        emitStatus('IDLE', 'WASM HEAPF32 not ready yet');
                        emitLog('FEED', {
                            type: 'FEED_STATUS',
                            active: false,
                            heapReady: false,
                            baseIndex: $safeBase,
                            message: 'window.HEAPF32 not detected yet. Wait for the WASM engine to finish loading.'
                        });
                        return;
                    }
                    const clamped = Math.min($safeBase, Math.max(0, heaps.hf32.length - 3));
                    const sampled = startDirectFeedInternal(clamped);
                    emitStatus('COMPLETED', '200ms feed active @ [' + clamped + ']');
                    emitLog('FEED', {
                        type: 'FEED_STATUS',
                        active: true,
                        heapReady: true,
                        sampled: sampled,
                        baseIndex: clamped,
                        heapSource: heaps.source,
                        heapByteLength: heaps.hf32.byteLength,
                        message: 'Direct 200ms feed active at ' + heaps.source + '[' + clamped + '..' + (clamped + 2) + ']'
                    });
                } catch (err) {
                    emitStatus('ERROR', 'Feed error: ' + String(err));
                    emitLog('ERR', { type: 'FEED_ERROR', error: String(err) });
                }
            })();
        """.trimIndent()
    }

    /**
     * Pauses the active 200ms direct coordinate feed timer without altering game memory.
     */
    fun buildPauseDirectHeapFeedScript(): String {
        return """
            (function() {
                try {
                    $RUNTIME_BOOTSTRAP_JS
                    if (root.feedTimerId !== null) {
                        clearInterval(root.feedTimerId);
                        root.feedTimerId = null;
                    }
                    emitStatus('IDLE', '200ms feed paused');
                    emitLog('FEED', {
                        type: 'FEED_STATUS',
                        active: false,
                        heapReady: !!resolveWasmHeaps(),
                        baseIndex: root.activeBaseIndex,
                        message: 'Paused 200ms direct WASM heap feed.'
                    });
                } catch (err) {
                    emitLog('ERR', { type: 'FEED_PAUSE_ERROR', error: String(err) });
                }
            })();
        """.trimIndent()
    }

    /**
     * Step 1A: Captures a true, isolated `Float32Array` clone of the active WebAssembly heap
     * (`new Float32Array(hf32.buffer.slice(0))` with an indexed fallback) while the player stands
     * still, verifies `clone.buffer !== hf32.buffer`, and counts non-zero plausible floats.
     */
    fun buildCaptureFloatBaselineScript(): String {
        return """
            (async function() {
                try {
                    $RUNTIME_BOOTSTRAP_JS
                    const heaps = resolveWasmHeaps();
                    if (!heaps) {
                        emitStatus('ERROR', 'window.HEAPF32 unavailable');
                        emitLog('SCAN', {
                            type: 'FLOAT_BASELINE_CAPTURED',
                            success: false,
                            error: 'window.HEAPF32 not found. Load the game world before capturing baseline.'
                        });
                        return;
                    }

                    const myGen = ++root.scanGeneration;
                    if (root.previewTimerId !== null) {
                        clearInterval(root.previewTimerId);
                        root.previewTimerId = null;
                    }
                    root.previewCandidates = [];
                    root.candidateIndices = [];
                    root.scanRound = 0;

                    emitStatus('RUNNING', 'Cloning isolated Float32Array baseline…');
                    const t0 = performance.now();
                    const hf32 = heaps.hf32;
                    const len = hf32.length;

                    let isolatedClone = null;
                    let cloneMethod = 'buffer.slice';
                    try {
                        const clonedBuffer = hf32.buffer.slice(0);
                        isolatedClone = new Float32Array(clonedBuffer, hf32.byteOffset, len);
                    } catch (_sliceErr) {
                        cloneMethod = 'indexed_copy';
                        isolatedClone = new Float32Array(len);
                        const CHUNK = 524288;
                        for (let offset = 0; offset < len; offset += CHUNK) {
                            if (myGen !== root.scanGeneration) return;
                            const end = Math.min(offset + CHUNK, len);
                            for (let i = offset; i < end; i++) {
                                isolatedClone[i] = hf32[i];
                            }
                            if (end < len) await yieldFrame();
                        }
                    }

                    const isIsolatedClone = (isolatedClone.buffer !== hf32.buffer);
                    if (!isIsolatedClone) {
                        cloneMethod = 'forced_indexed_copy';
                        const forced = new Float32Array(len);
                        for (let i = 0; i < len; i++) {
                            forced[i] = hf32[i];
                        }
                        isolatedClone = forced;
                    }

                    // Brief 120ms stillness check to count naturally jittering floats (timers/clocks)
                    await new Promise(function(resolve) { setTimeout(resolve, 120); });
                    if (myGen !== root.scanGeneration) return;

                    let plausibleCount = 0;
                    let jitterCount = 0;
                    const currentHeaps = resolveWasmHeaps();
                    const liveF32 = currentHeaps ? currentHeaps.hf32 : hf32;
                    const checkLen = Math.min(len, liveF32.length);
                    const SAMPLE_CHUNK = 524288;
                    for (let start = 0; start < checkLen; start += SAMPLE_CHUNK) {
                        if (myGen !== root.scanGeneration) return;
                        const end = Math.min(start + SAMPLE_CHUNK, checkLen);
                        for (let i = start; i < end; i++) {
                            const bVal = isolatedClone[i];
                            if (bVal !== 0 && isPlausibleWorldFloat(bVal)) {
                                plausibleCount++;
                                const nVal = liveF32[i];
                                if (Math.abs(nVal - bVal) > 0.0005) {
                                    jitterCount++;
                                }
                            }
                        }
                        if (end < checkLen) await yieldFrame();
                    }

                    root.baselineClone = isolatedClone;
                    root.baselineLen = len;
                    root.baselineSource = heaps.source;
                    const elapsedMs = Number((performance.now() - t0).toFixed(1));

                    emitStatus('COMPLETED', 'Isolated baseline ready (' + plausibleCount + ' floats)');
                    emitLog('SCAN', {
                        type: 'FLOAT_BASELINE_CAPTURED',
                        success: true,
                        heapSource: heaps.source,
                        heapLength: len,
                        heapByteLength: hf32.byteLength,
                        sampledCount: plausibleCount,
                        prunedJitterCount: jitterCount,
                        cloneMethod: cloneMethod,
                        isIsolatedClone: isolatedClone.buffer !== liveF32.buffer,
                        durationMs: elapsedMs
                    });
                } catch (err) {
                    emitStatus('ERROR', 'Baseline failed: ' + String(err));
                    emitLog('SCAN', {
                        type: 'FLOAT_BASELINE_CAPTURED',
                        success: false,
                        error: String(err)
                    });
                }
            })();
        """.trimIndent()
    }

    /**
     * Step 1B: Compares the current live `window.HEAPF32` against the isolated `baselineClone`
     * using permissive movement delta thresholds (`0.02..500.0`), detects contiguous `X/Y/Z`
     * coordinate clusters, ranks top candidates, and starts a 200ms live preview timer.
     */
    fun buildScanMovementDeltasScript(
        minDelta: Double = DEFAULT_MIN_MOVEMENT_DELTA,
        maxDelta: Double = DEFAULT_MAX_MOVEMENT_DELTA
    ): String {
        val safeMin = minDelta.coerceIn(0.001, 50.0)
        val safeMax = maxDelta.coerceIn(safeMin + 0.1, 100_000.0)
        return """
            (async function() {
                try {
                    $RUNTIME_BOOTSTRAP_JS
                    const heaps = resolveWasmHeaps();
                    if (!heaps) {
                        emitStatus('ERROR', 'window.HEAPF32 unavailable');
                        emitLog('SCAN', {
                            type: 'FLOAT_DELTA_SCANNED',
                            success: false,
                            error: 'window.HEAPF32 not found.'
                        });
                        return;
                    }
                    if (!(root.baselineClone instanceof Float32Array) || root.baselineClone.length === 0) {
                        emitStatus('ERROR', 'Capture Standing Baseline first');
                        emitLog('SCAN', {
                            type: 'FLOAT_DELTA_SCANNED',
                            success: false,
                            error: 'No isolated baseline captured yet. Stand still and tap Capture Standing Baseline first.'
                        });
                        return;
                    }

                    const myGen = ++root.scanGeneration;
                    const t0 = performance.now();
                    const hf32 = heaps.hf32;
                    const base = root.baselineClone;
                    const len = Math.min(hf32.length, base.length);
                    const MIN_DELTA = $safeMin;
                    const MAX_DELTA = $safeMax;
                    const CHUNK = 262144;

                    emitStatus('RUNNING', 'Scanning movement deltas (' + MIN_DELTA + '..' + MAX_DELTA + ')…');

                    async function scanIndexList(indices) {
                        const matched = [];
                        for (let p = 0; p < indices.length; p++) {
                            if (p > 0 && (p % CHUNK) === 0) {
                                if (myGen !== root.scanGeneration) return null;
                                await yieldFrame();
                            }
                            const idx = indices[p];
                            if (idx < 0 || idx + 2 >= len) continue;
                            const bv = base[idx];
                            const cv = hf32[idx];
                            if (!isPlausibleWorldFloat(bv) || !isPlausibleWorldFloat(cv)) continue;
                            const diff = cv - bv;
                            const absDiff = Math.abs(diff);
                            if (absDiff >= MIN_DELTA && absDiff <= MAX_DELTA) {
                                matched.push(idx);
                            }
                        }
                        return matched;
                    }

                    async function scanFullHeap() {
                        const matched = [];
                        for (let start = 0; start < len - 2; start += CHUNK) {
                            if (myGen !== root.scanGeneration) return null;
                            const end = Math.min(start + CHUNK, len - 2);
                            for (let i = start; i < end; i++) {
                                const bv = base[i];
                                const cv = hf32[i];
                                if (!isPlausibleWorldFloat(bv) || !isPlausibleWorldFloat(cv)) continue;
                                const absDiff = Math.abs(cv - bv);
                                if (absDiff >= MIN_DELTA && absDiff <= MAX_DELTA) {
                                    matched.push(i);
                                }
                            }
                            if (end < len - 2) await yieldFrame();
                        }
                        return matched;
                    }

                    let usedFallbackFullScan = false;
                    let rawMatches = null;
                    if (root.candidateIndices.length > 0 && root.scanRound > 0) {
                        rawMatches = await scanIndexList(root.candidateIndices);
                        if (rawMatches === null) return;
                        if (rawMatches.length === 0) {
                            usedFallbackFullScan = true;
                            rawMatches = await scanFullHeap();
                            if (rawMatches === null) return;
                        }
                    } else {
                        rawMatches = await scanFullHeap();
                        if (rawMatches === null) return;
                    }

                    root.candidateIndices = rawMatches;
                    root.scanRound = (root.scanRound || 0) + 1;

                    // Index lookup set for contiguous X/Y/Z cluster detection
                    const matchSet = new Set();
                    const maxSetItems = Math.min(rawMatches.length, 60000);
                    for (let i = 0; i < maxSetItems; i++) {
                        matchSet.add(rawMatches[i]);
                    }

                    // Brief 90ms stability verification so stationary coords rank above ticking timers
                    const firstSnap = new Map();
                    const maxScored = Math.min(rawMatches.length, 2000);
                    for (let i = 0; i < maxScored; i++) {
                        const idx = rawMatches[i];
                        firstSnap.set(idx, hf32[idx]);
                    }
                    await new Promise(function(resolve) { setTimeout(resolve, 90); });
                    if (myGen !== root.scanGeneration) return;

                    const liveHeaps2 = resolveWasmHeaps();
                    const liveF32 = liveHeaps2 ? liveHeaps2.hf32 : hf32;
                    const scored = [];
                    const emittedClusterBases = new Set();

                    for (let i = 0; i < maxScored; i++) {
                        const idx = rawMatches[i];
                        if (idx + 2 >= liveF32.length) continue;

                        // Normalize contiguous changed triplet/pair to its leading X base index
                        const hasPrev = matchSet.has(idx - 1);
                        const hasPrev2 = matchSet.has(idx - 2);
                        const hasNext = matchSet.has(idx + 1);
                        const hasNext2 = matchSet.has(idx + 2);

                        let baseIdx = idx;
                        let clusterSize = 1;
                        let axisHint = 'X';
                        if (hasNext && hasNext2) {
                            clusterSize = 3;
                            axisHint = 'X/Y/Z';
                        } else if (hasNext) {
                            clusterSize = 2;
                            axisHint = 'X/Y';
                        } else if (hasPrev && !hasPrev2) {
                            baseIdx = idx - 1;
                            clusterSize = 2;
                            axisHint = 'X/Y';
                        } else if (hasPrev && hasPrev2) {
                            baseIdx = idx - 2;
                            clusterSize = 3;
                            axisHint = 'X/Y/Z';
                        }

                        if (emittedClusterBases.has(baseIdx)) continue;
                        emittedClusterBases.add(baseIdx);

                        const bv = Number(base[baseIdx]);
                        const cv = Number(liveF32[baseIdx]);
                        const prevV = firstSnap.has(baseIdx) ? firstSnap.get(baseIdx) : cv;
                        const isStillNow = Math.abs(cv - prevV) < 0.005;
                        const yVal = Number(liveF32[baseIdx + 1]);
                        const zVal = Number(liveF32[baseIdx + 2]);
                        const neighborPlausible = isPlausibleWorldFloat(yVal) && isPlausibleWorldFloat(zVal);
                        const delta = cv - bv;

                        // Priority score: contiguous Vec3/Vec2 clusters + stationary now + plausible Y/Z
                        let score = 0;
                        if (clusterSize >= 3) score += 100;
                        else if (clusterSize === 2) score += 60;
                        if (isStillNow) score += 35;
                        if (neighborPlausible) score += 20;
                        if (Math.abs(delta) >= 0.25 && Math.abs(delta) <= 150.0) score += 15;

                        scored.push({
                            heapIndex: baseIdx,
                            byteAddress: baseIdx * 4,
                            baselineValue: Number(bv.toFixed(4)),
                            currentValue: Number(cv.toFixed(4)),
                            delta: Number(delta.toFixed(4)),
                            previewY: neighborPlausible ? Number(yVal.toFixed(4)) : 0,
                            previewZ: neighborPlausible ? Number(zVal.toFixed(4)) : 0,
                            isStillNow: isStillNow,
                            clusterSize: clusterSize,
                            axisHint: axisHint,
                            liveChangeCount: 0,
                            _score: score
                        });
                    }

                    scored.sort(function(a, b) {
                        if (b._score !== a._score) return b._score - a._score;
                        return a.heapIndex - b.heapIndex;
                    });

                    const topCandidates = scored.slice(0, 60).map(function(c) {
                        return {
                            heapIndex: c.heapIndex,
                            byteAddress: c.byteAddress,
                            baselineValue: c.baselineValue,
                            currentValue: c.currentValue,
                            delta: c.delta,
                            previewY: c.previewY,
                            previewZ: c.previewZ,
                            isStillNow: c.isStillNow,
                            clusterSize: c.clusterSize,
                            axisHint: c.axisHint,
                            liveChangeCount: 0
                        };
                    });

                    // Start 200ms live preview stream for top 25 candidates
                    if (root.previewTimerId !== null) {
                        clearInterval(root.previewTimerId);
                        root.previewTimerId = null;
                    }
                    root.previewCandidates = topCandidates.slice(0, 25).map(function(c) {
                        return {
                            heapIndex: c.heapIndex,
                            baselineValue: c.baselineValue,
                            lastValue: c.currentValue,
                            liveChangeCount: 0
                        };
                    });

                    if (root.previewCandidates.length > 0) {
                        root.previewTimerId = setInterval(function() {
                            const h = resolveWasmHeaps();
                            if (!h) return;
                            const f = h.hf32;
                            const updates = [];
                            for (let k = 0; k < root.previewCandidates.length; k++) {
                                const item = root.previewCandidates[k];
                                const i = item.heapIndex;
                                if (i + 2 >= f.length) continue;
                                const x = Number(f[i]);
                                const y = Number(f[i + 1]);
                                const z = Number(f[i + 2]);
                                if (!Number.isFinite(x)) continue;
                                const movedNow = Math.abs(x - item.lastValue) > 0.002;
                                if (movedNow) item.liveChangeCount++;
                                item.lastValue = x;
                                updates.push({
                                    heapIndex: i,
                                    currentValue: Number(x.toFixed(4)),
                                    delta: Number((x - item.baselineValue).toFixed(4)),
                                    previewY: Number.isFinite(y) ? Number(y.toFixed(4)) : 0,
                                    previewZ: Number.isFinite(z) ? Number(z.toFixed(4)) : 0,
                                    isStillNow: !movedNow,
                                    liveChangeCount: item.liveChangeCount
                                });
                            }
                            if (updates.length > 0 && window.TelemetryBridge && typeof window.TelemetryBridge.updateFloatDeltaPreview === 'function') {
                                window.TelemetryBridge.updateFloatDeltaPreview(JSON.stringify({ updates: updates }));
                            }
                        }, 200);
                    }

                    const durationMs = Number((performance.now() - t0).toFixed(1));
                    emitStatus('COMPLETED', 'Found ' + rawMatches.length + ' movement deltas');
                    emitLog('SCAN', {
                        type: 'FLOAT_DELTA_SCANNED',
                        success: true,
                        scanRound: root.scanRound,
                        totalMatchesFound: rawMatches.length,
                        durationMs: durationMs,
                        usedFallbackFullScan: usedFallbackFullScan,
                        isLivePreviewing: root.previewCandidates.length > 0,
                        minDelta: MIN_DELTA,
                        maxDelta: MAX_DELTA,
                        candidates: topCandidates
                    });
                } catch (err) {
                    emitStatus('ERROR', 'Delta scan error: ' + String(err));
                    emitLog('SCAN', {
                        type: 'FLOAT_DELTA_SCANNED',
                        success: false,
                        error: String(err)
                    });
                }
            })();
        """.trimIndent()
    }

    /**
     * Releases the isolated Float32Array baseline clone, clears candidates, and stops preview ticks.
     */
    fun buildResetFloatScanScript(): String {
        return """
            (function() {
                try {
                    $RUNTIME_BOOTSTRAP_JS
                    root.scanGeneration++;
                    if (root.previewTimerId !== null) {
                        clearInterval(root.previewTimerId);
                        root.previewTimerId = null;
                    }
                    root.baselineClone = null;
                    root.baselineLen = 0;
                    root.candidateIndices = [];
                    root.previewCandidates = [];
                    root.scanRound = 0;
                    emitStatus('IDLE', 'Float delta scanner reset');
                } catch (_e) {}
            })();
        """.trimIndent()
    }

    /**
     * Step 2: Inspects `±16` neighboring `HEAPF32` / `HEAPU32` slots around [baseIndex], extracts
     * static AOB/Float struct anchors (excluding live coordinate offsets `0, 1, 2`), and performs
     * a non-blocking reverse scan across `HEAPU32` for parent pointers referencing the struct.
     */
    fun buildAnalyzePointerSignatureScript(baseIndex: Int): String {
        val safeBase = baseIndex.coerceAtLeast(0)
        return """
            (async function() {
                try {
                    $RUNTIME_BOOTSTRAP_JS
                    const heaps = resolveWasmHeaps();
                    if (!heaps) {
                        emitStatus('ERROR', 'window.HEAPF32 unavailable');
                        emitLog('PTR', {
                            type: 'POINTER_SIGNATURE_ANALYZED',
                            success: false,
                            baseIndex: $safeBase,
                            error: 'window.HEAPF32 / window.HEAPU32 not found.'
                        });
                        return;
                    }

                    const t0 = performance.now();
                    const hf32 = heaps.hf32;
                    const hu32 = heaps.hu32;
                    const baseIdx = Math.min($safeBase, Math.max(0, hf32.length - 3));
                    const targetByteAddr = baseIdx * 4;

                    emitStatus('RUNNING', 'Analyzing ±16 block & reverse HEAPU32 pointers…');

                    // Also switch live 200ms feed immediately onto the inspected candidate
                    startDirectFeedInternal(baseIdx);

                    const surroundingSlots = [];
                    const anchors = [];
                    const aobTokens = [];
                    const floatTokens = [];

                    for (let off = -16; off <= 16; off++) {
                        const idx = baseIdx + off;
                        if (idx < 0 || idx >= hf32.length) continue;
                        const u32 = hu32[idx] >>> 0;
                        const f32 = Number(hf32[idx]);
                        const isCoordAxis = (off === 0 || off === 1 || off === 2);

                        // Select strong non-zero static structural anchors outside X/Y/Z
                        const isUsefulFloat = Number.isFinite(f32) && Math.abs(f32) <= 100000 && Math.abs(f32) >= 0.0001;
                        const isAnchor = !isCoordAxis && u32 !== 0 && u32 !== 0xFFFFFFFF && (
                            (Math.abs(off) <= 8) || isUsefulFloat
                        );

                        if (isAnchor && anchors.length < 12) {
                            anchors.push({
                                offset: off,
                                u32: u32,
                                f32: Number.isFinite(f32) ? Number(f32.toFixed(4)) : null
                            });
                            if (isUsefulFloat && floatTokens.length < 6) {
                                floatTokens.push((off >= 0 ? '+' : '') + off + ':' + f32.toFixed(2));
                            }
                        }

                        if (Math.abs(off) <= 8) {
                            if (isCoordAxis) {
                                aobTokens.push('??');
                            } else {
                                aobTokens.push(u32.toString(16).toUpperCase().padStart(8, '0'));
                            }
                        }

                        surroundingSlots.push({
                            offset: off,
                            heapIndex: idx,
                            byteAddress: idx * 4,
                            floatValue: Number.isFinite(f32) ? Number(f32.toFixed(4)) : 0,
                            uint32Hex: hexU32(u32),
                            isCoordAxis: isCoordAxis,
                            isAnchor: isAnchor
                        });
                    }

                    // Reverse-scan HEAPU32 in non-blocking chunks for exact or struct-base parent pointers
                    const exactPointers = [];
                    const structBasePointers = [];
                    const minStructAddr = Math.max(0, targetByteAddr - 0x200);
                    const totalU32 = hu32.length;
                    const CHUNK = 393216;

                    for (let start = 0; start < totalU32; start += CHUNK) {
                        const end = Math.min(start + CHUNK, totalU32);
                        for (let i = start; i < end; i++) {
                            const val = hu32[i] >>> 0;
                            if (val === targetByteAddr) {
                                if (exactPointers.length < 16) {
                                    exactPointers.push({
                                        pointerHeapIndex: i,
                                        pointerByteAddress: i * 4,
                                        targetByteAddress: val,
                                        fieldOffsetBytes: 0,
                                        isExactMatch: true
                                    });
                                }
                            } else if (val >= minStructAddr && val < targetByteAddr && ((val & 3) === 0)) {
                                if (structBasePointers.length < 16) {
                                    structBasePointers.push({
                                        pointerHeapIndex: i,
                                        pointerByteAddress: i * 4,
                                        targetByteAddress: val,
                                        fieldOffsetBytes: targetByteAddr - val,
                                        isExactMatch: false
                                    });
                                }
                            }
                        }
                        if (end < totalU32) await yieldFrame();
                    }

                    const parentPointers = exactPointers.concat(structBasePointers).slice(0, 16);
                    const signatureObj = {
                        version: 1,
                        capturedBaseIndex: baseIdx,
                        capturedByteAddress: targetByteAddr,
                        anchors: anchors
                    };
                    const signatureJson = JSON.stringify(signatureObj);
                    const durationMs = Number((performance.now() - t0).toFixed(1));

                    emitStatus('COMPLETED', 'Signature extracted (' + anchors.length + ' anchors, ' + parentPointers.length + ' ptrs)');
                    emitLog('PTR', {
                        type: 'POINTER_SIGNATURE_ANALYZED',
                        success: true,
                        baseIndex: baseIdx,
                        byteAddress: targetByteAddr,
                        aobPatternHex: aobTokens.join(' '),
                        floatPatternSummary: floatTokens.join('  '),
                        anchorCount: anchors.length,
                        signatureJson: signatureJson,
                        parentPointers: parentPointers,
                        surroundingSlots: surroundingSlots,
                        analysisDurationMs: durationMs
                    });
                } catch (err) {
                    emitStatus('ERROR', 'Signature error: ' + String(err));
                    emitLog('PTR', {
                        type: 'POINTER_SIGNATURE_ANALYZED',
                        success: false,
                        baseIndex: $safeBase,
                        error: String(err)
                    });
                }
            })();
        """.trimIndent()
    }

    /**
     * Step 3: Relocks onto the player's `(X, Y, Z)` base index after a page reload using:
     * 1) Direct verification at [preferredBaseIndex],
     * 2) Saved `HEAPU32` parent pointer dereferencing, and
     * 3) Non-blocking chunked AOB/Struct signature scan across `HEAPU32`.
     */
    fun buildAutoRelockScript(
        signatureJson: String,
        preferredBaseIndex: Int,
        parentPointersJson: String
    ): String {
        val safeBase = preferredBaseIndex.coerceAtLeast(0)
        val escapedSig = escapeForJsSingleQuotedString(signatureJson)
        val escapedPtrs = escapeForJsSingleQuotedString(parentPointersJson)
        return """
            (async function() {
                try {
                    $RUNTIME_BOOTSTRAP_JS
                    const t0 = performance.now();
                    emitStatus('RUNNING', 'Auto-relocking saved WASM signature…');

                    let heaps = resolveWasmHeaps();
                    for (let attempt = 0; attempt < 6 && !heaps; attempt++) {
                        await new Promise(function(resolve) { setTimeout(resolve, 350); });
                        heaps = resolveWasmHeaps();
                    }
                    if (!heaps) {
                        emitStatus('ERROR', 'WASM heap unavailable for relock');
                        emitLog('RELOCK', {
                            type: 'AUTO_RELOCK_RESULT',
                            success: false,
                            error: 'window.HEAPF32 not ready yet after waiting for page init.'
                        });
                        return;
                    }

                    const hf32 = heaps.hf32;
                    const hu32 = heaps.hu32;
                    const len = hu32.length;

                    let sig = null;
                    try { sig = JSON.parse('$escapedSig'); } catch (_e) {}
                    let savedPtrs = [];
                    try { savedPtrs = JSON.parse('$escapedPtrs'); } catch (_e) {}
                    const anchors = (sig && Array.isArray(sig.anchors)) ? sig.anchors : [];

                    function scoreCandidate(baseIdx) {
                        if (baseIdx < 16 || baseIdx + 16 >= len) return 0;
                        const x = hf32[baseIdx], y = hf32[baseIdx + 1], z = hf32[baseIdx + 2];
                        if (!isPlausibleWorldFloat(x) || !isPlausibleWorldFloat(y) || !isPlausibleWorldFloat(z)) return 0;
                        if (anchors.length === 0) return 50;
                        let hits = 0;
                        for (let a = 0; a < anchors.length; a++) {
                            const anc = anchors[a];
                            const slot = baseIdx + (anc.offset | 0);
                            if (slot < 0 || slot >= len) continue;
                            if ((hu32[slot] >>> 0) === (anc.u32 >>> 0)) {
                                hits++;
                            } else if (typeof anc.f32 === 'number' && Number.isFinite(hf32[slot]) && Math.abs(hf32[slot] - anc.f32) < 0.01) {
                                hits += 0.75;
                            }
                        }
                        return Math.round((hits / anchors.length) * 100);
                    }

                    let matchedIndex = -1;
                    let bestConfidence = 0;
                    let methodUsed = '';

                    // Pass 1: Direct verification at preferred base index
                    const directScore = scoreCandidate($safeBase);
                    if (directScore >= 75) {
                        matchedIndex = $safeBase;
                        bestConfidence = directScore;
                        methodUsed = 'Direct Index Verify';
                    }

                    // Pass 2: Saved HEAPU32 parent pointer dereferencing
                    if (matchedIndex < 0 && Array.isArray(savedPtrs)) {
                        for (let p = 0; p < savedPtrs.length; p++) {
                            const ptr = savedPtrs[p];
                            const ptrIdx = ptr.pointerHeapIndex | 0;
                            const fieldOff = ptr.fieldOffsetBytes | 0;
                            if (ptrIdx < 0 || ptrIdx >= len) continue;
                            const targetAddr = (hu32[ptrIdx] >>> 0) + fieldOff;
                            if ((targetAddr & 3) !== 0) continue;
                            const resolvedIdx = targetAddr >>> 2;
                            const s = scoreCandidate(resolvedIdx);
                            if (s > bestConfidence && s >= 65) {
                                bestConfidence = s;
                                matchedIndex = resolvedIdx;
                                methodUsed = 'HEAPU32 Parent Pointer';
                            }
                        }
                    }

                    // Pass 3: Non-blocking chunked signature scan across HEAPU32
                    if (matchedIndex < 0 && anchors.length > 0) {
                        const primary = anchors[0];
                        const pOff = primary.offset | 0;
                        const pU32 = primary.u32 >>> 0;
                        const CHUNK = 393216;
                        for (let start = 16; start < len - 16; start += CHUNK) {
                            const end = Math.min(start + CHUNK, len - 16);
                            for (let i = start; i < end; i++) {
                                if ((hu32[i] >>> 0) === pU32) {
                                    const candidateBase = i - pOff;
                                    const s = scoreCandidate(candidateBase);
                                    if (s > bestConfidence) {
                                        bestConfidence = s;
                                        matchedIndex = candidateBase;
                                        methodUsed = 'AOB Struct Signature Scan';
                                        if (bestConfidence >= 95) break;
                                    }
                                }
                            }
                            if (bestConfidence >= 95) break;
                            if (end < len - 16) await yieldFrame();
                        }
                    }

                    const elapsedMs = Number((performance.now() - t0).toFixed(1));
                    if (matchedIndex >= 0 && bestConfidence >= 65) {
                        startDirectFeedInternal(matchedIndex);
                        emitStatus('COMPLETED', 'Relocked [' + matchedIndex + '] (' + bestConfidence + '%)');
                        emitLog('RELOCK', {
                            type: 'AUTO_RELOCK_RESULT',
                            success: true,
                            matchedBaseIndex: matchedIndex,
                            byteAddress: matchedIndex * 4,
                            confidencePercent: bestConfidence,
                            methodUsed: methodUsed,
                            durationMs: elapsedMs,
                            x: Number(hf32[matchedIndex]),
                            y: Number(hf32[matchedIndex + 1]),
                            z: Number(hf32[matchedIndex + 2]),
                            heapByteLength: hf32.byteLength,
                            heapSource: heaps.source
                        });
                    } else {
                        emitStatus('IDLE', 'Relock signature not matched');
                        emitLog('RELOCK', {
                            type: 'AUTO_RELOCK_RESULT',
                            success: false,
                            confidencePercent: bestConfidence,
                            durationMs: elapsedMs,
                            error: 'No memory block matched saved signature (>= 65% confidence). Re-run Step 1 Scanner.'
                        });
                    }
                } catch (err) {
                    emitStatus('ERROR', 'Relock failed: ' + String(err));
                    emitLog('RELOCK', {
                        type: 'AUTO_RELOCK_RESULT',
                        success: false,
                        error: String(err)
                    });
                }
            })();
        """.trimIndent()
    }

    /**
     * Scans `±radius` (default `±50,000`) float indices around [playerBaseIndex] in `window.HEAPF32`
     * to discover neighboring `(X, Y, Z)` entity structs and estimate dominant struct stride.
     */
    fun buildScanNearbyEntitiesScript(
        playerBaseIndex: Int,
        radius: Int = DEFAULT_ENTITY_SCAN_RADIUS
    ): String {
        val safeBase = playerBaseIndex.coerceAtLeast(0)
        val safeRadius = radius.coerceIn(500, 250_000)
        return """
            (async function() {
                try {
                    $RUNTIME_BOOTSTRAP_JS
                    const heaps = resolveWasmHeaps();
                    if (!heaps) {
                        emitStatus('ERROR', 'window.HEAPF32 unavailable');
                        emitLog('ENTITY', {
                            type: 'NEARBY_ENTITIES_SCANNED',
                            success: false,
                            error: 'window.HEAPF32 not found.'
                        });
                        return;
                    }

                    const t0 = performance.now();
                    const hf32 = heaps.hf32;
                    const len = hf32.length;
                    const playerIdx = Math.min($safeBase, Math.max(0, len - 3));
                    const px = Number(hf32[playerIdx]);
                    const py = Number(hf32[playerIdx + 1]);
                    const pz = Number(hf32[playerIdx + 2]);

                    if (!isPlausibleWorldFloat(px) || !isPlausibleWorldFloat(py) || !isPlausibleWorldFloat(pz)) {
                        emitStatus('ERROR', 'Player coordinates non-finite');
                        emitLog('ENTITY', {
                            type: 'NEARBY_ENTITIES_SCANNED',
                            success: false,
                            error: 'Current base index does not hold finite player coordinates.'
                        });
                        return;
                    }

                    emitStatus('RUNNING', 'Scanning ±$safeRadius structs around [' + playerIdx + ']…');
                    const startIdx = Math.max(0, playerIdx - $safeRadius);
                    const endIdx = Math.min(len - 3, playerIdx + $safeRadius);
                    const CHUNK = 32768;
                    const rawEntities = [];

                    for (let chunkStart = startIdx; chunkStart < endIdx; chunkStart += CHUNK) {
                        const chunkEnd = Math.min(chunkStart + CHUNK, endIdx);
                        for (let i = chunkStart; i < chunkEnd; i++) {
                            if (Math.abs(i - playerIdx) < 4) continue;
                            const ex = Number(hf32[i]);
                            const ey = Number(hf32[i + 1]);
                            const ez = Number(hf32[i + 2]);
                            if (!isPlausibleWorldFloat(ex) || !isPlausibleWorldFloat(ey) || !isPlausibleWorldFloat(ez)) continue;
                            if (ex === 0 && ey === 0 && ez === 0) continue;

                            const dx = ex - px;
                            const dy = ey - py;
                            const dz = ez - pz;
                            if (Math.abs(dx) > 1200 || Math.abs(dy) > 300 || Math.abs(dz) > 1200) continue;
                            const dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
                            if (dist < 0.35 || dist > 1500.0) continue;

                            // Avoid overlapping sliding windows inside the same Vec3
                            if (rawEntities.length > 0 && (i - rawEntities[rawEntities.length - 1].baseIndex) < 4) {
                                continue;
                            }

                            rawEntities.push({
                                baseIndex: i,
                                byteAddress: i * 4,
                                offsetFromPlayer: i - playerIdx,
                                x: Number(ex.toFixed(3)),
                                y: Number(ey.toFixed(3)),
                                z: Number(ez.toFixed(3)),
                                distanceToPlayer: Number(dist.toFixed(2)),
                                strideGroup: 0,
                                confidenceScore: Math.abs(dy) <= 25 ? 88 : 70
                            });
                            if (rawEntities.length >= 240) break;
                        }
                        if (rawEntities.length >= 240) break;
                        if (chunkEnd < endIdx) await yieldFrame();
                    }

                    // Estimate dominant struct stride from index differences between nearby hits
                    const strideCounts = new Map();
                    for (let k = 1; k < rawEntities.length; k++) {
                        const step = rawEntities[k].baseIndex - rawEntities[k - 1].baseIndex;
                        if (step >= 8 && step <= 2048) {
                            strideCounts.set(step, (strideCounts.get(step) || 0) + 1);
                        }
                    }
                    let detectedStride = null;
                    let bestStrideFreq = 1;
                    strideCounts.forEach(function(freq, stride) {
                        if (freq > bestStrideFreq) {
                            bestStrideFreq = freq;
                            detectedStride = stride;
                        }
                    });

                    if (detectedStride !== null) {
                        for (let k = 0; k < rawEntities.length; k++) {
                            const rel = Math.abs(rawEntities[k].offsetFromPlayer);
                            if (rel % detectedStride === 0) {
                                rawEntities[k].strideGroup = detectedStride;
                                rawEntities[k].confidenceScore = Math.min(99, rawEntities[k].confidenceScore + 10);
                            }
                        }
                    }

                    rawEntities.sort(function(a, b) {
                        if (b.confidenceScore !== a.confidenceScore) return b.confidenceScore - a.confidenceScore;
                        return a.distanceToPlayer - b.distanceToPlayer;
                    });

                    const topEntities = rawEntities.slice(0, 80);
                    const durationMs = Number((performance.now() - t0).toFixed(1));

                    emitStatus('COMPLETED', 'Found ' + topEntities.length + ' nearby structs');
                    emitLog('ENTITY', {
                        type: 'NEARBY_ENTITIES_SCANNED',
                        success: true,
                        playerBaseIndex: playerIdx,
                        radius: $safeRadius,
                        detectedStride: detectedStride,
                        durationMs: durationMs,
                        entities: topEntities
                    });
                } catch (err) {
                    emitStatus('ERROR', 'Entity scan failed: ' + String(err));
                    emitLog('ENTITY', {
                        type: 'NEARBY_ENTITIES_SCANNED',
                        success: false,
                        error: String(err)
                    });
                }
            })();
        """.trimIndent()
    }

    /**
     * Collects read-only viewport, canvas, and WebAssembly heap diagnostics for JSON report export.
     */
    fun buildCollectViewportMetricsScript(): String {
        return """
            (function() {
                try {
                    $RUNTIME_BOOTSTRAP_JS
                    const heaps = resolveWasmHeaps();
                    const canvases = Array.prototype.slice.call(document.querySelectorAll('canvas')).slice(0, 8).map(function(c, i) {
                        const rect = c.getBoundingClientRect();
                        return {
                            index: i,
                            id: c.id || '',
                            width: c.width,
                            height: c.height,
                            cssWidth: Math.round(rect.width),
                            cssHeight: Math.round(rect.height)
                        };
                    });
                    const payload = {
                        capturedAtIso: new Date().toISOString(),
                        url: String(location.href || ''),
                        title: String(document.title || ''),
                        viewport: {
                            innerWidth: window.innerWidth,
                            innerHeight: window.innerHeight,
                            devicePixelRatio: window.devicePixelRatio || 1
                        },
                        wasmHeap: heaps ? {
                            source: heaps.source,
                            floatLength: heaps.hf32.length,
                            byteLength: heaps.hf32.byteLength,
                            activeBaseIndex: root.activeBaseIndex
                        } : null,
                        canvases: canvases
                    };
                    if (window.TelemetryBridge && typeof window.TelemetryBridge.exportReport === 'function') {
                        window.TelemetryBridge.exportReport(JSON.stringify(payload));
                    }
                } catch (err) {
                    if (window.TelemetryBridge && typeof window.TelemetryBridge.exportReport === 'function') {
                        window.TelemetryBridge.exportReport(JSON.stringify({ metricsError: String(err) }));
                    }
                }
            })();
        """.trimIndent()
    }

    /**
     * Evaluates a user-supplied read-only diagnostic JS snippet and returns a formatted preview.
     */
    fun buildManualJsEvalScript(userScript: String): String {
        val escaped = escapeForJsSingleQuotedString(userScript)
        return """
            (function() {
                try {
                    $RUNTIME_BOOTSTRAP_JS
                    const code = '$escaped';
                    const result = (0, eval)(code);
                    let formatted;
                    if (result === undefined) {
                        formatted = 'undefined';
                    } else if (result === null) {
                        formatted = 'null';
                    } else if (typeof result === 'object') {
                        try {
                            formatted = JSON.stringify(result, null, 2);
                            if (formatted && formatted.length > 4000) {
                                formatted = formatted.slice(0, 4000) + '… [truncated]';
                            }
                        } catch (_e) {
                            formatted = Object.prototype.toString.call(result);
                        }
                    } else {
                        formatted = String(result);
                    }
                    emitLog('JS', {
                        type: 'MANUAL_EVAL',
                        success: true,
                        result: formatted
                    });
                } catch (err) {
                    emitLog('ERR', {
                        type: 'MANUAL_EVAL',
                        success: false,
                        error: String(err)
                    });
                }
            })();
        """.trimIndent()
    }

    private fun escapeForJsSingleQuotedString(raw: String): String {
        val sb = StringBuilder(raw.length + 16)
        for (ch in raw) {
            when (ch) {
                '\\' -> sb.append("\\\\")
                '\'' -> sb.append("\\'")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\u2028' -> sb.append("\\u2028")
                '\u2029' -> sb.append("\\u2029")
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }
}
