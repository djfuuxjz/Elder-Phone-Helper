package cn.elder.assistant.guidance

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import cn.elder.assistant.config.Settings
import cn.elder.assistant.core.*
import cn.elder.assistant.logging.RunLog
import cn.elder.assistant.model.*
import cn.elder.assistant.observation.*
import cn.elder.assistant.overlay.GuideOverlay
import cn.elder.assistant.speech.Speaker
import cn.elder.assistant.workflow.*
import cn.elder.assistant.logging.RunMetrics
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

class GuidanceController(private val service:AccessibilityService) {
    private val handler=Handler(Looper.getMainLooper())
    private val worker=Executors.newSingleThreadExecutor()
    private val reader=ScreenReader(service)
    private val screenshots=Screenshotter(service,reader,worker)
    private val overlay=GuideOverlay(service,{ stopAndReturn() },{ retry() })
    private val successNotice=SuccessNotice(handler,{ overlay.show("任务已成功",controls=false) },{ overlay.hide() })
    private val stable=StablePage()
    private val epoch=PageEpoch()
    private var lastStructure:String?=null
    private val flow=SessionFlow()
    private var log:RunLog?=null
    private val workflows=WorkflowStore(File(service.filesDir,"workflows/library.json"))
    private var workflow:JSONObject?=null
    private var reviewProvider:ModelProvider?=null
    private var provider:ModelProvider?=null
    private var speaker:Speaker?=null
    private var lastProgress=0L
    private val timing=RunTiming()
    private var stepStarted=0L
    private var needImage=false
    private val history=TaskHistory()
    private var providerContext:JSONObject?=null
    private var key=""
    private var endpoint=""
    private var model=""
    private var vision=true
    private var closed=false
    private var launchedFromHome=false
    private val homePackage=service.packageManager.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),0)?.activityInfo?.packageName
    private val ticker=object:Runnable { override fun run() { if(flow.active && !closed) { runCatching { poll() }.onFailure { attention(it.message ?: "读取页面失败") }; handler.postDelayed(this,600) } } }
    fun start() {
        if(flow.active || closed) return
        successNotice.cancel()
        cancelReview("开始新任务，取消上一任务复盘")
        try {
            val cfg=Settings(service);key=cfg.apiKey();endpoint=cfg.endpoint;model=cfg.model;vision=cfg.vision
            require(key.isNotBlank()) { "请先在我的页面填写模型密钥" }
            log=RunLog(File(service.filesDir,"runs"),listOf(key))
            workflow=workflows.selected(AppRuntime.workflowTrialId);AppRuntime.workflowTrialId=null
            flow.start();history.clear();stable.reset();epoch.reset();lastStructure=null;needImage=false
            launchedFromHome=false
            timing.reset(AppRuntime.debugWaitForStart)
            AppRuntime.debugWaitForStart=false
            lastProgress=SystemClock.elapsedRealtime();stepStarted=lastProgress
            AppRuntime.active=true
            logEvent("start",JSONObject().put("model",model).put("provider",endpoint).put("prompt_version",TaskSpec.PROMPT_VERSION)
                .put("workflow",workflow ?: JSONObject.NULL).put("device",android.os.Build.MODEL).put("android",android.os.Build.VERSION.RELEASE).put("wait_for_start",timing.waiting))
            speaker=Speaker(service) { reason -> handler.post { if(flow.active) logEvent("tts_error",JSONObject().put("error",reason)) } }
            if(timing.waiting) {
                AppRuntime.status="请准备测试起点，再点从当前页开始"
                overlay.show(AppRuntime.status,retry=true,retryLabel="从当前页开始")
            } else {
                if(reader.capture()?.packageName=="com.tencent.mm") show("正在读取微信页面",speak=false)
                else openTargetApp()
            }
            handler.post(ticker)
        } catch(e:Exception) { if(flow.active) stop(Phase.FAILED);AppRuntime.status=e.message ?: "无法开始任务" }
    }
    private fun poll() {
        if(flow.phase==Phase.NEEDS_ATTENTION || timing.waiting) return
        val now=SystemClock.elapsedRealtime()
        val elapsed=timing.elapsed(now)
        if(elapsed!=null && elapsed>300000) { attention("本次引导已等待较久，请停止后重新开始");return }
        if(elapsed!=null && now-lastProgress>60000) { attention("暂时没有看到页面变化，请重试或停止");return }
        val s=reader.capture() ?: run { overlay.hide();speaker?.cancel();cancelRequest("当前页面无法读取，取消旧请求");return }
        if(s.packageName !in setOf("com.tencent.mm",homePackage)) {
            if(flow.fingerprint!=null) attention("请先回到微信或桌面，再点重试")
            return
        }
        if(s.packageName==homePackage) {
            if(!launchedFromHome) { launchedFromHome=true;cancelRequest("回到桌面，直接打开微信");openTargetApp() }
            return
        }
        launchedFromHome=false
        val structure=s.fingerprint
        if(structure!=lastStructure) epoch.trackingVisual=s.nodes.none { it.text.isNotBlank() || it.description.isNotBlank() }
        val currentKey=epoch.key(structure)
        // Remove stale guidance immediately, before the debounce window finishes.
        if(flow.fingerprint!=null && currentKey!=flow.fingerprint) { overlay.hide();speaker?.cancel();cancelRequest("页面状态变化，取消旧请求") }
        if(!stable.offer(currentKey,now)) return
        val first=timing.elapsed(now)==null
        if(!timing.observe(now)) return
        if(first) logEvent("measurement_start",JSONObject().put("snapshot_id",s.id).put("page",s.packageName))
        if(flow.observe(currentKey)) {
            lastStructure=structure;needImage=epoch.trackingVisual;lastProgress=now;stepStarted=now;cancelRequest("已进入新的观察步骤，取消旧请求");stable.reset()
            history.add(flow.step,"观察变化","当前应用 ${s.packageName}；可见文字：${s.nodes.map { it.text.ifBlank { it.description } }.filter { it.isNotBlank() }.take(12).joinToString(" / ")}；页面变化不代表上一建议已成功执行")
            logEvent("observation",JSONObject().put("step",flow.step).put("snapshot_id",s.id).put("page",s.packageName))
        }
        if(flow.phase in setOf(Phase.OBSERVING,Phase.VERIFYING)) dispatch(s)
    }
    private fun dispatch(s:Snapshot) {
        val sentEpoch=epoch.version
        val token=try { flow.request() } catch(e:Exception) { attention(e.message ?: "请求次数过多");return }
        val thisLog=log ?: return
        val requestId="${thisLog.id}_${token.step}_${token.attempt}"
        val treeRef=try { thisLog.artifact("${requestId}_tree.json",s.json().toString(2).toByteArray()) }
            catch(e:Exception) { attention(e.message ?: "页面快照保存失败");return }
        show("正在看页面，请稍候",speak=false)
        val done:(Result<ByteArray?>)->Unit = done@{ imageResult ->
            if(!flow.accepts(token)) return@done
            if(sentEpoch!=epoch.version || !SnapshotConsistency.matches(s,reader.capture())) { flow.responded(token,Phase.OBSERVING); stable.reset();return@done }
            val image=imageResult.getOrElse {
                logEvent("capture_error",JSONObject().put("step",token.step).put("attempt",token.attempt).put("snapshot_id",s.id)
                    .put("ui_tree_ref",treeRef).put("error",it.message ?: "截图失败"))
                attention(it.message ?: "截图失败");return@done
            }
            try {
                val imageRef=image?.let { thisLog.artifact("${requestId}.jpg",it) }
                val p=DeepSeekProvider(endpoint,model,key,vision);provider=p
                val body=p.body(ModelInput(thisLog.id,token.step,token.attempt,s,history.snapshot(),image,WorkflowGuide.context(workflow,s)))
                val logged=JSONObject(body.toString())
                if(imageRef!=null) logged.getJSONArray("messages").getJSONObject(1).getJSONArray("content").getJSONObject(1)
                    .getJSONObject("image_url").put("url","local:$imageRef")
                val base=JSONObject().put("step",token.step).put("attempt",token.attempt).put("request_id",requestId)
                    .put("snapshot_id",s.id).put("ui_tree_ref",treeRef).put("screenshot_ref",imageRef ?: JSONObject.NULL)
                    .put("screenshot_reason",if(image==null) "UI Tree 优先" else "补充视觉定位/完成确认")
                providerContext=base
                thisLog.event("request_prepared",JSONObject(base.toString()).put("input",logged).put("model",model))
                worker.execute {
                    var requested:Long?=null
                    val result=runCatching { p.execute(body) {
                        requested=SystemClock.elapsedRealtime()
                        thisLog.event("request",JSONObject(base.toString()).put("input",logged).put("model",model))
                    } }
                    val latency=requested?.let { SystemClock.elapsedRealtime()-it }
                    handler.post {
                        if(provider===p) { provider=null;providerContext=null }
                        val event=JSONObject(base.toString())
                        if(latency!=null) event.put("latency_ms",latency)
                        result.fold({ r -> event.put("output_raw",r.raw).put("content",r.content) },{ e ->
                            val details=RequestFailure.describe(e,p.cancellationReason)
                            details.keys().forEach { name -> event.put(name,details.get(name)) }
                            if(e is ModelFailure) event.put("output_raw",e.raw)
                        })
                        if(!safeEvent(thisLog,if(requested==null) "request_cancelled" else "response",event)) return@post
                        if(!flow.accepts(token) || p.cancellationReason!=null) {
                            val reason=p.cancellationReason ?: "会话/页面版本已失效"
                            safeEvent(thisLog,"discarded",JSONObject(base.toString()).put("reason",reason))
                            if(thisLog===log) history.add(token.step,"请求未采用",reason)
                            if(flow.accepts(token)) { flow.responded(token,Phase.OBSERVING);stable.reset() }
                            return@post
                        }
                        AppRuntime.modelStatus=if(result.isSuccess) "最近一次模型请求成功" else "最近一次模型请求失败，请查看 Debug"
                        if(sentEpoch!=epoch.version || !SnapshotConsistency.matches(s,reader.capture())) {
                            flow.responded(token,Phase.OBSERVING);overlay.hide();stable.reset()
                            history.add(token.step,"结果未采用","模型返回时页面已变化，重新观察")
                            safeEvent(thisLog,"discarded",JSONObject(base.toString()).put("reason","响应到达时页面已变化"));return@post
                        }
                        result.fold({ r -> applyDecision(token,s,r.content,image!=null,base) },{ e ->
                            val message=when((e as? ModelFailure)?.code) { "HTTP_401" -> "模型密钥无效，请停止后在我的页面检查"; "HTTP_429" -> "模型服务繁忙或额度不足，请稍后重试"; else -> "模型调用失败，请检查网络和配置后重试" }
                            history.add(token.step,"调用失败",event.optString("error",message))
                            attention(message)
                        })
                    }
                }
            } catch(e:Exception) { attention(e.message ?: "模型请求准备失败") }
        }
        if(needImage || s.nodes.none { it.text.isNotBlank() || it.description.isNotBlank() }) {
            if(!vision) { attention("当前页面需要图片理解，请停止后启用支持图片的模型");return }
            epoch.trackingVisual=true
            screenshots.capture(s,{ overlay.hide() },{ !closed && flow.accepts(token) }) { result -> done(result.map { it }) }
        } else done(Result.success(null))
    }
    private fun applyDecision(token:RequestToken,s:Snapshot,raw:String,hasImage:Boolean,base:JSONObject) {
        try {
            val d=DecisionCodec.parse(raw)
            history.add(token.step,"模型判断","${d.state}；建议 ${d.action}：${d.instruction}")
            logEvent("decision",JSONObject(base.toString()).put("output_parsed",JSONObject(raw)))
            if(d.action==Action.NEED_SCREENSHOT) {
                if(hasImage) { attention("有截图仍无法确定下一步，请重试");return }
                history.add(token.step,"需要截图","文本信息不足，下一次提供截图");needImage=true;flow.responded(token,Phase.OBSERVING);return
            }
            val originalTarget=DecisionCodec.target(d,s)
            val target=WorkflowGuide.target(d,s,originalTarget,workflow!=null)
            if(ReturnGuidance.needsLocation(d,target,hasImage,vision,flow.attempt)) {
                history.add(token.step,"补充返回位置",ReturnGuidance.REQUEST)
                logEvent("return_location_requested",JSONObject(base.toString()).put("reason",ReturnGuidance.REQUEST).put("with_screenshot",vision))
                needImage=vision;flow.responded(token,Phase.OBSERVING);return
            }
            val instruction=d.instruction
            if(CompletionCheck.modelConfirms(d)) {
                logEvent("completed_evidence",JSONObject(base.toString()).put("evidence",org.json.JSONArray(d.evidence)).put("method","model_decision"))
                stop(Phase.COMPLETED);return
            }
            if(d.action==Action.BLOCKED) { history.add(token.step,"模型无法继续",d.instruction);attention(d.instruction);return }
            flow.responded(token,Phase.WAITING_USER);lastProgress=SystemClock.elapsedRealtime()
            history.add(token.step,"已展示，等待用户操作","${d.state}：${instruction}；证据：${d.evidence.joinToString()}")
            show(instruction,ReturnGuidance.highlight(d.action,target))
            logEvent("guidance",JSONObject(base.toString()).put("instruction",instruction).put("action",d.action.name).put("target",target?.json() ?: JSONObject.NULL)
                .put("guidance_latency_ms",SystemClock.elapsedRealtime()-stepStarted))
        } catch(e:Exception) {
            history.add(token.step,"建议未展示", "${e.message ?: "解析失败"}；返回摘要：${raw.take(250)}")
            logEvent("invalid_decision",JSONObject(base.toString()).put("error",e.message ?: "解析失败"))
            if(!hasImage && vision && flow.attempt<3) { needImage=true;flow.responded(token,Phase.OBSERVING) }
            else attention("未找到可靠的操作位置，请重试")
        }
    }
    fun retry() {
        if(!flow.active) return
        if(timing.waiting) {
            timing.release();stable.reset();logEvent("preparation_finished");show("正在读取测试起点",speak=false);return
        }
        cancelRequest("用户点击重试，取消上一请求");history.add(flow.step,"用户重试","上一轮没有完成，需根据当前页面调整方案");flow.retry();stable.reset();needImage=false;lastProgress=SystemClock.elapsedRealtime();stepStarted=lastProgress
        logEvent("manual_retry");show("正在重新看页面",speak=false)
    }
    fun pageEvent(packageName:String) {
        if(flow.active && packageName in setOf("com.tencent.mm",homePackage)) epoch.pageEvent()
    }
    private fun attention(reason:String) { if(!flow.active) return;cancelRequest("任务暂停：$reason");flow.pause();logEvent("attention",JSONObject().put("error",reason));show(reason,retry=true) }
    private fun show(text:String,target:Box?=null,retry:Boolean=false,speak:Boolean=true) {
        if(!flow.active) return
        AppRuntime.status=text
        overlay.show(text,target,retry)
        if(speak) speaker?.speak(text)
    }
    private fun logEvent(type:String,data:JSONObject=JSONObject()) { log?.let { safeEvent(it,type,data) } }
    private fun safeEvent(destination:RunLog,type:String,data:JSONObject):Boolean = try { destination.event(type,data);true } catch(e:Exception) {
        // An old response may arrive after its run was cleared. It must not kill a newer session.
        if(destination===log && flow.active) {
            flow.finish(Phase.FAILED);provider?.cancel("日志保存失败，任务终止");overlay.hide();speaker?.close();speaker=null;handler.removeCallbacks(ticker)
            AppRuntime.active=false;AppRuntime.status="日志保存失败，请在 Debug 清理空间后重新开始"
        }
        false
    }
    fun stop(result:Phase=Phase.STOPPED,reason:String?=null) {
        successNotice.cancel()
        val wasActive=flow.active
        val reviewKey=key
        if(result!=Phase.COMPLETED) cancelReview(reason ?: "用户停止或服务关闭")
        if(wasActive) flow.finish(result) // Invalidate callbacks before cleanup or logging.
        cancelRequest(reason ?: if(result==Phase.STOPPED) "用户停止任务" else "任务结束：${result.name}");provider=null;handler.removeCallbacks(ticker);overlay.hide();speaker?.close();speaker=null;key=""
        if(wasActive) logEvent("end",JSONObject().put("result",result.name).put("reason",reason ?: if(result==Phase.STOPPED) "用户停止任务" else "任务结束").put("duration_ms",timing.elapsed(SystemClock.elapsedRealtime()) ?: JSONObject.NULL))
        AppRuntime.active=false;AppRuntime.status=if(result==Phase.COMPLETED) "已向女儿发起视频通话" else "引导已停止，可重新开始"
        if(wasActive && result==Phase.COMPLETED) {
            successNotice.show()
            log?.let { startReview(it,reviewKey,workflow) }
        }
    }
    private fun cancelReview(reason:String) {
        reviewProvider?.cancel(reason);reviewProvider=null;AppRuntime.reviewRunning=false
    }
    private fun startReview(destination:RunLog,secret:String,version:JSONObject?) {
        if(secret.isBlank()) return
        val p=DeepSeekProvider(endpoint,model,secret,false);reviewProvider=p;AppRuntime.reviewRunning=true
        val reviewModel=model
        val base=JSONObject().put("purpose","workflow_review").put("request_id","${destination.id}_review").put("step",0).put("attempt",1)
        worker.execute {
            var started:Long?=null
            val result=runCatching {
                val events=RunMetrics.events(destination.directory)
                val body=WorkflowReview.body(reviewModel,version,events)
                destination.event("request_prepared",JSONObject(base.toString()).put("input",body))
                val response=p.execute(body) { started=SystemClock.elapsedRealtime();destination.event("request",JSONObject(base.toString()).put("input",body)) }
                destination.event("response",JSONObject(base.toString()).put("output_raw",response.raw).put("content",response.content).put("latency_ms",SystemClock.elapsedRealtime()-started!!))
                response.content to events.mapNotNull { it.optJSONObject("data")?.optString("request_id")?.takeIf { id -> id.isNotBlank() } }.toSet()
            }
            if(result.isFailure) runCatching {
                val error=result.exceptionOrNull()!!;val detail=RequestFailure.describe(error,p.cancellationReason)
                base.keys().forEach { name -> detail.put(name,base.get(name)) }
                if(started!=null) detail.put("latency_ms",SystemClock.elapsedRealtime()-started!!)
                if(error is ModelFailure) detail.put("output_raw",error.raw)
                destination.event(if(started==null) "request_cancelled" else "response",detail)
            }
            handler.post {
                // Only this review owns its completion flag; stale work cannot mutate a newer run.
                if(reviewProvider!==p || p.cancellationReason!=null || closed) {
                    runCatching { destination.event("workflow_review_cancelled",JSONObject(base.toString()).put("reason",p.cancellationReason ?: "控制器已关闭")) };return@post
                }
                reviewProvider=null;AppRuntime.reviewRunning=false
                result.fold({ (content,ids) ->
                    runCatching { WorkflowReview.save(content,workflows,version?.optString("id"),destination.id,ids) }
                        .onSuccess { id -> runCatching { destination.event("workflow_candidate_saved",JSONObject(base.toString()).put("version_id",id).put("status",if(workflows.selected()?.optString("id")==id) "已自动启用" else "已保存，保留当前人工选择或锁定版本")) } }
                        .onFailure { e -> runCatching { destination.event("workflow_review_error",JSONObject(base.toString()).put("error",e.message)) } }
                },{ e -> runCatching { destination.event("workflow_review_error",RequestFailure.describe(e,p.cancellationReason)) } })
            }
        }
    }
    private fun cancelRequest(reason:String) {
        val p=provider ?: return
        if(p.cancellationReason==null) {
            p.cancel(reason)
            history.add(flow.step,"请求取消",reason)
            val data=JSONObject(providerContext?.toString() ?: "{}").put("reason",reason)
            log?.let { destination -> runCatching { destination.event("request_cancelled_by_app",data) } }
        }
    }
    private fun openTargetApp() {
        launchedFromHome=true
        TargetAppLauncher.open(service).fold({
            history.add(0,"打开应用","已请求系统打开微信，等待实际微信页面")
            logEvent("app_launch",JSONObject().put("package","com.tencent.mm").put("result","requested"))
            show("正在打开微信，请稍候",speak=false)
        },{ e ->
            logEvent("app_launch_error",JSONObject().put("error",e.message ?: "系统未允许打开微信"))
            attention("无法自动打开微信，请手动打开微信后重试")
        })
    }
    fun stopAndReturn() {
        stop()
        try {
            service.startActivity(Intent(service,cn.elder.assistant.ui.MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        } catch(_:RuntimeException) {
            AppRuntime.status="引导已停止，请手动打开手机助手"
            android.widget.Toast.makeText(service,AppRuntime.status,android.widget.Toast.LENGTH_LONG).show()
        }
    }
    fun close() { stop(Phase.FAILED,"无障碍服务已断开或控制器关闭");closed=true;handler.removeCallbacksAndMessages(null);worker.shutdown();overlay.hide() }
}
