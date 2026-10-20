package cn.elder.assistant.core

/** Immutable per-session goal. The shortcut remains the backwards-compatible default. */
class TaskGoal private constructor(val goal:String,val fixed:Boolean) {
    val key:String get()=if(fixed) TaskSpec.KEY else "custom_task"
    val completionMessage:String get()=if(fixed) "已向女儿发起视频通话" else "任务已成功"
    fun observes(packageName:String,ownPackage:String,homePackage:String?):Boolean =
        packageName.isNotBlank() && packageName!=ownPackage && (!fixed || packageName in setOf("com.tencent.mm",homePackage))
    companion object {
        val DEFAULT=TaskGoal(TaskSpec.GOAL,true)
        fun custom(text:String):TaskGoal {
            val clean=text.trim()
            require(clean.isNotBlank()) { "请先说出或输入想做的事" }
            require(clean.length<=500) { "任务请控制在 500 字以内" }
            return TaskGoal(clean,false)
        }
    }
}
