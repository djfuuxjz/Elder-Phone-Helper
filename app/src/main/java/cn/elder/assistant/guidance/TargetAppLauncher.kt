package cn.elder.assistant.guidance

import android.content.Context
import android.content.Intent
import org.json.JSONArray
import org.json.JSONObject

data class InstalledApp(val packageName:String,val label:String)
object TargetAppLauncher {
    fun installed(context:Context):List<InstalledApp> = context.packageManager.queryIntentActivities(
        Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),0).map {
        InstalledApp(it.activityInfo.packageName,it.loadLabel(context.packageManager).toString())
    }.distinctBy { it.packageName }.filter { it.packageName!=context.packageName }
    fun catalog(apps:List<InstalledApp>)=JSONArray(apps.map { JSONObject().put("package",it.packageName).put("label",it.label) })
    fun open(context:Context,packageName:String="com.tencent.mm"):Result<Unit> = runCatching {
        val intent=context.packageManager.getLaunchIntentForPackage(packageName)
            ?: error("未找到可打开的应用入口：$packageName（微信或其他应用可能未安装、不可见或被系统限制）")
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
    fun guiFallback(systemVerified:Boolean,automaticAllowed:Boolean,alreadyTried:Boolean,atHome:Boolean,iconCount:Int)=
        !systemVerified && automaticAllowed && !alreadyTried && atHome && iconCount==1
    fun verifies(packageName:String,actualPackage:String?)=actualPackage==packageName
}
