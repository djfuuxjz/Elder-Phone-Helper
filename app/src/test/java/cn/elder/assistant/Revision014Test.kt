package cn.elder.assistant

import cn.elder.assistant.core.*
import cn.elder.assistant.workflow.*
import cn.elder.assistant.model.DecisionCodec
import cn.elder.assistant.guidance.*
import cn.elder.assistant.accessibility.GuideService
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
class Revision014Test {
    private fun store()=WorkflowStore(Files.createTempDirectory("flows014").resolve("library.json").toFile())
    private fun review(store:WorkflowStore,base:String="builtin")=WorkflowReview.save(JSONObject().put("workflow",WorkflowStore.builtin()).put("rationale","删除重复返回，推测捷径尚未验证").put("evidence_request_ids",JSONArray().put("r1")).toString(),store,base,"run_success",setOf("r1"))
    @Test fun realLogUnlabeledSearchImageAndMatchingBboxAreAccepted() {
        val box=Box(901,147,1047,293);val n=UiNode("snap_36","","","android.widget.ImageView",true,box)
        val s=Snapshot("s","com.tencent.mm",1,1200,2670,listOf(n),0,true)
        val d=Decision("微信首页",Action.CLICK,n.id,box,"点击右上角放大镜搜索",0.85,false,listOf("右上角放大镜图标"))
        assertEquals(box,WorkflowGuide.target(d,s,DecisionCodec.target(d,s),true))
    }
    @Test fun screenshotOnlyInputIsAcceptedWithValidBbox() {
        val box=Box(100,100,900,200);val s=Snapshot("s","com.tencent.mm",1,1200,2670,emptyList(),0)
        val d=Decision("输入页",Action.INPUT,null,box,"请输入女儿",0.8,false,emptyList())
        assertEquals(box,WorkflowGuide.target(d,s,DecisionCodec.target(d,s),true))
        assertThrows(IllegalArgumentException::class.java) { DecisionCodec.target(d.copy(box=Box(-1,0,900,100)),s) }
    }
    @Test fun reviewAutomaticallyReplacesPreviouslyLearnedVersionAndCanRollback() {
        val s=store();val first=review(s);val second=review(s,first)
        assertEquals(second,s.selected()!!.getString("id"));assertEquals(3,s.list().size)
        s.activate(first);assertEquals(first,s.selected()!!.getString("id"))
    }
    @Test fun lockedManualWorkflowIsPreservedAndReviewStillSaved() {
        val s=store();val manual=s.add(WorkflowStore.builtin());s.activate(manual);s.setLocked(manual,true)
        val learned=review(s,manual);assertEquals(manual,s.selected()!!.getString("id"));assertTrue(s.list().any { it.getString("id")==learned })
    }
    @Test fun explicitBuiltinLockAlsoPreventsAutoSwitch() { val s=store();s.setLocked("builtin",true);review(s);assertEquals("builtin",s.selected()!!.getString("id")) }
    @Test fun disablingOrSwitchingWhileRunningIsNotUndoneByReview() {
        val s=store();s.activate(null);review(s);assertNull(s.selected())
        val manual=s.add(WorkflowStore.builtin());s.activate(manual);review(s);assertEquals(manual,s.selected()!!.getString("id"))
    }
    @Test fun parsedCompleteWithoutCurrentEvidenceIsRejected() {
        val service=Robolectric.buildService(GuideService::class.java).create()
        val c=GuidanceController(service.get())
        try {
            val flow=GuidanceController::class.java.getDeclaredField("flow").apply { isAccessible=true }.get(c) as SessionFlow
            flow.start();flow.observe("page");val token=flow.request()
            val s=Snapshot("s","com.tencent.mm",1,1200,2670,emptyList(),0,true)
            val raw=JSONObject().put("current_state","done").put("next_action","complete").put("instruction","任务已成功").put("confidence",0.2).put("task_completed",true).put("evidence",JSONArray()).toString()
            val method=GuidanceController::class.java.getDeclaredMethod("applyDecision",RequestToken::class.java,Snapshot::class.java,String::class.java,Boolean::class.javaPrimitiveType,JSONObject::class.java).apply { isAccessible=true }
            method.invoke(c,token,s,raw,false,JSONObject())
            assertNotEquals(Phase.COMPLETED,flow.phase);assertTrue(flow.active);assertFalse(flow.accepts(token))
        } finally { c.close();service.destroy() }
    }
}
