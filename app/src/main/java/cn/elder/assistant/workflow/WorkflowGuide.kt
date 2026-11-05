package cn.elder.assistant.workflow

import cn.elder.assistant.core.*
import org.json.JSONObject

object WorkflowGuide {
    private fun taskKey(workflow:JSONObject?):String {
        if(workflow==null) return ""
        return workflow.optString("task_key").ifBlank { workflow.optJSONObject("body")?.optString("task_key").orEmpty() }
    }
    fun label(n:UiNode)=listOf(n.text,n.description,n.hint).filter { it.isNotBlank() }.joinToString(" ")
    fun inputs(s:Snapshot)=s.nodes.filter { it.editable || it.kind.endsWith("EditText") }
    fun searches(s:Snapshot)=s.nodes.filter { n -> listOf(n.text,n.description,n.hint).any { it.trim() in setOf("搜索","搜索按钮","搜索联系人") } && n.box.top<s.height/2 }
    fun plus(s:Snapshot)=s.nodes.filter { n -> listOf(n.text,n.description).any { it.trim() in setOf("更多功能按钮","更多功能","加号","添加","+","更多") } && n.box.top>s.height/2 }
    fun phase(s:Snapshot):String {
        val labels=s.nodes.map { label(it) }
        if(labels.any { it.contains("挂断") } && labels.any { it.contains("摄像头") }) return "call"
        if(plus(s).isNotEmpty()) return "chat"
        if(labels.any { it.contains("视频通话") } && labels.any { it.contains("语音通话") }) return "video_menu"
        if(inputs(s).isNotEmpty() && (searches(s).isNotEmpty() || labels.any { it.contains("取消") })) return if(inputs(s).any { it.text.isNotBlank() && it.text!="搜索" }) "search_results" else "search_input"
        if(searches(s).isNotEmpty() || labels.any { it=="通讯录" }) return "find_contact"
        return "recover"
    }
    fun context(version:JSONObject?,s:Snapshot):JSONObject = JSONObject().put("version",version ?: JSONObject.NULL)
        .put("current_phase",if(taskKey(version)==TaskSpec.KEY) phase(s) else "observe_current_page")
        .put("search_nodes",org.json.JSONArray(searches(s).map { it.id })).put("input_nodes",org.json.JSONArray(inputs(s).map { it.id }))
        .put("plus_nodes",org.json.JSONArray(plus(s).map { it.id }))
    fun usesFixedContactSearchRule(workflow:JSONObject?)=taskKey(workflow)==TaskSpec.KEY
    fun target(d:Decision,s:Snapshot,fallback:Box?,enabled:Boolean):Box? {
        if(enabled && d.action==Action.SCROLL && phase(s) in setOf("find_contact","search_input","search_results")) error("流程要求优先搜索联系人，禁止逐屏滑动查找；请使用搜索入口")
        return fallback
    }
}
