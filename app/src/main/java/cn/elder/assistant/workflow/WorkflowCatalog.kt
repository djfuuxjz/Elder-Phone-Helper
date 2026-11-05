package cn.elder.assistant.workflow

import cn.elder.assistant.core.TaskGoal
import org.json.JSONArray
import org.json.JSONObject

/**
 * Read-only, parameterized workflow templates for the first high-frequency task set.
 * Matching is deliberately conservative: an ambiguous request gets no template and is
 * left to the model to clarify rather than silently choosing an app or recipient.
 */
object WorkflowCatalog {
    private fun workflow(
        key:String,
        name:String,
        slots:List<String>,
        steps:List<String>,
        completion:String,
        safety:String=""
    )=JSONObject()
        .put("name",name)
        .put("task_key",key)
        .put("intent_id",key)
        .put("slots",JSONArray(slots))
        .put("steps",JSONArray(steps))
        .put("completion_evidence",completion)
        .put("safety_note",safety)

    private val templates=listOf(
        workflow("wechat_video_call","微信视频通话",listOf("contact"),listOf(
            "打开微信；已有合适微信页面时从当前页面继续。",
            "优先使用搜索找到联系人 {contact}，不要逐屏翻找；结果有歧义时请用户确认。",
            "进入正确联系人聊天页，点击输入栏旁的加号/更多功能。",
            "选择音视频通话，再选择视频通话。",
            "其他页面优先点击可见返回按钮，回到合适入口后继续。"
        ),"当前页面明确显示联系人 {contact} 的视频呼叫状态、摄像头和挂断控件；仅看到菜单不算完成。"),
        workflow("wechat_voice_call","微信语音通话",listOf("contact"),listOf(
            "打开微信；已有合适微信页面时从当前页面继续。",
            "优先使用搜索找到联系人 {contact}，不要逐屏翻找；结果有歧义时请用户确认。",
            "进入正确联系人聊天页，点击输入栏旁的加号/更多功能。",
            "选择音视频通话，再选择语音通话。",
            "其他页面优先点击可见返回按钮，回到合适入口后继续。"
        ),"当前页面明确显示联系人 {contact} 的语音呼叫状态和挂断控件；仅看到菜单不算完成。"),
        workflow("send_wechat_text","发送微信文字",listOf("contact","content"),listOf(
            "打开微信；不要改用系统短信。",
            "优先搜索并确认联系人 {contact}，有歧义时请用户确认。",
            "进入正确聊天页，点击消息输入框。",
            "请用户亲自输入或核对内容 {content}。",
            "点击发送；如果内容或联系人不明确，先停止并请用户补充。"
        ),"正确联系人聊天页中明确出现已发送内容 {content}；仅输入未发送不算完成。",
            "不得把微信消息改成短信；不得发送支付、验证码或其他敏感信息。"),
        workflow("send_wechat_photo","发送微信照片",listOf("contact","photo"),listOf(
            "打开微信并搜索确认联系人 {contact}。",
            "进入正确聊天页，点击输入栏旁的加号/更多功能。",
            "进入相册或照片选择器。",
            "按目标 {photo} 选择照片；无法确认是哪张时请用户确认。",
            "核对联系人和照片后点击发送。"
        ),"正确联系人聊天页中明确出现已发送的目标照片。",
            "不能根据猜测选择照片；涉及身份证、银行卡等敏感图片时停止。"),
        workflow("wechat_scan","打开微信扫一扫",emptyList(),listOf(
            "打开微信；已有合适微信页面时从当前页面继续。",
            "进入微信加号菜单或发现页中的扫一扫。",
            "进入扫码取景页面后停止，不继续打开扫码结果。"
        ),"当前页面是微信扫一扫的扫码取景页面。",
            "只负责打开扫一扫；不继续支付、授权或访问陌生链接。"),
        workflow("phone_call","拨打普通电话",listOf("contact"),listOf(
            "打开系统电话或联系人应用；不要进入微信。",
            "搜索并确认联系人 {contact}；同名或号码不明确时请用户确认。",
            "进入正确联系人或号码页面。",
            "请用户核对后点击拨号。"
        ),"系统通话页面明确显示联系人 {contact} 或其已确认号码及通话/呼叫状态。",
            "联系人或号码不明确时不得拨出。"),
        workflow("send_sms","发送短信",listOf("contact","content"),listOf(
            "打开系统短信应用；不要进入微信。",
            "新建短信或搜索会话，确认收件人 {contact}。",
            "点击短信输入框，请用户亲自输入或核对内容 {content}。",
            "再次核对收件人与内容后点击发送。"
        ),"系统短信会话中明确显示发给 {contact} 的内容 {content} 已发送。",
            "不得把短信改成微信；不得发送验证码、支付或其他敏感信息。"),
        workflow("increase_font_size","调大系统字体",emptyList(),listOf(
            "打开系统设置。",
            "优先搜索字体大小、字体设置或显示大小。",
            "进入系统字体大小设置，不进入单个 App 的字体设置。",
            "请用户把字体档位向更大方向调整一级并观察预览。"
        ),"系统字体设置页面显示档位比调整前更大；仅进入设置页不算完成。"),
        workflow("increase_brightness","调亮屏幕",emptyList(),listOf(
            "优先打开系统快捷设置中的亮度控制；找不到时进入显示与亮度设置。",
            "确认操作的是屏幕亮度，不是音量或护眼模式。",
            "请用户把亮度滑块向更亮方向移动一段。"
        ),"亮度滑块位置相对调整前提高，且屏幕亮度已明显变亮。"),
        workflow("increase_media_volume","调大媒体音量",emptyList(),listOf(
            "打开系统音量面板或声音与振动设置。",
            "展开详细音量控制并确认媒体音量项。",
            "请用户把媒体音量向更大方向调整一段；不要调整铃声或闹钟音量。"
        ),"媒体音量位置相对调整前提高；铃声、通知或闹钟音量变化不能作为完成证据。"),
        workflow("set_alarm","设置闹钟",listOf("date","time"),listOf(
            "打开系统时钟并进入闹钟。",
            "点击新增闹钟。",
            "按目标设置日期 {date} 和时间 {time}；日期含糊时请用户确认。",
            "核对日期、时间和启用状态后保存。"
        ),"闹钟列表明确出现日期 {date}、时间 {time} 且处于启用状态；仅停在编辑页不算完成。"),
        workflow("view_latest_photo","查看最近照片",emptyList(),listOf(
            "打开系统相册或图库。",
            "进入最近照片或按拍摄时间排序的照片列表。",
            "打开时间最新的一张照片查看大图；如果用户另有指定则按实际目标选择。"
        ),"相册大图页面正在显示按拍摄时间判断的最近一张照片。"),
        workflow("view_weather","查看天气",listOf("date","city"),listOf(
            "打开可信的系统天气应用；已有天气页面时从当前页面继续。",
            "没有指定城市时使用当前城市；指定了 {city} 时搜索并切换到该城市。",
            "切换到目标日期 {date} 的预报。",
            "让用户看到温度、天气状况及降雨信息。"
        ),"天气页面明确显示目标城市 {city}（未指定则当前城市）和目标日期 {date} 的天气信息。")
    )

    fun all():List<JSONObject> = templates.map { JSONObject(it.toString()) }

    fun forTask(task:TaskGoal):JSONObject? =
        if(task.fixed) JSONObject(WorkflowStore.builtin().toString()) else match(task.goal)

    fun match(goal:String):JSONObject? {
        val text=goal.trim().replace(Regex("\\s+"),"")
        fun has(vararg words:String)=words.any { text.contains(it) }
        val key=when {
            has("微信扫一扫") || (has("微信") && has("扫一扫","扫码")) -> "wechat_scan"
            has("微信") && has("照片","图片","相片") && has("发","发送","传给") -> "send_wechat_photo"
            has("微信") && has("视频") && has("打","通话","呼叫","联系") -> "wechat_video_call"
            has("微信") && has("语音","电话") && !has("视频") -> "wechat_voice_call"
            has("微信") && has("发","发送","告诉","说") && !has("照片","图片","相片") -> "send_wechat_text"
            has("短信") && has("发","发送","告诉","说") -> "send_sms"
            !has("微信","短信") && has("打电话","拨打","拨电话","呼叫") -> "phone_call"
            has("闹钟") && has("设置","定","叫醒","提醒") -> "set_alarm"
            has("字体","字号","字太小","字看不清") && has("大","调","设置","看不清") -> "increase_font_size"
            has("亮度") || (has("屏幕","手机") && has("太暗","调亮","亮一点","变亮")) -> "increase_brightness"
            has("媒体音量","视频声音","音乐声音") || (has("声音") && has("视频","媒体","音乐") && has("大","提高","调")) -> "increase_media_volume"
            has("天气","下雨","冷不冷","热不热","带伞") -> "view_weather"
            has("相册","图库","照片") && has("最近","最新","刚拍","上一张") && has("打开","查看","看看","看") -> "view_latest_photo"
            else -> null
        }
        return templates.firstOrNull { it.optString("task_key")==key }?.let { JSONObject(it.toString()) }
    }
}
