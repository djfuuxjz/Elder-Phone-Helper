package cn.elder.assistant

import android.os.Looper
import android.view.*
import android.widget.TextView
import cn.elder.assistant.accessibility.GuideService
import cn.elder.assistant.core.Box
import cn.elder.assistant.core.AppRuntime
import cn.elder.assistant.guidance.GuidanceController
import cn.elder.assistant.guidance.SessionFlow
import cn.elder.assistant.overlay.GuideOverlay
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.lang.reflect.Proxy

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class GuideOverlayTest {
    private fun org.robolectric.android.controller.ServiceController<GuideService>.use(block:(org.robolectric.android.controller.ServiceController<GuideService>)->Unit) {
        try { block(this) } finally { destroy() }
    }
    private class Windows(val real:WindowManager) {
        val views=linkedMapOf<View,WindowManager.LayoutParams>()
        val wm=Proxy.newProxyInstance(WindowManager::class.java.classLoader,arrayOf(WindowManager::class.java)) { _,method,args ->
            when(method.name) {
                "addView","updateViewLayout" -> { views[args!![0] as View]=args[1] as WindowManager.LayoutParams;null }
                "removeViewImmediate" -> { if(views.remove(args!![0])==null) throw IllegalArgumentException("not registered");null }
                else -> method.invoke(real,*(args ?: emptyArray()))
            }
        } as WindowManager
    }
    private fun descendants(v:View):List<View> = listOf(v)+if(v is ViewGroup) (0 until v.childCount).flatMap { descendants(v.getChildAt(it)) } else emptyList()
    private fun text(root:View,value:String)=descendants(root).filterIsInstance<TextView>().single { it.text.toString()==value }
    private fun event(v:View,action:Int,x:Float,y:Float) { val e=MotionEvent.obtain(0,0,action,x,y,0);try { assertTrue(v.dispatchTouchEvent(e)) } finally { e.recycle() } }

    @Test fun stopRemovesPendingUnattachedWindowsBeforeCallback() {
        Robolectric.buildService(GuideService::class.java).create().use { c ->
            val windows=Windows(c.get().getSystemService(WindowManager::class.java));var stopped=false
            val overlay=GuideOverlay(c.get(),{ assertTrue(windows.views.isEmpty());stopped=true },{},windows.wm)
            overlay.show("点击目标",Box(0,0,50,50))
            assertEquals(2,windows.views.size)
            assertTrue(windows.views.keys.none { it.isAttachedToWindow })
            text(windows.views.keys.last(),"停止").performClick()
            assertTrue(stopped);assertTrue(windows.views.isEmpty())
            shadowOf(Looper.getMainLooper()).idle();assertTrue(windows.views.isEmpty())
            overlay.hide() // Repeated cleanup is safe.
        }
    }
    @Test fun dragClampsToScreenAndSurvivesInstructionRefresh() {
        Robolectric.buildService(GuideService::class.java).create().use { c ->
            val windows=Windows(c.get().getSystemService(WindowManager::class.java));var stopped=false
            val overlay=GuideOverlay(c.get(),{ stopped=true },{},windows.wm)
            overlay.show("第一步")
            val card=windows.views.keys.single();val handle=text(card,"按住这里拖动")
            event(handle,MotionEvent.ACTION_DOWN,100f,100f)
            event(handle,MotionEvent.ACTION_MOVE,-10000f,-10000f)
            event(handle,MotionEvent.ACTION_UP,-10000f,-10000f)
            val first=windows.views.getValue(card);assertEquals(0,first.x);assertEquals(0,first.y);assertFalse(stopped)
            overlay.show("下一步",retry=true)
            assertEquals(1,windows.views.size)
            val replacement=windows.views.keys.single();assertEquals(0,windows.views.getValue(replacement).y)
            val message=text(replacement,"下一步")
            event(message,MotionEvent.ACTION_DOWN,0f,0f);event(message,MotionEvent.ACTION_MOVE,10000f,10000f);event(message,MotionEvent.ACTION_UP,10000f,10000f)
            val moved=windows.views.getValue(replacement);val metrics=windows.real.currentWindowMetrics
            val insets=metrics.windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            assertTrue(moved.x+moved.width<=metrics.bounds.width()-insets.left-insets.right)
            assertTrue(moved.y+moved.height<=metrics.bounds.height()-insets.top-insets.bottom)
            text(replacement,"停止").performClick();assertTrue(stopped);assertTrue(windows.views.isEmpty())
        }
    }
    @Test fun retryRemainsClickableAndTargetCollisionKeepsStopAvailable() {
        Robolectric.buildService(GuideService::class.java).create().use { c ->
            val windows=Windows(c.get().getSystemService(WindowManager::class.java));var retries=0
            val overlay=GuideOverlay(c.get(),{},{ retries++ },windows.wm)
            overlay.show("测试",Box(0,0,100,100),retry=true)
            shadowOf(Looper.getMainLooper()).idle()
            val card=windows.views.keys.last();text(card,"重试").performClick()
            assertEquals(1,retries);assertNotNull(text(card,"停止"))
            // System removed the highlighter first: cleanup must still remove the card.
            windows.views.remove(windows.views.keys.first());overlay.hide();assertTrue(windows.views.isEmpty())
        }
    }
    @Test fun userStopInvalidatesRequestAndOpensAssistantEvenWhenRepeated() {
        Robolectric.buildService(GuideService::class.java).create().use { c ->
            val controller=GuidanceController(c.get())
            try {
                val field=GuidanceController::class.java.getDeclaredField("flow").apply { isAccessible=true }
                val flow=field.get(controller) as SessionFlow
                flow.start();flow.observe("page");val request=flow.request();AppRuntime.active=true
                controller.stopAndReturn()
                assertFalse(flow.accepts(request));assertFalse(AppRuntime.active)
                val intent=shadowOf(c.get()).nextStartedActivity
                assertEquals("cn.elder.assistant.ui.MainActivity",intent.component!!.className)
                assertTrue(intent.flags and android.content.Intent.FLAG_ACTIVITY_NEW_TASK!=0)
                controller.stopAndReturn();assertNotNull(shadowOf(c.get()).nextStartedActivity)
                controller.close();assertNull(shadowOf(c.get()).nextStartedActivity)
            } finally { controller.close() }
        }
    }
}
