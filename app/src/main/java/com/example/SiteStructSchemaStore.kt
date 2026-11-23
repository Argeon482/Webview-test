package com.example

import android.content.Context
import org.json.JSONObject

/**
 * Private on-device per-site persistence store for confirmed WASM entity struct field offsets
 * (HP, MaxHP, MP, MaxMP, FP, MaxFP, and Mob/Player Name).
 *
 * Keyed by normalized host (e.g. `universe.flyff.com`) via [SitePointerStore.normalizeHostKey]
 * so bound stat and name offsets survive Reset, WebView page reloads, and full app restarts
 * without requiring extra permissions or external storage.
 */
class SiteStructSchemaStore(
    private val storage: PointerKeyValueStorage
) {
    constructor(context: Context) : this(
        SharedPrefsPointerStorage(
            context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        )
    )

    fun loadSchemaForUrl(url: String): WasmEntityStructSchema {
        val hostKey = SitePointerStore.normalizeHostKey(url) ?: return WasmEntityStructSchema()
        val raw = storage.getString(prefKeyForHost(hostKey)) ?: return WasmEntityStructSchema(hostKey = hostKey)
        return deserializeSchema(raw, hostKey)
    }

    fun saveSchemaForUrl(url: String, schema: WasmEntityStructSchema): WasmEntityStructSchema {
        val hostKey = SitePointerStore.normalizeHostKey(url) ?: return schema
        if (!schema.hasAnyBound) {
            storage.remove(prefKeyForHost(hostKey))
            return WasmEntityStructSchema(hostKey = hostKey, isSavedLocally = false)
        }
        val persisted = schema.copy(isSavedLocally = true, hostKey = hostKey)
        storage.putString(prefKeyForHost(hostKey), serializeSchema(persisted))
        return persisted
    }

    fun clearSchemaForUrl(url: String) {
        val hostKey = SitePointerStore.normalizeHostKey(url) ?: return
        storage.remove(prefKeyForHost(hostKey))
    }

    companion object {
        private const val PREFS_NAME = "webview_diagnostics_site_struct_schema"
        private const val KEY_PREFIX = "site_schema_"

        private fun prefKeyForHost(hostKey: String): String = "$KEY_PREFIX$hostKey"

        fun serializeSchema(schema: WasmEntityStructSchema): String {
            val root = JSONObject()
            WasmStructFieldKind.entries.forEach { kind ->
                val field = schema.fieldFor(kind)
                if (field != null) {
                    val obj = JSONObject()
                        .put("relOffsetBytes", field.relOffsetBytes)
                        .put("valueType", field.valueType.name)
                        .put("expression", field.displayExpression)
                    if (field.subOffsetBytes != null) {
                        obj.put("subOffsetBytes", field.subOffsetBytes)
                    }
                    root.put(kind.jsonKey, obj)
                }
            }
            return root.toString()
        }

        fun deserializeSchema(rawJson: String, hostKey: String = ""): WasmEntityStructSchema {
            val root = try {
                JSONObject(rawJson)
            } catch (_: Exception) {
                return WasmEntityStructSchema(hostKey = hostKey)
            }
            var schema = WasmEntityStructSchema(hostKey = hostKey)
            WasmStructFieldKind.entries.forEach { kind ->
                val obj = root.optJSONObject(kind.jsonKey) ?: return@forEach
                if (!obj.has("relOffsetBytes")) return@forEach
                val rel = obj.optInt("relOffsetBytes", 0)
                val sub = if (obj.has("subOffsetBytes") && !obj.isNull("subOffsetBytes")) {
                    obj.optInt("subOffsetBytes", 0)
                } else {
                    null
                }
                val valueType = WasmStructValueType.fromRaw(obj.optString("valueType", "INT32"))
                val expr = obj.optString("expression", "")
                schema = schema.withField(
                    kind,
                    WasmStructFieldOffset(
                        relOffsetBytes = rel,
                        subOffsetBytes = sub,
                        valueType = valueType,
                        expression = expr
                    )
                )
            }
            return schema.copy(isSavedLocally = schema.hasAnyBound, hostKey = hostKey)
        }
    }
}
