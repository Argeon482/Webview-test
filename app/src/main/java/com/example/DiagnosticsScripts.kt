package com.example

import org.json.JSONObject

/**
 * Zero-freeze, filtered & player-anchored JavaScript diagnostic routines.
 *
 * Key optimizations:
 * 1. Zero-Freeze Step 1: Filters `Object.getOwnPropertyNames(window)` strictly by key name
 *    against standard DOM/Web API prefixes (`HTML*`, `SVG*`, `WebGL*`, `webkit*`, `on*`, `CSS*`,
 *    etc.) BEFORE touching any getter—narrowing 12,000+ browser keys to ~10–30 custom app globals
 *    in under 2 ms with zero frame drops.
 * 2. Player-Locked Step 2A / 2B + Live XYZ Polling: Captures a non-blocking chunked baseline
 *    over custom globals, isolates the exact moving player coordinate container path
 *    (`discoveredPlayerPath`), and streams live `X, Y, Z` updates to Kotlin every 250 ms.
 * 3. Anchored Step 3 Entity Discovery: Resolves `discoveredPlayerPath`, climbs 1–3 parent levels
 *    up the object tree, inspects sibling Arrays/Maps/Sets/dictionaries for nearby coordinate-bearing
 *    entities, and tests 3D->2D canvas projection (`screenX`, `screenY`).
 * 4. Circular/DOM/WebGL-Safe Serialization: Skips `window`, DOM nodes, functions, WebGL contexts,
 *    and circular references across all bridge calls.
 */
object DiagnosticsScripts {

    private const val SHARED_RUNTIME = """
        const __bridge = window.TelemetryBridge;
        const __state = (window.__wvDiagState = window.__wvDiagState || {
          scanSeq: 0,
          customGlobals: [],
          shallowPaths: [],
          baseline: null,
          baselineTimeMs: 0,
          isolatedPaths: [],
          discoveredPlayerPath: null,
          playerAxes: null,
          discoveredEntityArrayPath: null,
          entityProjectionMode: '',
          discoveredEntities: [],
          livePollId: null
        });

        const __KNOWN_ENGINE_GLOBALS = new Set([
          'THREE', 'BABYLON', 'PIXI', 'Phaser', 'pc', 'cc', 'Module', 'unityInstance',
          'Game', 'game', 'App', 'app', 'Engine', 'engine', 'Scene', 'scene',
          'World', 'world', 'Player', 'player', 'Client', 'client', 'Net', 'net',
          'Hero', 'hero', 'Actor', 'actor', 'Main', 'main', 'Core', 'core', 'State', 'state'
        ]);

        // Fast string-only filter eliminating 99%+ of WebIDL/DOM/Web API keys with ZERO getter reads
        const __WEB_API_PREFIX_RE = /^(HTML|SVG|WebGL|webkit|WebKit|on[a-z]|CSS|DOM|Audio|MIDI|RTC|IDB|XR|GPU|USB|HID|Bluetooth|Media|Speech|Presentation|Payment|Credential|Performance|Navigation|Animation|Font|Style|Event|Pointer|Touch|Mouse|Keyboard|Input|Wheel|Focus|UI|Text|Range|Selection|Node|Element|Attr|CharacterData|Document|Window|Screen|History|Location|Navigator|Storage|Crypto|Subtle|Canvas|Offscreen|Image|Path2D|Video|File|Blob|URL|FormData|Headers|Request|Response|Readable|Writable|Transform|Compression|Decompression|Abort|Custom|Shadow|Mutation|Resize|Intersection|Reporting|Broadcast|Message|Worker|Shared|Service|Cache|Cookie|Lock|WakeLock|Permissions|Geolocation|Sensor|Clipboard|Notification|Push|Sync|Background|VisualViewport|VirtualKeyboard|Launch|Scheduler|Trusted|Task|Atomics|Reflect|Intl|Proxy|BigInt|Float|Int|Uint|Array|DataView|Map|Set|Weak|Promise|Symbol|RegExp|Error|Eval|Reference|Syntax|Type|URI|Aggregate|Finalization|WebAssembly|XML|XPath|XSLT|VTTCue|Track|TimeRanges|Validity|BarProp|CryptoKey|External|Gamepad|Idle|Ink|Layout|MathML|NDEF|NFC|OTPCredential|Periodic|PictureInPicture|PublicKey|Sanitizer|Serial|SourceBuffer|Toggle|ViewTransition|Worklet)/;
        const __WEB_API_SUFFIX_RE = /^[A-Z][A-Za-z0-9]*(Event|Element|Error|List|Map|Set|Array|Stream|Controller|Reader|Writer|Observer|Entry|Info|Config|Options|Init|Prototype|Interface|Provider|Registry|Target|Source|Buffer|Context|Encoder|Decoder|Track|Recorder|Capabilities|Settings|Metrics|Rect|Point|Matrix|Quad|Filter|Node|Param|Collection|Declaration|Rule|Sheet|Style|Channel|Port|Store|Transaction|Cursor|Request|Response|Key|Handle|Device|Adapter|Queue|Pipeline|Shader|Texture|Sampler|BindGroup|Command)$/;

        const __STANDARD_EXACT = new Set([
          'window','self','globalThis','document','name','location','customElements','history',
          'navigation','locationbar','menubar','personalbar','scrollbars','statusbar','toolbar',
          'status','closed','frames','length','top','opener','parent','frameElement','navigator',
          'origin','external','screen','innerWidth','innerHeight','scrollX','pageXOffset',
          'scrollY','pageYOffset','visualViewport','screenX','screenY','outerWidth','outerHeight',
          'devicePixelRatio','event','clientInformation','screenLeft','screenTop','styleMedia',
          'trustedTypes','performance','crypto','indexedDB','sessionStorage','localStorage',
          'isSecureContext','crossOriginIsolated','scheduler','alert','atob','blur','btoa',
          'cancelAnimationFrame','cancelIdleCallback','captureEvents','clearInterval','clearTimeout',
          'close','confirm','createImageBitmap','fetch','find','focus','getComputedStyle',
          'getSelection','matchMedia','moveBy','moveTo','open','postMessage','print','prompt',
          'queueMicrotask','releaseEvents','reportError','requestAnimationFrame',
          'requestIdleCallback','resizeBy','resizeTo','scroll','scrollBy','scrollTo','setInterval',
          'setTimeout','stop','structuredClone','chrome','caches','cookieStore','speechSynthesis',
          'credentialless','originAgentCluster','fence','launchQueue','sharedStorage',
          'documentPictureInPicture','fetchLater','getScreenDetails','queryLocalFonts',
          'showDirectoryPicker','showOpenFilePicker','showSaveFilePicker',
          'TelemetryBridge','__wvDiagState','console','undefined','NaN','Infinity',
          'Object','Function','Boolean','Number','String','Date','JSON','Math',
          'parseInt','parseFloat','isNaN','isFinite','decodeURI','decodeURIComponent',
          'encodeURI','encodeURIComponent','escape','unescape','eval'
        ]);

        const __HEURISTIC_KEY_RE = /(player|actor|scene|game|pos|coord|hero|net|camera|cam|entity|entities|world|avatar|char|unit|mob|monster|npc|enemy|enemies|state|transform|vec|loc|vel|rot)/i;
        const __TIME_KEY_RE = /(time|timestamp|elapsed|clock|frame|tick|now|date|epoch|ms|fps|ping|latency|delta|dt)/i;
        const __COORD_AXIS_RE = /(\.(x|y|z|w|vx|vy|vz|px|py|pz|lat|lng|lon|left|top|tx|ty|tz)|\[[0-2]\]$)/i;
        const __CHUNK_BUDGET_MS = 4.0;

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

        const __safeSerialize = function(value, indent) {
          const seen = typeof WeakSet !== 'undefined' ? new WeakSet() : null;
          try {
            const json = JSON.stringify(value, function(key, val) {
              if (val === undefined) return undefined;
              if (val === null) return null;
              const t = typeof val;
              if (t === 'function' || t === 'symbol') return undefined;
              if (t === 'bigint') return val.toString();
              if (t === 'number' && !Number.isFinite(val)) return null;
              if (t === 'object') {
                if (__isSpecialHostObject(val)) return __describeSkippedObject(val);
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
          } catch (_) {}
        };

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

        /**
         * Instant (<2 ms) custom global filter: narrows window's own properties strictly by
         * key string without invoking getters on standard Web APIs.
         */
        const __filterCustomWindowKeys = function() {
          const rawKeys = Object.getOwnPropertyNames(window);
          const filtered = [];
          for (let i = 0; i < rawKeys.length; i++) {
            const k = rawKeys[i];
            if (__KNOWN_ENGINE_GLOBALS.has(k)) {
              filtered.push(k);
              continue;
            }
            if (__STANDARD_EXACT.has(k)) continue;
            if (__WEB_API_PREFIX_RE.test(k)) continue;
            if (__WEB_API_SUFFIX_RE.test(k)) continue;
            filtered.push(k);
          }
          return { totalWindowKeys: rawKeys.length, customKeys: filtered };
        };

        const __isShortNumericVector = function(v) {
          if (!v || typeof v !== 'object') return false;
          if (!(Array.isArray(v) || (ArrayBuffer.isView(v) && !(v instanceof DataView)))) return false;
          if (v.length < 2 || v.length > 4) return false;
          for (let i = 0; i < v.length; i++) {
            if (typeof v[i] !== 'number' || !Number.isFinite(v[i])) return false;
          }
          return true;
        };

        const __parsePathTokens = function(path) {
          if (!path || typeof path !== 'string') return [];
          const clean = path.replace(/^window\./, '');
          return clean.match(/[^.\[\]]+/g) || [];
        };

        const __resolvePath = function(path) {
          try {
            const tokens = __parsePathTokens(path);
            if (tokens.length === 0) return undefined;
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

        const __buildPathFromTokens = function(tokens) {
          let out = 'window';
          for (let i = 0; i < tokens.length; i++) {
            const t = tokens[i];
            if (/^\d+$/.test(t)) out += '[' + t + ']';
            else out += '.' + t;
          }
          return out;
        };

        const __extractVec3 = function(obj) {
          if (!obj || typeof obj !== 'object' || __isSpecialHostObject(obj)) return null;
          try {
            if (typeof obj.x === 'number' && Number.isFinite(obj.x) && typeof obj.y === 'number' && Number.isFinite(obj.y)) {
              return { x: obj.x, y: obj.y, z: (typeof obj.z === 'number' && Number.isFinite(obj.z)) ? obj.z : 0 };
            }
            if (typeof obj.x === 'number' && Number.isFinite(obj.x) && typeof obj.z === 'number' && Number.isFinite(obj.z)) {
              return { x: obj.x, y: (typeof obj.y === 'number' && Number.isFinite(obj.y)) ? obj.y : 0, z: obj.z };
            }
            if (__isShortNumericVector(obj)) {
              return { x: obj[0], y: obj[1], z: obj.length >= 3 ? obj[2] : 0 };
            }
            const nestedKeys = ['position', 'pos', 'coords', 'coord', 'location', 'loc', 'transform'];
            for (let i = 0; i < nestedKeys.length; i++) {
              const sub = obj[nestedKeys[i]];
              if (!sub || typeof sub !== 'object' || __isSpecialHostObject(sub)) continue;
              if (typeof sub.x === 'number' && Number.isFinite(sub.x) && typeof sub.y === 'number' && Number.isFinite(sub.y)) {
                return { x: sub.x, y: sub.y, z: (typeof sub.z === 'number' && Number.isFinite(sub.z)) ? sub.z : 0 };
              }
              if (__isShortNumericVector(sub)) {
                return { x: sub[0], y: sub[1], z: sub.length >= 3 ? sub[2] : 0 };
              }
            }
          } catch (_) {}
          return null;
        };

        const __readLivePlayerVec = function() {
          const pPath = __state.discoveredPlayerPath;
          if (!pPath) return null;
          const target = __resolvePath(pPath);
          const vec = __extractVec3(target);
          if (vec) return vec;
          const axes = __state.playerAxes;
          if (axes) {
            const rx = axes.x ? __resolvePath(axes.x) : 0;
            const ry = axes.y ? __resolvePath(axes.y) : 0;
            const rz = axes.z ? __resolvePath(axes.z) : 0;
            if (typeof rx === 'number' || typeof ry === 'number' || typeof rz === 'number') {
              return {
                x: typeof rx === 'number' && Number.isFinite(rx) ? rx : 0,
                y: typeof ry === 'number' && Number.isFinite(ry) ? ry : 0,
                z: typeof rz === 'number' && Number.isFinite(rz) ? rz : 0
              };
            }
          }
          return null;
        };

        const __startLivePlayerPoll = function() {
          try {
            if (__state.livePollId !== null) {
              clearInterval(__state.livePollId);
              __state.livePollId = null;
            }
            const pushNow = function() {
              try {
                const v = __readLivePlayerVec();
                if (v && __bridge && typeof __bridge.updatePlayerCoords === 'function') {
                  __bridge.updatePlayerCoords(__state.discoveredPlayerPath, v.x, v.y, v.z);
                }
              } catch (_) {}
            };
            pushNow();
            __state.livePollId = setInterval(pushNow, 250);
          } catch (_) {}
        };
    """

    /**
     * Step 1: Zero-Freeze Filtered Scope Check (<2 ms).
     * Filters window keys by string name BEFORE touching any getters, leaving only the ~10-30
     * custom game/app globals plus active <canvas> / WebGL contexts.
     */
    fun buildInspectGlobalScopeScript(): String = """
        (function() {
          try {
            $SHARED_RUNTIME
            const mySeq = ++__state.scanSeq;
            const t0 = performance.now();
            __setStatus('RUNNING', 'Step 1: Filtering custom globals…');

            const filterRes = __filterCustomWindowKeys();
            const customKeys = filterRes.customKeys;
            const customObjects = [];
            const globalContexts = [];

            const limit = Math.min(customKeys.length, 60);
            for (let i = 0; i < limit; i++) {
              const k = customKeys[i];
              let val;
              try { val = window[k]; } catch (_) { continue; }
              if (val === undefined || val === null) continue;
              const t = typeof val;
              if (t === 'object' || t === 'function') {
                if (__isSpecialHostObject(val)) {
                  const desc = __describeSkippedObject(val);
                  if (desc.startsWith('[Context:')) {
                    globalContexts.push({ source: 'window.' + k, kind: desc });
                  }
                  continue;
                }
                let ctor = t;
                let keyCount = 0;
                try { ctor = val.constructor && val.constructor.name ? val.constructor.name : t; } catch (_) {}
                try { if (t === 'object') keyCount = Object.keys(val).length; } catch (_) {}
                customObjects.push({
                  key: k,
                  path: 'window.' + k,
                  type: t,
                  constructor: ctor,
                  keyCount: keyCount
                });
              }
            }

            const canvases = [];
            const domCanvases = document.querySelectorAll('canvas');
            for (let c = 0; c < domCanvases.length && c < 8; c++) {
              const el = domCanvases[c];
              const rect = el.getBoundingClientRect();
              let ctxHint = 'active';
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

            const durationMs = Number((performance.now() - t0).toFixed(2));
            __state.customGlobals = customObjects.map(function(o) { return o.key; });
            __state.shallowPaths = customObjects.map(function(o) { return o.path; });

            if (mySeq !== __state.scanSeq) return;
            __setStatus('COMPLETED', 'Step 1: ' + customObjects.length + ' globals in ' + durationMs + 'ms');

            __emit('SCOPE', {
              stage: 'GLOBAL_SCOPE_INSPECTED',
              durationMs: durationMs,
              totalWindowKeysFiltered: filterRes.totalWindowKeys,
              customGlobalCount: customObjects.length,
              customGlobals: customObjects.slice(0, 30),
              canvasCount: domCanvases.length,
              canvases: canvases,
              globalContexts: globalContexts,
              viewport: {
                width: window.innerWidth,
                height: window.innerHeight,
                dpr: window.devicePixelRatio || 1
              }
            });
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
     * Step 2A: Non-blocking, chunked shallow baseline capture over the filtered custom
     * globals (depth 1-3 on heuristic game/player containers, strictly capped & yielding every ~4 ms).
     */
    fun buildCaptureBaselineScript(): String = """
        (function() {
          try {
            $SHARED_RUNTIME
            const mySeq = ++__state.scanSeq;
            __setStatus('RUNNING', 'Step 2A: Capturing standing baseline…');

            let rootKeys = Array.isArray(__state.customGlobals) && __state.customGlobals.length > 0
              ? __state.customGlobals.slice(0, 40)
              : __filterCustomWindowKeys().customKeys.slice(0, 40);

            const baseline = Object.create(null);
            const visited = new WeakSet();
            const queue = [];
            for (let i = 0; i < rootKeys.length; i++) {
              const k = rootKeys[i];
              let v;
              try { v = window[k]; } catch (_) { continue; }
              if (v && typeof v === 'object' && !__isSpecialHostObject(v)) {
                queue.push({ obj: v, path: 'window.' + k, depth: 1 });
              }
            }

            let metricCount = 0;
            let objectsScanned = 0;
            let chunksExecuted = 0;
            const MAX_DEPTH = 3;
            const MAX_METRICS = 2500;
            const MAX_OBJECTS = 600;

            const recordNum = function(path, val) {
              if (metricCount >= MAX_METRICS) return;
              if (typeof val === 'number' && Number.isFinite(val)) {
                baseline[path] = val;
                metricCount++;
              }
            };

            const processChunk = function() {
              if (mySeq !== __state.scanSeq) return;
              chunksExecuted++;
              const sliceStart = performance.now();

              while (queue.length > 0 && metricCount < MAX_METRICS && objectsScanned < MAX_OBJECTS && (performance.now() - sliceStart) < __CHUNK_BUDGET_MS) {
                const item = queue.shift();
                const obj = item.obj;
                if (!obj || typeof obj !== 'object' || __isSpecialHostObject(obj) || visited.has(obj)) continue;
                visited.add(obj);
                objectsScanned++;

                if (__isShortNumericVector(obj)) {
                  for (let vi = 0; vi < obj.length; vi++) {
                    recordNum(item.path + '[' + vi + ']', obj[vi]);
                  }
                  continue;
                }

                if (Array.isArray(obj)) {
                  const arrLim = Math.min(obj.length, 16);
                  for (let ai = 0; ai < arrLim && item.depth < MAX_DEPTH; ai++) {
                    let elem;
                    try { elem = obj[ai]; } catch (_) { continue; }
                    if (elem && typeof elem === 'object' && !__isSpecialHostObject(elem)) {
                      queue.push({ obj: elem, path: item.path + '[' + ai + ']', depth: item.depth + 1 });
                    }
                  }
                  continue;
                }

                let keys;
                try { keys = Object.keys(obj); } catch (_) { continue; }
                const keyLimit = Math.min(keys.length, 56);
                for (let i = 0; i < keyLimit && metricCount < MAX_METRICS; i++) {
                  const k = keys[i];
                  if (k.startsWith('__wvDiag')) continue;
                  let v;
                  try { v = obj[k]; } catch (_) { continue; }
                  const nextPath = item.path + '.' + k;

                  if (typeof v === 'number' && Number.isFinite(v)) {
                    recordNum(nextPath, v);
                  } else if (__isShortNumericVector(v)) {
                    for (let vi = 0; vi < v.length; vi++) {
                      recordNum(nextPath + '[' + vi + ']', v[vi]);
                    }
                  } else if (v && typeof v === 'object' && !__isSpecialHostObject(v) && item.depth < MAX_DEPTH) {
                    const isLikelyCoordContainer = __HEURISTIC_KEY_RE.test(k) || item.depth === 1;
                    if (isLikelyCoordContainer) {
                      queue.push({ obj: v, path: nextPath, depth: item.depth + 1 });
                    }
                  }
                }
              }

              if (queue.length > 0 && metricCount < MAX_METRICS && objectsScanned < MAX_OBJECTS) {
                __setStatus('RUNNING', 'Step 2A: ' + metricCount + ' values…');
                __yieldFrame(processChunk);
              } else {
                __state.baseline = baseline;
                __state.baselineTimeMs = performance.now();
                __setStatus('COMPLETED', '2A Captured (' + metricCount + ' values)');
                __emit('DELTA', {
                  stage: 'BASELINE_CAPTURED',
                  chunksExecuted: chunksExecuted,
                  rootObjectCount: rootKeys.length,
                  objectsScanned: objectsScanned,
                  metricCount: metricCount
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
     * Step 2B: Isolates the exact player coordinate container (`discoveredPlayerPath`) by
     * comparing standing baseline against post-movement values, grouping co-moving X/Y/Z axes
     * on the same parent object, and starting live 250ms X/Y/Z telemetry updates.
     */
    fun buildComputeDeltaScript(): String = """
        (function() {
          try {
            $SHARED_RUNTIME
            const mySeq = ++__state.scanSeq;
            if (!__state.baseline) {
              __setStatus('IDLE', 'Run Step 2A while standing still first');
              __emit('DELTA', {
                stage: 'BASELINE_MISSING',
                message: 'No standing baseline found. Stand still in-game and tap 2A first.'
              });
              return;
            }

            __setStatus('RUNNING', 'Step 2B: Isolating player coords…');
            const baseline = __state.baseline;
            const keys = Object.keys(baseline);
            const changed = [];
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

                const tokens = __parsePathTokens(p);
                const leaf = tokens.length > 0 ? tokens[tokens.length - 1] : '';
                const parentPath = tokens.length > 1
                  ? __buildPathFromTokens(tokens.slice(0, tokens.length - 1))
                  : p;

                const score =
                  (__COORD_AXIS_RE.test(p) ? 120 : 0) +
                  (__HEURISTIC_KEY_RE.test(p) ? 60 : 0) +
                  (absDiff <= 500 ? 25 : 0);

                changed.push({
                  path: p,
                  parentPath: parentPath,
                  leaf: leaf,
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
                // Group changed numeric properties by parent container to find co-moving X/Y/Z vectors
                const groups = Object.create(null);
                for (let i = 0; i < changed.length; i++) {
                  const c = changed[i];
                  if (!groups[c.parentPath]) {
                    groups[c.parentPath] = { parentPath: c.parentPath, items: [], score: 0 };
                  }
                  const g = groups[c.parentPath];
                  g.items.push(c);
                  g.score += c.score;
                }

                const rankedGroups = Object.keys(groups).map(function(gp) {
                  const g = groups[gp];
                  // Bonus when 2 or 3 coordinate axes moved together on the same container
                  const multiAxisBonus = g.items.length >= 2 && g.items.length <= 4 ? 180 : 0;
                  const playerHintBonus = /(player|hero|avatar|self|local|char|pos|position|coord)/i.test(gp) ? 100 : 0;
                  return {
                    parentPath: g.parentPath,
                    items: g.items,
                    totalScore: g.score + multiAxisBonus + playerHintBonus
                  };
                });
                rankedGroups.sort(function(a, b) { return b.totalScore - a.totalScore; });

                changed.sort(function(a, b) {
                  if (b.score !== a.score) return b.score - a.score;
                  return Math.abs(a.delta) - Math.abs(b.delta);
                });

                const topDeltas = changed.slice(0, 20).map(function(item) {
                  return {
                    path: item.path,
                    baseline: item.baseline,
                    current: item.current,
                    delta: item.delta
                  };
                });

                let discoveredPlayerPath = null;
                let liveSample = null;
                if (rankedGroups.length > 0) {
                  const best = rankedGroups[0];
                  discoveredPlayerPath = best.parentPath;
                  const axes = { x: null, y: null, z: null };
                  for (let i = 0; i < best.items.length; i++) {
                    const it = best.items[i];
                    const lf = it.leaf.toLowerCase();
                    if (lf === 'x' || lf === '0' || lf === 'px' || lf === 'left') axes.x = it.path;
                    else if (lf === 'y' || lf === '1' || lf === 'py' || lf === 'top') axes.y = it.path;
                    else if (lf === 'z' || lf === '2' || lf === 'pz') axes.z = it.path;
                  }
                  if (!axes.x && best.items[0]) axes.x = best.items[0].path;
                  if (!axes.y && best.items[1]) axes.y = best.items[1].path;
                  if (!axes.z && best.items[2]) axes.z = best.items[2].path;

                  __state.discoveredPlayerPath = discoveredPlayerPath;
                  __state.playerAxes = axes;
                  liveSample = __readLivePlayerVec();
                  __startLivePlayerPoll();
                }

                __state.isolatedPaths = topDeltas;
                const elapsedMs = Math.round(performance.now() - (__state.baselineTimeMs || performance.now()));
                __setStatus(
                  discoveredPlayerPath ? 'COMPLETED' : 'IDLE',
                  discoveredPlayerPath ? ('Locked: ' + discoveredPlayerPath) : 'No movement detected'
                );

                __emit('DELTA', {
                  stage: 'DELTA_COMPUTED',
                  chunksExecuted: chunksExecuted,
                  elapsedMs: elapsedMs,
                  comparedMetrics: keys.length,
                  totalChanged: changed.length,
                  discoveredPlayerPath: discoveredPlayerPath,
                  liveSample: liveSample,
                  deltas: topDeltas
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
     * Step 3: Player-Anchored Entity Discovery.
     * Resolves `discoveredPlayerPath`, climbs 1-3 parent levels up the object tree, inspects all
     * sibling Arrays / Maps / Sets / plain-object collections for objects sharing coordinate fields
     * near the player's current coordinates, and projects them into 2D canvas screen pixels.
     */
    fun buildScanEntitiesScript(anchorPlayerPath: String?): String {
        val anchorLiteral = if (anchorPlayerPath.isNullOrBlank()) "null" else JSONObject.quote(anchorPlayerPath)
        return """
        (function() {
          try {
            $SHARED_RUNTIME
            const mySeq = ++__state.scanSeq;
            const anchorPath = $anchorLiteral || __state.discoveredPlayerPath;

            if (!anchorPath) {
              __setStatus('IDLE', 'Complete Step 2B first');
              __emit('ENTITY', {
                stage: 'ANCHOR_MISSING',
                message: 'No player coordinate path isolated yet. Complete Step 2A and 2B first.'
              });
              return;
            }

            __state.discoveredPlayerPath = anchorPath;
            __setStatus('RUNNING', 'Step 3: Climbing parent tree from player…');

            const playerObj = __resolvePath(anchorPath);
            const playerVec = __readLivePlayerVec() || { x: 0, y: 0, z: 0 };
            const anchorTokens = __parsePathTokens(anchorPath);

            // Build ancestor containers climbing 1, 2, and 3 parent levels up from discoveredPlayerPath
            const ancestors = [];
            for (let climb = 1; climb <= 3; climb++) {
              if (anchorTokens.length - climb < 1) break;
              const parentTokens = anchorTokens.slice(0, anchorTokens.length - climb);
              const parentPath = __buildPathFromTokens(parentTokens);
              const parentObj = __resolvePath(parentPath);
              if (parentObj && typeof parentObj === 'object' && !__isSpecialHostObject(parentObj)) {
                ancestors.push({ climb: climb, path: parentPath, obj: parentObj });
              }
            }

            // Locate primary canvas viewport
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

            const isMat4 = function(v) {
              if (!v || typeof v !== 'object') return false;
              const arr = Array.isArray(v.elements) || ArrayBuffer.isView(v.elements) ? v.elements : v;
              if (!(Array.isArray(arr) || ArrayBuffer.isView(arr)) || arr.length !== 16) return false;
              for (let i = 0; i < 16; i++) {
                if (typeof arr[i] !== 'number' || !Number.isFinite(arr[i])) return false;
              }
              return true;
            };

            const extractEntityRecord = function(item, itemPath) {
              if (!item || typeof item !== 'object' || __isSpecialHostObject(item)) return null;
              if (item === playerObj) return null;
              const vec = __extractVec3(item);
              if (!vec) return null;

              const dx = vec.x - playerVec.x;
              const dy = vec.y - playerVec.y;
              const dz = vec.z - playerVec.z;
              const dist = Math.sqrt(dx * dx + dy * dy + dz * dz);

              let sampleKeys = [];
              try { sampleKeys = Object.keys(item).slice(0, 10); } catch (_) {}
              const name = String(item.name || item.title || item.label || item.type || (item.constructor && item.constructor.name) || 'Entity').slice(0, 36);
              const id = String(item.id !== undefined ? item.id : (item.uid !== undefined ? item.uid : (item.uuid !== undefined ? item.uuid : ''))).slice(0, 36);

              return {
                name: name,
                id: id,
                path: itemPath,
                world: {
                  x: Number(vec.x.toFixed(3)),
                  y: Number(vec.y.toFixed(3)),
                  z: Number(vec.z.toFixed(3))
                },
                distanceToPlayer: Number(dist.toFixed(2)),
                sampleKeys: sampleKeys
              };
            };

            const evaluateCollection = function(coll, collPath) {
              if (!coll || typeof coll !== 'object' || __isSpecialHostObject(coll)) return null;
              const found = [];

              if (Array.isArray(coll)) {
                const len = Math.min(coll.length, 80);
                for (let i = 0; i < len && found.length < 30; i++) {
                  let el;
                  try { el = coll[i]; } catch (_) { continue; }
                  const rec = extractEntityRecord(el, collPath + '[' + i + ']');
                  if (rec) found.push(rec);
                }
              } else if (typeof Map !== 'undefined' && coll instanceof Map) {
                let count = 0;
                coll.forEach(function(val, mk) {
                  if (count++ >= 80 || found.length >= 30) return;
                  const rec = extractEntityRecord(val, collPath + '.get(' + JSON.stringify(String(mk)) + ')');
                  if (rec) found.push(rec);
                });
              } else if (typeof Set !== 'undefined' && coll instanceof Set) {
                let idx = 0;
                coll.forEach(function(val) {
                  if (idx >= 80 || found.length >= 30) { idx++; return; }
                  const rec = extractEntityRecord(val, collPath + '[set#' + idx + ']');
                  if (rec) found.push(rec);
                  idx++;
                });
              } else {
                // Plain object dictionary keyed by entity ID
                let keys;
                try { keys = Object.keys(coll); } catch (_) { return null; }
                if (keys.length < 2 || keys.length > 400) return null;
                const lim = Math.min(keys.length, 60);
                for (let i = 0; i < lim && found.length < 30; i++) {
                  const ek = keys[i];
                  let el;
                  try { el = coll[ek]; } catch (_) { continue; }
                  const rec = extractEntityRecord(el, collPath + '.' + ek);
                  if (rec) found.push(rec);
                }
              }

              if (found.length === 0) return null;
              found.sort(function(a, b) { return a.distanceToPlayer - b.distanceToPlayer; });
              const nearbyCount = found.filter(function(e) { return e.distanceToPlayer <= 2000; }).length;
              const nameBonus = /(entit|monster|mob|enemy|enemies|npc|actor|player|unit|object|children|sprite|char)/i.test(collPath) ? 40 : 0;
              return {
                collectionPath: collPath,
                entities: found,
                score: (found.length * 10) + (nearbyCount * 15) + nameBonus
              };
            };

            // Queue sibling properties across ancestors (1-3 levels up)
            const inspectionTasks = [];
            const seenObjs = new WeakSet();
            for (let a = 0; a < ancestors.length; a++) {
              const anc = ancestors[a];
              if (seenObjs.has(anc.obj)) continue;
              seenObjs.add(anc.obj);
              let keys = [];
              try { keys = Object.keys(anc.obj); } catch (_) {}
              const lim = Math.min(keys.length, 72);
              for (let i = 0; i < lim; i++) {
                const k = keys[i];
                if (k.startsWith('__wvDiag')) continue;
                inspectionTasks.push({ parent: anc.obj, key: k, path: anc.path + '.' + k, depth: 1 });
              }
            }

            let vpMatrix = null;
            let matrixSource = 'pinhole_viewport_fallback';
            const candidateCollections = [];
            let cursor = 0;
            let chunksExecuted = 0;

            const processChunk = function() {
              if (mySeq !== __state.scanSeq) return;
              chunksExecuted++;
              const sliceStart = performance.now();

              while (cursor < inspectionTasks.length && (performance.now() - sliceStart) < __CHUNK_BUDGET_MS) {
                const task = inspectionTasks[cursor++];
                let val;
                try { val = task.parent[task.key]; } catch (_) { continue; }
                if (!val || typeof val !== 'object' || __isSpecialHostObject(val)) continue;

                // Check for active camera/projection matrix in the same manager tree
                if (!vpMatrix && /(viewProjection|projectionMatrix|mvp|vpMatrix)/i.test(task.key) && isMat4(val)) {
                  const raw = Array.isArray(val.elements) || ArrayBuffer.isView(val.elements) ? val.elements : val;
                  vpMatrix = Array.from(raw);
                  matrixSource = task.path;
                }

                // Evaluate if this sibling is itself an entity collection
                const evalRes = evaluateCollection(val, task.path);
                if (evalRes) {
                  candidateCollections.push(evalRes);
                }

                // If depth 1 sibling looks like a scene/manager/camera object, inspect its direct properties (1 level)
                if (task.depth === 1 && !Array.isArray(val) && !seenObjs.has(val) &&
                    /(scene|world|manager|entity|entities|mob|monster|npc|actor|room|level|stage|camera|cam|render|net|state|players)/i.test(task.key)) {
                  seenObjs.add(val);
                  let subKeys = [];
                  try { subKeys = Object.keys(val); } catch (_) {}
                  const subLim = Math.min(subKeys.length, 32);
                  for (let j = 0; j < subLim; j++) {
                    inspectionTasks.push({ parent: val, key: subKeys[j], path: task.path + '.' + subKeys[j], depth: 2 });
                  }
                }
              }

              if (cursor < inspectionTasks.length) {
                __setStatus('RUNNING', 'Step 3: Scanning sibling containers…');
                __yieldFrame(processChunk);
              } else {
                candidateCollections.sort(function(a, b) { return b.score - a.score; });
                const bestColl = candidateCollections.length > 0 ? candidateCollections[0] : null;
                const rawEntities = bestColl ? bestColl.entities.slice(0, 20) : [];

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
                  const dx = w.x - playerVec.x;
                  const dy = w.y - playerVec.y;
                  const dz = (w.z - playerVec.z) + 10.0;
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

                const projected = rawEntities.map(function(ent) {
                  const proj = projectToScreen(ent.world);
                  return {
                    name: ent.name,
                    id: ent.id,
                    path: ent.path,
                    world: ent.world,
                    distanceToPlayer: ent.distanceToPlayer,
                    screenX: proj.screenX,
                    screenY: proj.screenY,
                    inViewport: proj.inViewport,
                    sampleKeys: ent.sampleKeys
                  };
                });

                const entityArrayPath = bestColl ? bestColl.collectionPath : null;
                __state.discoveredEntityArrayPath = entityArrayPath;
                __state.entityProjectionMode = matrixSource;
                __state.discoveredEntities = projected;

                __setStatus(
                  projected.length > 0 ? 'COMPLETED' : 'IDLE',
                  projected.length > 0
                    ? ('Step 3: ' + projected.length + ' entities in ' + entityArrayPath)
                    : 'Step 3: 0 sibling entities found'
                );

                __emit('ENTITY', {
                  stage: 'ENTITIES_SCANNED',
                  chunksExecuted: chunksExecuted,
                  anchoredPlayerPath: anchorPath,
                  ancestorsInspected: ancestors.map(function(a) { return a.path; }),
                  entityArrayPath: entityArrayPath,
                  alternateCollections: candidateCollections.slice(1, 5).map(function(c) {
                    return { path: c.collectionPath, count: c.entities.length };
                  }),
                  viewport: { width: Math.round(vpWidth), height: Math.round(vpHeight) },
                  projectionMode: matrixSource,
                  entityCount: projected.length,
                  entities: projected
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
    }

    /**
     * Step 4 Export Collector: Safely serializes live viewport dimensions, confirmed player path +
     * live coordinates, confirmed entity array path + samples, and passes JSON to exportReport().
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

            const livePlayer = __readLivePlayerVec();
            const payload = {
              url: location.href,
              title: document.title || '',
              readyState: document.readyState,
              userAgent: navigator.userAgent,
              viewport: {
                width: window.innerWidth,
                height: window.innerHeight,
                devicePixelRatio: window.devicePixelRatio || 1,
                scrollX: window.scrollX,
                scrollY: window.scrollY
              },
              canvases: canvases,
              discoveredPlayerPath: __state.discoveredPlayerPath || null,
              livePlayerSample: livePlayer,
              discoveredEntityArrayPath: __state.discoveredEntityArrayPath || null,
              entityProjectionMode: __state.entityProjectionMode || '',
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
     * Manual JS safe evaluator with circular/DOM/WebGL protection.
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
                    if (result === undefined) formatted = 'undefined';
                    else if (result === null) formatted = 'null';
                    else if (typeof result === 'function') formatted = '[Function ' + (result.name || 'anonymous') + ']';
                    else if (__isSpecialHostObject(result)) formatted = __describeSkippedObject(result);
                    else if (typeof result === 'object') {
                      formatted = __safeSerialize(result, 2);
                      if (formatted.length > 4000) formatted = formatted.slice(0, 4000) + '\n… [truncated]';
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
