package cn.elder.assistant.logging

import org.json.JSONObject
import java.io.File
import kotlin.math.ceil

object RunMetrics {
    fun events(directory:File):List<JSONObject> = File(directory,"events.jsonl").useLines { lines ->
        lines.filter { it.isNotBlank() }.mapIndexed { index,line ->
            try { JSONObject(line) } catch(e:Exception) { throw IllegalStateException("日志第 ${index+1} 行损坏",e) }
        }.toList()
    }
    fun percentile(values:List<Long>,p:Double):Long? {
        require(p>0 && p<=1)
        if(values.isEmpty()) return null
        val sorted=values.sorted();return sorted[(ceil(p*sorted.size).toInt()-1).coerceAtLeast(0)]
    }
    fun summarize(events:List<JSONObject>):JSONObject {
        val requests=events.filter { it.optString("event")=="request" }
        val responses=events.filter { it.optString("event")=="response" }.map { it.getJSONObject("data") }
        val times=responses.filter { it.has("latency_ms") }.map { it.getLong("latency_ms") }
        val good=responses.filter { !it.has("error") && it.has("latency_ms") }.map { it.getLong("latency_ms") }
        val guidance=events.filter { it.optString("event")=="guidance" }.map { it.getJSONObject("data") }
        val end=events.lastOrNull { it.optString("event")=="end" }?.getJSONObject("data")
        return JSONObject().put("calls",requests.size).put("review_calls",requests.count { it.getJSONObject("data").optString("purpose")=="workflow_review" }).put("guidance_calls",requests.count { it.getJSONObject("data").optString("purpose")!="workflow_review" }).put("additional_attempts",requests.count { it.getJSONObject("data").optInt("attempt",1)>1 })
            .put("response_count",responses.size).put("error_count",responses.count { it.has("error") })
            .put("all_latency_p50_ms",median(times) ?: JSONObject.NULL).put("all_latency_p95_ms",percentile(times,0.95) ?: JSONObject.NULL)
            .put("all_latency_max_ms",times.maxOrNull() ?: JSONObject.NULL).put("success_latency_p95_ms",percentile(good,0.95) ?: JSONObject.NULL)
            .put("guidance_count",guidance.size).put("guidance_p95_ms",percentile(guidance.map { it.getLong("guidance_latency_ms") },0.95) ?: JSONObject.NULL)
            .put("result",end?.optString("result") ?: "INCOMPLETE").put("duration_ms",if(end==null || end.isNull("duration_ms")) JSONObject.NULL else end.getLong("duration_ms"))
    }
    fun median(values:List<Long>):Double? {
        if(values.isEmpty()) return null
        val sorted=values.sorted();val middle=sorted.size/2
        return if(sorted.size%2==1) sorted[middle].toDouble() else sorted[middle-1]/2.0+sorted[middle]/2.0
    }
    fun artifact(directory:File,name:String):File {
        require(name.matches(Regex("[A-Za-z0-9_.-]+")) && !name.startsWith(".")) { "无效附件引用" }
        val file=File(directory,name).canonicalFile
        require(file.parentFile==directory.canonicalFile && file.isFile) { "附件不存在" }
        return file
    }
}
