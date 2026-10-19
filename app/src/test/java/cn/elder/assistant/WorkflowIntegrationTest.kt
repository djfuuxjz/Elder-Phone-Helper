package cn.elder.assistant

import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import cn.elder.assistant.core.*
import cn.elder.assistant.workflow.*
import cn.elder.assistant.model.*
import cn.elder.assistant.guidance.*
import cn.elder.assistant.accessibility.GuideService
import cn.elder.assistant.ui.WorkflowActivity
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.nio.file.Files
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
class WorkflowIntegrationTest {
    private fun store()=WorkflowStore(Files.createTempDirectory("review").resolve("library.json").toFile())
    private fun event(name:String,data:JSONObject=JSONObject())=JSONObject().put("event",name).put("data",data)
    private fun response(id:String="request1")=JSONObject().put("workflow",WorkflowStore.builtin()).put("rationale","删除已观察到的往返；更短路线待真机验证")
        .put("evidence_request_ids",JSONArray().put(id)).toString()
    @Test fun reviewRequiresActualCompletionEvidence() {
        assertThrows(IllegalArgumentException::class.java) { WorkflowReview.body("test",null,listOf(event("guidance"))) }
    }
    @Test fun reviewContainsFailureAndDisplayedGuidanceWithoutRawNetworkSecrets() {
        val input=WorkflowReview.body("test",null,listOf(event("guidance",JSONObject().put("instruction","点击加号")),event("invalid_decision",JSONObject().put("error","空白目标")),event("response",JSONObject().put("output_raw","secret-envelope")),event("completed_evidence")))
        val text=input.getJSONArray("messages").getJSONObject(1).getString("content")
        assertTrue(text.contains("空白目标"));assertTrue(text.contains("点击加号"));assertFalse(text.contains("secret-envelope"))
    }
    @Test fun validReviewPersistsProvenanceAndActivatesForNextRun() {
        val store=store();val id=WorkflowReview.save(response(),store,"builtin","run_success",setOf("request1"))
        val saved=store.list().first { it.getString("id")==id }
        assertEquals("run_success",saved.getString("source_run"));assertEquals("model",saved.getString("source"));assertTrue(saved.getBoolean("active"))
        assertEquals(id,store.selected()!!.getString("id"))
    }
    @Test fun fabricatedReviewEvidenceDoesNotWriteCandidate() {
        val store=store();assertThrows(IllegalArgumentException::class.java) { WorkflowReview.save(response("fake"),store,"builtin","run",setOf("request1")) };assertEquals(1,store.list().size)
    }
    @Test fun requestCarriesWorkflowPhaseAndActualInputNodes() {
        val page=Snapshot("s","com.tencent.mm",1,1080,2400,listOf(UiNode("edit","","","EditText",true,Box(10,100,900,200),true,true,"搜索")),0)
        val body=DeepSeekProvider("https://example.test","test","test-key",false).body(ModelInput("run",1,1,page,listOf("未完成"),workflow=WorkflowGuide.context(store().selected(),page)))
        val user=JSONObject(body.getJSONArray("messages").getJSONObject(1).getString("content"))
        assertEquals("search_input",user.getJSONObject("workflow").getString("current_phase"))
        assertEquals("edit",user.getJSONObject("workflow").getJSONArray("input_nodes").getString(0))
        assertTrue(user.getJSONObject("snapshot").getJSONArray("nodes").getJSONObject(0).getBoolean("focused"))
    }
    private fun all(v:View):List<View> = listOf(v)+if(v is ViewGroup) (0 until v.childCount).flatMap { all(v.getChildAt(it)) } else emptyList()
    @Test fun developerCanTrialDisableAndRollbackFromPage() {
        AppRuntime.active=false;AppRuntime.reviewRunning=false;AppRuntime.workflowTrialId=null
        try { Robolectric.buildActivity(WorkflowActivity::class.java).setup().use { c ->
            fun click(text:String) { all(c.get().window.decorView).filterIsInstance<Button>().first { it.text.toString().contains(text) }.performClick() }
            click("已启用 ·");click("下次任务试运行此版本");assertEquals("builtin",AppRuntime.workflowTrialId)
            click("停用流程约束");val s=WorkflowStore(java.io.File(c.get().filesDir,"workflows/library.json"));assertNull(s.selected())
            click("候选/保留 ·");click("启用 / 回退");assertEquals("builtin",s.selected()!!.getString("id"))
        } } finally { AppRuntime.workflowTrialId=null }
    }
    @Test fun successfulControllerStopsSessionBeforeNoticeAndNeverRestartsAfterTimer() {
        val c=Robolectric.buildService(GuideService::class.java).create()
        try {
            val controller=GuidanceController(c.get())
            val field=GuidanceController::class.java.getDeclaredField("flow").apply { isAccessible=true };val flow=field.get(controller) as SessionFlow
            flow.start();flow.observe("call");val token=flow.request();AppRuntime.active=true
            controller.stop(Phase.COMPLETED)
            assertFalse(flow.active);assertFalse(flow.accepts(token));assertFalse(AppRuntime.active);assertEquals(Phase.COMPLETED,flow.phase)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1000))
            assertFalse(flow.observe("changed"));assertEquals(Phase.COMPLETED,flow.phase)
            controller.close()
        } finally { c.destroy() }
    }
}
