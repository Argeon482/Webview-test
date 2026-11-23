package com.example

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.util.Locale

/**
 * Minimal key-value persistence abstraction so [SitePointerStore] can run on Android
 * [SharedPreferences] in production and on a pure in-memory map in fast JVM unit tests.
 */
interface PointerKeyValueStorage {
    fun getString(key: String): String?
    fun putString(key: String, value: String)
    fun remove(key: String)
}

class SharedPrefsPointerStorage(
    private val prefs: SharedPreferences
) : PointerKeyValueStorage {
    override fun getString(key: String): String? = prefs.getString(key, null)

    override fun putString(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }

    override fun remove(key: String) {
        prefs.edit().remove(key).apply()
    }
}

class InMemoryPointerStorage : PointerKeyValueStorage {
    private val map = LinkedHashMap<String, String>()
    override fun getString(key: String): String? = map[key]
    override fun putString(key: String, value: String) {
        map[key] = value
    }
    override fun remove(key: String) {
        map.remove(key)
    }
}

/**
 * Private on-device per-site persistence store for bookmarked WASM pointer chains.
 *
 * Keyed by normalized host (e.g. `universe.flyff.com`) so saved pointer chains survive
 * scanner Reset, WebView reloads, and full app restarts without requiring extra permissions
 * or external services.
 */
class SitePointerStore(
    private val storage: PointerKeyValueStorage
) {
    constructor(context: Context) : this(
        SharedPrefsPointerStorage(
            context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        )
    )

    fun loadPointersForUrl(url: String): List<WasmPointerChain> {
        val hostKey = normalizeHostKey(url) ?: return emptyList()
        val raw = storage.getString(prefKeyForHost(hostKey)) ?: return emptyList()
        return deserializeChains(raw)
    }

    fun savePointerForUrl(url: String, chain: WasmPointerChain): List<WasmPointerChain> {
        val hostKey = normalizeHostKey(url) ?: return emptyList()
        val existing = loadPointersForUrl(url).toMutableList()
        val savedEntry = chain.copy(
            isPinned = true,
            isSavedLocally = true
        )
        val idx = existing.indexOfFirst { it.id == savedEntry.id }
        if (idx >= 0) {
            existing[idx] = savedEntry
        } else {
            existing.add(0, savedEntry)
        }
        val bounded = existing.take(MAX_SAVED_PER_HOST)
        storage.putString(prefKeyForHost(hostKey), serializeChains(bounded))
        return bounded
    }

    fun removePointerForUrl(url: String, chainId: String): List<WasmPointerChain> {
        val hostKey = normalizeHostKey(url) ?: return emptyList()
        val updated = loadPointersForUrl(url).filterNot { it.id == chainId }
        if (updated.isEmpty()) {
            storage.remove(prefKeyForHost(hostKey))
        } else {
            storage.putString(prefKeyForHost(hostKey), serializeChains(updated))
        }
        return updated
    }

    fun clearPointersForUrl(url: String) {
        val hostKey = normalizeHostKey(url) ?: return
        storage.remove(prefKeyForHost(hostKey))
    }

    fun countSavedForUrl(url: String): Int = loadPointersForUrl(url).size

    companion object {
        private const val PREFS_NAME = "webview_diagnostics_site_pointers"
        private const val KEY_PREFIX = "site_ptrs_"
        private const val MAX_SAVED_PER_HOST = 32

        fun normalizeHostKey(rawUrl: String): String? {
            val trimmed = rawUrl.trim()
            if (trimmed.isEmpty() || trimmed.equals("about:blank", ignoreCase = true)) return null
            val candidate = if (trimmed.contains("://")) trimmed else "https://$trimmed"
            val host = runCatching { URI(candidate).host }.getOrNull()?.trim()?.lowercase(Locale.US)
            if (host.isNullOrBlank()) return null
            return host
        }

        private fun prefKeyForHost(hostKey: String): String = "$KEY_PREFIX$hostKey"

        fun serializeChains(chains: List<WasmPointerChain>): String {
            val arr = JSONArray()
            chains.forEach { c ->
                val offs = JSONArray()
                c.offsets.forEach { offs.put(it) }
                arr.put(
                    JSONObject()
                        .put("id", c.id)
                        .put("depth", c.depth)
                        .put("basePointerAddress", c.basePointerAddress)
                        .put("offsets", offs)
                        .put("resolvedTargetAddress", c.resolvedTargetAddress)
                        .put("resolvedX", c.resolvedX)
                        .put("resolvedY", c.resolvedY)
                        .put("resolvedZ", c.resolvedZ)
                        .put("isLikelyStatic", c.isLikelyStatic)
                        .put("expression", c.expression)
                )
            }
            return arr.toString()
        }

        fun deserializeChains(rawJson: String): List<WasmPointerChain> {
            val arr = try {
                JSONArray(rawJson)
            } catch (_: Exception) {
                return emptyList()
            }
            return buildList {
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val id = obj.optString("id", "").trim()
                    if (id.isEmpty()) continue
                    val depth = obj.optInt("depth", 1).coerceIn(1, 4)
                    val baseAddr = obj.optLong("basePointerAddress", 0L)
                    if (baseAddr <= 0L) continue
                    val offArr = obj.optJSONArray("offsets")
                    val offsets = buildList {
                        if (offArr != null) {
                            for (j in 0 until offArr.length()) {
                                add(offArr.optInt(j, 0))
                            }
                        }
                    }
                    val expression = obj.optString("expression", "").ifBlank {
                        val hexBase = DiagnosticsViewModel.formatHexAddress(baseAddr)
                        if (offsets.size >= 2) {
                            "*(*($hexBase) + 0x${offsets[0].toString(16).uppercase(Locale.US)}) + 0x${offsets[1].toString(16).uppercase(Locale.US)}"
                        } else {
                            val off = offsets.firstOrNull() ?: 0
                            "*($hexBase) + 0x${off.toString(16).uppercase(Locale.US)}"
                        }
                    }
                    add(
                        WasmPointerChain(
                            id = id,
                            depth = depth,
                            basePointerAddress = baseAddr,
                            offsets = offsets,
                            resolvedTargetAddress = obj.optLong("resolvedTargetAddress", 0L),
                            resolvedX = obj.optDouble("resolvedX", 0.0),
                            resolvedY = obj.optDouble("resolvedY", 0.0),
                            resolvedZ = obj.optDouble("resolvedZ", 0.0),
                            isLikelyStatic = obj.optBoolean("isLikelyStatic", false),
                            isVerifiedLive = false,
                            expression = expression,
                            isPinned = true,
                            status = WasmPointerStatus.PENDING,
                            isSavedLocally = true
                        )
                    )
                }
            }
        }
    }
}
