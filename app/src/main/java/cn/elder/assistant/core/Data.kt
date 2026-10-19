package cn.elder.assistant.core

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

object TaskSpec {
    const val KEY = "video_call_daughter"
    const val GOAL = "在微信中给联系人‘女儿’发起视频通话"
    const val PROMPT_VERSION = "5"
}

data class Box(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    fun valid(width: Int, height: Int) = left >= 0 && top >= 0 && right > left && bottom > top && right <= width && bottom <= height
    fun json() = JSONArray(listOf(left, top, right, bottom))
    fun overlaps(other: Box) = left < other.right && right > other.left && top < other.bottom && bottom > other.top
}
data class UiNode(val id: String, val text: String, val description: String, val kind: String, val clickable: Boolean, val box: Box, val editable: Boolean = false, val focused: Boolean = false, val hint: String = "") {
    fun json() = JSONObject().put("id", id).put("text", text).put("description", description)
        .put("editable", editable).put("focused", focused).put("hint", hint).put("kind", kind).put("clickable", clickable).put("bounds", box.json())
}
data class Snapshot(val id: String, val packageName: String, val windowId: Int, val width: Int, val height: Int,
                    val nodes: List<UiNode>, val capturedAt: Long, val truncated: Boolean = false) {
    val fingerprint: String get() {
        val payload = "$packageName|$windowId|$width|$height|" + nodes.joinToString { "${it.text}|${it.description}|${it.clickable}|${it.editable}|${it.focused}|${it.hint}|${it.box}" }
        return MessageDigest.getInstance("SHA-256").digest(payload.toByteArray()).joinToString("") { "%02x".format(it) }
    }
    fun json() = JSONObject().put("snapshot_id", id).put("package_name", packageName).put("window_id", windowId)
        .put("screen_width", width).put("screen_height", height).put("captured_at", capturedAt)
        .put("truncated", truncated).put("nodes", JSONArray(nodes.map { it.json() }))
}
enum class Action { CLICK, BACK, SCROLL, INPUT, WAIT, COMPLETE, BLOCKED, NEED_SCREENSHOT }
data class Decision(val state: String, val action: Action, val node: String?, val box: Box?, val instruction: String,
                    val confidence: Double, val completed: Boolean, val evidence: List<String>)
