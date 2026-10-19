package cn.elder.assistant.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings as AndroidSettings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.*
import cn.elder.assistant.config.Settings
import cn.elder.assistant.core.AppRuntime

class MainActivity : Activity() {
    private lateinit var body: LinearLayout
    private var statusView: TextView? = null
    private var permissionView: TextView? = null
    private var modelView: TextView? = null
    private val handler=Handler(Looper.getMainLooper())
    private val refresh=object: Runnable { override fun run() {
        statusView?.text=AppRuntime.status
        permissionView?.text=if(AppRuntime.connected) "无障碍服务已连接" else "无障碍服务未开启"
        modelView?.text=AppRuntime.modelStatus
        handler.postDelayed(this,1000)
    } }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(24),dp(16),dp(24),dp(8)); setBackgroundColor(WarmStyle.background) }
        root.setOnApplyWindowInsetsListener { v, insets ->
            val bars=insets.getInsets(android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.ime()); v.setPadding(dp(24),bars.top+dp(16),dp(24),bars.bottom+dp(8)); insets
        }
        body=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
        val scroll=ScrollView(this).apply { addView(body) }
        root.addView(scroll,LinearLayout.LayoutParams(-1,0,1f))
        val nav=LinearLayout(this)
        nav.addView(button("任务") { showTask() },LinearLayout.LayoutParams(0,-2,1f).apply { setMargins(dp(4),dp(8),dp(4),0) })
        nav.addView(button("我的") { showMine() },LinearLayout.LayoutParams(0,-2,1f).apply { setMargins(dp(4),dp(8),dp(4),0) })
        root.addView(nav); setContentView(root); showTask()
    }
    override fun onResume() { super.onResume(); handler.post(refresh) }
    override fun onPause() { handler.removeCallbacks(refresh); super.onPause() }
    private fun label(text:String,size:Float=20f): TextView = TextView(this).apply { this.text=text; textSize=size; WarmStyle.text(this); setPadding(dp(4),dp(12),dp(4),dp(12)) }
    private fun dp(value:Int) = (value*resources.displayMetrics.density).toInt()
    private fun button(text:String, click:()->Unit): Button = Button(this).apply { this.text=text; textSize=20f; WarmStyle.button(this,text=="给女儿打视频电话"); setOnClickListener { click() } }
    private fun reset(title:String) { body.removeAllViews(); statusView=null;permissionView=null;modelView=null; body.addView(label(title,30f)) }
    private fun showTask() {
        reset("手机助手")
        body.addView(CatView(this),LinearLayout.LayoutParams(-1,dp(120)))
        body.addView(label("一步一步，陪您操作手机",22f))
        statusView=label(AppRuntime.status).also { body.addView(it) }
        body.addView(button("给女儿打视频电话") {
            if(AppRuntime.active) { toast("任务正在进行，请按屏幕提示操作"); return@button }
            if(!AppRuntime.connected) { showPermission(); return@button }
            if(!Settings(this).hasKey()) { toast("请先在“我的”中配置模型"); showMine(); return@button }
            AlertDialog.Builder(this).setTitle("开始操作引导")
                .setMessage("助手将读取当前页面，必要时把截图发送给您配置的模型。每一步由您亲自点击。输入输出和截图会保存在本机 Debug 中，供排查问题，可随时清除。\n\n开始后，助手会打开微信，再由您按提示操作。")
                .setPositiveButton("开始") { _,_ -> AppRuntime.start?.invoke() }.setNegativeButton("取消",null).show()
        })
        body.addView(button("停止当前引导") { AppRuntime.stop?.invoke() })
        body.addView(label("助手只提示位置，不会替您点击。",18f))
    }
    private fun showPermission() {
        AlertDialog.Builder(this).setTitle("开启操作引导权限").setMessage("在系统无障碍设置中找到“手机助手 · 操作引导”并开启。返回这里后即可开始。")
            .setPositiveButton("打开设置") { _,_-> startActivity(Intent(AndroidSettings.ACTION_ACCESSIBILITY_SETTINGS)) }.setNegativeButton("稍后",null).show()
    }
    private fun showMine() {
        reset("我的")
        val settings=Settings(this)
        permissionView=label(if(AppRuntime.connected) "无障碍服务已连接" else "无障碍服务未开启").also { body.addView(it) }
        body.addView(button("无障碍设置") { showPermission() })
        body.addView(label("开发配置 · DeepSeek",24f))
        modelView=label(AppRuntime.modelStatus,16f).also { body.addView(it) }
        val endpoint=EditText(this).apply { hint="API 地址（HTTPS）"; setText(settings.endpoint); inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI }
        val model=EditText(this).apply { hint="模型名称"; setText(settings.model) }
        val key=EditText(this).apply { hint=if(settings.hasKey()) "已保存密钥；留空保持不变" else "填写您的 API Key"; inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }
        val vision=CheckBox(this).apply { text="模型支持图片输入"; textSize=18f; isChecked=settings.vision }
        listOf(endpoint,model,key).forEach { field ->
            WarmStyle.text(field);field.textSize=20f;field.minHeight=dp(56)
            field.background=WarmStyle.surface(this,Color.WHITE,14)
            field.setPadding(dp(12),dp(12),dp(12),dp(12))
            field.layoutParams=LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(8);bottomMargin=dp(8) }
        }
        body.addView(endpoint); body.addView(model); body.addView(key); body.addView(vision)
        body.addView(button("保存模型配置") {
            if(AppRuntime.active) { toast("请先停止引导再修改模型"); return@button }
            val url=runCatching { java.net.URI(endpoint.text.toString().trim()) }.getOrNull()
            if(url?.scheme!="https" || url.host.isNullOrBlank() || url.userInfo!=null || url.query!=null || url.fragment!=null || model.text.isBlank()) { toast("请输入有效 HTTPS 地址和模型名称"); return@button }
            runCatching {
                if(key.text.isNotBlank()) settings.saveKey(key.text.toString())
                settings.endpoint=endpoint.text.toString(); settings.model=model.text.toString(); settings.vision=vision.isChecked
            }.onSuccess { key.text.clear();AppRuntime.modelStatus="模型连接尚未验证"; toast("已保存；实际连接在任务运行时验证") }.onFailure { toast("保存失败：${it.message}") }
        })
        body.addView(button("删除已保存密钥") { if(!AppRuntime.active) { settings.clearKey(); toast("密钥已删除") } else toast("请先停止引导") })
        body.addView(button("流程库 · 开发维护") { startActivity(Intent(this,WorkflowActivity::class.java)) })
        body.addView(button("Debug · 开发日志") { startActivity(Intent().setClassName(this,"cn.elder.assistant.ui.DebugActivity")) })
        body.addView(label("调试数据仅保存在本机，不是用户历史记录。模型调用由您所填的服务计费。",16f))
    }
    private fun toast(message:String) { Toast.makeText(this,message,Toast.LENGTH_LONG).show() }
}
