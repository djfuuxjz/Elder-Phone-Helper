package cn.elder.assistant

import cn.elder.assistant.core.*
import cn.elder.assistant.logging.RunLog
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder

class FoundationTest {
    @get:Rule val tmp = TemporaryFolder()
    private fun snap(text: String = "微信", box: Box = Box(10,20,100,80)) = Snapshot("a", "launcher", 1, 1080, 2400,
        listOf(UiNode("a_0", text, "", "Button", true, box)), 1)
    @Test fun fixedGoalIsSingleTask() { assertEquals("video_call_daughter", TaskSpec.KEY); assertTrue(TaskSpec.GOAL.contains("女儿")) }
    @Test fun snapshotRoundTripsUsefulFields() { val j=snap().json(); assertEquals(1080,j.getInt("screen_width")); assertEquals("微信", j.getJSONArray("nodes").getJSONObject(0).getString("text")) }
    @Test fun timestampsDoNotTriggerNewPage() { assertEquals(snap().fingerprint,snap().copy(capturedAt=2,id="b").fingerprint) }
    @Test fun scrollAndTextChangeFingerprint() { assertNotEquals(snap().fingerprint,snap("通讯录").fingerprint); assertNotEquals(snap().fingerprint,snap(box=Box(10,30,100,90)).fingerprint) }
    @Test fun failureAndSuccessAreBothLogged() { val l=RunLog(tmp.root); l.event("request"); l.event("error",JSONObject().put("error","timeout")); val lines=l.directory.resolve("events.jsonl").readLines(); assertEquals(2,lines.size); assertEquals("error",JSONObject(lines[1]).getString("event")) }
    @Test fun keyIsRedactedEvenInRawOutput() { val l=RunLog(tmp.root,listOf("sk-secret")); l.event("response",JSONObject().put("raw","sk-secret")); assertFalse(l.directory.resolve("events.jsonl").readText().contains("sk-secret")) }
    @Test fun artifactReferenceIsReadable() { val l=RunLog(tmp.root); val ref=l.artifact("tree.json","{}".toByteArray()); assertEquals("{}",l.directory.resolve(ref).readText()) }
    @Test(expected=IllegalArgumentException::class) fun traversalIsRejected() { RunLog(tmp.root).artifact("../escape",byteArrayOf(1)) }
    @Test(expected=java.io.IOException::class) fun storageLimitIsVisibleFailure() { RunLog(tmp.root,maxBytes=1).event("request") }
    @Test fun sessionsCannotShareDirectory() { assertNotEquals(RunLog(tmp.root).id,RunLog(tmp.root).id) }
}
