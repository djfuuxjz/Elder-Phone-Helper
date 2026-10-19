package cn.elder.assistant.logging

import cn.elder.assistant.core.TaskSpec
import org.json.JSONObject
import java.io.File
import java.io.IOException

class RunLog(private val root: File, private val secrets: List<String> = emptyList(), private val maxBytes: Long = 80L * 1024 * 1024) {
    val id: String = RunNames.newId()
    val directory = File(root, id)
    init { if (!directory.mkdirs()) throw IOException("无法创建日志目录") }
    @Synchronized fun event(type: String, data: JSONObject = JSONObject()) {
        val obj = JSONObject().put("timestamp", System.currentTimeMillis()).put("session_id", id)
            .put("time_zone", java.time.ZoneId.systemDefault().id).put("task_key", TaskSpec.KEY).put("goal", TaskSpec.GOAL).put("event", type).put("data", data)
        val text = redact(obj.toString()) + "\n"
        checkCapacity(text.toByteArray().size.toLong())
        File(directory, "events.jsonl").appendText(text)
    }
    @Synchronized fun artifact(name: String, bytes: ByteArray): String {
        require(name.matches(Regex("[A-Za-z0-9_.-]+")) && !name.startsWith("."))
        checkCapacity(bytes.size.toLong())
        File(directory, name).writeBytes(bytes)
        return name
    }
    private fun checkCapacity(extra: Long) {
        val size = root.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        if (size + extra > maxBytes) throw IOException("调试存储已满，请在 Debug 中导出并清理")
    }
    private fun redact(text: String): String = secrets.filter { it.isNotBlank() }.fold(text) { s, key ->
        s.replace(key, "[REDACTED]").replace(JSONObject.quote(key).removeSurrounding("\""), "[REDACTED]")
    }
}
