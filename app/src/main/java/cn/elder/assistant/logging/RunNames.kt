package cn.elder.assistant.logging

import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID
import org.json.JSONObject

object RunNames {
    fun stamp(time:Long, zone:ZoneId=ZoneId.systemDefault()):String = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS").withZone(zone).format(Instant.ofEpochMilli(time))
    fun newId(time:Long=System.currentTimeMillis()):String = "run_${stamp(time)}_${UUID.randomUUID().toString().take(8)}"
    fun time(dir:File):Long = runCatching { File(dir,"events.jsonl").bufferedReader().use { JSONObject(it.readLine()).getLong("timestamp") } }.getOrElse { dir.name.split('_').getOrNull(1)?.takeIf { it.length==13 }?.toLongOrNull() ?: dir.lastModified() }
    fun exportName(dir:File):String = "debug_${stamp(time(dir))}_${dir.name.substringAfterLast('_')}.zip"
}
