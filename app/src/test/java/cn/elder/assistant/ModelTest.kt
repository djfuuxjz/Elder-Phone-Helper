package cn.elder.assistant

import cn.elder.assistant.core.*
import cn.elder.assistant.model.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

class ModelTest {
    private val s=Snapshot("s","com.tencent.mm",1,1080,2400,listOf(UiNode("s_1","通讯录","","TextView",true,Box(20,2000,200,2100))),0)
    private fun json()=JSONObject("""{"current_state":"wechat_home","next_action":"click","target_node":"s_1","target_bbox":null,"instruction":"请点击通讯录","confidence":0.94,"task_completed":false,"evidence":[]}""")
    @Test fun validNodeGroundingUsesCurrentBounds() { assertEquals(s.nodes[0].box,DecisionCodec.target(DecisionCodec.parse(json().toString()),s)) }
    @Test(expected=IllegalArgumentException::class) fun stringConfidenceRejected() { DecisionCodec.parse(json().put("confidence","0.99").toString()) }
    @Test(expected=IllegalArgumentException::class) fun outsideConfidenceRejected() { DecisionCodec.parse(json().put("confidence",2).toString()) }
    @Test(expected=IllegalArgumentException::class) fun stringCompletedRejected() { DecisionCodec.parse(json().put("task_completed","false").toString()) }
    @Test(expected=IllegalArgumentException::class) fun completionConflictRejected() { DecisionCodec.parse(json().put("task_completed",true).toString()) }
    @Test fun confidenceIsRecordedButDoesNotGateValidTarget() { val d=DecisionCodec.parse(json().put("confidence",0.2).toString());assertEquals(0.2,d.confidence,0.0);assertEquals(s.nodes[0].box,DecisionCodec.target(d,s)) }
    @Test(expected=IllegalStateException::class) fun oldNodeNotShown() { DecisionCodec.target(DecisionCodec.parse(json().put("target_node","old_1").toString()),s) }
    @Test(expected=IllegalArgumentException::class) fun absentTargetNotShown() { DecisionCodec.target(DecisionCodec.parse(json().put("target_node",JSONObject.NULL).toString()),s) }
    @Test(expected=IllegalArgumentException::class) fun differentNodeAndBboxRejected() { DecisionCodec.target(DecisionCodec.parse(json().put("target_bbox",Box(0,0,20,20).json()).toString()),s) }
    @Test(expected=IllegalArgumentException::class) fun outsideBboxRejected() { DecisionCodec.target(DecisionCodec.parse(json().put("target_bbox",Box(0,0,2000,3000).json()).toString()),s) }
    @Test fun backInstructionDoesNotNeedTarget() { val d=DecisionCodec.parse(json().put("next_action","back").put("target_node",JSONObject.NULL).toString()); assertNull(DecisionCodec.target(d,s)) }
    @Test(expected=IllegalArgumentException::class) fun insecureEndpointRejected() { DeepSeekProvider("http://example.com","model","key",true) }
    @Test fun requestContainsFixedGoalAndActualNodes() {
        val p=DeepSeekProvider("https://api.deepseek.com","deepseek-flash","key",true)
        val b=p.body(ModelInput("run",1,1,s,emptyList()))
        val observation=JSONObject(b.getJSONArray("messages").getJSONObject(1).getString("content"))
        assertEquals(TaskSpec.GOAL,observation.getString("goal"))
        assertEquals("disabled",b.getJSONObject("thinking").getString("type"))
        assertEquals("s_1",observation.getJSONObject("snapshot").getJSONArray("nodes").getJSONObject(0).getString("id"))
        assertFalse(b.toString().contains("Bearer"))
    }
    @Test fun imageUsesUserMessageDataUrl() { val p=DeepSeekProvider("https://api.deepseek.com","deepseek-flash","key",true);val b=p.body(ModelInput("r",1,1,s,emptyList(),byteArrayOf(1,2,3)));val parts=b.getJSONArray("messages").getJSONObject(1).getJSONArray("content");assertEquals("data:image/jpeg;base64,AQID",parts.getJSONObject(1).getJSONObject("image_url").getString("url")) }
    @Test(expected=ModelFailure::class) fun unsupportedVisionDoesNotSendImage() { DeepSeekProvider("https://api.deepseek.com","text-model","key",false).body(ModelInput("r",1,1,s,emptyList(),byteArrayOf(1))) }
    private fun provider(server:MockWebServer, timeout:Long=3000):DeepSeekProvider {
        val client=OkHttpClient.Builder().callTimeout(timeout,TimeUnit.MILLISECONDS).retryOnConnectionFailure(false)
            .addInterceptor { chain -> chain.proceed(chain.request().newBuilder().url(server.url("/chat/completions")).build()) }.build()
        return DeepSeekProvider("https://api.deepseek.com","deepseek-flash","test-only-key",true,client)
    }
    @Test fun httpPayloadAndResponseRoundTrip() { MockWebServer().use { server ->
        server.enqueue(MockResponse().setBody(JSONObject().put("choices",org.json.JSONArray().put(JSONObject().put("message",JSONObject().put("content",json().toString())))).toString()))
        val p=provider(server);val r=p.execute(p.body(ModelInput("run",1,1,s,emptyList())))
        assertEquals("wechat_home",DecisionCodec.parse(r.content).state)
        val request=server.takeRequest();assertEquals("Bearer test-only-key",request.getHeader("Authorization"));assertEquals("POST",request.method)
        assertEquals("deepseek-flash",JSONObject(request.body.readUtf8()).getString("model"))
    } }
    @Test fun httpErrorPreservesBodyForDebug() { MockWebServer().use { server ->server.enqueue(MockResponse().setResponseCode(401).setBody("bad-key")); val p=provider(server)
        try { p.execute(JSONObject());fail("must fail") } catch(e:ModelFailure) { assertEquals("HTTP_401",e.code);assertEquals("bad-key",e.raw) } } }
    @Test fun malformedEnvelopePreservesRawOutput() { MockWebServer().use { server ->server.enqueue(MockResponse().setBody("invalid"));val p=provider(server)
        try {p.execute(JSONObject());fail("must fail")} catch(e:ModelFailure) { assertEquals("INVALID_ENVELOPE",e.code);assertEquals("invalid",e.raw) } } }
    @Test fun networkTimeoutIsFinite() { MockWebServer().use { server ->server.enqueue(MockResponse().setBody("{}").setBodyDelay(2,TimeUnit.SECONDS)); val p=provider(server,100)
        try {p.execute(JSONObject());fail("must timeout")}catch(e:java.io.IOException) { assertTrue(e !is ModelFailure) } } }
    @Test fun cancellationBeforeWorkerStartsCannotSendRequest() { MockWebServer().use { server ->
        val p=provider(server);p.cancel()
        var started=false
        try {p.execute(JSONObject()) { started=true };fail("must cancel")}catch(e:java.io.IOException) { assertEquals(0,server.requestCount);assertFalse(started) }
    } }
    @Test fun realProviderStartsAreAtLeastOneSecondApart() { MockWebServer().use { server ->
        val envelope=JSONObject().put("choices",org.json.JSONArray().put(JSONObject().put("message",JSONObject().put("content",json().toString())))).toString()
        repeat(2) { server.enqueue(MockResponse().setBody(envelope)) }
        val starts=mutableListOf<Long>()
        repeat(2) { provider(server).execute(JSONObject()) { starts.add(System.nanoTime()) } }
        assertTrue("Two actual request start callbacks must be >= 1 second apart",starts[1]-starts[0]>=1_000_000_000L)
        assertEquals(2,server.requestCount)
    } }

}
