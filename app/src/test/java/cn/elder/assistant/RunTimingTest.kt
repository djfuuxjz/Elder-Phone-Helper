package cn.elder.assistant

import cn.elder.assistant.guidance.RunTiming
import cn.elder.assistant.logging.RunMetrics
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class RunTimingTest {
    @Test fun longPreparationDoesNotConsumeTaskBudgetOrAcceptObservations() {
        val timing=RunTiming().apply { reset(true) }
        assertFalse(timing.observe(900000))
        assertNull(timing.elapsed(900000))
        timing.release()
        assertNull(timing.elapsed(900500)) // still waiting for a stable eligible page
        assertTrue(timing.observe(901000))
        assertEquals(1000L,timing.elapsed(902000))
    }
    @Test fun normalStartAlsoExcludesTimeSpentLeavingTheAssistant() {
        val timing=RunTiming().apply { reset(false) }
        assertNull(timing.elapsed(120000))
        assertTrue(timing.observe(121000))
        assertEquals(0L,timing.elapsed(121000))
    }
    @Test fun newPagesAndRetryDoNotResetTaskBudget() {
        val timing=RunTiming().apply { reset(false);observe(1000) }
        timing.release();timing.observe(150000);timing.observe(290000)
        assertEquals(300001L,timing.elapsed(301001))
    }
    @Test fun newSessionDoesNotReusePreviousDuration() {
        val timing=RunTiming().apply { reset(false);observe(1000);reset(true) }
        assertNull(timing.elapsed(500000))
        assertFalse(timing.observe(500000))
    }
    @Test fun stoppedDuringPreparationReportsUnknownDurationInsteadOfZero() {
        val event=JSONObject().put("event","end").put("data",JSONObject().put("result","STOPPED").put("duration_ms",JSONObject.NULL))
        assertTrue(RunMetrics.summarize(listOf(event)).isNull("duration_ms"))
    }
}
