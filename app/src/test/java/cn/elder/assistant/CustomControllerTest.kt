package cn.elder.assistant

import android.os.Looper
import cn.elder.assistant.accessibility.GuideService
import cn.elder.assistant.core.*
import cn.elder.assistant.guidance.*
import cn.elder.assistant.logging.RunLog
import cn.elder.assistant.logging.RunMetrics
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class CustomControllerTest {
    private fun field(c:GuidanceController,name:String)=GuidanceController::class.java.getDeclaredField(name).apply { isAccessible=true }
    @Test fun completedCustomTaskStopsAndDoesNotWriteDaughterWorkflowReview() {
        val c=Robolectric.buildService(GuideService::class.java).create()
        val controller=GuidanceController(c.get())
        try {
            val goal=TaskGoal.custom("打开相机")
            field(controller,"task").set(controller,goal)
            val flow=field(controller,"flow").get(controller) as SessionFlow
            val log=RunLog(File(c.get().cacheDir,"custom-runs"),task=goal)
            field(controller,"log").set(controller,log)
            flow.start();flow.observe("camera");val token=flow.request();AppRuntime.active=true
            // A second start while active must not replace the in-flight custom goal.
            controller.start(TaskGoal.DEFAULT)
            assertSame(goal,field(controller,"task").get(controller))
            controller.stop(Phase.COMPLETED)
            assertFalse(flow.accepts(token));assertFalse(AppRuntime.active);assertEquals("任务已成功",AppRuntime.status)
            assertFalse(AppRuntime.reviewRunning)
            controller.awaitLogs()
            assertTrue(RunMetrics.events(log.directory).any { it.getString("event")=="workflow_review_not_applicable" })
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1000))
            assertFalse(flow.observe("new-page"));assertEquals(Phase.COMPLETED,flow.phase)
            assertNull(field(controller,"reviewProvider").get(controller))
        } finally { controller.close();c.destroy() }
    }
}
