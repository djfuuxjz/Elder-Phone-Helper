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
import cn.elder.assistant.core.TaskGoal
import cn.elder.assistant.speech.VoiceTaskInput

class MainActivity : Activity() {
    private lateinit var body: LinearLayout
    private lateinit var shortcut: LinearLayout
    private lateinit var rootLayout:LinearLayout
    private var taskInput:EditText?=null
    private var draft=""
    private var mine=false
    private var voicePending=false
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
        draft=savedInstanceState?.getString("task_draft").orEmpty()
        mine=savedInstanceState?.getBoolean("mine") ?: false
        voicePending=savedInstanceState?.getBoolean("voice_pending") ?: false
        val root=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(24),dp(16),dp(24),dp(8)); setBackgroundColor(WarmStyle.background) }
        rootLayout=root
        root.viewTreeObserver.addOnGlobalLayoutListener { positionShortcut() }
        root.setOnApplyWindowInsetsListener { v, insets ->
            val bars=insets.getInsets(android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.ime()); v.setPadding(dp(24),bars.top+dp(16),dp(24),bars.bottom+dp(8)); insets
        }
        body=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
        val scroll=ScrollView(this).apply { isFillViewport=true;addView(body) }
        root.addView(scroll,LinearLayout.LayoutParams(-1,0,1f))
        shortcut=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
        shortcut.addView(label("快捷任务",18f))
        shortcut.addView(button("给女儿打视频电话") { begin(TaskGoal.DEFAULT) })
        root.addView(shortcut)
        val nav=LinearLayout(this)
        nav.addView(button("任务") { showTask() },LinearLayout.LayoutParams(0,-2,1f).apply { setMargins(dp(4),dp(8),dp(4),0) })
        nav.addView(button("我的") { showMine() },LinearLayout.LayoutParams(0,-2,1f).apply { setMargins(dp(4),dp(8),dp(4),0) })
        root.addView(nav); setContentView(root); if(mine) showMine() else showTask()
    }
    override fun onResume() { super.onResume(); handler.post(refresh) }
    override fun onPause() { handler.removeCallbacks(refresh); super.onPause() }
    private fun label(text:String,size:Float=20f): TextView = TextView(this).apply { this.text=text; textSize=size; WarmStyle.text(this); setPadding(dp(4),dp(12),dp(4),dp(12)) }
    private fun dp(value:Int) = (value*resources.displayMetrics.density).toInt()
    private fun button(text:String, click:()->Unit): Button = Button(this).apply { this.text=text; textSize=20f; WarmStyle.button(this,text=="给女儿打视频电话"); setOnClickListener { click() } }
    private fun reset(title:String) { if(shortcut.parent===body) { body.removeView(shortcut);rootLayout.addView(shortcut,1) };taskInput?.let { draft=it.text.toString() };taskInput=null;body.removeAllViews(); statusView=null;permissionView=null;modelView=null; body.addView(label(title,30f)) }
    private fun positionShortcut() {
        if(!::shortcut.isInitialized || mine || rootLayout.height==0) return
        val nav=rootLayout.getChildAt(rootLayout.childCount-1)
        val remaining=rootLayout.height-rootLayout.paddingTop-rootLayout.paddingBottom-nav.measuredHeight-shortcut.measuredHeight
        // Keep input usable with a keyboard or large text on a small screen.
        // In compact space the same shortcut remains last in the scrollable content.
        if(remaining<dp(240) && shortcut.parent!==body) {
            (shortcut.parent as? android.view.ViewGroup)?.removeView(shortcut);body.addView(shortcut)
        } else if(remaining>=dp(240) && shortcut.parent===body) {
            body.removeView(shortcut);rootLayout.addView(shortcut,1)
        }
    }
    private fun showTask() {
        mine=false;shortcut.visibility=View.VISIBLE
        reset("手机助手")
        body.addView(CatView(this),LinearLayout.LayoutParams(-1,dp(100)))
        body.addView(label("今天想做什么？",24f))
        body.addView(button("语音输入任务") { speakTask() })
        taskInput=EditText(this).apply {
            hint="说出或输入想做的事，例如：打开设置调大字体"
            contentDescription="自定义任务内容"
            textSize=20f;minLines=2;gravity=Gravity.TOP
            inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            background=WarmStyle.surface(this@MainActivity,Color.WHITE,14)
            WarmStyle.text(this);setPadding(dp(12),dp(12),dp(12),dp(12));setText(draft)
        }.also { body.addView(it,LinearLayout.LayoutParams(-1,-2)) }
        body.addView(button("开始引导") {
            runCatching { TaskGoal.custom(taskInput?.text.toString()) }
                .onSuccess { begin(it) }.onFailure { toast(it.message ?: "请填写任务") }
        })
        statusView=label(AppRuntime.status).also { body.addView(it) }
        body.addView(button("停止当前引导") { AppRuntime.stop?.invoke() })
        body.addView(label("助手只提示位置，不会替您点击。",18f))
        body.addView(label("语音由手机的识别服务处理，可能需要联网；本助手不保存录音。",16f))
    }
    private fun begin(task:TaskGoal) {
        if(AppRuntime.active) { toast("任务正在进行，请按屏幕提示操作");return }
        if(!AppRuntime.connected) { showPermission();return }
        if(!Settings(this).hasKey()) { toast("请先在“我的”中配置模型");showMine();return }
        val launch=if(task.fixed) "开始后，助手会打开微信，再由您按提示操作。" else "任务：${task.goal}\n开始后返回桌面，由您按提示操作。"
        AlertDialog.Builder(this).setTitle("开始操作引导")
            .setMessage("助手将读取当前页面，必要时把截图发送给您配置的模型。每一步由您亲自点击。输入输出和截图会保存在本机 Debug 中，供排查问题，可随时清除。\n\n$launch")
            .setPositiveButton("开始") { _,_ ->
                if(AppRuntime.active) { toast("任务正在进行");return@setPositiveButton }
                if(!AppRuntime.connected) { showPermission();return@setPositiveButton }
                if(task.fixed) AppRuntime.start?.invoke() else AppRuntime.startCustom?.invoke(task)
            }.setNegativeButton("取消",null).show()
    }
    private fun speakTask() {
        if(AppRuntime.active) { toast("请先停止当前引导，再输入新任务");return }
        if(voicePending) return
        voicePending=true
        try { startActivityForResult(VoiceTaskInput.intent(),VoiceTaskInput.REQUEST) }
        catch(_:android.content.ActivityNotFoundException) { voicePending=false;toast("手机没有可用的语音识别服务，请直接输入文字，也可使用键盘的语音输入") }
        catch(_:SecurityException) { voicePending=false;toast("无法打开语音识别，请检查系统设置或直接输入文字") }
    }
    @Deprecated("Android legacy activity result bridge")
    override fun onActivityResult(requestCode:Int,resultCode:Int,data:Intent?) {
        super.onActivityResult(requestCode,resultCode,data)
        if(requestCode!=VoiceTaskInput.REQUEST || !voicePending) return
        voicePending=false
        if(resultCode!=RESULT_OK) { toast("语音输入已取消或未识别，请重试或直接输入文字");return }
        runCatching { VoiceTaskInput.result(data) }.onSuccess {
            draft=it;taskInput?.setText(it)
            if(mine) showTask()
            toast("已识别，请核对任务后点开始引导")
        }.onFailure { toast(it.message ?: "没有听清，请重试") }
    }
    override fun onSaveInstanceState(outState:Bundle) {
        outState.putString("task_draft",taskInput?.text?.toString() ?: draft)
        outState.putBoolean("mine",mine);outState.putBoolean("voice_pending",voicePending)
        super.onSaveInstanceState(outState)
    }
    private fun showPermission() {
        AlertDialog.Builder(this).setTitle("开启操作引导权限").setMessage("在系统无障碍设置中找到“手机助手 · 操作引导”并开启。返回这里后即可开始。")
            .setPositiveButton("打开设置") { _,_-> startActivity(Intent(AndroidSettings.ACTION_ACCESSIBILITY_SETTINGS)) }.setNegativeButton("稍后",null).show()
    }
    private fun showMine() {
        mine=true;shortcut.visibility=View.GONE
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
