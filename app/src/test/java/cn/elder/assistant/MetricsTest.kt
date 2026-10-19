package cn.elder.assistant

import cn.elder.assistant.logging.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder

class MetricsTest {
    @get:Rule val tmp=TemporaryFolder()
    private fun event(type:String,data:JSONObject=JSONObject())=JSONObject().put("event",type).put("data",data)
    @Test fun failuresAndRetriesStayInTotals() {
        val events=listOf(event("request",JSONObject().put("attempt",1)),event("request",JSONObject().put("attempt",2)),
            event("response",JSONObject().put("error","TIMEOUT").put("latency_ms",30000)),event("response",JSONObject().put("latency_ms",1000)))
        val s=RunMetrics.summarize(events);assertEquals(2,s.getInt("calls"));assertEquals(1,s.getInt("additional_attempts"));assertEquals(1,s.getInt("error_count"));assertEquals(30000,s.getLong("all_latency_p95_ms"));assertEquals(1000,s.getLong("success_latency_p95_ms"))
    }
    @Test fun reviewCallsAreIncludedAndDistinguished() {
        val summary=RunMetrics.summarize(listOf(event("request"),event("request",JSONObject().put("purpose","workflow_review"))))
        assertEquals(2,summary.getInt("calls"));assertEquals(1,summary.getInt("review_calls"));assertEquals(1,summary.getInt("guidance_calls"))
    }
    @Test fun percentileUsesNearestRank() { assertEquals(19L,RunMetrics.percentile((1L..20L).toList(),0.95));assertEquals(10L,RunMetrics.percentile((1L..20L).toList(),0.5)) }
    @Test fun medianAveragesTwoMiddleSamples() { assertEquals(15500.0,RunMetrics.median(listOf(30000L,1000L))!!,0.0);assertEquals(3.0,RunMetrics.median(listOf(1L,3L,10L))!!,0.0) }
    @Test fun zeroSamplesAreNotReportedAsZeroLatency() { assertNull(RunMetrics.percentile(emptyList(),0.95));assertTrue(RunMetrics.summarize(emptyList()).isNull("all_latency_p95_ms")) }
    @Test fun interruptedRunIsNotSuccessful() { assertEquals("INCOMPLETE",RunMetrics.summarize(listOf(event("start"))).getString("result")) }
    @Test fun completedDurationIsPreserved() { val s=RunMetrics.summarize(listOf(event("end",JSONObject().put("result","COMPLETED").put("duration_ms",15000))));assertEquals(15000,s.getLong("duration_ms")) }
    @Test(expected=IllegalArgumentException::class) fun artifactTraversalRejected() { RunMetrics.artifact(tmp.root,"../key") }
    @Test(expected=IllegalArgumentException::class) fun missingArtifactRejected() { RunMetrics.artifact(tmp.root,"missing.jpg") }
    @Test fun savedArtifactRoundTrips() { tmp.root.resolve("tree.json").writeText("{}");assertEquals("{}",RunMetrics.artifact(tmp.root,"tree.json").readText()) }
    @Test(expected=IllegalStateException::class) fun corruptLogIsNotSilentlyIgnored() { tmp.root.resolve("events.jsonl").writeText("broken\n");RunMetrics.events(tmp.root) }
}
