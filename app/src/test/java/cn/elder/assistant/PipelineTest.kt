package cn.elder.assistant

import cn.elder.assistant.core.*
import cn.elder.assistant.guidance.*
import cn.elder.assistant.model.*
import cn.elder.assistant.logging.*
import org.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder

/** Synthetic fixtures validate module contracts, not real WeChat success rate. */
class PipelineTest {
    @get:Rule val tmp=TemporaryFolder()
    private fun snapshot(id:String,labels:List<String>)=Snapshot(id,"com.tencent.mm",1,1080,2400,
        labels.mapIndexed { i,t -> UiNode("${id}_$i",t,"","Button",true,Box(20,100+i*100,300,160+i*100)) },0)
    private fun response(s:Snapshot,index:Int,action:String="click",complete:Boolean=false)=JSONObject()
        .put("current_state",if(complete) "video_call_outgoing" else "wechat_page").put("next_action",action)
        .put("target_node",if(action=="click") s.nodes[index].id else JSONObject.NULL).put("target_bbox",JSONObject.NULL)
        .put("instruction",if(complete) "已发起视频通话" else if(action=="back") "请您返回上一页" else "请点击${s.nodes[index].text}")
        .put("confidence",0.98).put("task_completed",complete).put("evidence",JSONArray(listOf("女儿","视频通话","正在呼叫"))).toString()
    @Test fun observedPagesAndModelDecisionsReachVerifiedCompletion() {
        val f=SessionFlow();f.start();val log=RunLog(tmp.root)
        val pages=listOf(snapshot("home",listOf("通讯录")),snapshot("contacts",listOf("女儿")),snapshot("chat",listOf("女儿","视频通话")),snapshot("call",listOf("女儿","视频通话","正在呼叫","挂断")))
        for((i,s) in pages.withIndex()) {
            assertTrue(f.observe(s.fingerprint));val token=f.request()
            log.event("request",JSONObject().put("step",token.step).put("attempt",token.attempt))
            val raw=response(s,if(i==2) 1 else 0,if(i==3) "complete" else "click",i==3)
            val d=DecisionCodec.parse(raw);DecisionCodec.target(d,s)
            log.event("response",JSONObject().put("latency_ms",100).put("output_raw",raw))
            if(i==3) { assertTrue(CompletionCheck.modelConfirms(d));f.finish(Phase.COMPLETED);log.event("end",JSONObject().put("result","COMPLETED").put("duration_ms",1000)) }
            else assertTrue(f.responded(token,Phase.WAITING_USER))
        }
        assertFalse(f.active);val metrics=RunMetrics.summarize(RunMetrics.events(log.directory));assertEquals(4,metrics.getInt("calls"));assertEquals("COMPLETED",metrics.getString("result"))
    }
    @Test fun deviationRequiresNewObservationAndRejectsOldGrounding() {
        val f=SessionFlow();f.start();val a=snapshot("a",listOf("女儿","视频通话"));f.observe(a.fingerprint);val old=f.request()
        val wrong=snapshot("wrong",listOf("张三","视频通话"));assertTrue(f.observe(wrong.fingerprint));assertFalse(f.accepts(old))
        val token=f.request();val back=DecisionCodec.parse(response(wrong,0,"back"));assertNull(DecisionCodec.target(back,wrong));assertFalse(CompletionCheck.modelConfirms(back));assertTrue(f.responded(token,Phase.WAITING_USER))
        assertTrue(f.observe(a.fingerprint));assertEquals(3,f.step)
    }
}
