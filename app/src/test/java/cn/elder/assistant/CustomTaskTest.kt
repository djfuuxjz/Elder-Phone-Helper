package cn.elder.assistant

import cn.elder.assistant.core.*
import cn.elder.assistant.model.*
import cn.elder.assistant.logging.RunLog
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class CustomTaskTest {
    private fun snapshot()=Snapshot("s","com.android.settings",1,1080,2400,emptyList(),0)
    @Test fun customGoalIsSentWithoutDaughterPromptOrWorkflow() {
        val goal=TaskGoal.custom("  打开设置调大字体  ")
        val p=DeepSeekProvider("https://example.com","test","secret",false)
        val body=p.body(ModelInput("run",1,1,snapshot(),emptyList(),task=goal))
        val messages=body.getJSONArray("messages")
        val observation=JSONObject(messages.getJSONObject(1).getString("content"))
        assertEquals("打开设置调大字体",observation.getString("goal"));assertTrue(observation.isNull("workflow"))
        val prompt=messages.getJSONObject(0).getString("content")
        assertFalse(prompt.contains("唯一目标："+TaskSpec.GOAL));assertTrue(prompt.contains("用户的具体目标"))
        assertEquals("任务已成功",goal.completionMessage)
    }
    @Test fun shortcutRetainsOriginalPromptAndGoal() {
        val body=DeepSeekProvider("https://example.com","test","secret",false).body(ModelInput("r",1,1,snapshot(),emptyList()))
        assertEquals(GuidancePrompt.text,body.getJSONArray("messages").getJSONObject(0).getString("content"))
        assertEquals(TaskSpec.GOAL,JSONObject(body.getJSONArray("messages").getJSONObject(1).getString("content")).getString("goal"))
        assertEquals("已向女儿发起视频通话",TaskGoal.DEFAULT.completionMessage)
    }
    @Test fun logsKeepActualGoalAndSecretRedactionWithoutChangingDefault() {
        val dir=Files.createTempDirectory("custom-logs").toFile()
        try {
            val log=RunLog(dir,listOf("private-key"),task=TaskGoal.custom("打开相机"))
            log.event("start",JSONObject().put("test","private-key"))
            val row=JSONObject(log.directory.resolve("events.jsonl").readText())
            assertEquals("打开相机",row.getString("goal"));assertEquals("custom_task",row.getString("task_key"))
            assertFalse(row.toString().contains("private-key"))
            val fixed=RunLog(dir);fixed.event("start")
            assertEquals(TaskSpec.GOAL,JSONObject(fixed.directory.resolve("events.jsonl").readText()).getString("goal"))
        } finally { dir.deleteRecursively() }
    }
    @Test fun customObservesOtherAppsWhileShortcutKeepsWechatScope() {
        val task=TaskGoal.custom("调大字体")
        assertTrue(task.observes("com.android.settings","cn.elder.assistant","launcher"))
        assertTrue(task.observes("launcher","cn.elder.assistant","launcher"))
        assertFalse(task.observes("cn.elder.assistant","cn.elder.assistant","launcher"))
        assertFalse(TaskGoal.DEFAULT.observes("com.android.settings","cn.elder.assistant","launcher"))
        assertTrue(TaskGoal.DEFAULT.observes("com.tencent.mm","cn.elder.assistant","launcher"))
    }
    @Test fun invalidGoalsAreRejectedBeforeStarting() {
        for(text in listOf("   ","字".repeat(501))) {
            assertThrows(IllegalArgumentException::class.java) { TaskGoal.custom(text) }
        }
        assertEquals(500,TaskGoal.custom("字".repeat(500)).goal.length)
    }
}
