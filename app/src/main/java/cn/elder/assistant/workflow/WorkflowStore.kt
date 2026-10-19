package cn.elder.assistant.workflow

import android.util.AtomicFile
import cn.elder.assistant.core.TaskSpec
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Immutable version bodies; activation/lock metadata lives in the same atomic transaction. */
class WorkflowStore(private val file:File) {
    companion object {
        private val lock=Any()
        val phases=listOf("find_contact","search_input","search_results","chat","video_menu","call","recover")
        fun builtin():JSONObject = JSONObject().put("name","微信视频通话 · 搜索优先").put("task_key",TaskSpec.KEY)
            .put("steps",JSONArray(listOf(
                "微信首页或通讯录：优先点击搜索，不逐屏滑动找联系人。",
                "搜索页：在真实输入框中输入女儿；已有搜索词时按实际情况修改。",
                "搜索结果：根据备注和上下文选择目标联系人；有歧义请用户确认。",
                "聊天页：点击输入栏旁的加号/更多功能，不点右上角更多信息。",
                "功能面板：点击音视频通话；有选择菜单时选择视频通话。",
                "呼叫页：等待对方接受邀请且有摄像头与挂断控件时完成；不重复发起。",
                "其他页面：优先点击可见返回按钮回到合适入口，尽量不使用全面屏手势；已在目标联系人聊天页时不重新搜索。"
            )))
        fun validate(body:JSONObject):JSONObject {
            require(body.getString("task_key")==TaskSpec.KEY) { "当前仅支持给女儿打视频电话" }
            require(body.getString("name").length in 1..80)
            val steps=body.getJSONArray("steps"); require(steps.length() in 1..20)
            repeat(steps.length()) { require(steps.getString(it).length in 1..500) }
            return JSONObject().put("name",body.getString("name")).put("task_key",TaskSpec.KEY).put("steps",JSONArray(steps.toString()))
        }
    }
    private fun read():JSONObject {
        if(!file.exists() && !File(file.path+".bak").exists()) return JSONObject().put("active","builtin").put("versions",JSONArray().put(
            JSONObject().put("id","builtin").put("body",builtin()).put("source","builtin").put("locked",true).put("created_at",0)))
        return JSONObject(AtomicFile(file).openRead().use { it.bufferedReader().readText() })
    }
    private fun write(data:JSONObject) {
        file.parentFile?.mkdirs();val atomic=AtomicFile(file);val stream=atomic.startWrite()
        try { stream.write(data.toString(2).toByteArray());atomic.finishWrite(stream) } catch(e:Exception) { atomic.failWrite(stream);throw e }
    }
    fun list():List<JSONObject> = synchronized(lock) { val all=read();val a=all.getJSONArray("versions");(0 until a.length()).map { JSONObject(a.getJSONObject(it).toString()).put("active",all.optString("active")==a.getJSONObject(it).getString("id")) }.reversed() }
    fun selected(trial:String?=null):JSONObject? = synchronized(lock) { val versions=list();if(trial!=null) versions.find { it.getString("id")==trial } ?: error("试运行版本已不存在") else versions.find { it.getBoolean("active") } }
    fun add(body:JSONObject,source:String="manual",base:String?=null,run:String?=null,rationale:String="",autoActivate:Boolean=false):String = synchronized(lock) {
        require(source in setOf("manual","model")); val clean=validate(body);val data=read()
        val id=UUID.randomUUID().toString();data.getJSONArray("versions").put(JSONObject().put("id",id).put("body",clean).put("source",source)
            .put("base_version",base ?: JSONObject.NULL).put("source_run",run ?: JSONObject.NULL).put("rationale",rationale.take(3000)).put("created_at",System.currentTimeMillis()).put("locked",false))
        if(autoActivate) {
            val versions=data.getJSONArray("versions")
            val current=(0 until versions.length()).map { versions.getJSONObject(it) }.find { it.getString("id")==data.optString("active") }
            // Respect explicit developer locking, disabling or switching during this run.
            val sameSelection=data.optString("active")==base
            val protected=current?.optBoolean("auto_update_locked")==true || (current?.optBoolean("locked")==true && current.optString("source")!="builtin")
            if(sameSelection && !protected) data.put("active",id)
        }
        write(data);id
    }
    fun activate(id:String?) = synchronized(lock) { val data=read();if(id!=null) require(list().any { it.getString("id")==id });data.put("active",id ?: JSONObject.NULL);write(data) }
    fun setLocked(id:String,value:Boolean) = synchronized(lock) { val data=read();val versions=data.getJSONArray("versions");val found=(0 until versions.length()).map { versions.getJSONObject(it) }.find { it.getString("id")==id } ?: error("版本不存在");found.put("locked",value).put("auto_update_locked",value);write(data) }
    fun revise(id:String,body:JSONObject):String = synchronized(lock) { require(list().any { it.getString("id")==id && !it.getBoolean("locked") }) { "该版本已锁定，请先解锁或新建流程" };add(body,"manual",id) }
}
