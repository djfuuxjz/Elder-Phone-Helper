package cn.elder.assistant

import cn.elder.assistant.accessibility.GuideService
import cn.elder.assistant.core.*
import cn.elder.assistant.guidance.*
import cn.elder.assistant.logging.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class PipelineDiagnosticsTest {
    private fun field(c:GuidanceController,n:String)=GuidanceController::class.java.getDeclaredField(n).apply { isAccessible=true }
    private fun apply(c:GuidanceController,t:RequestToken,s:Snapshot,raw:String,base:JSONObject) {
        GuidanceController::class.java.getDeclaredMethod("applyDecision",RequestToken::class.java,Snapshot::class.java,String::class.java,Boolean::class.javaPrimitiveType,JSONObject::class.java)
            .apply { isAccessible=true }.invoke(c,t,s,raw,true,base)
        c.awaitLogs()
    }
    private val raw="""{"current_state":"桌面","next_action":"click","target_node":"","target_bbox":[972,2310,1112,2475],"instruction":"点击设置","confidence":0.75,"task_completed":false,"evidence":["设置"]}"""
    @Test fun manualClickIsOnlyGuidanceAndInvalidCoordinatesKeepDiagnosticsAndReferences() {
        val service=Robolectric.buildService(GuideService::class.java).create();val c=GuidanceController(service.get())
        try {
            val flow=field(c,"flow").get(c) as SessionFlow
            val log=RunLog(File(service.get().cacheDir,"pipeline-runs"));field(c,"log").set(c,log)
            val s=Snapshot("snap","launcher",1,1200,2670,emptyList(),1)
            val base=JSONObject().put("request_id","r").put("snapshot_id",s.id).put("step",1).put("attempt",1).put("ui_tree_ref","r_tree.json").put("screenshot_ref","r.jpg")
            flow.start();flow.observe("desktop");apply(c,flow.request(),s,raw,base)
            assertEquals(Phase.WAITING_USER,flow.phase)
            var events=RunMetrics.events(log.directory)
            assertFalse(events.any { it.optString("event")=="execution_requested" })
            assertFalse(events.first { it.optString("event")=="guidance" }.getJSONObject("data").getBoolean("executed"))
            flow.retry();apply(c,flow.request(),s,JSONObject(raw).put("target_bbox",Box(0,0,1300,300).json()).toString(),base)
            events=RunMetrics.events(log.directory)
            val d=events.first { it.optString("event")=="invalid_decision" }.getJSONObject("data")
            assertEquals("BBOX_OUT_OF_BOUNDS",d.getString("error_code"));assertEquals("target",d.getString("stage"))
            assertEquals("r.jpg",d.getString("screenshot_ref"));assertEquals("snap",d.getString("snapshot_id"));assertTrue(d.getString("error").contains("1300"))
        } finally { c.close();service.destroy() }
    }
    @Test fun completeWithNoCurrentEvidenceNeverDisplaysSuccess() {
        val service=Robolectric.buildService(GuideService::class.java).create();val c=GuidanceController(service.get())
        try {
            val flow=field(c,"flow").get(c) as SessionFlow
            val log=RunLog(File(service.get().cacheDir,"completion-runs"));field(c,"log").set(c,log)
            flow.start();flow.observe("s")
            val j=JSONObject(raw).put("next_action","complete").put("task_completed",true).put("evidence",org.json.JSONArray())
            apply(c,flow.request(),Snapshot("s","app",1,1200,2670,emptyList(),0),j.toString(),JSONObject())
            assertNotEquals(Phase.COMPLETED,flow.phase)
            assertFalse(RunMetrics.events(log.directory).any { it.optString("event")=="success_displayed" })
        } finally { c.close();service.destroy() }
    }
}
