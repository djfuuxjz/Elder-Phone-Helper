package cn.elder.assistant

import cn.elder.assistant.guidance.*
import cn.elder.assistant.core.*
import cn.elder.assistant.model.DecisionCodec
import org.junit.Assert.*
import org.junit.Test

class FlowTest {
    private fun flow()=SessionFlow().apply { start();observe("home") }
    @Test fun newPageStartsNewStep() { val f=flow();assertEquals(1,f.step);assertFalse(f.observe("home"));assertTrue(f.observe("wechat"));assertEquals(2,f.step) }
    @Test fun repeatedEventsDoNotCreateRequestLoop() { val f=flow();val t=f.request();f.responded(t,Phase.WAITING_USER);repeat(100) { assertFalse(f.observe("home")) };assertEquals(1,f.attempt) }
    @Test fun pageChangeInvalidatesInflightResponse() { val f=flow();val t=f.request();f.observe("wrong_chat");assertFalse(f.accepts(t));assertFalse(f.responded(t,Phase.WAITING_USER)) }
    @Test fun stopInvalidatesResponse() { val f=flow();val t=f.request();f.finish(Phase.STOPPED);assertFalse(f.accepts(t));assertFalse(f.active) }
    @Test fun restartNeverAcceptsOldSessionResponse() { val f=flow();val t=f.request();f.finish(Phase.STOPPED);f.start();f.observe("home");f.request();assertFalse(f.accepts(t)) }
    @Test fun supplementalImageCountsAsNewAttemptSameStep() { val f=flow();val t=f.request();f.responded(t,Phase.OBSERVING);val next=f.request();assertEquals(2,next.attempt);assertEquals(t.step,next.step) }
    @Test(expected=IllegalStateException::class) fun concurrentRequestsRejected() { val f=flow();f.request();f.request() }
    @Test(expected=IllegalStateException::class) fun fourthAutomaticAttemptRejected() { val f=flow();repeat(3) { val t=f.request();f.responded(t,Phase.OBSERVING) };f.request() }
    @Test fun manualRetryInvalidatesOldTokenAndStartsStep() { val f=flow();val t=f.request();f.pause();f.retry();val n=f.request();assertFalse(f.accepts(t));assertEquals(2,n.step);assertEquals(1,n.attempt) }
    @Test fun pausedFlowCannotAutoResumeFromEvents() { val f=flow();f.pause();assertFalse(f.observe("other"));assertEquals(Phase.NEEDS_ATTENTION,f.phase) }
    @Test fun completionIsTerminal() { val f=flow();f.finish(Phase.COMPLETED);assertFalse(f.observe("home"));assertFalse(f.active) }
    private fun decision()=Decision("video_call_outgoing",Action.COMPLETE,null,null,"已发起视频通话",0.98,true,listOf("女儿","正在呼叫","视频通话"))
    private fun page(texts:List<String>)=Snapshot("s","com.tencent.mm",1,1080,2400,texts.mapIndexed { i,t -> UiNode("n$i",t,"","TextView",false,Box(0,100+i*50,300,150+i*50)) },0)
    @Test fun modelCanCompleteWithoutLocalContactPageRules() { assertTrue(CompletionCheck.modelConfirms(decision().copy(evidence=listOf("模型确认")))) }
    @Test fun menuSuggestionDoesNotCompleteUnlessModelDeclaresSuccess() { assertFalse(CompletionCheck.modelConfirms(decision().copy(action=Action.CLICK,completed=false))) }
    @Test fun contactAliasDoesNotBlockModelCompletion() { assertTrue(CompletionCheck.modelConfirms(decision().copy(evidence=listOf("艾子翔女儿")))) }
    @Test fun actualCallEvidenceCanComplete() { assertTrue(CompletionCheck.modelConfirms(decision())) }
    @Test fun staleCompletionStillCannotFinishNewSession() { val f=flow();val token=f.request();f.observe("changed");assertFalse(f.accepts(token));assertTrue(f.active) }
    @Test fun suggestionCannotComplete() { assertFalse(CompletionCheck.modelConfirms(decision().copy(completed=false,action=Action.CLICK))) }
    @Test fun completionDoesNotRequireFixedEvidenceWordsOrConfidence() { assertTrue(CompletionCheck.modelConfirms(decision().copy(evidence=emptyList(),confidence=0.1)));assertFalse(CompletionCheck.modelConfirms(decision().copy(completed=false))) }
    @Test fun recipientDecisionIsDelegatedToModelWithValidGrounding() { val p=page(listOf("其他联系人","视频通话"));assertEquals(p.nodes[1].box,DecisionCodec.target(decision().copy(action=Action.CLICK,completed=false,node="n1",instruction="请点击视频通话"),p)) }
    @Test fun fullContactAliasCanGuideVideoCall() { val p=page(listOf("艾子翔女儿","视频通话"));assertEquals(p.nodes[1].box,DecisionCodec.target(decision().copy(action=Action.CLICK,completed=false,node="n1",instruction="请点击视频通话"),p)) }
    @Test(expected=IllegalArgumentException::class) fun missingTargetStillCannotGuideCall() { DecisionCodec.target(decision().copy(action=Action.CLICK,completed=false,instruction="请点击视频通话"),page(listOf("视频通话"))) }
}
