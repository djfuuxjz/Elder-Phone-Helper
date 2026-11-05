package cn.elder.assistant

import cn.elder.assistant.core.Box
import cn.elder.assistant.core.Snapshot
import cn.elder.assistant.core.TaskGoal
import cn.elder.assistant.model.DeepSeekProvider
import cn.elder.assistant.model.ModelInput
import cn.elder.assistant.workflow.WorkflowCatalog
import cn.elder.assistant.workflow.WorkflowGuide
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class WorkflowCatalogTest {
    private fun key(goal:String)=WorkflowCatalog.match(goal)?.getString("task_key")

    @Test fun catalogContainsThirteenParameterizedEvaluationTasks() {
        val all=WorkflowCatalog.all()
        assertEquals(13,all.size)
        assertEquals(13,all.map { it.getString("task_key") }.toSet().size)
        for(flow in all) {
            assertTrue(flow.getJSONArray("steps").length()>0)
            assertTrue(flow.has("completion_evidence"))
        }
        val sms=all.single { it.getString("task_key")=="send_sms" }.toString()
        assertTrue(sms.contains("{contact}"));assertTrue(sms.contains("{content}"))
    }

    @Test fun communicationChannelsAreMatchedWithoutSilentSubstitution() {
        assertEquals("send_wechat_text",key("给女儿发微信说我晚上回家"))
        assertEquals("send_sms",key("给儿子发短信说我已经到家了"))
        assertEquals("send_wechat_photo",key("把刚拍的照片微信发给女儿"))
        assertEquals("wechat_voice_call",key("用微信给女儿打电话"))
        assertEquals("wechat_video_call",key("给女儿打微信视频"))
        assertEquals("phone_call",key("给儿子打电话"))
        assertNull(key("给女儿发消息说我已经到家"))
    }

    @Test fun settingsMediaAndWeatherTasksMatchNaturalPhrases() {
        assertEquals("increase_font_size",key("手机上的字太小了，帮我调大一点"))
        assertEquals("increase_brightness",key("屏幕太暗了，帮我调亮一点"))
        assertEquals("increase_media_volume",key("视频声音太小了，帮我调大一点"))
        assertEquals("set_alarm",key("帮我设置明天早上七点的闹钟"))
        assertEquals("view_latest_photo",key("打开我刚拍的照片"))
        assertEquals("view_weather",key("帮我看看今天会不会下雨"))
        assertEquals("wechat_scan",key("打开微信扫一扫"))
    }

    @Test fun matchedTemplateIsIncludedInCustomModelRequest() {
        val task=TaskGoal.custom("给儿子发短信说我已经到家了")
        val flow=WorkflowCatalog.forTask(task)!!
        val snapshot=Snapshot("s","com.android.mms",1,1080,2400,emptyList(),0)
        val context=WorkflowGuide.context(flow,snapshot)
        val body=DeepSeekProvider("https://example.com","test","secret",false)
            .body(ModelInput("run",1,1,snapshot,emptyList(),workflow=context,task=task))
        val observation=JSONObject(body.getJSONArray("messages").getJSONObject(1).getString("content"))
        val sent=observation.getJSONObject("workflow")
        assertEquals("observe_current_page",sent.getString("current_phase"))
        assertEquals("send_sms",sent.getJSONObject("version").getString("intent_id"))
        assertTrue(body.getJSONArray("messages").getJSONObject(0).getString("content").contains("参数化流程模板"))
    }
}
