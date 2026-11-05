package cn.elder.assistant.model

import org.json.JSONObject

class RuleFailure(val stage:String,val code:String,val rule:String,val expected:String,val actual:String,message:String):IllegalArgumentException(message)
object Diagnostic {
    fun requireRule(ok:Boolean,stage:String,code:String,rule:String,expected:String,actual:Any?,message:String) {
        if(!ok) throw RuleFailure(stage,code,rule,expected,actual.toString(),message)
    }
    fun describe(e:Throwable,stage:String):JSONObject = JSONObject().put("stage",(e as? RuleFailure)?.stage ?: stage)
        .put("error_code",(e as? RuleFailure)?.code ?: "${stage.uppercase()}_ERROR")
        .put("rule",(e as? RuleFailure)?.rule ?: e.javaClass.simpleName)
        .put("expected",(e as? RuleFailure)?.expected ?: "该阶段正确完成")
        .put("actual",(e as? RuleFailure)?.actual ?: e.message ?: e.javaClass.simpleName)
        .put("error",e.message ?: "${stage}：${e.javaClass.simpleName}")
}
