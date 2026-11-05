package cn.elder.assistant

import cn.elder.assistant.core.*
import cn.elder.assistant.model.*
import cn.elder.assistant.guidance.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class DecisionDiagnosticsTest {
    private val raw="""{"current_state":"当前在手机桌面第5页，底部右侧可见设置图标","next_action":"click","target_node":"","target_bbox":[972,2310,1112,2475],"instruction":"点击桌面右下角的设置图标","confidence":0.75,"task_completed":false,"evidence":["桌面底部右侧有齿轮形设置图标"]}"""
    private val s=Snapshot("s","launcher",1,1200,2670,emptyList(),0)
    @Test fun blankNodeWithValidCoordinatesAccepted() {
        for(value in listOf("","  ","\t\n")) {
            val d=DecisionCodec.parse(JSONObject(raw).put("target_node",value).toString())
            assertNull(d.node);assertEquals(Box(972,2310,1112,2475),DecisionCodec.target(d,s))
        }
    }
    @Test fun nullJsonAcceptedButLiteralNullAndUnknownIdsRejected() {
        assertNull(DecisionCodec.parse(JSONObject(raw).put("target_node",JSONObject.NULL).toString()).node)
        for(id in listOf("null","old_id"))assertThrows(IllegalStateException::class.java) { DecisionCodec.target(DecisionCodec.parse(JSONObject(raw).put("target_node",id).toString()),s) }
    }
    @Test fun otherEmptyFieldsRemainRejectedWithRuleAndExpectedActual() {
        for(field in listOf("current_state","instruction","next_action","confidence","task_completed")) {
            val e=assertThrows(RuleFailure::class.java) { DecisionCodec.parse(JSONObject(raw).put(field,"").toString()) }
            val j=Diagnostic.describe(e,"parse");assertEquals("parse",j.getString("stage"));assertTrue(j.has("rule"));assertTrue(j.has("expected"));assertTrue(j.has("actual"));assertFalse(j.getString("error").contains("Failed requirement"))
        }
    }
    @Test fun targetGuardsRemainAndDescribeExactBounds() {
        val d=DecisionCodec.parse(raw)
        val missing=assertThrows(RuleFailure::class.java) { DecisionCodec.target(d.copy(box=null),s) }
        assertEquals("MISSING_CLICK_TARGET",missing.code)
        val outside=assertThrows(RuleFailure::class.java) { DecisionCodec.target(d.copy(box=Box(972,2310,1300,2475)),s) }
        assertTrue(outside.message!!.contains("right=1300"));assertTrue(outside.message!!.contains("1200"))
        assertEquals("TARGET_TOO_LARGE",assertThrows(RuleFailure::class.java) { DecisionCodec.target(d.copy(box=Box(0,0,1200,2670)),s) }.code)
        val node=UiNode("s_1","设置","","icon",true,Box(0,0,50,50))
        assertEquals("NODE_BBOX_MISMATCH",assertThrows(RuleFailure::class.java) { DecisionCodec.target(d.copy(node="s_1"),s.copy(nodes=listOf(node))) }.code)
    }
    @Test fun autoGuiPolicyAndForegroundVerificationDoNotConfuseDispatchWithSuccess() {
        assertFalse(GuiExecutor.allowed(false,Action.CLICK));assertTrue(GuiExecutor.allowed(true,Action.CLICK))
        assertFalse(GuiExecutor.allowed(true,Action.INPUT));assertFalse(GuiExecutor.allowed(true,Action.SCROLL))
        assertFalse(TargetAppLauncher.verifies("camera.app","launcher"));assertTrue(TargetAppLauncher.verifies("camera.app","camera.app"))
    }
    @Test fun apiVerificationPreventsGuiAndFallbackIsExplicitAndBounded() {
        assertFalse(TargetAppLauncher.guiFallback(true,true,false,true,1))
        assertTrue(TargetAppLauncher.guiFallback(false,true,false,true,1))
        assertFalse(TargetAppLauncher.guiFallback(false,false,false,true,1))
        assertFalse(TargetAppLauncher.guiFallback(false,true,true,true,1))
        assertFalse(TargetAppLauncher.guiFallback(false,true,false,true,2))
        assertFalse(TargetAppLauncher.guiFallback(false,true,false,false,1))
    }
    @Test fun screenshotMetadataStaysWithSnapshotAndRequestWithoutSendingImageToTextModel() {
        val info=JSONObject().put("snapshot_id",s.id).put("request_id","r_2_1").put("image_width",600).put("image_height",1335).put("screenshot_saved",true)
        val p=DeepSeekProvider("https://example.com","test","key",false)
        val body=p.body(ModelInput("r",2,1,s,emptyList(),captureInfo=info))
        val observation=JSONObject(body.getJSONArray("messages").getJSONObject(1).getString("content"))
        assertEquals(s.id,observation.getJSONObject("snapshot").getString("snapshot_id"))
        assertEquals("r_2_1",observation.getJSONObject("capture_info").getString("request_id"))
        assertEquals(600,observation.getJSONObject("capture_info").getInt("image_width"))
    }
    @Test fun openAppRequiresActualPackageFieldAndDoesNotDeclareCompletion() {
        val open=JSONObject(raw).put("next_action","open_app").put("target_app","test.installed")
        assertEquals("test.installed",DecisionCodec.parse(open.toString()).targetApp)
        assertThrows(RuleFailure::class.java) { DecisionCodec.parse(open.put("task_completed",true).toString()) }
    }
    @Test fun onlyExactOpenGoalCanCompleteOnForegroundAndModelNeedsEvidence() {
        val apps=listOf(InstalledApp("camera.app","相机"));val page=s.copy(packageName="camera.app")
        assertTrue(CompletionCheck.local(TaskGoal.custom("打开相机"),page,apps))
        assertFalse(CompletionCheck.local(TaskGoal.custom("打开相机并拍照"),page,apps))
        assertFalse(CompletionCheck.local(TaskGoal.custom("打开相机"),s,apps))
        val d=DecisionCodec.parse(raw)
        assertFalse(CompletionCheck.evidence(d,s,true))
        assertFalse(CompletionCheck.evidence(d.copy(action=Action.COMPLETE,completed=true,evidence=emptyList()),s,true))
        assertFalse(CompletionCheck.evidence(d.copy(action=Action.COMPLETE,completed=true),s,false))
    }
}
