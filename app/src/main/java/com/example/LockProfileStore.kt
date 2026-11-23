package com.example

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * Lightweight `SharedPreferences` persistence store for the user's saved WASM coordinate lock
 * profile (AOB/Float struct signature, discovered `HEAPU32` parent pointer offsets, and
 * auto-relock preferences).
 */
class LockProfileStore(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun saveProfile(profile: SavedLockProfile) {
        val pointersArr = JSONArray()
        profile.parentPointers.forEach { ptr ->
            pointersArr.put(
                JSONObject().apply {
                    put("pointerHeapIndex", ptr.pointerHeapIndex)
                    put("pointerByteAddress", ptr.pointerByteAddress)
                    put("targetByteAddress", ptr.targetByteAddress)
                    put("fieldOffsetBytes", ptr.fieldOffsetBytes)
                    put("isExactMatch", ptr.isExactMatch)
                }
            )
        }

        val root = JSONObject().apply {
            put("baseIndex", profile.baseIndex)
            put("host", profile.host)
            put("aobPatternHex", profile.aobPatternHex)
            put("floatPatternSummary", profile.floatPatternSummary)
            put("anchorCount", profile.anchorCount)
            put("signatureJson", profile.signatureJson)
            put("parentPointers", pointersArr)
            put("autoRelockOnReload", profile.autoRelockOnReload)
            put("savedAtEpochMs", profile.savedAtEpochMs)
        }

        prefs.edit()
            .putString(KEY_SAVED_PROFILE_JSON, root.toString())
            .putBoolean(KEY_AUTO_RELOCK_ENABLED, profile.autoRelockOnReload)
            .apply()
    }

    fun loadProfile(): SavedLockProfile? {
        val raw = prefs.getString(KEY_SAVED_PROFILE_JSON, null) ?: return null
        return runCatching {
            val root = JSONObject(raw)
            val ptrArr = root.optJSONArray("parentPointers") ?: JSONArray()
            val pointers = buildList {
                for (i in 0 until ptrArr.length()) {
                    val obj = ptrArr.optJSONObject(i) ?: continue
                    val idx = obj.optInt("pointerHeapIndex", -1)
                    if (idx < 0) continue
                    add(
                        ParentPointerMatch(
                            pointerHeapIndex = idx,
                            pointerByteAddress = obj.optLong("pointerByteAddress", idx.toLong() * 4L),
                            targetByteAddress = obj.optLong("targetByteAddress", 0L),
                            fieldOffsetBytes = obj.optInt("fieldOffsetBytes", 0),
                            isExactMatch = obj.optBoolean("isExactMatch", false)
                        )
                    )
                }
            }
            val signatureJson = root.optString("signatureJson", "")
            if (signatureJson.isBlank()) return null

            SavedLockProfile(
                baseIndex = root.optInt("baseIndex", VERIFIED_PLAYER_BASE_INDEX),
                host = root.optString("host", ""),
                aobPatternHex = root.optString("aobPatternHex", ""),
                floatPatternSummary = root.optString("floatPatternSummary", ""),
                anchorCount = root.optInt("anchorCount", 0),
                signatureJson = signatureJson,
                parentPointers = pointers,
                autoRelockOnReload = prefs.getBoolean(
                    KEY_AUTO_RELOCK_ENABLED,
                    root.optBoolean("autoRelockOnReload", true)
                ),
                savedAtEpochMs = root.optLong("savedAtEpochMs", 0L)
            )
        }.getOrNull()
    }

    fun setAutoRelockEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_RELOCK_ENABLED, enabled).apply()
    }

    fun isAutoRelockEnabled(): Boolean {
        return prefs.getBoolean(KEY_AUTO_RELOCK_ENABLED, true)
    }

    fun clearProfile() {
        prefs.edit().remove(KEY_SAVED_PROFILE_JSON).apply()
    }

    companion object {
        private const val PREFS_NAME = "webview_diagnostics_lock_profiles"
        private const val KEY_SAVED_PROFILE_JSON = "saved_lock_profile_v1"
        private const val KEY_AUTO_RELOCK_ENABLED = "auto_relock_on_reload"
    }
}
