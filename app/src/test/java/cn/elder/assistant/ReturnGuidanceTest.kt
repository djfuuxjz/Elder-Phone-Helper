package cn.elder.assistant

import cn.elder.assistant.core.*
import cn.elder.assistant.guidance.*
import cn.elder.assistant.model.DecisionCodec
import cn.elder.assistant.accessibility.GuideService
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ReturnGuidanceTest {
    private val box=Box(0,100,100,200)
    private fun decision()=Decision("file",Action.BACK,null,null,"请点返回键",0.35,false,emptyList())
    @Test fun realLogMissingBackLocationRequestsScreenshot() { assertTrue(ReturnGuidance.needsLocation(decision(),null,false,true,1)) }
    @Test fun noVisionRequestsOneTextClarificationOnly() { assertTrue(ReturnGuidance.needsLocation(decision(),null,false,false,1));assertFalse(ReturnGuidance.needsLocation(decision(),null,false,false,2)) }
    @Test fun fallbackAfterImageOrAttemptLimitDoesNotLoop() { assertFalse(ReturnGuidance.needsLocation(decision(),null,true,true,2));assertFalse(ReturnGuidance.needsLocation(decision(),null,false,true,3)) }
    @Test fun visibleBackTargetIsHighlightedLikeClickAndInput() {
        listOf(Action.BACK,Action.CLICK,Action.INPUT).forEach { assertEquals(box,ReturnGuidance.highlight(it,box)) }
        assertNull(ReturnGuidance.highlight(Action.WAIT,box));assertFalse(ReturnGuidance.needsLocation(decision(),box,false,true,1))
    }
    @Test fun unlabeledBackArrowAndScreenshotCoordinatesAccepted() {
        val n=UiNode("arrow","","","ImageView",true,box);val s=Snapshot("s","com.tencent.mm",1,1200,2670,listOf(n),0)
        assertEquals(box,DecisionCodec.target(decision().copy(node="arrow",box=box),s))
        assertEquals(box,DecisionCodec.target(decision().copy(box=box),s))
        assertThrows(IllegalArgumentException::class.java) { DecisionCodec.target(decision().copy(box=Box(0,0,1200,2670)),s) }
        val stale=assertThrows(IllegalStateException::class.java) { DecisionCodec.target(decision().copy(node="old"),s) }
        assertTrue(stale.message!!.startsWith("目标节点不属于当前页面"))
    }
    @Test fun controllerRequestsLocationRatherThanShowingVagueFirstBackInstruction() {
        val service=Robolectric.buildService(GuideService::class.java).create();val c=GuidanceController(service.get())
        try {
            val flow=GuidanceController::class.java.getDeclaredField("flow").apply { isAccessible=true }.get(c) as SessionFlow
            flow.start();flow.observe("file");val token=flow.request()
            val s=Snapshot("s","com.tencent.mm",1,1200,2670,emptyList(),0)
            val raw=JSONObject().put("current_state","file").put("next_action","back").put("instruction","请点返回键").put("confidence",0.35).put("task_completed",false).toString()
            GuidanceController::class.java.getDeclaredMethod("applyDecision",RequestToken::class.java,Snapshot::class.java,String::class.java,Boolean::class.javaPrimitiveType,JSONObject::class.java).apply { isAccessible=true }.invoke(c,token,s,raw,false,JSONObject())
            assertEquals(Phase.OBSERVING,flow.phase)
            assertTrue(GuidanceController::class.java.getDeclaredField("needImage").apply { isAccessible=true }.getBoolean(c))
            val history=GuidanceController::class.java.getDeclaredField("history").apply { isAccessible=true }.get(c) as TaskHistory
            assertTrue(history.snapshot().any { it.contains("补充返回位置") })
        } finally { c.close();service.destroy() }
    }
}
