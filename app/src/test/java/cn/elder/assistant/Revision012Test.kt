package cn.elder.assistant

import cn.elder.assistant.model.*
import cn.elder.assistant.guidance.*
import cn.elder.assistant.core.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.net.SocketException

class Revision012Test {
    @Test fun globalGateIncludesRetryAndNewSessionAndUsesActualStartTime() {
        var time=0L;val starts=mutableListOf<Long>()
        val gate=RequestPacer({time},{ time+=it })
        repeat(3) { gate.start({false}) { starts.add(time);time+=20 } }
        assertEquals(listOf(0L,1020L,2040L),starts)
    }
    @Test fun pacingWaitCanBeCancelledWithoutSendingOrConsumingSlot() {
        var time=0L;var cancelled=false;var sends=0
        val gate=RequestPacer({time},{ time+=it;cancelled=true })
        gate.start({false}) { sends++ }
        try { gate.start({cancelled}) { sends++ };fail("must cancel") } catch(_:java.io.IOException) {}
        assertEquals(1,sends)
        time=1000;gate.start({false}) { sends++ };assertEquals(2,sends)
    }
    @Test fun originalCancellationCauseIsPreserved() {
        val p=DeepSeekProvider("https://api.deepseek.com","model","key",true)
        p.cancel("页面状态变化，取消旧请求");p.cancel("用户停止任务")
        assertEquals("页面状态变化，取消旧请求",p.cancellationReason)
        val error=RequestFailure.describe(SocketException("Socket closed"),p.cancellationReason)
        assertEquals("APP_CANCELLED",error.getString("error_code"));assertEquals("SocketException",error.getString("exception_type"))
        assertTrue(error.getString("error").contains("页面状态变化"))
    }
    @Test fun realNetworkErrorIsNotMislabelledAsPageChange() {
        val error=RequestFailure.describe(SocketException("reset"),null)
        assertEquals("TRANSPORT_ERROR",error.getString("error_code"));assertTrue(error.isNull("cancel_reason"))
        assertTrue(error.getString("error").contains("未记录到 App 主动取消"))
    }
    @Test fun proseAndArrayEvidenceBothRetainFullAlias() {
        val j=JSONObject("""{"current_state":"chat","next_action":"click","target_node":"n","target_bbox":null,"instruction":"点击视频通话","confidence":0.2,"task_completed":false,"evidence":"标题是艾子翔女儿"}""")
        assertEquals(listOf("标题是艾子翔女儿"),DecisionCodec.parse(j.toString()).evidence)
        j.put("evidence",org.json.JSONArray(listOf("艾子翔女儿","视频通话")))
        assertEquals("艾子翔女儿",DecisionCodec.parse(j.toString()).evidence.first())
    }
    @Test fun failureHistoryActuallyReachesNextProviderRequestAndResets() {
        val h=TaskHistory();h.add(1,"已展示，等待用户操作","点击加号");h.add(2,"建议未展示","目标越界，不要重复该坐标");h.add(2,"用户重试","上一轮没有完成")
        val snapshot=Snapshot("s","com.tencent.mm",1,100,200,emptyList(),0)
        val body=DeepSeekProvider("https://api.deepseek.com","model","key",true).body(ModelInput("run",3,1,snapshot,h.snapshot()))
        val ctx=JSONObject(body.getJSONArray("messages").getJSONObject(1).getString("content")).getJSONArray("step_context")
        assertEquals(3,ctx.length());assertTrue(ctx.getString(1).contains("目标越界"));assertTrue(ctx.getString(0).contains("等待用户操作"))
        h.clear();assertTrue(h.snapshot().isEmpty())
    }
    @Test fun historyIsBoundedAndDoesNotMutateAlreadyPreparedRequest() {
        val h=TaskHistory();repeat(50) { h.add(it,"观察","页面变化") };val before=h.snapshot();assertEquals(40,before.size)
        h.add(51,"错误","新的错误");assertEquals(40,before.size);assertFalse(before.last().contains("新的错误"));assertTrue(h.snapshot().last().contains("新的错误"))
    }
}
