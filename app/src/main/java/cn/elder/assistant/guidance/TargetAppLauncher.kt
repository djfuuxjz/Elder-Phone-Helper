package cn.elder.assistant.guidance

import android.content.Context
import android.content.Intent

object TargetAppLauncher {
    fun open(context:Context):Result<Unit> = runCatching {
        val intent=context.packageManager.getLaunchIntentForPackage("com.tencent.mm")
            ?: error("未找到可打开的微信，请先安装微信，或在系统中允许访问该应用")
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
