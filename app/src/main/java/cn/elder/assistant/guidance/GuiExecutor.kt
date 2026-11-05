package cn.elder.assistant.guidance

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import cn.elder.assistant.core.*

/** A dispatch callback is only delivery; the controller independently observes the next page. */
class GuiExecutor(private val service:AccessibilityService) {
    companion object { fun allowed(autoGui:Boolean,action:Action)=autoGui && action in setOf(Action.CLICK,Action.BACK) }
    fun execute(action:Action,target:Box?,snapshot:Snapshot,callback:(Result<Unit>)->Unit) {
        try {
            if(action==Action.BACK && target==null) {
                check(service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)) { "系统拒绝返回操作" }
                callback(Result.success(Unit));return
            }
            require(action==Action.CLICK || action==Action.BACK) { "当前自动执行仅支持点击和返回；输入、滚动请人工操作" }
            require(target!=null && target.valid(snapshot.width,snapshot.height)) { "GUI 点击目标无效或越界" }
            val path=Path().apply { moveTo((target!!.left+target.right)/2f,(target.top+target.bottom)/2f) }
            val gesture=GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path,0,80)).build()
            val accepted=service.dispatchGesture(gesture,object:AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription:GestureDescription?) { callback(Result.success(Unit)) }
                override fun onCancelled(gestureDescription:GestureDescription?) { callback(Result.failure(IllegalStateException("系统取消了点击手势"))) }
            },null)
            if(!accepted) callback(Result.failure(IllegalStateException("系统拒绝手势：请检查无障碍权限")))
        } catch(e:Exception) { callback(Result.failure(e)) }
    }
}
