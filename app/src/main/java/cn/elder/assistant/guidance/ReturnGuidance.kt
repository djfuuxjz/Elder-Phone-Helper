package cn.elder.assistant.guidance

import cn.elder.assistant.core.*

object ReturnGuidance {
    fun needsLocation(d:Decision,target:Box?,hasImage:Boolean,vision:Boolean,attempt:Int):Boolean =
        d.action==Action.BACK && target==null && !hasImage && attempt<3 && (vision || attempt==1)
    fun highlight(action:Action,target:Box?):Box? = if(action in setOf(Action.CLICK,Action.INPUT,Action.BACK)) target else null
    const val REQUEST="请优先定位当前页面可点击的返回箭头或返回按钮，返回节点或坐标；无文字图标可结合截图识别。不要只说点返回键，尽量避免全面屏手势。确无可点击返回按钮时再说明具体系统返回方法。"
}
