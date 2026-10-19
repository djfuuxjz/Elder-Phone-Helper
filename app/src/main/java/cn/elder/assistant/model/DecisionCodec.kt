package cn.elder.assistant.model

import cn.elder.assistant.core.*
import org.json.JSONObject

object DecisionCodec {
    fun parse(raw: String): Decision {
        require(raw.length <= 20000) { "模型响应过长" }
        val j=JSONObject(raw)
        val confidence=j.get("confidence")
        require(confidence is Number && confidence.toDouble().isFinite() && confidence.toDouble() in 0.0..1.0) { "confidence 无效" }
        val completed=j.get("task_completed"); require(completed is Boolean) { "task_completed 必须是布尔值" }
        val state=j.getString("current_state").also { require(it.isNotBlank() && it.length<=100) }
        val instruction=j.getString("instruction").also { require(it.isNotBlank() && it.length<=100) { "提示必须是简短单步指引" } }
        val action=Action.valueOf(j.getString("next_action").uppercase())
        require(completed == (action==Action.COMPLETE)) { "完成状态与动作冲突" }
        val node=if(j.isNull("target_node")) null else j.getString("target_node").also { require(it.isNotBlank()) }
        val box=if(j.isNull("target_bbox")) null else j.getJSONArray("target_bbox").let { a ->
            require(a.length()==4)
            val values=(0..3).map { index -> val n=a.get(index); require(n is Number && n.toDouble().isFinite() && n.toDouble()==n.toInt().toDouble()); n.toInt() }
            Box(values[0],values[1],values[2],values[3])
        }
        val evidence=when(val value=j.opt("evidence")) {
            null,JSONObject.NULL -> emptyList()
            is String -> listOf(value.take(1000))
            is org.json.JSONArray -> { require(value.length()<=20);(0 until value.length()).map { value.getString(it).take(1000) } }
            else -> error("evidence 必须是文字或文字数组")
        }
        return Decision(state,action,node,box,instruction,confidence.toDouble(),completed,evidence)
    }
    fun target(d:Decision,s:Snapshot):Box? {
        val node=d.node?.let { id -> s.nodes.find { it.id==id } ?: error("目标节点不属于当前页面") }
        d.box?.let { require(it.valid(s.width,s.height)) { "目标位置越界" } }
        if(node!=null && d.box!=null) require(node.box.overlaps(d.box)) { "节点和坐标指向不同位置" }
        val target=node?.box ?: d.box
        if(d.action==Action.CLICK || (d.action==Action.BACK && target!=null)) {
            require(target!=null && target.valid(s.width,s.height)) { "点击指引缺少有效目标" }
            require((target.right-target.left).toLong()*(target.bottom-target.top) < s.width.toLong()*s.height*0.6) { "目标范围过大，无法明确引导" }
        }
        return target
    }
}
