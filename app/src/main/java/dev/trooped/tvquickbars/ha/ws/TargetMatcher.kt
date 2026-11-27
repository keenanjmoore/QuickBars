package dev.trooped.tvquickbars.ha.ws

import android.content.Context
import android.os.Build
import android.provider.Settings
import dev.trooped.tvquickbars.data.AppIdProvider
import dev.trooped.tvquickbars.persistence.AppPrefs
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * Normalizes target matching across QuickBars events (`quickbars.open`, `quickbars.close`,
 * `quickbars.notify`, `quickbars.config`, `quickbars.ping`).
 *
 * Matches when:
 * - Neither `target_id`/`target` nor `target_ids`/`targets` is present (broadcast)
 * - Or any specified target equals `*` / `all`
 * - Or any specified target matches the TV's Crockford Base32 App ID (case-insensitive)
 * - Or matches configured `pref_ha_device_alias`, Android device name slug, or Build.MODEL slug
 */
object TargetMatcher {

    fun matches(ctx: Context, payload: JSONObject): Boolean {
        val targets = extractTargets(payload)
        if (targets.isEmpty()) return true

        val myAppId = AppIdProvider.get(ctx).trim().uppercase(Locale.US)
        val myAppIdNorm = myAppId.replace("-", "").replace("_", "").replace(" ", "")
        val localAliases = getLocalAliases(ctx, myAppId, myAppIdNorm)

        for (raw in targets) {
            val t = raw.trim()
            if (t.isEmpty()) continue
            val low = t.lowercase(Locale.US)
            if (low == "*" || low == "all" || low == "broadcast") return true

            val upperNorm = AppIdProvider.normalizeUserId(t)
            if (upperNorm == myAppIdNorm) return true

            val slug = slugify(t)
            if (slug.isNotEmpty() && slug in localAliases) return true
        }
        return false
    }

    fun getDeviceName(ctx: Context): String {
        val customAlias = AppPrefs.getHaDeviceAlias(ctx)
        if (!customAlias.isNullOrBlank()) return customAlias
        val sysName = runCatching {
            Settings.Global.getString(ctx.contentResolver, Settings.Global.DEVICE_NAME)
        }.getOrNull()
        return sysName?.takeIf { it.isNotBlank() } ?: Build.MODEL ?: "Android TV"
    }

    fun getDeviceSlug(ctx: Context): String = slugify(getDeviceName(ctx))

    private fun getLocalAliases(ctx: Context, myAppId: String, myAppIdNorm: String): Set<String> {
        val out = LinkedHashSet<String>()
        out += myAppId.lowercase(Locale.US)
        out += myAppIdNorm.lowercase(Locale.US)
        AppPrefs.getHaDeviceAlias(ctx)?.let {
            val s = slugify(it)
            if (s.isNotEmpty()) out += s
        }
        val sysName = runCatching {
            Settings.Global.getString(ctx.contentResolver, Settings.Global.DEVICE_NAME)
        }.getOrNull()
        if (!sysName.isNullOrBlank()) {
            out += slugify(sysName)
        }
        if (!Build.MODEL.isNullOrBlank()) {
            out += slugify(Build.MODEL)
        }
        return out
    }

    private fun extractTargets(payload: JSONObject): List<String> {
        val list = ArrayList<String>()
        for (key in listOf("target_id", "target", "id_target")) {
            if (payload.has(key) && !payload.isNull(key)) {
                val obj = payload.opt(key)
                when (obj) {
                    is JSONArray -> collectArray(obj, list)
                    is String -> if (obj.isNotBlank()) list += obj
                }
            }
        }
        for (key in listOf("target_ids", "targets")) {
            val arr = payload.optJSONArray(key)
            if (arr != null) collectArray(arr, list)
        }
        return list
    }

    private fun collectArray(arr: JSONArray, out: MutableList<String>) {
        for (i in 0 until arr.length()) {
            val s = arr.optString(i, "")
            if (s.isNotBlank()) out += s
        }
    }

    fun slugify(input: String): String =
        input.trim()
            .lowercase(Locale.US)
            .replace(Regex("[^a-z0-9]+"), "_")
            .trim('_')
}
