package io.inputcontrol.connection

import org.json.JSONArray
import org.json.JSONObject

data class PcApp(val id: Int, val name: String, val exe: String, val icon: String)

data class PcMedia(
    val foreground: String = "",
    val title: String = "",
    val artist: String = "",
    val art: String = "",
    val playing: Boolean? = null,
    val position: Int = -1,
    val duration: Int = -1,
    val available: Boolean = false,
)

internal fun parseApps(raw: String): List<PcApp> {
    val data = JSONArray(raw)
    return (0 until minOf(256, data.length()))
        .map { index ->
            val app = data.getJSONObject(index)
            PcApp(
                app.getInt("id"),
                app.getString("name"),
                app.optString("exe"),
                app.optString("icon"),
            )
        }
        .sortedBy { it.name.lowercase() }
}

internal fun parseMedia(raw: String): PcMedia {
    val m = JSONObject(raw)
    return PcMedia(
        m.optString("foreground"),
        m.optString("title"),
        m.optString("artist"),
        m.optString("art"),
        if (m.has("playing")) m.getBoolean("playing") else null,
        m.optInt("position", -1),
        m.optInt("duration", -1),
        true,
    )
}
