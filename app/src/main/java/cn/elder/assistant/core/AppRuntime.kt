package cn.elder.assistant.core

/** Main-thread bridge. No UI owns the lifetime of a guidance session. */
object AppRuntime {
    var connected = false
    var active = false
    var reviewRunning = false
    var workflowTrialId: String? = null
    var status = "准备好后，点下面的按钮开始"
    var modelStatus = "模型连接尚未验证"
    var debugWaitForStart = false
    var start: (() -> Unit)? = null
    var startCustom: ((TaskGoal) -> Unit)? = null
    var stop: (() -> Unit)? = null
    var retry: (() -> Unit)? = null
}
