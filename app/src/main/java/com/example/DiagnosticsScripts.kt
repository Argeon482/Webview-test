package com.example

import org.json.JSONObject

/**
 * Modular, circular-safe JavaScript diagnostic routines injected into the target WebView.
 * Every routine is wrapped in robust try/catch guards with depth and node caps so complex
 * web apps (WebGL, Canvas, SPA frameworks) remain responsive without main-thread stalls.
 */
object DiagnosticsScripts {

    private const val SHARED_HELPERS = """
        const __bridge = window.TelemetryBridge;
        const __emit = function(cat, obj) {
          try {
            if (!__bridge || typeof __bridge.logTelemetry !== 'function') return;
            const seen = typeof WeakSet !== 'undefined' ? new WeakSet() : null;
            const str = typeof obj === 'string' ? obj : JSON.stringify(obj, function(k, v) {
              if (typeof v === 'bigint') return v.toString();
              if (typeof v === 'function') return '[Function]';
              if (v && typeof v === 'object') {
                if (seen) {
                  if (seen.has(v)) return '[Circular]';
                  seen.add(v);
                }
              }
              return v;
            });
            __bridge.logTelemetry(cat, str);
          } catch (e) {
            try {
              if (__bridge && typeof __bridge.logTelemetry === 'function') {
                __bridge.logTelemetry('ERR', 'Telemetry serialization error: ' + String(e));
              }
            } catch (_) {}
          }
        };

        const __getStandardGlobals = function() {
          const std = new Set([
            'window','self','document','name','location','customElements','history','navigation',
            'locationbar','menubar','personalbar','scrollbars','statusbar','toolbar','status',
            'closed','frames','length','top','opener','parent','frameElement','navigator',
            'origin','external','screen','innerWidth','innerHeight','scrollX','pageXOffset',
            'scrollY','pageYOffset','visualViewport','screenX','screenY','outerWidth','outerHeight',
            'devicePixelRatio','event','clientInformation','screenLeft','screenTop','styleMedia',
            'onsearch','trustedTypes','performance','onappinstalled','onbeforeinstallprompt',
            'crypto','indexedDB','sessionStorage','localStorage','onbeforexrselect','onabort',
            'onbeforeinput','onbeforematch','onbeforetoggle','onblur','oncancel','oncanplay',
            'oncanplaythrough','onchange','onclick','onclose','oncontentvisibilityautostatechange',
            'oncontextlost','oncontextmenu','oncontextrestored','oncuechange','ondblclick',
            'ondrag','ondragend','ondragenter','ondragleave','ondragover','ondragstart','ondrop',
            'ondurationchange','onemptied','onended','onerror','onfocus','onformdata',
            'oninput','oninvalid','onkeydown','onkeypress','onkeyup','onload','onloadeddata',
            'onloadedmetadata','onloadstart','onmousedown','onmouseenter','onmouseleave',
            'onmousemove','onmouseout','onmouseover','onmouseup','onmousewheel','onpause',
            'onplay','onplaying','onprogress','onratechange','onreset','onresize','onscroll',
            'onsecuritypolicyviolation','onseeked','onseeking','onselect','onslotchange',
            'onstalled','onsubmit','onsuspend','ontimeupdate','ontoggle','onvolumechange',
            'onwaiting','onwebkitanimationend','onwebkitanimationiteration','onwebkitanimationstart',
            'onwebkittransitionend','onwheel','onauxclick','ongotpointercapture','onlostpointercapture',
            'onpointerdown','onpointermove','onpointerrawupdate','onpointerup','onpointercancel',
            'onpointerover','onpointerout','onpointerenter','onpointerleave','onselectstart',
            'onselectionchange','onanimationend','onanimationiteration','onanimationstart',
            'ontransitionrun','ontransitionstart','ontransitionend','ontransitioncancel',
            'onafterprint','onbeforeprint','onbeforeunload','onhashchange','onlanguagechange',
            'onmessage','onmessageerror','onoffline','ononline','onpagehide','onpageshow',
            'onpopstate','onrejectionhandled','onstorage','onunhandledrejection','onunload',
            'isSecureContext','crossOriginIsolated','scheduler','alert','atob','blur','btoa',
            'cancelAnimationFrame','cancelIdleCallback','captureEvents','clearInterval',
            'clearTimeout','close','confirm','createImageBitmap','fetch','find','focus',
            'getComputedStyle','getSelection','matchMedia','moveBy','moveTo','open','postMessage',
            'print','prompt','queueMicrotask','releaseEvents','reportError','requestAnimationFrame',
            'requestIdleCallback','resizeBy','resizeTo','scroll','scrollBy','scrollTo','setInterval',
            'setTimeout','stop','structuredClone','webkitCancelAnimationFrame',
            'webkitRequestAnimationFrame','chrome','caches','cookieStore','ondevicemotion',
            'ondeviceorientation','ondeviceorientationabsolute','launchQueue','sharedStorage',
            'documentPictureInPicture','fetchLater','getScreenDetails','queryLocalFonts',
            'showDirectoryPicker','showOpenFilePicker','showSaveFilePicker','originAgentCluster',
            'credentialless','fence','speechSynthesis','onscrollend','onscrollsnapchange',
            'onscrollsnapchanging','webkitRequestFileSystem','webkitResolveLocalFileSystemURL',
            'TelemetryBridge','__wvDiagState','console','undefined','NaN','Infinity',
            'Object','Function','Array','Number','Boolean','String','Symbol','Date','Promise',
            'RegExp','Error','EvalError','RangeError','ReferenceError','SyntaxError','TypeError',
            'URIError','JSON','Math','Intl','Reflect','Proxy','Map','Set','WeakMap','WeakSet',
            'ArrayBuffer','SharedArrayBuffer','DataView','Float32Array','Float64Array','Int8Array',
            'Int16Array','Int32Array','Uint8Array','Uint8ClampedArray','Uint16Array','Uint32Array',
            'BigInt','BigInt64Array','BigUint64Array','parseInt','parseFloat','isNaN','isFinite',
            'decodeURI','decodeURIComponent','encodeURI','encodeURIComponent','escape','unescape','eval'
          ]);
          try {
            const iframe = document.createElement('iframe');
            iframe.style.display = 'none';
            if (document.documentElement) {
              document.documentElement.appendChild(iframe);
              if (iframe.contentWindow) {
                Object.getOwnPropertyNames(iframe.contentWindow).forEach(function(k) { std.add(k); });
              }
              iframe.remove();
            }
          } catch (_) {}
          return std;
        };

        const __isDomOrWindow = function(v) {
          if (!v || typeof v !== 'object') return false;
          try {
            if (v === window || v === document) return true;
            if (typeof Node !== 'undefined' && v instanceof Node) return true;
            if (typeof Window !== 'undefined' && v instanceof Window) return true;
            if (typeof HTMLCollection !== 'undefined' && v instanceof HTMLCollection) return true;
            if (typeof NodeList !== 'undefined' && v instanceof NodeList) return true;
            if (typeof CSSStyleDeclaration !== 'undefined' && v instanceof CSSStyleDeclaration) return true;
          } catch (_) {}
          return false;
        };
    """

    /**
     * Step 1: Scans window properties, detects non-standard application objects,
     * inventories Canvas / WebGL / WebGPU rendering contexts, and samples rAF cadence.
     */
    fun buildInspectGlobalScopeScript(): String = """
        (function() {
          try {
            $SHARED_HELPERS
            const std = __getStandardGlobals();
            const customObjects = [];
            const detectedContexts = [];
            const keys = Object.getOwnPropertyNames(window);

            for (let i = 0; i < keys.length; i++) {
              const k = keys[i];
              if (std.has(k) || k.startsWith('webkit') || k.startsWith('on')) continue;
              let val;
              try { val = window[k]; } catch (_) { continue; }
              if (val === undefined || val === null) continue;
              const t = typeof val;
              if (t === 'object' || t === 'function') {
                let ctor = '';
                let propCount = 0;
                try { ctor = val.constructor && val.constructor.name ? val.constructor.name : t; } catch (_) { ctor = t; }
                try { propCount = Object.keys(val).length; } catch (_) {}
                if (customObjects.length < 40) {
                  customObjects.push({ key: k, type: t, constructor: ctor, keys: propCount });
                }
                try {
                  if (typeof WebGL2RenderingContext !== 'undefined' && val instanceof WebGL2RenderingContext) {
                    detectedContexts.push({ source: 'window.' + k, kind: 'WebGL2' });
                  } else if (typeof WebGLRenderingContext !== 'undefined' && val instanceof WebGLRenderingContext) {
                    detectedContexts.push({ source: 'window.' + k, kind: 'WebGL1' });
                  } else if (typeof CanvasRenderingContext2D !== 'undefined' && val instanceof CanvasRenderingContext2D) {
                    detectedContexts.push({ source: 'window.' + k, kind: 'Canvas2D' });
                  }
                } catch (_) {}
              }
            }

            const canvases = [];
            const domCanvases = document.querySelectorAll('canvas');
            for (let c = 0; c < domCanvases.length && c < 12; c++) {
              const el = domCanvases[c];
              const rect = el.getBoundingClientRect();
              let ctxHint = 'unknown';
              try {
                if (el.__webgl || el.gl) ctxHint = 'WebGL';
                else if (el.getContext) {
                  // Only check existing context signature on already-sized canvases
                  if (el.width > 0 && el.height > 0) {
                    const gl2 = el.getContext('webgl2');
                    if (gl2) ctxHint = 'WebGL2';
                    else {
                      const gl1 = el.getContext('webgl');
                      if (gl1) ctxHint = 'WebGL1';
                      else if (el.getContext('2d')) ctxHint = 'Canvas2D';
                    }
                  }
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

            const reportScope = function(rafDeltaMs, estimatedFps) {
              __emit('SCOPE', {
                stage: 'GLOBAL_SCOPE_INSPECTED',
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
                globalContexts: detectedContexts,
                customObjectCount: customObjects.length,
                customObjects: customObjects.slice(0, 20)
              });
            };

            if (typeof requestAnimationFrame === 'function') {
              let done = false;
              const fallbackTimer = setTimeout(function() {
                if (!done) { done = true; reportScope(null, null); }
              }, 350);
              requestAnimationFrame(function(t1) {
                requestAnimationFrame(function(t2) {
                  if (done) return;
                  done = true;
                  clearTimeout(fallbackTimer);
                  const dt = Math.max(0.1, t2 - t1);
                  const fps = Math.min(240, Math.round(1000 / dt));
                  reportScope(Number(dt.toFixed(2)), fps);
                });
              });
            } else {
              reportScope(null, null);
            }
          } catch (err) {
            try {
              if (window.TelemetryBridge) {
                window.TelemetryBridge.logTelemetry('ERR', 'Step 1 Scope error: ' + String(err));
              }
            } catch (_) {}
          }
        })();
    """.trimIndent()

    /**
     * Step 2A: Captures a bounded baseline snapshot of finite numeric properties and
     * numeric vector arrays across non-standard application objects.
     */
    fun buildCaptureBaselineScript(): String = """
        (function() {
          try {
            $SHARED_HELPERS
            const std = __getStandardGlobals();
            const baseline = Object.create(null);
            const visited = new WeakSet();
            let metricCount = 0;
            let objectsScanned = 0;
            const MAX_DEPTH = 5;
            const MAX_METRICS = 6000;
            const MAX_OBJECTS = 2500;

            const isNumericTypedArray = function(v) {
              return ArrayBuffer.isView(v) && !(v instanceof DataView) && v.length >= 2 && v.length <= 16;
            };

            const walk = function(obj, path, depth) {
              if (!obj || typeof obj !== 'object') return;
              if (depth > MAX_DEPTH || metricCount >= MAX_METRICS || objectsScanned >= MAX_OBJECTS) return;
              if (__isDomOrWindow(obj) || visited.has(obj)) return;
              visited.add(obj);
              objectsScanned++;

              if (Array.isArray(obj) || isNumericTypedArray(obj)) {
                const len = Math.min(obj.length, 16);
                let allNumeric = len >= 2;
                for (let i = 0; i < len; i++) {
                  const item = obj[i];
                  if (typeof item !== 'number' || !Number.isFinite(item)) {
                    allNumeric = false;
                    break;
                  }
                }
                if (allNumeric) {
                  for (let i = 0; i < len && metricCount < MAX_METRICS; i++) {
                    baseline[path + '[' + i + ']'] = obj[i];
                    metricCount++;
                  }
                  return;
                }
                if (Array.isArray(obj)) {
                  const maxIdx = Math.min(obj.length, 24);
                  for (let i = 0; i < maxIdx && objectsScanned < MAX_OBJECTS; i++) {
                    let elem;
                    try { elem = obj[i]; } catch (_) { continue; }
                    if (elem && typeof elem === 'object') {
                      walk(elem, path + '[' + i + ']', depth + 1);
                    }
                  }
                }
                return;
              }

              let ownKeys;
              try { ownKeys = Object.keys(obj); } catch (_) { return; }
              const limit = Math.min(ownKeys.length, 80);
              for (let i = 0; i < limit; i++) {
                if (metricCount >= MAX_METRICS || objectsScanned >= MAX_OBJECTS) break;
                const k = ownKeys[i];
                if (k.startsWith('__wvDiag')) continue;
                let v;
                try { v = obj[k]; } catch (_) { continue; }
                const nextPath = path + '.' + k;
                if (typeof v === 'number' && Number.isFinite(v)) {
                  baseline[nextPath] = v;
                  metricCount++;
                } else if (v && typeof v === 'object') {
                  walk(v, nextPath, depth + 1);
                }
              }
            };

            const rootKeys = Object.getOwnPropertyNames(window);
            const rootApps = [];
            for (let i = 0; i < rootKeys.length; i++) {
              const k = rootKeys[i];
              if (std.has(k) || k.startsWith('webkit') || k.startsWith('on')) continue;
              let v;
              try { v = window[k]; } catch (_) { continue; }
              if (v && typeof v === 'object' && !__isDomOrWindow(v)) {
                rootApps.push(k);
                walk(v, 'window.' + k, 1);
              }
            }

            window.__wvDiagState = window.__wvDiagState || {};
            window.__wvDiagState.baseline = baseline;
            window.__wvDiagState.baselineTimeMs = performance.now();
            window.__wvDiagState.rootApps = rootApps;

            __emit('DELTA', {
              stage: 'BASELINE_CAPTURED',
              rootObjectCount: rootApps.length,
              objectsScanned: objectsScanned,
              metricCount: metricCount,
              rootsPreview: rootApps.slice(0, 12)
            });
          } catch (err) {
            try {
              if (window.TelemetryBridge) {
                window.TelemetryBridge.logTelemetry('ERR', 'Step 2A Baseline error: ' + String(err));
              }
            } catch (_) {}
          }
        })();
    """.trimIndent()

    /**
     * Step 2B: Evaluates current numeric values at baseline paths, filters out timestamps
     * and noise, and isolates coordinate / motion variables with exact paths and live values.
     */
    fun buildComputeDeltaScript(): String = """
        (function() {
          try {
            $SHARED_HELPERS
            const state = window.__wvDiagState;
            if (!state || !state.baseline) {
              __emit('DELTA', {
                stage: 'BASELINE_MISSING',
                message: 'No baseline snapshot found. Run Step 2A first, move or interact in the web view, then tap 2B.'
              });
              return;
            }

            const baseline = state.baseline;
            const resolvePath = function(path) {
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

            const timePattern = /(time|timestamp|elapsed|clock|frame|tick|now|date|epoch|ms|fps|ping|latency)/i;
            const coordPattern = /(\.(x|y|z|w|vx|vy|vz|rx|ry|rz|yaw|pitch|roll|lat|lng|lon|left|top|pos|position|coord|camera|player|target|offset|scroll)|\[[0-3]\]$)/i;

            const candidates = [];
            const keys = Object.keys(baseline);
            for (let i = 0; i < keys.length; i++) {
              const p = keys[i];
              const baseVal = baseline[p];
              const curVal = resolvePath(p);
              if (typeof curVal !== 'number' || !Number.isFinite(curVal)) continue;
              const diff = curVal - baseVal;
              const absDiff = Math.abs(diff);
              if (absDiff < 0.0001 || absDiff > 10000) continue;
              if (Math.abs(curVal) > 1e11) continue; // Epoch ms guard
              if (timePattern.test(p)) continue;

              const score = (coordPattern.test(p) ? 100 : 10) + (absDiff <= 250 ? 25 : 0);
              candidates.push({
                path: p,
                baseline: Number(baseVal.toFixed(4)),
                current: Number(curVal.toFixed(4)),
                delta: Number(diff.toFixed(4)),
                score: score
              });
            }

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

            state.isolatedPaths = top;
            const elapsedMs = Math.round(performance.now() - (state.baselineTimeMs || performance.now()));

            __emit('DELTA', {
              stage: 'DELTA_COMPUTED',
              elapsedMs: elapsedMs,
              comparedMetrics: keys.length,
              totalChanged: candidates.length,
              deltas: top
            });
          } catch (err) {
            try {
              if (window.TelemetryBridge) {
                window.TelemetryBridge.logTelemetry('ERR', 'Step 2B Delta error: ' + String(err));
              }
            } catch (_) {}
          }
        })();
    """.trimIndent()

    /**
     * Step 3: Locates entity collections near isolated coordinate variables (or active app objects),
     * extracts bounding boxes / world positions, and tests projecting 3D coordinates to 2D canvas
     * coordinates (screenX, screenY).
     */
    fun buildScanEntitiesScript(): String = """
        (function() {
          try {
            $SHARED_HELPERS
            const state = window.__wvDiagState || {};
            const isolated = Array.isArray(state.isolatedPaths) ? state.isolatedPaths : [];

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
              if (!obj || typeof obj !== 'object') return null;
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
                if ((Array.isArray(obj.position) || ArrayBuffer.isView(obj.position)) && obj.position.length >= 2) {
                  return {
                    x: Number(obj.position[0]) || 0,
                    y: Number(obj.position[1]) || 0,
                    z: obj.position.length >= 3 ? (Number(obj.position[2]) || 0) : 0
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

            // Search for a 4x4 View-Projection matrix and entity arrays near isolated roots or custom globals
            const rootsToInspect = [];
            const seenRoots = new Set();
            for (let i = 0; i < isolated.length; i++) {
              const seg = isolated[i].path.replace(/^window\./, '').split(/[.\[]/)[0];
              if (seg && !seenRoots.has(seg)) {
                seenRoots.add(seg);
                rootsToInspect.push(seg);
              }
            }
            if (rootsToInspect.length === 0) {
              const std = __getStandardGlobals();
              const wKeys = Object.getOwnPropertyNames(window);
              for (let i = 0; i < wKeys.length && rootsToInspect.length < 20; i++) {
                const k = wKeys[i];
                if (!std.has(k) && !k.startsWith('webkit') && !k.startsWith('on')) {
                  rootsToInspect.push(k);
                }
              }
            }

            let vpMatrix = null;
            let matrixSource = 'pinhole_fallback';
            const entities = [];
            const visited = new WeakSet();
            let nodesVisited = 0;

            const isMat4 = function(v) {
              if (!v || typeof v !== 'object') return false;
              const arr = Array.isArray(v.elements) || ArrayBuffer.isView(v.elements) ? v.elements : v;
              if (!(Array.isArray(arr) || ArrayBuffer.isView(arr)) || arr.length !== 16) return false;
              for (let i = 0; i < 16; i++) {
                if (typeof arr[i] !== 'number' || !Number.isFinite(arr[i])) return false;
              }
              return true;
            };

            const scan = function(obj, path, depth) {
              if (!obj || typeof obj !== 'object' || depth > 5 || nodesVisited > 1800 || entities.length >= 25) return;
              if (__isDomOrWindow(obj) || visited.has(obj)) return;
              visited.add(obj);
              nodesVisited++;

              if (Array.isArray(obj)) {
                const len = Math.min(obj.length, 40);
                for (let i = 0; i < len && entities.length < 25; i++) {
                  let item;
                  try { item = obj[i]; } catch (_) { continue; }
                  const vec = extractVec3(item);
                  if (vec) {
                    const label = (item.name || item.id || item.type || (item.constructor && item.constructor.name) || 'Entity');
                    entities.push({
                      path: path + '[' + i + ']',
                      label: String(label).slice(0, 32),
                      world: vec,
                      bounds: extractBounds(item)
                    });
                  } else if (item && typeof item === 'object') {
                    scan(item, path + '[' + i + ']', depth + 1);
                  }
                }
                return;
              }

              let keys;
              try { keys = Object.keys(obj); } catch (_) { return; }
              const limit = Math.min(keys.length, 60);
              for (let i = 0; i < limit && nodesVisited <= 1800 && entities.length < 25; i++) {
                const k = keys[i];
                if (k.startsWith('__wvDiag')) continue;
                let v;
                try { v = obj[k]; } catch (_) { continue; }
                const nextPath = path + '.' + k;

                if (!vpMatrix && /(viewProjection|projectionMatrix|mvp|vpMatrix)/i.test(k) && isMat4(v)) {
                  const raw = Array.isArray(v.elements) || ArrayBuffer.isView(v.elements) ? v.elements : v;
                  vpMatrix = Array.from(raw);
                  matrixSource = nextPath;
                }
                if (v && typeof v === 'object') {
                  scan(v, nextPath, depth + 1);
                }
              }
            };

            for (let i = 0; i < rootsToInspect.length; i++) {
              const rk = rootsToInspect[i];
              try {
                const rv = window[rk];
                if (rv && typeof rv === 'object') scan(rv, 'window.' + rk, 1);
              } catch (_) {}
            }

            // Reference focus coordinate from Step 2B if available
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
                const ndcX = cx * invW;
                const ndcY = cy * invW;
                const sx = (ndcX * 0.5 + 0.5) * vpWidth;
                const sy = (-ndcY * 0.5 + 0.5) * vpHeight;
                return {
                  screenX: Number(sx.toFixed(1)),
                  screenY: Number(sy.toFixed(1)),
                  inViewport: sx >= 0 && sx <= vpWidth && sy >= 0 && sy <= vpHeight && cw > 0
                };
              }
              // Deterministic pinhole perspective projection fallback relative to focus point
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

            __emit('ENTITY', {
              stage: 'ENTITIES_SCANNED',
              viewport: { width: Math.round(vpWidth), height: Math.round(vpHeight) },
              projectionMode: matrixSource,
              entityCount: projectedEntities.length,
              entities: projectedEntities
            });
          } catch (err) {
            try {
              if (window.TelemetryBridge) {
                window.TelemetryBridge.logTelemetry('ERR', 'Step 3 Entity scan error: ' + String(err));
              }
            } catch (_) {}
          }
        })();
    """.trimIndent()

    /**
     * Step 5: Collects live viewport and DOM rendering metrics and passes them through
     * TelemetryBridge.exportReport(jsonContent) for final JSON file assembly.
     */
    fun buildCollectViewportMetricsScript(): String = """
        (function() {
          try {
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
              memory: perfMem
            };
            if (window.TelemetryBridge && typeof window.TelemetryBridge.exportReport === 'function') {
              window.TelemetryBridge.exportReport(JSON.stringify(payload));
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
     * Safely evaluates a developer-entered manual JavaScript expression or statement block,
     * formatting output with circular reference protection and reporting execution latency.
     */
    fun buildManualEvalScript(rawCode: String): String {
        val escapedCodeLiteral = JSONObject.quote(rawCode)
        return """
            (function() {
              try {
                $SHARED_HELPERS
                const code = $escapedCodeLiteral;
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
                } else if (typeof Node !== 'undefined' && result instanceof Node) {
                  formatted = '[DOMNode ' + result.nodeName + (result.id ? '#' + result.id : '') + ']';
                } else if (typeof result === 'object') {
                  const seen = new WeakSet();
                  formatted = JSON.stringify(result, function(k, v) {
                    if (typeof v === 'bigint') return v.toString();
                    if (typeof v === 'function') return '[Function]';
                    if (v && typeof v === 'object') {
                      if (seen.has(v)) return '[Circular]';
                      seen.add(v);
                    }
                    return v;
                  }, 2);
                  if (formatted && formatted.length > 4000) {
                    formatted = formatted.slice(0, 4000) + '\n… [truncated]';
                  }
                } else {
                  formatted = String(result);
                }

                __emit('JS', {
                  stage: 'MANUAL_EVAL',
                  mode: mode,
                  durationMs: durationMs,
                  expression: code.slice(0, 140),
                  resultType: typeof result,
                  result: formatted
                });
              } catch (err) {
                try {
                  if (window.TelemetryBridge) {
                    window.TelemetryBridge.logTelemetry('ERR', 'JS Eval error: ' + (err && err.stack ? err.stack.slice(0, 600) : String(err)));
                  }
                } catch (_) {}
              }
            })();
        """.trimIndent()
    }
}
