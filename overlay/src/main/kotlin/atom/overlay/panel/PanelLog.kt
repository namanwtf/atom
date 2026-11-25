package atom.overlay.panel

import atom.overlay.OverlayMessage
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** The mini chat's own short display log, so the panel shows its conversation right after a restart. */
internal object PanelLog {
    const val MAX_MESSAGES = 60
    const val MAX_CHARS = 60_000

    fun append(log: List<OverlayMessage>, message: OverlayMessage): List<OverlayMessage> = trim(log + message)

    fun trim(log: List<OverlayMessage>): List<OverlayMessage> {
        var out = log.takeLast(MAX_MESSAGES)
        while (out.size > 1 && out.sumOf { it.text.length } > MAX_CHARS) out = out.drop(1)
        return out
    }

    fun encode(log: List<OverlayMessage>): String = JSONArray().apply {
        log.forEach { put(JSONObject().put("u", it.fromUser).put("t", it.text)) }
    }.toString()

    fun decode(raw: String?): List<OverlayMessage> {
        if (raw.isNullOrEmpty()) return emptyList()
        return try {
            val array = JSONArray(raw)
            (0 until array.length()).map {
                val o = array.getJSONObject(it)
                OverlayMessage(o.optBoolean("u"), o.optString("t"))
            }
        } catch (e: JSONException) {
            emptyList()
        }
    }
}
