package cn.elder.assistant.logging

import android.graphics.Rect
import android.view.accessibility.AccessibilityEvent
import cn.elder.assistant.core.Snapshot
import org.json.JSONObject

/** Accessibility events identify reported actions, never raw touches or a certain human actor. */
@Suppress("DEPRECATION")
class BehaviorRecorder {
    private var lastKey=""
    private var lastAt=0L
    private var suppressed=0
    fun record(event:AccessibilityEvent,snapshot:Snapshot?,automatic:Boolean):JSONObject? {
        val kind=when(event.eventType) {
            AccessibilityEvent.TYPE_VIEW_CLICKED->"reported_click"
            AccessibilityEvent.TYPE_VIEW_SCROLLED->"reported_scroll"
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED->"reported_text_change"
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,AccessibilityEvent.TYPE_WINDOWS_CHANGED->"window_change"
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED->"content_change"
            else->return null
        }
        val source=event.source
        val rect=Rect()
        val details=try {
            source?.getBoundsInScreen(rect)
            val node=snapshot?.nodes?.find { it.box.left==rect.left && it.box.top==rect.top && it.box.right==rect.right && it.box.bottom==rect.bottom }
            // Never persist event.text, beforeText, contentDescription or typed values (including OTP).
            JSONObject().put("node_id",node?.id ?: JSONObject.NULL).put("view_id",source?.viewIdResourceName ?: JSONObject.NULL)
                .put("bounds",if(source==null) JSONObject.NULL else org.json.JSONArray(listOf(rect.left,rect.top,rect.right,rect.bottom)))
                .put("text_redacted",true).put("password",event.isPassword)
        } finally { source?.recycle() }
        val key="$kind|${event.packageName}|${details.opt("bounds")}|${event.scrollX}|${event.scrollY}"
        if(key==lastKey && event.eventTime-lastAt<250) { suppressed++;return null }
        val count=suppressed;suppressed=0;lastKey=key;lastAt=event.eventTime
        return details.put("event_type",kind).put("system_event_time_ms",event.eventTime).put("package",event.packageName?.toString())
            .put("actor",if(automatic) "automatic_window_uncertain" else "user_or_external_app")
            .put("actor_certain",false).put("evidence_source","android_accessibility_event").put("duplicates_suppressed_before",count)
            .put("scroll_x",event.scrollX).put("scroll_y",event.scrollY)
            .put("before_snapshot_id",snapshot?.id ?: JSONObject.NULL)
            .put("limitation","系统事件不证明物理点击来源；页面切换不证明点击或返回，无法获取完整触摸轨迹")
    }
}
