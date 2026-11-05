package cn.elder.assistant.model

import cn.elder.assistant.core.*
import org.json.JSONObject
import org.json.JSONArray

object DecisionCodec {
    private fun check(ok:Boolean,code:String,field:String,expected:String,actual:Any?,message:String)=
        Diagnostic.requireRule(ok,"parse",code,field,expected,actual,message)
    fun parse(raw:String):Decision {
        check(raw.length<=20000,"RESPONSE_LENGTH","response_length","<=20000",raw.length,"模型响应过长：${raw.length}")
        val j=try { JSONObject(raw) } catch(e:Exception) { throw RuleFailure("parse","INVALID_JSON","json_object","JSON 对象",e.message.orEmpty(),"模型返回不是有效 JSON：${e.message}") }
        fun text(name:String,max:Int):String {
            val value=j.opt(name)
            check(value is String && value.isNotBlank() && value.length<=max,"INVALID_FIELD",name,"非空字符串，长度 <= $max",value,"字段 $name 无效：实际为 $value")
            return value as String
        }
        val confidence=j.opt("confidence")
        check(confidence is Number && confidence.toDouble().isFinite() && confidence.toDouble() in 0.0..1.0,"INVALID_CONFIDENCE","confidence","0..1 的数字",confidence,"confidence 必须是 0 到 1 的数字，实际为 $confidence")
        val completed=j.opt("task_completed")
        check(completed is Boolean,"INVALID_COMPLETED","task_completed","Boolean",completed,"task_completed 必须是布尔值，实际为 $completed")
        val state=text("current_state",100)
        val instruction=text("instruction",100)
        val actionName=text("next_action",30)
        val action=Action.entries.find { it.name.equals(actionName,true) }
        check(action!=null,"INVALID_ACTION","next_action",Action.entries.joinToString(),actionName,"未知动作：$actionName")
        check(completed==(action==Action.COMPLETE),"COMPLETION_CONFLICT","completion_flag","complete 与 true 同时出现",completed,"完成状态与动作冲突")
        val value=j.opt("target_node")
        check(value==null || value===JSONObject.NULL || value is String,"INVALID_NODE_TYPE","target_node","字符串或 JSON null",value,"target_node 必须是字符串或 JSON null")
        // Only target_node gets legacy blank normalization. A literal "null" stays an ID and is rejected by grounding.
        val node=(value as? String)?.takeUnless { it.isBlank() }
        val box=if(j.isNull("target_bbox")) null else {
            val a=j.opt("target_bbox")
            check(a is JSONArray && a.length()==4,"INVALID_BBOX","target_bbox","四个整数",a,"target_bbox 必须包含四个整数")
            a as JSONArray
            val values=(0..3).map { i ->
                val n=a.get(i)
                check(n is Number && n.toDouble().isFinite() && n.toDouble()==n.toInt().toDouble(),"INVALID_COORDINATE","target_bbox[$i]","Int 范围内的整数",n,"坐标第 $i 项不是合法整数：$n")
                (n as Number).toInt()
            }
            Box(values[0],values[1],values[2],values[3])
        }
        val evidence=when(val e=j.opt("evidence")) {
            null,JSONObject.NULL -> emptyList()
            is String -> listOf(e.take(1000))
            is JSONArray -> { check(e.length()<=20,"EVIDENCE_LENGTH","evidence","<=20 项",e.length(),"evidence 条目过多")
                (0 until e.length()).map { i -> val v=e.get(i);check(v is String,"INVALID_EVIDENCE","evidence[$i]","字符串",v,"evidence 第 $i 项必须是字符串");(v as String).take(1000) } }
            else -> throw RuleFailure("parse","INVALID_EVIDENCE","evidence","文字或文字数组",e.toString(),"evidence 必须是文字或文字数组")
        }
        val app=if(action==Action.OPEN_APP) text("target_app",255) else null
        return Decision(state,action!!,node,box,instruction,(confidence as Number).toDouble(),completed as Boolean,evidence,app)
    }
    fun target(d:Decision,s:Snapshot):Box? {
        val node=d.node?.let { id -> s.nodes.find { it.id==id } ?: error("目标节点不属于当前页面：node=$id，snapshot=${s.id}") }
        fun valid(b:Box) = Diagnostic.requireRule(b.valid(s.width,s.height),"target","BBOX_OUT_OF_BOUNDS","bounds_within_screen",
            "0<=left<right<=${s.width}, 0<=top<bottom<=${s.height}",b,
            "坐标越界或区域无效：left=${b.left}, top=${b.top}, right=${b.right}, bottom=${b.bottom}，屏幕=${s.width}×${s.height}")
        d.box?.let { valid(it) }
        Diagnostic.requireRule(node==null || d.box==null || node.box.overlaps(d.box),"target","NODE_BBOX_MISMATCH","node_bbox_overlap","节点与坐标重叠","node=${node?.box}, bbox=${d.box}","节点和坐标指向不同位置")
        val target=node?.box ?: d.box
        if(d.action==Action.CLICK || (d.action==Action.BACK && target!=null)) {
            Diagnostic.requireRule(target!=null,"target","MISSING_CLICK_TARGET","click_target","有效节点或有效坐标","node=${d.node}, bbox=${d.box}","点击目标校验失败：节点和坐标均为空")
            valid(target!!)
            Diagnostic.requireRule((target.right-target.left).toLong()*(target.bottom-target.top)<s.width.toLong()*s.height*0.6,"target","TARGET_TOO_LARGE","target_area_ratio","<0.6",target,"目标区域过大，必须小于屏幕面积的 60%")
        }
        return target
    }
}
