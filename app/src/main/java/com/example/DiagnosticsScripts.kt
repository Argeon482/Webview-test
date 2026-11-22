package com.example

import org.json.JSONObject

/**
 * Non-blocking, asynchronous, shallow heuristic JavaScript diagnostic routines.
 *
 * Key performance & safety guarantees:
 * 1. Chunked Async Execution: Every scanning loop yields back to the browser render thread
 *    every ~4.5 ms via `requestAnimationFrame` + staggered `setTimeout`, preventing frame drops
 *    or game loop freezes.
 * 2. Shallow Heuristic Matching (Max Depth 1-2): Inspects candidate global objects on `window`
 *    and known engine namespaces using surface-level key heuristics (`player`, `actor`, `scene`,
 *    `game`, `pos`, `coord`, `hero`, `net`, etc.) and numeric property checks—never walking deep
 *    recursive object graphs.
 * 3. Safe JSON Serialization: Dedicated circular-safe serializer explicitly skips `window`,
 *    DOM nodes, functions, WebGL/Canvas contexts, and circular references before pushing payloads
 *    across `@JavascriptInterface`.
 */
object DiagnosticsScripts {

    private const val SHARED_RUNTIME = """
        const __bridge = window.TelemetryBridge;
        const __state = (window.__wvDiagState = window.__wvDiagState || {
          scanSeq: 0,
          candidates: [],
          shallowPaths: [],
          baseline: null,
          baselineTimeMs: 0,
          isolatedPaths: []
        });

        const __HEURISTIC_KEY_RE = /(player|actor|scene|game|pos|coord|hero|net|camera|cam|entity|entities|world|avatar|char|unit|state|transform|vec|loc|vel|rot)/i;
        const __ENGINE_NAMESPACES = [
          'game', 'Game', 'app', 'App', 'engine', 'Engine', 'scene', 'Scene',
          'world', 'World', 'player', 'Player', 'client', 'Client', 'net', 'Net',
          'THREE', 'BABYLON', 'PIXI', 'Phaser', 'pc', 'cc', 'unityInstance', 'Module'
        ];
        const __TIME_KEY_RE = /(time|timestamp|elapsed|clock|frame|tick|now|date|epoch|ms|fps|ping|latency)/i;
        const __COORD_KEY_RE = /(\.(x|y|z|w|vx|vy|vz|rx|ry|rz|yaw|pitch|roll|lat|lng|lon|left|top|pos|position|coord|player|actor|hero|camera)|\[[0-3]\]$)/i;
        const __CHUNK_BUDGET_MS = 4.5;

        const __setStatus = function(status, detail) {
          try {
            if (__bridge && typeof __bridge.updateScanStatus === 'function') {
              __bridge.updateScanStatus(String(status || 'IDLE'), String(detail || ''));
            }
          } catch (_) {}
        };

        const __isSpecialHostObject = function(v) {
          if (!v || typeof v !== 'object') return false;
          try {
            if (v === window || v === document || v === globalThis) return true;
            if (typeof Window !== 'undefined' && v instanceof Window) return true;
            if (typeof Document !== 'undefined' && v instanceof Document) return true;
            if (typeof Node !== 'undefined' && v instanceof Node) return true;
            if (typeof HTMLCollection !== 'undefined' && v instanceof HTMLCollection) return true;
            if (typeof NodeList !== 'undefined' && v instanceof NodeList) return true;
            if (typeof CSSStyleDeclaration !== 'undefined' && v instanceof CSSStyleDeclaration) return true;
            if (typeof WebGL2RenderingContext !== 'undefined' && v instanceof WebGL2RenderingContext) return true;
            if (typeof WebGLRenderingContext !== 'undefined' && v instanceof WebGLRenderingContext) return true;
            if (typeof CanvasRenderingContext2D !== 'undefined' && v instanceof CanvasRenderingContext2D) return true;
            if (typeof AudioContext !== 'undefined' && v instanceof AudioContext) return true;
          } catch (_) {
            return true;
          }
          return false;
        };

        const __describeSkippedObject = function(v) {
          try {
            if (v === window || v === globalThis) return '[Window]';
            if (v === document) return '[Document]';
            if (typeof Node !== 'undefined' && v instanceof Node) {
              return '[DOM:' + (v.nodeName || 'Node') + ']';
            }
            if (typeof WebGL2RenderingContext !== 'undefined' && v instanceof WebGL2RenderingContext) {
              return '[Context:WebGL2]';
            }
            if (typeof WebGLRenderingContext !== 'undefined' && v instanceof WebGLRenderingContext) {
              return '[Context:WebGL1]';
            }
            if (typeof CanvasRenderingContext2D !== 'undefined' && v instanceof CanvasRenderingContext2D) {
              return '[Context:Canvas2D]';
            }
            const ctor = v && v.constructor && v.constructor.name ? v.constructor.name : 'HostObject';
            return '[' + ctor + ']';
          } catch (_) {
            return '[Skipped]';
          }
        };

        /**
         * Circular-safe JSON serializer that strips DOM elements, functions, window,
         * WebGL/Canvas rendering contexts, and repeated circular references.
         */
        const __safeSerialize = function(value, indent) {
          const seen = typeof WeakSet !== 'undefined' ? new WeakSet() : null;
          try {
            const json = JSON.stringify(value, function(key, val) {
              if (val === undefined) return undefined;
              if (val === null) return null;
              const t = typeof val;
              if (t === 'function') return undefined;
              if (t === 'bigint') return val.toString();
              if (t === 'symbol') return undefined;
              if (t === 'number' && !Number.isFinite(val)) return null;
              if (t === 'object') {
                if (__isSpecialHostObject(val)) {
                  return __describeSkippedObject(val);
                }
                if (seen) {
                  if (seen.has(val)) return '[Circular]';
                  seen.add(val);
                }
              }
              return val;
            }, indent || 0);
            if (json && json.length > 14000) {
              return json.slice(0, 14000) + '… [truncated]';
            }
            return json || '{}';
          } catch (e) {
            return JSON.stringify({ serializationError: String(e) });
          }
        };

        const __emit = function(cat, obj) {
          try {
            if (!__bridge || typeof __bridge.logTelemetry !== 'function') return;
            const payload = typeof obj === 'string' ? obj : __safeSerialize(obj, 0);
            __bridge.logTelemetry(cat, payload);
          } catch (e) {
            try {
              if (__bridge && typeof __bridge.logTelemetry === 'function') {
                __bridge.logTelemetry('ERR', 'Bridge emit error: ' + String(e));
              }
            } catch (_) {}
          }
        };

        /**
         * Yields execution back to the browser render loop between chunks so animations
         * and game frames stay smooth.
         */
        const __yieldFrame = function(callback) {
          if (typeof requestAnimationFrame === 'function') {
            let fired = false;
            const guardTimer = setTimeout(function() {
              if (!fired) { fired = true; callback(); }
            }, 32);
            requestAnimationFrame(function() {
              if (!fired) {
                fired = true;
                clearTimeout(guardTimer);
                setTimeout(callback, 0);
              }
            });
          } else {
            setTimeout(callback, 8);
          }
        };

        const __STANDARD_GLOBALS = new Set([
          'window','self','globalThis','document','name','location','customElements','history',
          'navigation','locationbar','menubar','personalbar','scrollbars','statusbar','toolbar',
          'status','closed','frames','length','top','opener','parent','frameElement','navigator',
          'origin','external','screen','innerWidth','innerHeight','scrollX','pageXOffset',
          'scrollY','pageYOffset','visualViewport','screenX','screenY','outerWidth','outerHeight',
          'devicePixelRatio','event','clientInformation','screenLeft','screenTop','styleMedia',
          'trustedTypes','performance','crypto','indexedDB','sessionStorage','localStorage',
          'isSecureContext','crossOriginIsolated','scheduler','alert','atob','blur','btoa',
          'cancelAnimationFrame','cancelIdleCallback','clearInterval','clearTimeout','close',
          'confirm','createImageBitmap','fetch','find','focus','getComputedStyle','getSelection',
          'matchMedia','moveBy','moveTo','open','postMessage','print','prompt','queueMicrotask',
          'reportError','requestAnimationFrame','requestIdleCallback','resizeBy','resizeTo',
          'scroll','scrollBy','scrollTo','setInterval','setTimeout','stop','structuredClone',
          'chrome','caches','cookieStore','speechSynthesis','TelemetryBridge','__wvDiagState',
          'console','undefined','NaN','Infinity','Object','Function','Array','Number','Boolean',
          'String','Symbol','Date','Promise','RegExp','Error','JSON','Math','Intl','Reflect',
          'Proxy','Map','Set','WeakMap','WeakSet','ArrayBuffer','DataView','Float32Array',
          'Float64Array','Int8Array','Int16Array','Int32Array','Uint8Array','Uint16Array',
          'Uint32Array','BigInt','parseInt','parseFloat','isNaN','isFinite','eval'
        ]);

        const __isShortNumericVector = function(v) {
          if (!v || typeof v !== 'object') return false;
          if (!(Array.isArray(v) || (ArrayBuffer.isView(v) && !(v instanceof DataView)))) return false;
          if (v.length < 2 || v.length > 4) return false;
          for (let i = 0; i < v.length; i++) {
            if (typeof v[i] !== 'number' || !Number.isFinite(v[i])) return false;
          }
          return true;
        };

        const __countSurfaceNumerics = function(obj) {
          if (!obj || typeof obj !== 'object' || __isSpecialHostObject(obj)) return 0;
          let count = 0;
          let keys;
          try { keys = Object.keys(obj); } catch (_) { return 0; }
          const limit = Math.min(keys.length, 48);
          for (let i = 0; i < limit; i++) {
            let v;
            try { v = obj[keys[i]]; } catch (_) { continue; }
            if (typeof v === 'number' && Number.isFinite(v)) count++;
            else if (__isShortNumericVector(v)) count += v.length;
          }
          return count;
        };

        const __resolvePath = function(path) {
          try {
            const clean = path.replace(/^window\./, '');
            const tokens = clean.match(/[^.\[\]]+/g);
            if (!tokens) return undefined;
            let cur = window;
            for (let i = 0; i < tokens.length; i++) {
              if (cur === null || cur === undefined) return undefined;
              cur = cur[tokens[i]];
            }
            return cur;
          } catch (_) {
            return undefined;
          }
        };

        const __collectRootKeys = function() {
          const ordered = [];
          const seen = new Set();
          for (let i = 0; i < __ENGINE_NAMESPACES.length; i++) {
            const ns = __ENGINE_NAMESPACES[i];
            if (ns in window && !seen.has(ns)) {
              seen.add(ns);
              ordered.push(ns);
            }
          }
          let allKeys = [];
          try { allKeys = Object.getOwnPropertyNames(window); } catch (_) {}
          for (let i = 0; i < allKeys.length; i++) {
            const k = allKeys[i];
            if (seen.has(k) || __STANDARD_GLOBALS.has(k) || k.startsWith('webkit') || k.startsWith('on')) continue;
            seen.add(k);
            ordered.push(k);
          }
          return ordered;
        };
    """

    /**
     * Step 1: Non-blocking, chunked shallow heuristic scan (depth 1-2) of window globals,
     * engine namespaces, surface-level game/scene/player/coord keys, numeric containers,
     * canvas rendering contexts, and rAF cadence.
     */
    fun buildInspectGlobalScopeScript(): String = """
        (function() {
          try {
            $SHARED_RUNTIME
            const mySeq = ++__state.scanSeq;
            __setStatus('RUNNING', 'Step 1: Shallow heuristic scan…');

            const rootKeys = __collectRootKeys();
            const candidates = [];
            const shallowPaths = [];
            const seenPaths = new Set();
            const globalContexts = [];
            let cursor = 0;
            let chunksExecuted = 0;

            const recordPath = function(p) {
              if (!seenPaths.has(p) && shallowPaths.length < 120) {
                seenPaths.add(p);
                shallowPaths.push(p);
              }
            };

            const inspectCanvasesAndFinish = function() {
              if (mySeq !== __state.scanSeq) return;
              const canvases = [];
              const domCanvases = document.querySelectorAll('canvas');
              for (let c = 0; c < domCanvases.length && c < 12; c++) {
                const el = domCanvases[c];
                const rect = el.getBoundingClientRect();
                let ctxHint = 'unknown';
                try {
                  if (el.__webgl || el.gl) ctxHint = 'WebGL';
                  else if (el.width > 0 && el.height > 0 && el.getContext) {
                    if (el.getContext('webgl2')) ctxHint = 'WebGL2';
                    else if (el.getContext('webgl')) ctxHint = 'WebGL1';
                    else if (el.getContext('2d')) ctxHint = 'Canvas2D';
                  }
                } catch (_) {}
                canvases.push({
                  index: c,
                  id: el.id || null,
                  width: el.width,
                  height: el.height,
                  cssWidth: Math.round(rect.width),
                  cssHeight: Math.round(rect.height),
                  context: ctxHint
                });
              }

              __state.candidates = candidates.map(function(c) { return c.path; });
              __state.shallowPaths = shallowPaths.slice();

              const emitSummary = function(rafDeltaMs, estimatedFps) {
                if (mySeq !== __state.scanSeq) return;
                __setStatus('COMPLETED', 'Step 1: ' + candidates.length + ' heuristic containers');
                __emit('SCOPE', {
                  stage: 'GLOBAL_SCOPE_INSPECTED',
                  scannerMode: 'async_chunked_shallow_depth_2',
                  chunksExecuted: chunksExecuted,
                  url: location.href,
                  title: document.title || '',
                  viewport: {
                    width: window.innerWidth,
                    height: window.innerHeight,
                    dpr: window.devicePixelRatio || 1
                  },
                  rafDeltaMs: rafDeltaMs,
                  estimatedFps: estimatedFps,
                  canvasCount: domCanvases.length,
                  canvases: canvases,
                  globalContexts: globalContexts,
                  candidateCount: candidates.length,
                  candidates: candidates.slice(0, 24),
                  shallowPaths: shallowPaths.slice(0, 48)
                });
              };

              if (typeof requestAnimationFrame === 'function') {
                let done = false;
                const fallbackTimer = setTimeout(function() {
                  if (!done) { done = true; emitSummary(null, null); }
                }, 300);
                requestAnimationFrame(function(t1) {
                  requestAnimationFrame(function(t2) {
                    if (done) return;
                    done = true;
                    clearTimeout(fallbackTimer);
                    const dt = Math.max(0.1, t2 - t1);
                    const fps = Math.min(240, Math.round(1000 / dt));
                    emitSummary(Number(dt.toFixed(2)), fps);
                  });
                });
              } else {
                emitSummary(null, null);
              }
            };

            const processChunk = function() {
              if (mySeq !== __state.scanSeq) return;
              chunksExecuted++;
              const sliceStart = performance.now();

              while (cursor < rootKeys.length && (performance.now() - sliceStart) < __CHUNK_BUDGET_MS) {
                const k = rootKeys[cursor++];
                let val;
                try { val = window[k]; } catch (_) { continue; }
                if (!val || typeof val !== 'object') continue;

                // Detect exposed rendering contexts without diving into them
                if (__isSpecialHostObject(val)) {
                  const desc = __describeSkippedObject(val);
                  if (desc.startsWith('[Context:')) {
                    globalContexts.push({ source: 'window.' + k, kind: desc });
                  }
                  continue;
                }

                // Depth 1 inspection
                const rootPath = 'window.' + k;
                const rootMatchesHeuristic = __HEURISTIC_KEY_RE.test(k);
                const rootNumericCount = __countSurfaceNumerics(val);
                const matchedSubKeys = [];

                // Depth 2 surface-key heuristic sweep (strictly bounded)
                let subKeys = [];
                try { subKeys = Object.keys(val); } catch (_) {}
                const subLimit = Math.min(subKeys.length, 64);
                for (let j = 0; j < subLimit; j++) {
                  const sk = subKeys[j];
                  if (sk.startsWith('__wvDiag')) continue;
                  let sv;
                  try { sv = val[sk]; } catch (_) { continue; }

                  const subPath = rootPath + '.' + sk;
                  if (typeof sv === 'number' && Number.isFinite(sv)) {
                    if (rootMatchesHeuristic || __HEURISTIC_KEY_RE.test(sk) || __COORD_KEY_RE.test(subPath)) {
                      recordPath(subPath);
                    }
                  } else if (sv && typeof sv === 'object' && !__isSpecialHostObject(sv)) {
                    const subKeyMatches = __HEURISTIC_KEY_RE.test(sk);
                    const subNumCount = __countSurfaceNumerics(sv);
                    if (subKeyMatches || (rootMatchesHeuristic && subNumCount > 0)) {
                      if (matchedSubKeys.length < 12) {
                        matchedSubKeys.push(sk + (subNumCount > 0 ? '(' + subNumCount + ' num)' : ''));
                      }
                      recordPath(subPath);
                    }
                  }
                }

                if (rootMatchesHeuristic || rootNumericCount > 0 || matchedSubKeys.length > 0) {
                  let ctor = 'Object';
                  try { ctor = val.constructor && val.constructor.name ? val.constructor.name : 'Object'; } catch (_) {}
                  if (candidates.length < 36) {
                    candidates.push({
                      path: rootPath,
                      constructor: ctor,
                      heuristicMatch: rootMatchesHeuristic,
                      surfaceNumerics: rootNumericCount,
                      matchedChildren: matchedSubKeys
                    });
                  }
                  recordPath(rootPath);
                }
              }

              if (cursor < rootKeys.length) {
                __setStatus('RUNNING', 'Step 1: ' + cursor + '/' + rootKeys.length + ' globals…');
                __yieldFrame(processChunk);
              } else {
                inspectCanvasesAndFinish();
              }
            };

            __yieldFrame(processChunk);
          } catch (err) {
            try {
              if (window.TelemetryBridge) {
                window.TelemetryBridge.updateScanStatus('ERROR', 'Step 1 error');
                window.TelemetryBridge.logTelemetry('ERR', 'Step 1 Scope error: ' + String(err));
              }
            } catch (_) {}
          }
        })();
    """.trimIndent()

    /**
     * Step 2A: Non-blocking, chunked shallow baseline capture (max depth 2) across
     * heuristic candidate containers and their immediate numeric properties/vectors.
     */
    fun buildCaptureBaselineScript(): String = """
        (function() {
          try {
            $SHARED_RUNTIME
            const mySeq = ++__state.scanSeq;
            __setStatus('RUNNING', 'Step 2A: Capturing shallow baseline…');

            // Seed candidate roots from Step 1 or build a quick heuristic root list
            const rootPaths = [];
            const seenRoots = new Set();
            if (Array.isArray(__state.candidates) && __state.candidates.length > 0) {
              for (let i = 0; i < __state.candidates.length; i++) {
                const rp = __state.candidates[i];
                if (!seenRoots.has(rp)) {
                  seenRoots.add(rp);
                  rootPaths.push(rp);
                }
              }
            } else {
              const keys = __collectRootKeys();
              for (let i = 0; i < keys.length && rootPaths.length < 48; i++) {
                const k = keys[i];
                const rp = 'window.' + k;
                if (!seenRoots.has(rp)) {
                  seenRoots.add(rp);
                  rootPaths.push(rp);
                }
              }
            }

            const baseline = Object.create(null);
            const shallowPaths = new Set(__state.shallowPaths || []);
            let metricCount = 0;
            let containersScanned = 0;
            let cursor = 0;
            let chunksExecuted = 0;
            const MAX_METRICS = 2000;

            const recordNumeric = function(path, num) {
              if (metricCount >= MAX_METRICS) return;
              if (typeof num === 'number' && Number.isFinite(num)) {
                baseline[path] = num;
                metricCount++;
              }
            };

            // Strictly shallow: depth 1 properties and depth 2 child numeric fields only
            const snapshotContainer = function(obj, basePath) {
              if (!obj || typeof obj !== 'object' || __isSpecialHostObject(obj)) return;
              containersScanned++;

              if (__isShortNumericVector(obj)) {
                for (let i = 0; i < obj.length; i++) {
                  recordNumeric(basePath + '[' + i + ']', obj[i]);
                }
                shallowPaths.add(basePath);
                return;
              }

              let depth1Keys;
              try { depth1Keys = Object.keys(obj); } catch (_) { return; }
              const d1Limit = Math.min(depth1Keys.length, 64);

              for (let i = 0; i < d1Limit && metricCount < MAX_METRICS; i++) {
                const k1 = depth1Keys[i];
                if (k1.startsWith('__wvDiag')) continue;
                let v1;
                try { v1 = obj[k1]; } catch (_) { continue; }
                const p1 = basePath + '.' + k1;

                if (typeof v1 === 'number' && Number.isFinite(v1)) {
                  recordNumeric(p1, v1);
                } else if (__isShortNumericVector(v1)) {
                  for (let vi = 0; vi < v1.length; vi++) {
                    recordNumeric(p1 + '[' + vi + ']', v1[vi]);
                  }
                  shallowPaths.add(p1);
                } else if (v1 && typeof v1 === 'object' && !__isSpecialHostObject(v1)) {
                  // Depth 2 only (no deeper recursion)
                  const heuristicChild = __HEURISTIC_KEY_RE.test(k1) || __HEURISTIC_KEY_RE.test(basePath);
                  let depth2Keys;
                  try { depth2Keys = Object.keys(v1); } catch (_) { continue; }
                  const d2Limit = Math.min(depth2Keys.length, heuristicChild ? 40 : 16);
                  let childHadNumeric = false;

                  for (let j = 0; j < d2Limit && metricCount < MAX_METRICS; j++) {
                    const k2 = depth2Keys[j];
                    let v2;
                    try { v2 = v1[k2]; } catch (_) { continue; }
                    const p2 = p1 + '.' + k2;
                    if (typeof v2 === 'number' && Number.isFinite(v2)) {
                      recordNumeric(p2, v2);
                      childHadNumeric = true;
                    } else if (__isShortNumericVector(v2)) {
                      for (let vi = 0; vi < v2.length; vi++) {
                        recordNumeric(p2 + '[' + vi + ']', v2[vi]);
                      }
                      childHadNumeric = true;
                    }
                  }
                  if (childHadNumeric && shallowPaths.size < 140) {
                    shallowPaths.add(p1);
                  }
                }
              }
            };

            const processChunk = function() {
              if (mySeq !== __state.scanSeq) return;
              chunksExecuted++;
              const sliceStart = performance.now();

              while (cursor < rootPaths.length && metricCount < MAX_METRICS && (performance.now() - sliceStart) < __CHUNK_BUDGET_MS) {
                const rp = rootPaths[cursor++];
                const obj = __resolvePath(rp);
                if (obj && typeof obj === 'object' && !__isSpecialHostObject(obj)) {
                  snapshotContainer(obj, rp);
                }
              }

              if (cursor < rootPaths.length && metricCount < MAX_METRICS) {
                __setStatus('RUNNING', 'Step 2A: ' + metricCount + ' metrics…');
                __yieldFrame(processChunk);
              } else {
                __state.baseline = baseline;
                __state.baselineTimeMs = performance.now();
                __state.shallowPaths = Array.from(shallowPaths).slice(0, 120);

                __setStatus('COMPLETED', '2A Armed (' + metricCount + ' shallow metrics)');
                __emit('DELTA', {
                  stage: 'BASELINE_CAPTURED',
                  scannerMode: 'async_chunked_shallow_depth_2',
                  chunksExecuted: chunksExecuted,
                  rootObjectCount: rootPaths.length,
                  objectsScanned: containersScanned,
                  metricCount: metricCount,
                  shallowPaths: __state.shallowPaths.slice(0, 32)
                });
              }
            };

            __yieldFrame(processChunk);
          } catch (err) {
            try {
              if (window.TelemetryBridge) {
                window.TelemetryBridge.updateScanStatus('ERROR', 'Step 2A error');
                window.TelemetryBridge.logTelemetry('ERR', 'Step 2A Baseline error: ' + String(err));
              }
            } catch (_) {}
          }
        })();
    """.trimIndent()

    /**
     * Step 2B: Non-blocking, chunked shallow delta computation comparing recorded
     * depth 1-2 baseline paths against current runtime values.
     */
    fun buildComputeDeltaScript(): String = """
        (function() {
          try {
            $SHARED_RUNTIME
            const mySeq = ++__state.scanSeq;
            if (!__state.baseline) {
              __setStatus('IDLE', 'Capture 2A baseline first');
              __emit('DELTA', {
                stage: 'BASELINE_MISSING',
                message: 'No shallow baseline snapshot found. Run Step 2A first, trigger movement, then tap 2B.'
              });
              return;
            }

            __setStatus('RUNNING', 'Step 2B: Computing shallow deltas…');
            const baseline = __state.baseline;
            const keys = Object.keys(baseline);
            const candidates = [];
            let cursor = 0;
            let chunksExecuted = 0;

            const processChunk = function() {
              if (mySeq !== __state.scanSeq) return;
              chunksExecuted++;
              const sliceStart = performance.now();

              while (cursor < keys.length && (performance.now() - sliceStart) < __CHUNK_BUDGET_MS) {
                const p = keys[cursor++];
                const baseVal = baseline[p];
                const curVal = __resolvePath(p);
                if (typeof curVal !== 'number' || !Number.isFinite(curVal)) continue;

                const diff = curVal - baseVal;
                const absDiff = Math.abs(diff);
                if (absDiff < 0.0001 || absDiff > 10000) continue;
                if (Math.abs(curVal) > 1e11) continue;
                if (__TIME_KEY_RE.test(p)) continue;

                const score =
                  (__COORD_KEY_RE.test(p) ? 100 : 0) +
                  (__HEURISTIC_KEY_RE.test(p) ? 50 : 0) +
                  (absDiff <= 250 ? 25 : 0);

                candidates.push({
                  path: p,
                  baseline: Number(baseVal.toFixed(4)),
                  current: Number(curVal.toFixed(4)),
                  delta: Number(diff.toFixed(4)),
                  score: score
                });
              }

              if (cursor < keys.length) {
                __setStatus('RUNNING', 'Step 2B: ' + cursor + '/' + keys.length + ' checked…');
                __yieldFrame(processChunk);
              } else {
                candidates.sort(function(a, b) {
                  if (b.score !== a.score) return b.score - a.score;
                  return Math.abs(a.delta) - Math.abs(b.delta);
                });

                const top = candidates.slice(0, 25).map(function(item) {
                  return {
                    path: item.path,
                    baseline: item.baseline,
                    current: item.current,
                    delta: item.delta
                  };
                });

                __state.isolatedPaths = top;
                const mergedShallow = new Set(__state.shallowPaths || []);
                for (let i = 0; i < top.length; i++) mergedShallow.add(top[i].path);
                __state.shallowPaths = Array.from(mergedShallow).slice(0, 120);

                const elapsedMs = Math.round(performance.now() - (__state.baselineTimeMs || performance.now()));
                __setStatus('COMPLETED', 'Step 2B: ' + top.length + ' deltas isolated');

                __emit('DELTA', {
                  stage: 'DELTA_COMPUTED',
                  scannerMode: 'async_chunked_shallow_depth_2',
                  chunksExecuted: chunksExecuted,
                  elapsedMs: elapsedMs,
                  comparedMetrics: keys.length,
                  totalChanged: candidates.length,
                  deltas: top
                });
              }
            };

            __yieldFrame(processChunk);
          } catch (err) {
            try {
              if (window.TelemetryBridge) {
                window.TelemetryBridge.updateScanStatus('ERROR', 'Step 2B error');
                window.TelemetryBridge.logTelemetry('ERR', 'Step 2B Delta error: ' + String(err));
              }
            } catch (_) {}
          }
        })();
    """.trimIndent()

    /**
     * Step 3: Non-blocking, chunked shallow entity & 3D->2D canvas projection scanner.
     * Inspects shallow entity arrays/collections inside heuristic candidate roots without
     * blocking the main render loop.
     */
    fun buildScanEntitiesScript(): String = """
        (function() {
          try {
            $SHARED_RUNTIME
            const mySeq = ++__state.scanSeq;
            __setStatus('RUNNING', 'Step 3: Scanning shallow entities…');

            const canvases = document.querySelectorAll('canvas');
            let primaryCanvas = null;
            let bestArea = -1;
            for (let i = 0; i < canvases.length; i++) {
              const r = canvases[i].getBoundingClientRect();
              const area = r.width * r.height;
              if (area > bestArea) {
                bestArea = area;
                primaryCanvas = canvases[i];
              }
            }
            const vpWidth = primaryCanvas ? (primaryCanvas.clientWidth || primaryCanvas.width || window.innerWidth) : window.innerWidth;
            const vpHeight = primaryCanvas ? (primaryCanvas.clientHeight || primaryCanvas.height || window.innerHeight) : window.innerHeight;

            const extractVec3 = function(obj) {
              if (!obj || typeof obj !== 'object' || __isSpecialHostObject(obj)) return null;
              try {
                if (typeof obj.x === 'number' && typeof obj.y === 'number') {
                  return { x: obj.x, y: obj.y, z: typeof obj.z === 'number' ? obj.z : 0 };
                }
                if (obj.position && typeof obj.position.x === 'number' && typeof obj.position.y === 'number') {
                  return { x: obj.position.x, y: obj.position.y, z: typeof obj.position.z === 'number' ? obj.position.z : 0 };
                }
                if (obj.pos && typeof obj.pos.x === 'number' && typeof obj.pos.y === 'number') {
                  return { x: obj.pos.x, y: obj.pos.y, z: typeof obj.pos.z === 'number' ? obj.pos.z : 0 };
                }
                if (__isShortNumericVector(obj.position)) {
                  return {
                    x: obj.position[0],
                    y: obj.position[1],
                    z: obj.position.length >= 3 ? obj.position[2] : 0
                  };
                }
              } catch (_) {}
              return null;
            };

            const extractBounds = function(obj) {
              try {
                const b = obj.boundingBox || obj.bounds || obj.bbox || obj.aabb;
                if (b && b.min && b.max && typeof b.min.x === 'number' && typeof b.max.x === 'number') {
                  return {
                    w: Number((b.max.x - b.min.x).toFixed(3)),
                    h: Number((b.max.y - b.min.y).toFixed(3)),
                    d: typeof b.min.z === 'number' && typeof b.max.z === 'number' ? Number((b.max.z - b.min.z).toFixed(3)) : 0
                  };
                }
                if (typeof obj.width === 'number' && typeof obj.height === 'number') {
                  return { w: Number(obj.width.toFixed(2)), h: Number(obj.height.toFixed(2)), d: 0 };
                }
              } catch (_) {}
              return null;
            };

            const isMat4 = function(v) {
              if (!v || typeof v !== 'object') return false;
              const arr = Array.isArray(v.elements) || ArrayBuffer.isView(v.elements) ? v.elements : v;
              if (!(Array.isArray(arr) || ArrayBuffer.isView(arr)) || arr.length !== 16) return false;
              for (let i = 0; i < 16; i++) {
                if (typeof arr[i] !== 'number' || !Number.isFinite(arr[i])) return false;
              }
              return true;
            };

            // Build shallow root list from isolated paths + heuristic candidates
            const isolated = Array.isArray(__state.isolatedPaths) ? __state.isolatedPaths : [];
            const rootPaths = [];
            const seenRoots = new Set();
            const addRoot = function(p) {
              if (p && !seenRoots.has(p)) {
                seenRoots.add(p);
                rootPaths.push(p);
              }
            };
            for (let i = 0; i < isolated.length; i++) {
              const seg = isolated[i].path.replace(/^window\./, '').split(/[.\[]/)[0];
              if (seg) addRoot('window.' + seg);
            }
            if (Array.isArray(__state.candidates)) {
              for (let i = 0; i < __state.candidates.length; i++) addRoot(__state.candidates[i]);
            }
            if (rootPaths.length === 0) {
              const keys = __collectRootKeys();
              for (let i = 0; i < keys.length && rootPaths.length < 32; i++) addRoot('window.' + keys[i]);
            }

            let vpMatrix = null;
            let matrixSource = 'pinhole_fallback';
            const entities = [];
            let cursor = 0;
            let chunksExecuted = 0;

            const inspectCollection = function(arr, path) {
              const len = Math.min(arr.length, 32);
              for (let i = 0; i < len && entities.length < 24; i++) {
                let item;
                try { item = arr[i]; } catch (_) { continue; }
                const vec = extractVec3(item);
                if (vec) {
                  const label = (item.name || item.id || item.type || (item.constructor && item.constructor.name) || 'Entity');
                  entities.push({
                    path: path + '[' + i + ']',
                    label: String(label).slice(0, 32),
                    world: vec,
                    bounds: extractBounds(item)
                  });
                }
              }
            };

            const processChunk = function() {
              if (mySeq !== __state.scanSeq) return;
              chunksExecuted++;
              const sliceStart = performance.now();

              while (cursor < rootPaths.length && entities.length < 24 && (performance.now() - sliceStart) < __CHUNK_BUDGET_MS) {
                const rp = rootPaths[cursor++];
                const rootObj = __resolvePath(rp);
                if (!rootObj || typeof rootObj !== 'object' || __isSpecialHostObject(rootObj)) continue;

                if (Array.isArray(rootObj)) {
                  inspectCollection(rootObj, rp);
                  continue;
                }

                // Shallow depth 1-2 entity & matrix check
                let d1Keys;
                try { d1Keys = Object.keys(rootObj); } catch (_) { continue; }
                const limit = Math.min(d1Keys.length, 64);
                for (let i = 0; i < limit && entities.length < 24; i++) {
                  const k = d1Keys[i];
                  if (k.startsWith('__wvDiag')) continue;
                  let v;
                  try { v = rootObj[k]; } catch (_) { continue; }
                  const p1 = rp + '.' + k;

                  if (!vpMatrix && /(viewProjection|projectionMatrix|mvp|vpMatrix)/i.test(k) && isMat4(v)) {
                    const raw = Array.isArray(v.elements) || ArrayBuffer.isView(v.elements) ? v.elements : v;
                    vpMatrix = Array.from(raw);
                    matrixSource = p1;
                  }

                  if (Array.isArray(v)) {
                    inspectCollection(v, p1);
                  } else if (v && typeof v === 'object' && !__isSpecialHostObject(v)) {
                    const directVec = extractVec3(v);
                    if (directVec && __HEURISTIC_KEY_RE.test(k)) {
                      entities.push({
                        path: p1,
                        label: k.slice(0, 32),
                        world: directVec,
                        bounds: extractBounds(v)
                      });
                    } else if (__HEURISTIC_KEY_RE.test(k)) {
                      // Depth 2 check for entity arrays or matrix on camera/scene
                      let d2Keys;
                      try { d2Keys = Object.keys(v); } catch (_) { continue; }
                      const d2Limit = Math.min(d2Keys.length, 24);
                      for (let j = 0; j < d2Limit && entities.length < 24; j++) {
                        const k2 = d2Keys[j];
                        let v2;
                        try { v2 = v[k2]; } catch (_) { continue; }
                        const p2 = p1 + '.' + k2;
                        if (!vpMatrix && /(viewProjection|projectionMatrix|mvp|vpMatrix)/i.test(k2) && isMat4(v2)) {
                          const raw2 = Array.isArray(v2.elements) || ArrayBuffer.isView(v2.elements) ? v2.elements : v2;
                          vpMatrix = Array.from(raw2);
                          matrixSource = p2;
                        } else if (Array.isArray(v2)) {
                          inspectCollection(v2, p2);
                        }
                      }
                    }
                  }
                }
              }

              if (cursor < rootPaths.length && entities.length < 24) {
                __setStatus('RUNNING', 'Step 3: ' + entities.length + ' entities found…');
                __yieldFrame(processChunk);
              } else {
                let refX = 0, refY = 0, refZ = 0;
                for (let i = 0; i < isolated.length; i++) {
                  const p = isolated[i].path;
                  if (/(\.x|\[0\])$/i.test(p)) refX = isolated[i].current;
                  else if (/(\.y|\[1\])$/i.test(p)) refY = isolated[i].current;
                  else if (/(\.z|\[2\])$/i.test(p)) refZ = isolated[i].current;
                }

                const projectToScreen = function(w) {
                  if (vpMatrix) {
                    const m = vpMatrix;
                    const cx = m[0]*w.x + m[4]*w.y + m[8]*w.z + m[12];
                    const cy = m[1]*w.x + m[5]*w.y + m[9]*w.z + m[13];
                    const cw = m[3]*w.x + m[7]*w.y + m[11]*w.z + m[15];
                    const invW = Math.abs(cw) > 1e-6 ? (1 / cw) : 1;
                    const sx = ((cx * invW) * 0.5 + 0.5) * vpWidth;
                    const sy = (-(cy * invW) * 0.5 + 0.5) * vpHeight;
                    return {
                      screenX: Number(sx.toFixed(1)),
                      screenY: Number(sy.toFixed(1)),
                      inViewport: sx >= 0 && sx <= vpWidth && sy >= 0 && sy <= vpHeight && cw > 0
                    };
                  }
                  const dx = w.x - refX;
                  const dy = w.y - refY;
                  const dz = (w.z - refZ) + 10.0;
                  const focal = Math.min(vpWidth, vpHeight) * 0.85;
                  const depthSafe = Math.abs(dz) < 0.1 ? 0.1 : Math.abs(dz);
                  const sx = (vpWidth * 0.5) + (dx / depthSafe) * focal;
                  const sy = (vpHeight * 0.5) - (dy / depthSafe) * focal;
                  return {
                    screenX: Number(sx.toFixed(1)),
                    screenY: Number(sy.toFixed(1)),
                    inViewport: sx >= 0 && sx <= vpWidth && sy >= 0 && sy <= vpHeight
                  };
                };

                const projectedEntities = entities.map(function(ent) {
                  const proj = projectToScreen(ent.world);
                  return {
                    path: ent.path,
                    label: ent.label,
                    world: {
                      x: Number(ent.world.x.toFixed(3)),
                      y: Number(ent.world.y.toFixed(3)),
                      z: Number(ent.world.z.toFixed(3))
                    },
                    bounds: ent.bounds,
                    screenX: proj.screenX,
                    screenY: proj.screenY,
                    inViewport: proj.inViewport
                  };
                });

                __setStatus('COMPLETED', 'Step 3: ' + projectedEntities.length + ' entities projected');
                __emit('ENTITY', {
                  stage: 'ENTITIES_SCANNED',
                  scannerMode: 'async_chunked_shallow_depth_2',
                  chunksExecuted: chunksExecuted,
                  viewport: { width: Math.round(vpWidth), height: Math.round(vpHeight) },
                  projectionMode: matrixSource,
                  entityCount: projectedEntities.length,
                  entities: projectedEntities
                });
              }
            };

            __yieldFrame(processChunk);
          } catch (err) {
            try {
              if (window.TelemetryBridge) {
                window.TelemetryBridge.updateScanStatus('ERROR', 'Step 3 error');
                window.TelemetryBridge.logTelemetry('ERR', 'Step 3 Entity scan error: ' + String(err));
              }
            } catch (_) {}
          }
        })();
    """.trimIndent()

    /**
     * Step 5: Collects live viewport metrics and discovered shallow paths safely using
     * circular-safe serialization, passing JSON to TelemetryBridge.exportReport().
     */
    fun buildCollectViewportMetricsScript(): String = """
        (function() {
          try {
            $SHARED_RUNTIME
            const canvases = Array.from(document.querySelectorAll('canvas')).slice(0, 8).map(function(c, idx) {
              const r = c.getBoundingClientRect();
              return {
                index: idx,
                id: c.id || null,
                width: c.width,
                height: c.height,
                cssWidth: Math.round(r.width),
                cssHeight: Math.round(r.height)
              };
            });
            const perfMem = performance && performance.memory ? {
              usedJSHeapSize: performance.memory.usedJSHeapSize,
              totalJSHeapSize: performance.memory.totalJSHeapSize,
              jsHeapSizeLimit: performance.memory.jsHeapSizeLimit
            } : null;

            const payload = {
              url: location.href,
              title: document.title || '',
              readyState: document.readyState,
              userAgent: navigator.userAgent,
              viewport: {
                innerWidth: window.innerWidth,
                innerHeight: window.innerHeight,
                outerWidth: window.outerWidth,
                outerHeight: window.outerHeight,
                devicePixelRatio: window.devicePixelRatio || 1,
                scrollX: window.scrollX,
                scrollY: window.scrollY
              },
              domNodeCount: document.getElementsByTagName('*').length,
              canvases: canvases,
              memory: perfMem,
              discoveredShallowPaths: Array.isArray(__state.shallowPaths) ? __state.shallowPaths.slice(0, 120) : []
            };
            if (__bridge && typeof __bridge.exportReport === 'function') {
              __bridge.exportReport(__safeSerialize(payload, 0));
            }
          } catch (err) {
            try {
              if (window.TelemetryBridge && typeof window.TelemetryBridge.exportReport === 'function') {
                window.TelemetryBridge.exportReport(JSON.stringify({ collectionError: String(err) }));
              }
            } catch (_) {}
          }
        })();
    """.trimIndent()

    /**
     * Safely evaluates a developer-entered manual JavaScript expression or statement block
     * inside a deferred micro-slice using circular-safe serialization that skips DOM elements,
     * functions, window, and WebGL contexts.
     */
    fun buildManualEvalScript(rawCode: String): String {
        val escapedCodeLiteral = JSONObject.quote(rawCode)
        return """
            (function() {
              try {
                $SHARED_RUNTIME
                const code = $escapedCodeLiteral;
                __setStatus('RUNNING', 'Evaluating JS…');
                __yieldFrame(function() {
                  try {
                    const t0 = performance.now();
                    let result;
                    let mode = 'expr';
                    try {
                      result = (0, eval)('(' + code + ')');
                    } catch (syntaxErr) {
                      mode = 'stmt';
                      result = (0, eval)(code);
                    }
                    const durationMs = Number((performance.now() - t0).toFixed(2));

                    let formatted;
                    if (result === undefined) {
                      formatted = 'undefined';
                    } else if (result === null) {
                      formatted = 'null';
                    } else if (typeof result === 'function') {
                      formatted = '[Function ' + (result.name || 'anonymous') + ']';
                    } else if (__isSpecialHostObject(result)) {
                      formatted = __describeSkippedObject(result);
                    } else if (typeof result === 'object') {
                      formatted = __safeSerialize(result, 2);
                      if (formatted.length > 4000) {
                        formatted = formatted.slice(0, 4000) + '\n… [truncated]';
                      }
                    } else {
                      formatted = String(result);
                    }

                    __setStatus('COMPLETED', 'JS eval (' + durationMs + 'ms)');
                    __emit('JS', {
                      stage: 'MANUAL_EVAL',
                      mode: mode,
                      durationMs: durationMs,
                      expression: code.slice(0, 140),
                      resultType: typeof result,
                      result: formatted
                    });
                  } catch (evalErr) {
                    __setStatus('ERROR', 'JS eval failed');
                    __emit('ERR', 'JS Eval error: ' + (evalErr && evalErr.stack ? evalErr.stack.slice(0, 600) : String(evalErr)));
                  }
                });
              } catch (err) {
                try {
                  if (window.TelemetryBridge) {
                    window.TelemetryBridge.updateScanStatus('ERROR', 'JS eval failed');
                    window.TelemetryBridge.logTelemetry('ERR', 'JS Eval error: ' + String(err));
                  }
                } catch (_) {}
              }
            })();
        """.trimIndent()
    }
}
