package cn.elder.assistant

import android.view.accessibility.AccessibilityEvent
import cn.elder.assistant.logging.BehaviorRecorder
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class BehaviorRecorderTest {
    @Test fun textEventsDoNotStoreOtpAndActorIsNotAssumedHuman() {
        val r=BehaviorRecorder()
        val event=AccessibilityEvent.obtain(AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED).apply { packageName="test.app";eventTime=1000;text.add("secret-OTP-123456");isPassword=true }
        val data=r.record(event,null,false)!!
        assertFalse(data.toString().contains("123456"));assertFalse(data.getBoolean("actor_certain"));assertEquals("user_or_external_app",data.getString("actor"))
        event.eventTime=1100;assertNull(r.record(event,null,false))
        event.eventTime=1500
        assertEquals("automatic_window_uncertain",r.record(event,null,true)!!.getString("actor"))
    }
    @Test fun pageChangeIsNeverReportedAsClickOrBack() {
        val e=AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED).apply { packageName="test.app";eventTime=1000 }
        assertEquals("window_change",BehaviorRecorder().record(e,null,false)!!.getString("event_type"))
    }
}
