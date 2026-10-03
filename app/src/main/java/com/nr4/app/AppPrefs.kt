package com.nr4.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class SourceItem(
    val name: String,
    val url: String
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("name", name); put("url", url)
    }

    companion object {
        fun fromJson(o: JSONObject): SourceItem =
            SourceItem(o.optString("name"), o.optString("url"))
    }
}

object AppPrefs {
    private const val PREF = "iptv_prefs"
    private const val K_PL = "playlists"
    private const val K_ACTIVE = "active_playlist"

    private fun p(ctx: Context) = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun getPlaylists(ctx: Context): MutableList<SourceItem> {
        val raw = p(ctx).getString(K_PL, "[]") ?: "[]"
        val out = mutableListOf<SourceItem>()
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) out.add(SourceItem.fromJson(arr.getJSONObject(i)))
        } catch (_: Exception) {}
        return out
    }

    fun setPlaylists(ctx: Context, list: List<SourceItem>) {
        val arr = JSONArray()
        for (i in list) arr.put(i.toJson())
        p(ctx).edit().putString(K_PL, arr.toString()).apply()
    }

    fun getActive(ctx: Context): String = p(ctx).getString(K_ACTIVE, "") ?: ""

    fun setActive(ctx: Context, url: String) {
        p(ctx).edit().putString(K_ACTIVE, url).apply()
    }

    fun importFile(ctx: Context, uri: android.net.Uri, name: String): String? {
        return try {
            val safe = name.replace(Regex("[^A-Za-z0-9_.-]"), "_")
            val target = File(ctx.filesDir, "pl_${System.currentTimeMillis()}_$safe")
            ctx.contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
            target.absolutePath
        } catch (e: Exception) {
            null
        }
    }
}
