package cn.elder.assistant

import android.os.Handler
import android.os.Looper
import cn.elder.assistant.core.*
import cn.elder.assistant.guidance.*
import cn.elder.assistant.logging.RunNames
import cn.elder.assistant.workflow.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.nio.file.Files
import java.time.Duration
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
class Revision013Test {
    private fun node(id:String,t:String,y:Int=100,kind:String="TextView")=UiNode(id,t,"",kind,true,Box(10,y,400,y+50))
    private fun page(vararg n:UiNode)=Snapshot("s","com.tencent.mm",1,1080,2400,n.toList(),0)
    private fun decision(action:Action=Action.CLICK,id:String?="blank",text:String="请点击搜索")=Decision("search",action,id,null,text,0.9,false,emptyList())
    @Test fun modelConfirmedCallNeedsNoLiteralVideoLabel() {
        assertTrue(CompletionCheck.modelConfirms(decision(Action.COMPLETE,null).copy(completed=true,evidence=listOf("呼叫已发起"))))
    }
    @Test fun modelUnfinishedCallDoesNotAutoCompleteFromLocalControls() {
        assertFalse(CompletionCheck.modelConfirms(decision(Action.WAIT,null).copy(evidence=listOf("摄像头已开","挂断","00:08"))))
    }
    @Test fun successVisibleForExactlyOneSecondAndRestartCancelsOldHide() {
        var visible=false;val notice=SuccessNotice(Handler(Looper.getMainLooper()),{visible=true},{visible=false})
        notice.show();shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(999));assertTrue(visible)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1));assertFalse(visible)
        notice.show();notice.cancel();shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2));assertTrue(visible)
    }
    @Test fun inputPreservesModelTargetInsteadOfOverridingToEditableField() {
        val blank=node("blank","");val input=node("edit","",200,"android.widget.EditText")
        assertEquals(blank.box,WorkflowGuide.target(decision(Action.INPUT),page(blank,input),blank.box,true))
    }
    @Test fun ambiguousInputsPreserveModelCoordinates() { val box=Box(10,100,400,150);assertEquals(box,WorkflowGuide.target(decision(Action.INPUT),page(node("a","",100,"EditText"),node("b","",200,"EditText")),box,true)) }
    @Test fun searchTargetNotOverriddenByOtherLabel() {
        val search=node("search","搜索",200);assertEquals(Box(0,300,1000,352),WorkflowGuide.target(decision(),page(node("blank",""),search),Box(0,300,1000,352),true))
    }
    @Test fun unlabeledSearchTargetIsNotRejectedByBusinessRule() { val box=Box(0,300,1000,352);assertEquals(box,WorkflowGuide.target(decision(),page(node("blank","")),box,true)) }
    @Test(expected=IllegalStateException::class) fun scrollingContactsIsRejectedWithSpecificReason() { WorkflowGuide.target(decision(Action.SCROLL),page(node("search","搜索")),null,true) }
    @Test fun chatMoreInformationPreservesModelChoice() {
        val plus=node("plus","更多功能",2100);val more=node("more","更多信息",100)
        assertEquals(more.box,WorkflowGuide.target(decision(id="more",text="点击更多信息"),page(plus,more),more.box,true))
    }
    @Test fun candidatesDoNotReplaceActiveAndRollbackPersists() {
        val store=WorkflowStore(Files.createTempDirectory("flows").resolve("library.json").toFile())
        val id=store.add(WorkflowStore.builtin(),"model","builtin","run_test","去掉绕路")
        assertEquals("builtin",store.selected()!!.getString("id"));assertEquals(id,store.selected(id)!!.getString("id"))
        store.activate(id);assertEquals(id,store.selected()!!.getString("id"));store.activate("builtin");assertEquals("builtin",store.selected()!!.getString("id"))
        store.activate(null);assertNull(store.selected())
    }
    @Test(expected=IllegalArgumentException::class) fun lockedVersionCannotBeEdited() { WorkflowStore(Files.createTempDirectory("flows").resolve("library.json").toFile()).revise("builtin",WorkflowStore.builtin()) }
    @Test(expected=IllegalArgumentException::class) fun unrelatedTaskCannotBeImported() { WorkflowStore.validate(WorkflowStore.builtin().put("task_key","payment")) }
    @Test fun legacyRunExportUsesEventDateAndNewIdsAreUnique() {
        val dir=Files.createTempDirectory("run_old").toFile();java.io.File(dir,"events.jsonl").writeText(JSONObject().put("timestamp",0).toString()+"\n")
        assertEquals(0,RunNames.time(dir));assertEquals("19700101_080000_000",RunNames.stamp(0,ZoneId.of("Asia/Shanghai")))
        assertNotEquals(RunNames.newId(0),RunNames.newId(0));assertTrue(RunNames.exportName(dir).startsWith("debug_"))
    }
}
