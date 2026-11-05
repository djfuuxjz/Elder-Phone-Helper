package cn.elder.assistant.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.View
import android.view.WindowInsets
import android.widget.*
import cn.elder.assistant.core.AppRuntime
import cn.elder.assistant.logging.RunMetrics
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class DebugActivity:Activity() {
    private lateinit var body:LinearLayout
    private val worker=Executors.newSingleThreadExecutor()
    private var selected:File?=null
    private val root:File get()=File(filesDir,"runs")
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        body=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;setPadding(WarmStyle.dp(this@DebugActivity,20),WarmStyle.dp(this@DebugActivity,16),WarmStyle.dp(this@DebugActivity,20),WarmStyle.dp(this@DebugActivity,16));setBackgroundColor(WarmStyle.background) }
        val scroll=ScrollView(this).apply { setBackgroundColor(WarmStyle.background);addView(body) }
        scroll.setOnApplyWindowInsetsListener { v,i ->val b=i.getInsets(WindowInsets.Type.systemBars());v.setPadding(0,b.top,0,b.bottom);i }
        setContentView(scroll);showRuns()
    }
    private fun title(text:String) { body.removeAllViews();label(text,26f) }
    private fun label(text:String,size:Float=18f) { body.addView(TextView(this).apply { this.text=text;WarmStyle.text(this);textSize=size;setPadding(4,12,4,12);setTextIsSelectable(true) }) }
    private fun button(text:String,fn:()->Unit) { body.addView(Button(this).apply { WarmStyle.button(this);this.text=text;WarmStyle.text(this);textSize=18f;minHeight=(52*resources.displayMetrics.density).toInt();isAllCaps=false;setOnClickListener { fn() } }) }
    private fun showRuns() {
        selected=null;title("Debug · 开发调试")
        label("以下是本机技术日志。截图和页面文字可能包含个人信息，仅在需要排查时导出。")
        body.addView(CheckBox(this).apply {
            text="下次任务等待测试起点就绪";textSize=18f
            isChecked=AppRuntime.debugWaitForStart;isEnabled=!AppRuntime.active
            setOnCheckedChangeListener { _,checked -> AppRuntime.debugWaitForStart=checked }
        })
        label("勾选后返回任务页启动；切到指定微信页面，再点浮层“从当前页开始”。准备期间不读屏、不调用模型、不计时。",16f)
        button("刷新") { showRuns() }
        button("返回") { finish() }
        button("清理全部调试记录") {
            if(AppRuntime.active || AppRuntime.reviewRunning || cn.elder.assistant.logging.EvidenceWork.pending) { alert("请先停止任务，等待复盘和页面证据保存结束");return@button }
            AlertDialog.Builder(this).setTitle("清理调试记录？").setMessage("将删除本机的模型日志、页面快照和验收标注。请先导出需要保留的记录。")
                .setPositiveButton("清理") { _,_-> runCatching { check(root.deleteRecursively()) { "无法删除部分调试文件，请检查文件是否被占用" } }.onSuccess { showRuns() }.onFailure { alert("清理失败：${it.message}") } }.setNegativeButton("取消",null).show()
        }
        val runs=root.listFiles()?.filter { it.isDirectory && it.name.startsWith("run_") }?.sortedByDescending { cn.elder.assistant.logging.RunNames.time(it) }.orEmpty()
        if(runs.isEmpty()) label("还没有运行日志。请先完成模型配置并启动一次任务。")
        runs.forEach { dir -> button(dir.name) { showRun(dir) } }
    }
    private fun showRun(dir:File) {
        selected=dir;title(dir.name);button("返回日志列表") { showRuns() };label("正在读取…")
        worker.execute {
            val result=runCatching { val events=RunMetrics.events(dir); events to RunMetrics.summarize(events) }
            runOnUiThread {
                if(isDestroyed || selected!=dir) return@runOnUiThread
                title(dir.name);button("返回日志列表") { showRuns() }
                button("导出本次调试包") {
                    if(AppRuntime.active || AppRuntime.reviewRunning || cn.elder.assistant.logging.EvidenceWork.pending) { alert("请先停止任务，等待复盘和页面证据保存结束，保证导出数据完整");return@button }
                    startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/zip").putExtra(Intent.EXTRA_TITLE,cn.elder.assistant.logging.RunNames.exportName(dir)),101)
                }
                button("填写真机验收标注") { annotate(dir) }
                result.fold({ (events,summary) ->
                    label("任务目标："+events.firstOrNull()?.optString("goal").orEmpty())
                    label("调用 ${summary.getInt("calls")} 次（复盘 ${summary.getInt("review_calls")} 次） · 错误 ${summary.getInt("error_count")} 次\n状态：${summary.getString("result")}\nP95：${summary.opt("all_latency_p95_ms")} ms")
                    button("完整统计") { showText(summary.toString(2)) }
                    File(dir,"annotation.json").takeIf { it.exists() }?.let { label("人工标注：\n"+it.readText()) }
                    events.forEach { e -> val data=e.optJSONObject("data");val step=if(data?.has("step")==true) "第 ${data.getInt("step")} 步 " else ""
                        button(step+e.getString("event")) { eventDetails(dir,e) }
                    }
                },{ alert(it.message ?: "日志读取失败") })
            }
        }
    }
    private fun eventDetails(dir:File,e:JSONObject) {
        title("步骤详情");button("返回本次运行") { showRun(dir) }
        val data=e.optJSONObject("data")
        for(key in listOf("ui_tree_ref","screenshot_ref")) {
            if(data!=null && !data.isNull(key)) {
                val name=data.getString(key)
                button(if(key=="ui_tree_ref") "查看 UI Tree" else "查看截图") {
                    runCatching { RunMetrics.artifact(dir,name) }.onSuccess { file ->
                        if(file.extension=="jpg") showImage(file) else showText(file.readText())
                    }.onFailure { alert(it.message ?: "附件无法打开") }
                }
            }
        }
        label(e.toString(2),15f)
    }
    private fun showText(text:String) {
        val view=TextView(this).apply { this.text=text;WarmStyle.text(this);textSize=15f;setPadding(WarmStyle.dp(this@DebugActivity,20),WarmStyle.dp(this@DebugActivity,16),WarmStyle.dp(this@DebugActivity,20),WarmStyle.dp(this@DebugActivity,16));setBackgroundColor(WarmStyle.background);setTextIsSelectable(true) }
        AlertDialog.Builder(this).setView(ScrollView(this).apply { addView(view) }).setPositiveButton("关闭",null).show()
    }
    private fun showImage(file:File) {
        val image=ImageView(this).apply { adjustViewBounds=true;contentDescription="该步骤的页面截图" }
        val dialog=AlertDialog.Builder(this).setTitle("页面截图").setView(ScrollView(this).apply { addView(image) }).setPositiveButton("关闭",null).create()
        dialog.show()
        worker.execute {
            val bitmap=BitmapFactory.decodeFile(file.absolutePath)
            runOnUiThread { if(!isDestroyed && dialog.isShowing) { if(bitmap!=null) image.setImageBitmap(bitmap) else alert("截图无法解码") } else bitmap?.recycle() }
        }
    }
    private fun annotate(dir:File) {
        if(AppRuntime.active || AppRuntime.reviewRunning || cn.elder.assistant.logging.EvidenceWork.pending) { alert("请先停止任务，等待复盘和页面证据保存结束后标注");return }
        val form=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;setPadding(32,16,32,16) }
        val case=EditText(this).apply { hint="用例编号，例如 E01" }
        val success=CheckBox(this).apply { text="实际完成本次任务目标" }
        val errors=EditText(this).apply { hint="错误指引次数";inputType=android.text.InputType.TYPE_CLASS_NUMBER;setText("0") }
        val note=EditText(this).apply { hint="设备/应用版本、失败原因和错误步骤" }
        listOf<View>(case,success,errors,note).forEach { form.addView(it) }
        val dialog=AlertDialog.Builder(this).setTitle("人工验收记录").setView(form).setPositiveButton("保存",null).setNegativeButton("取消",null).create()
        dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val count=errors.text.toString().toIntOrNull()
            if(case.text.isBlank() || count==null || count<0) { alert("请填写用例编号和有效错误次数");return@setOnClickListener }
            runCatching { File(dir,"annotation.json").writeText(JSONObject().put("case_id",case.text.toString()).put("actual_success",success.isChecked)
                .put("wrong_guidance",count).put("note",note.text.toString()).toString(2)) }
                .onSuccess { dialog.dismiss();showRun(dir) }.onFailure { alert("标注保存失败") }
        } }
        dialog.show()
    }
    @Deprecated("Android legacy activity result bridge")
    override fun onActivityResult(requestCode:Int,resultCode:Int,data:Intent?) {
        super.onActivityResult(requestCode,resultCode,data)
        if(requestCode!=101 || resultCode!=RESULT_OK) return
        val uri=data?.data ?: return;val dir=selected ?: return
        worker.execute {
            val result=runCatching { contentResolver.openOutputStream(uri)?.use { out -> ZipOutputStream(out).use { zip ->
                dir.listFiles()?.filter { it.isFile }?.forEach { file -> zip.putNextEntry(ZipEntry(file.name));file.inputStream().use { it.copyTo(zip) };zip.closeEntry() }
            } } ?: error("无法写入所选位置") }
            runOnUiThread { if(!isDestroyed) alert(if(result.isSuccess) "调试包已导出" else "导出失败：${result.exceptionOrNull()?.message}") }
        }
    }
    private fun alert(message:String) { if(!isDestroyed) AlertDialog.Builder(this).setMessage(message).setPositiveButton("知道了",null).show() }
    override fun onDestroy() { worker.shutdown();super.onDestroy() }
}
