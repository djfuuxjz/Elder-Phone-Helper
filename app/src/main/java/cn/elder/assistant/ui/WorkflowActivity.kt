package cn.elder.assistant.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.WindowInsets
import android.widget.*
import cn.elder.assistant.core.AppRuntime
import cn.elder.assistant.workflow.WorkflowCatalog
import cn.elder.assistant.workflow.WorkflowStore
import org.json.JSONObject
import java.io.File

/** Developer tooling, separate from the elder-facing task screen. */
class WorkflowActivity:Activity() {
    private lateinit var body:LinearLayout
    private val store by lazy { WorkflowStore(File(filesDir,"workflows/library.json")) }
    private var exportText:String?=null
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        exportText=savedInstanceState?.getString("export")
        body=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;setPadding(WarmStyle.dp(this@WorkflowActivity,20),WarmStyle.dp(this@WorkflowActivity,16),WarmStyle.dp(this@WorkflowActivity,20),WarmStyle.dp(this@WorkflowActivity,16));setBackgroundColor(WarmStyle.background) }
        val scroll=ScrollView(this).apply { setBackgroundColor(WarmStyle.background);addView(body) }
        scroll.setOnApplyWindowInsetsListener { v,i -> val b=i.getInsets(WindowInsets.Type.systemBars());v.setPadding(0,b.top,0,b.bottom);i }
        setContentView(scroll);showList()
    }
    private fun label(text:String) { body.addView(TextView(this).apply { this.text=text;WarmStyle.text(this);textSize=18f;setPadding(4,16,4,16);setTextIsSelectable(true) }) }
    private fun button(text:String,action:()->Unit) { body.addView(Button(this).apply { WarmStyle.button(this);this.text=text;WarmStyle.text(this);minHeight=(52*resources.displayMetrics.density).toInt();setOnClickListener { attempt(action) } }) }
    private fun attempt(action:()->Unit) { runCatching(action).onFailure { Toast.makeText(this,it.message ?: "操作失败",Toast.LENGTH_LONG).show() } }
    private fun idle() { check(!AppRuntime.active && !AppRuntime.reviewRunning) { "请先停止任务并等待复盘完成" } }
    private fun showList() {
        body.removeAllViews();label("流程库 · 开发维护")
        label("固定微信视频流程默认启用，成功后可复盘、保留版本和回退。下方高频任务模板按自定义目标自动匹配，目前只读且不会自动晋升。")
        if(AppRuntime.reviewRunning) label("正在后台复盘，完成后点刷新。")
        AppRuntime.workflowTrialId?.let { label("下次任务试运行版本：$it") }
        button("刷新") { showList() };button("新建 / 粘贴 JSON 导入") { idle();edit(null) }
        button("停用流程约束") { idle();store.activate(null);showList() }
        button("取消下次试运行") { AppRuntime.workflowTrialId=null;showList() }
        button("返回") { finish() }
        store.list().forEach { v -> button("${if(v.getBoolean("active")) "已启用" else "候选/保留"} · ${v.getJSONObject("body").getString("name")}\n${v.getString("source")} · ${v.getString("id").take(8)}${if(v.getBoolean("locked")) " · 已锁定" else ""}") { detail(v.getString("id")) } }
        label("内置高频任务流程 · 匹配后自动使用（只读）")
        WorkflowCatalog.all().forEach { template ->
            button(template.getString("name")) { templateDetail(template) }
        }
    }
    private fun templateDetail(template:JSONObject) {
        body.removeAllViews();label("内置流程模板 · 尚未真机验收")
        label(template.toString(2))
        button("返回列表") { showList() }
    }
    private fun detail(id:String) {
        val v=store.list().first { it.getString("id")==id };body.removeAllViews();label(v.toString(2))
        val base=store.list().find { it.getString("id")==v.optString("base_version") }
        if(base!=null) label("原版本流程（供比较）：\n"+base.getJSONObject("body").toString(2))
        button("下次任务试运行此版本") { idle();AppRuntime.workflowTrialId=id;showList() }
        button("启用 / 回退到此版本") { idle();store.activate(id);showList() }
        button(if(v.getBoolean("locked")) "解锁" else "锁定") { idle();store.setLocked(id,!v.getBoolean("locked"));detail(id) }
        button("修改并保存新版本") { idle();check(!v.getBoolean("locked")) { "请先解锁，原版本始终保留" };edit(v) }
        button("导出流程 JSON") {
            exportText=v.getJSONObject("body").toString(2)
            startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/json").putExtra(Intent.EXTRA_TITLE,"workflow_${id.take(8)}.json"),201)
        }
        button("返回列表") { showList() }
    }
    private fun edit(version:JSONObject?) {
        val input=EditText(this).apply { setText((version?.getJSONObject("body") ?: WorkflowStore.builtin()).toString(2));textSize=16f;minLines=10;gravity=android.view.Gravity.TOP }
        val dialog=AlertDialog.Builder(this).setTitle("流程 JSON · 保存为候选版本").setView(ScrollView(this).apply { addView(input) }).setPositiveButton("保存",null).setNegativeButton("取消",null).create()
        dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { attempt {
            idle();require(input.text.length<=20000) { "流程过长" };val parsed=JSONObject(input.text.toString())
            if(version==null) store.add(parsed) else store.revise(version.getString("id"),parsed)
            dialog.dismiss();showList()
        } } };dialog.show()
    }
    override fun onSaveInstanceState(outState:Bundle) { outState.putString("export",exportText);super.onSaveInstanceState(outState) }
    @Deprecated("Android legacy activity result bridge")
    override fun onActivityResult(requestCode:Int,resultCode:Int,data:Intent?) {
        super.onActivityResult(requestCode,resultCode,data)
        if(requestCode!=201 || resultCode!=RESULT_OK) return
        val uri=data?.data ?: return;val text=exportText ?: return
        attempt { contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) } ?: error("无法写入所选位置");Toast.makeText(this,"流程已导出",Toast.LENGTH_SHORT).show() }
    }
}
