package cn.elder.assistant.accessibility

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import cn.elder.assistant.core.AppRuntime
import cn.elder.assistant.guidance.GuidanceController

class GuideService: AccessibilityService() {
    private var controller:GuidanceController?=null
    override fun onServiceConnected() {
        controller?.close();controller=GuidanceController(this)
        AppRuntime.connected=true; AppRuntime.status="操作引导权限已开启"
        AppRuntime.start={ controller?.start() };AppRuntime.stop={ controller?.stopAndReturn() };AppRuntime.retry={ controller?.retry() }
    }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pkg=event?.packageName?.toString() ?: return
        if(pkg!=packageName) controller?.pageEvent(pkg)
    }
    override fun onInterrupt() { controller?.stop(cn.elder.assistant.guidance.Phase.FAILED,"无障碍服务被系统中断");AppRuntime.status="服务已中断，请重新开始" }
    override fun onDestroy() { controller?.close();controller=null;AppRuntime.connected=false; AppRuntime.active=false; AppRuntime.start=null; AppRuntime.stop=null; AppRuntime.retry=null; super.onDestroy() }
}
