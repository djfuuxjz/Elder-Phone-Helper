package cn.elder.assistant.guidance

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import cn.elder.assistant.config.Settings
import cn.elder.assistant.core.*
import cn.elder.assistant.logging.EvidenceWork
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
    private val worker=Executors.newFixedThreadPool(2)
    private val captureWorker=Executors.newSingleThreadExecutor()
    private val logWorker=Executors.newSingleThreadExecutor()
    private val gui=GuiExecutor(service)
    private val behavior=cn.elder.assistant.logging.BehaviorRecorder()
    private var pageEventVersion=0L
    private var lastSnapshot:Snapshot?=null
    private var currentBase:JSONObject?=null
    private var autoGui=false
    private var automaticUntil=0L
    private var pendingExecution:JSONObject?=null
    private var pendingLaunch:InstalledApp?=null
    private var launchDeadline=0L
    private var launchFallback=false
    private var launchGuiAttempted=false
    private var apps=emptyList<InstalledApp>()
    private val reader=ScreenReader(service)
    private val screenshots=Screenshotter(service,reader,captureWorker)
    private val overlay=GuideOverlay(service,{ stopAndReturn() },{ retry() })
    private val successNotice=SuccessNotice(handler,{ overlay.show("任务已成功",controls=false) },{ overlay.hide() })
    private val stable=StablePage()
    private val epoch=PageEpoch()
    private var lastStructure:String?=null
    private var firstSeenKey:String?=null
    private var firstSeenAt=0L
    private var unreadableSince=0L
    private var lastUnreadableLog=0L
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
    private var task=TaskGoal.DEFAULT
    private var closed=false
    private var launchedFromHome=false
    private val homePackage=service.packageManager.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),0)?.activityInfo?.packageName
    private val ticker=object:Runnable { override fun run() { if(flow.active && !closed) { runCatching { poll() }.onFailure { logEvent("diagnostic",Diagnostic.describe(it,"observation"));attention(it.message ?: "读取页面失败") }; handler.postDelayed(this,200) } } }
    fun start(requestedTask:TaskGoal=TaskGoal.DEFAULT) {
        if(flow.active || closed) return
        task=requestedTask
        successNotice.cancel()
        cancelReview("开始新任务，取消上一任务复盘")
        try {
            val cfg=Settings(service);key=cfg.apiKey();endpoint=cfg.endpoint;model=cfg.model;vision=cfg.vision;autoGui=cfg.autoGui;apps=TargetAppLauncher.installed(service)
            require(key.isNotBlank()) { "请先在我的页面填写模型密钥" }
            log=RunLog(File(service.filesDir,"runs"),listOf(key),task=task)
            workflow=if(task.fixed) workflows.selected(AppRuntime.workflowTrialId) else WorkflowCatalog.forTask(task)
            if(task.fixed) AppRuntime.workflowTrialId=null
            flow.start();history.clear();stable.reset();epoch.reset();lastStructure=null;needImage=false
            launchedFromHome=false;firstSeenKey=null;unreadableSince=0L;lastUnreadableLog=0L;lastSnapshot=null;currentBase=null;pendingLaunch=null;pendingExecution=null
            timing.reset(AppRuntime.debugWaitForStart)
            AppRuntime.debugWaitForStart=false
            lastProgress=SystemClock.elapsedRealtime();stepStarted=lastProgress
            AppRuntime.active=true
            logEvent("start",JSONObject().put("model",model).put("provider",endpoint).put("prompt_version",if(task.fixed) TaskSpec.PROMPT_VERSION else "custom-1")
                .put("workflow",workflow ?: JSONObject.NULL).put("device",android.os.Build.MODEL).put("android",android.os.Build.VERSION.RELEASE).put("wait_for_start",timing.waiting))
            speaker=Speaker(service) { reason -> handler.post { if(flow.active) logEvent("tts_error",JSONObject().put("error",reason)) } }
            if(timing.waiting) {
                AppRuntime.status="请准备测试起点，再点从当前页开始"
                overlay.show(AppRuntime.status,retry=true,retryLabel="从当前页开始")
            } else {
                if(!task.fixed) {
                    service.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    show("请稍候，正在从桌面开始引导",speak=false)
                } else if(reader.capture()?.packageName=="com.tencent.mm") show("正在读取微信页面",speak=false)
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
        val s=reader.capture() ?: run {
            overlay.hide();speaker?.cancel();cancelRequest("当前页面无法读取，取消旧请求")
            if(unreadableSince==0L) unreadableSince=now
            if(now-lastUnreadableLog>=5000) {
                lastUnreadableLog=now
                logEvent("diagnostic",JSONObject().put("stage","observation").put("error_code","NO_ACCESSIBLE_ROOT").put("rule","readable_foreground_window")
                    .put("expected","可读取的外部应用窗口").put("actual","窗口为空或仅能读取助手自身").put("snapshot_id",JSONObject.NULL)
                    .put("error","当前页面无法读取：系统未提供外部应用 UI 树").put("screenshot_missing_reason","没有有效页面快照，无法关联同一页面截图"))
            }
            if(now-unreadableSince>15000) attention("当前页面无法读取，请切到目标应用并检查无障碍权限后重试")
            return
        }
        unreadableSince=0L
        if(!task.observes(s.packageName,service.packageName,homePackage)) {
            if(task.fixed && flow.fingerprint!=null) attention("请先回到微信或桌面，再点重试")
            return
        }
        if(pendingLaunch!=null && verifyLaunch(s)) return
        if(task.fixed && s.packageName==homePackage && pendingLaunch==null) {
            if(!launchedFromHome) { launchedFromHome=true;cancelRequest("回到桌面，直接打开微信");openTargetApp() }
            return
        }
        launchedFromHome=false
        val structure=s.fingerprint
        if(structure!=lastStructure) epoch.trackingVisual=s.nodes.none { it.text.isNotBlank() || it.description.isNotBlank() }
        val currentKey=epoch.key(structure)
        if(currentKey!=firstSeenKey) { firstSeenKey=currentKey;firstSeenAt=now }
        // Remove stale guidance immediately, before the debounce window finishes.
        if(flow.fingerprint!=null && currentKey!=flow.fingerprint) { overlay.hide();speaker?.cancel();cancelRequest("页面状态变化，取消旧请求") }
        if(pendingExecution!=null && now-pendingExecution!!.optLong("started_ms")>5000) {
            logEvent("execution_verification",JSONObject(pendingExecution.toString()).put("result","no_observed_change").put("after_snapshot_id",s.id))
            pendingExecution=null;attention("操作已派发，但未观察到页面变化，请检查后重试");return
        }
        if(!stable.offer(currentKey,now)) return
        val first=timing.elapsed(now)==null
        if(!timing.observe(now)) return
        if(first) logEvent("measurement_start",JSONObject().put("snapshot_id",s.id).put("page",s.packageName))
        if(flow.observe(currentKey)) {
            pendingExecution?.let { action ->
                logEvent("execution_verification",JSONObject(action.toString()).put("result","page_changed_not_task_completion").put("after_snapshot_id",s.id))
                pendingExecution=null
            }
            logEvent("behavior_page_link",JSONObject().put("before_snapshot_id",lastSnapshot?.id ?: JSONObject.NULL).put("after_snapshot_id",s.id).put("association","页面变化不证明某一次点击"))
            lastStructure=structure;needImage=epoch.trackingVisual;lastProgress=now;stepStarted=now;cancelRequest("已进入新的观察步骤，取消旧请求");stable.reset()
            history.add(flow.step,"观察变化","当前应用 ${s.packageName}；可见文字：${s.nodes.map { it.text.ifBlank { it.description } }.filter { it.isNotBlank() }.take(12).joinToString(" / ")}；页面变化不代表上一建议已成功执行")
            logEvent("observation",JSONObject().put("step",flow.step).put("snapshot_id",s.id).put("page",s.packageName))
        }
        if(flow.phase in setOf(Phase.OBSERVING,Phase.VERIFYING)) dispatch(s)
    }
    private fun dispatch(s:Snapshot) {
        val sentEpoch=epoch.version
        val captureEventVersion=pageEventVersion
        val token=try { flow.request() } catch(e:Exception) { attention(e.message ?: "请求次数过多");return }
        val thisLog=log ?: return
        val requestId="${thisLog.id}_${token.step}_${token.attempt}"
        val base=JSONObject().put("step",token.step).put("attempt",token.attempt).put("request_id",requestId)
            .put("snapshot_id",s.id).put("ui_tree_ref","${requestId}_tree.json").put("screenshot_ref",JSONObject.NULL)
            .put("screen_width",s.width).put("screen_height",s.height).put("observation_at_ms",s.capturedAt).put("tree_capture_at_ms",s.capturedAt).put("consistency_method","tree_and_event_bracketing")
            .put("page_first_seen_at_ms",firstSeenAt).put("stable_wait_ms",SystemClock.elapsedRealtime()-firstSeenAt)
            .put("execution_mode",if(autoGui) "automatic_click_back" else "manual_guidance")
        currentBase=base;lastSnapshot=s
        val captureStart=SystemClock.elapsedRealtime()
        safeEvent(thisLog,"capture_started",JSONObject(base.toString()))
        show("正在读取页面和截图",speak=false)
        // Persist the tree even when screenshot permission/capture fails.
        EvidenceWork.submit(captureWorker) { runCatching { thisLog.artifact("${requestId}_tree.json",s.json().toString(2).toByteArray()) }
            .onFailure { e -> safeEvent(thisLog,"diagnostic",merge(base,Diagnostic.describe(e,"artifact")));handler.post { if(flow.accepts(token)) attention("页面快照保存失败：${e.message}") } } }
        screenshots.captureFrame(s,{ overlay.hide() },{ !closed && flow.accepts(token) }) done@{ result ->
            if(!flow.accepts(token)) return@done
            val frame=result.getOrNull()
            base.put("capture_latency_ms",SystemClock.elapsedRealtime()-captureStart)
                .put("screenshot_saved",false).put("screenshot_sent_to_model",false)
            if(frame!=null) base.put("image_width",frame.width).put("image_height",frame.height).put("image_rotation",frame.rotation)
                .put("screenshot_at_ms",frame.capturedAt).put("coordinate_transform",JSONObject().put("scale_x",frame.width.toDouble()/s.width)
                    .put("scale_y",frame.height.toDouble()/s.height).put("offset_x",0).put("offset_y",0).put("rotation_degrees",0))
            val error=result.exceptionOrNull()
            if(error!=null) {
                base.put("screenshot_missing_reason",error.message).put("screenshot_reason",error.message)
                safeEvent(thisLog,"capture_error",merge(base,Diagnostic.describe(error,"capture")))
                if((error as? RuleFailure)?.code=="SNAPSHOT_CHANGED") {
                    flow.responded(token,Phase.OBSERVING);stable.reset();return@done
                }
            }
            if(captureEventVersion!=pageEventVersion || sentEpoch!=epoch.version || !SnapshotConsistency.matches(s,reader.capture())) {
                safeEvent(thisLog,"discarded",JSONObject(base.toString()).put("stage","capture").put("error_code","SNAPSHOT_CHANGED")
                    .put("reason","采集期间页面变化，丢弃本次观察并重新采集"))
                flow.responded(token,Phase.OBSERVING);stable.reset();return@done
            }
            if(CompletionCheck.local(task,s,apps)) {
                frame?.let { base.put("screenshot_ref","$requestId.jpg") }
                // UI confirmation does not wait for JPEG disk writes or log queue draining.
                complete(base,"local_exact_open_goal",listOf("当前前台应用 ${s.packageName}"))
                EvidenceWork.submit(worker) {
                    runCatching {
                        frame?.let { thisLog.artifact("$requestId.jpg",it.bytes);base.put("screenshot_saved",true) }
                        safeEvent(thisLog,"snapshot_saved",JSONObject(base.toString()).put("collection",s.collection))
                    }.onFailure { e -> safeEvent(thisLog,"diagnostic",merge(base,Diagnostic.describe(e,"artifact"))) }
                }
                return@done
            }
            val p=try { DeepSeekProvider(endpoint,model,key,vision) } catch(e:Exception) {
                safeEvent(thisLog,"diagnostic",merge(base,Diagnostic.describe(e,"request_prepare")));attention(e.message ?: "模型配置无效");return@done
            };provider=p;providerContext=base
            val context=history.snapshot()
            EvidenceWork.submit(worker) {
                var requested:Long?=null
                val preparedAt=SystemClock.elapsedRealtime()
                val outcome=runCatching {
                    frame?.let {
                        val ref=try { thisLog.artifact("$requestId.jpg",it.bytes) } catch(e:Exception) {
                            throw RuleFailure("artifact","ARTIFACT_SAVE_ERROR","screenshot_write","截图成功写入",e.message.orEmpty(),"截图保存失败：${e.message}")
                        }
                        base.put("screenshot_ref",ref).put("screenshot_saved",true)
                    }
                    safeEvent(thisLog,"snapshot_saved",JSONObject(base.toString()).put("collection",s.collection))
                    val image=if(vision) frame?.bytes else null
                    base.put("screenshot_sent_to_model",false).put("screenshot_in_request_body",image!=null)
                    val body=p.body(ModelInput(thisLog.id,token.step,token.attempt,s,context,image,
                        workflow?.let { WorkflowGuide.context(it,s) },task,TargetAppLauncher.catalog(apps),JSONObject(base.toString())))
                    val logged=JSONObject(body.toString())
                    if(image!=null) logged.getJSONArray("messages").getJSONObject(1).getJSONArray("content").getJSONObject(1)
                        .getJSONObject("image_url").put("url","local:${base.getString("screenshot_ref")}")
                    safeEvent(thisLog,"request_prepared",JSONObject(base.toString()).put("input",logged).put("model",model))
                    p.onBodySent={ safeEvent(thisLog,"request_body_sent",JSONObject(base.toString()).put("screenshot_sent_to_model",image!=null)) }
                    p.execute(body) {
                        requested=SystemClock.elapsedRealtime()
                        safeEvent(thisLog,"request",JSONObject(base.toString()).put("input",logged).put("model",model)
                            .put("queue_latency_ms",requested!!-preparedAt))
                    }
                }
                handler.post {
                    if(provider===p) { provider=null;providerContext=null }
                    val event=JSONObject(base.toString()).put("screenshot_sent_to_model",vision && frame!=null && p.requestBodySent)
                    requested?.let { event.put("latency_ms",SystemClock.elapsedRealtime()-it) }
                    outcome.fold({ r -> event.put("output_raw",r.raw).put("content",r.content) },{ e ->
                        val details=if(requested==null && p.cancellationReason==null) Diagnostic.describe(e,"request_prepare") else RequestFailure.describe(e,p.cancellationReason)
                        details.keys().forEach { name -> event.put(name,details.get(name)) }
                        if(e is ModelFailure) event.put("output_raw",e.raw)
                    })
                    safeEvent(thisLog,if(requested!=null) "response" else if(p.cancellationReason!=null) "request_cancelled" else "request_prepare_error",event)
                    if(!flow.accepts(token) || p.cancellationReason!=null) {
                        safeEvent(thisLog,"discarded",JSONObject(base.toString()).put("stage","response").put("error_code","STALE_OR_CANCELLED")
                            .put("reason",p.cancellationReason ?: "会话或步骤已失效"));return@post
                    }
                    if(sentEpoch!=epoch.version || !SnapshotConsistency.matches(s,reader.capture())) {
                        flow.responded(token,Phase.OBSERVING);overlay.hide();stable.reset()
                        safeEvent(thisLog,"discarded",JSONObject(base.toString()).put("stage","response").put("error_code","SNAPSHOT_CHANGED")
                            .put("reason","响应到达时页面已变化，重新观察"));return@post
                    }
                    outcome.fold({ r -> applyDecision(token,s,r.content,vision && frame!=null,base) },{ e ->
                        AppRuntime.modelStatus="模型调用失败：${event.optString("error")}"
                        attention(event.optString("error",e.message ?: "模型调用失败"))
                    })
                }
            }
        }
    }
    private fun merge(base:JSONObject,details:JSONObject):JSONObject = JSONObject(base.toString()).also { out -> details.keys().forEach { out.put(it,details.get(it)) } }
    private fun complete(base:JSONObject,method:String,evidence:List<String>) {
        val now=SystemClock.elapsedRealtime()
        logEvent("completed_evidence",JSONObject(base.toString()).put("method",method).put("evidence",org.json.JSONArray(evidence))
            .put("evidence_observed_at_ms",base.optLong("observation_at_ms",now)).put("confirmed_at_ms",now))
        stop(Phase.COMPLETED)
        logEvent("success_displayed",JSONObject(base.toString()).put("shown_at_ms",SystemClock.elapsedRealtime())
            .put("confirmation_to_display_ms",SystemClock.elapsedRealtime()-now).put("evidence_to_confirmation_ms",now-base.optLong("page_first_seen_at_ms",base.optLong("observation_at_ms",now))))
    }
    private fun applyDecision(token:RequestToken,s:Snapshot,raw:String,hasImage:Boolean,base:JSONObject) {
        var stage="parse"
        try {
            val d=DecisionCodec.parse(raw)
            stage="target"
            history.add(token.step,"模型判断","${d.state}；建议 ${d.action}：${d.instruction}")
            logEvent("decision",JSONObject(base.toString()).put("output_parsed",JSONObject(raw)))
            if(d.action==Action.NEED_SCREENSHOT) {
                if(hasImage) { attention("有截图仍无法确定下一步，请重试");return }
                history.add(token.step,"需要截图","文本信息不足，下一次提供截图");needImage=true;flow.responded(token,Phase.OBSERVING);return
            }
            val originalTarget=DecisionCodec.target(d,s)
            val target=WorkflowGuide.target(d,s,originalTarget,WorkflowGuide.usesFixedContactSearchRule(workflow))
            if(ReturnGuidance.needsLocation(d,target,hasImage,vision,flow.attempt)) {
                history.add(token.step,"补充返回位置",ReturnGuidance.REQUEST)
                logEvent("return_location_requested",JSONObject(base.toString()).put("reason",ReturnGuidance.REQUEST).put("with_screenshot",vision))
                needImage=vision;flow.responded(token,Phase.OBSERVING);return
            }
            val instruction=d.instruction
            if(CompletionCheck.modelConfirms(d)) {
                stage="completion"
                Diagnostic.requireRule(CompletionCheck.evidence(d,s,hasImage),"completion","COMPLETION_EVIDENCE_MISSING","current_page_evidence",
                    "当前页面可核对的完成证据",d.evidence,"完成确认失败：模型未提供当前页面可核对的证据")
                complete(base,"model_current_page",d.evidence);return
            }
            if(d.action==Action.OPEN_APP) {
                flow.responded(token,Phase.WAITING_USER);openApplication(d.targetApp!!,base);return
            }
            if(GuiExecutor.allowed(autoGui,d.action)) {
                flow.responded(token,Phase.WAITING_USER)
                executeGui(d.action,target,s,base);return
            }
            if(d.action==Action.BLOCKED) { history.add(token.step,"模型无法继续",d.instruction);attention(d.instruction);return }
            flow.responded(token,Phase.WAITING_USER);lastProgress=SystemClock.elapsedRealtime()
            history.add(token.step,"已展示，等待用户操作","${d.state}：${instruction}；证据：${d.evidence.joinToString()}")
            show("人工操作：$instruction",ReturnGuidance.highlight(d.action,target))
            logEvent("guidance",JSONObject(base.toString()).put("instruction",instruction).put("action",d.action.name).put("target",target?.json() ?: JSONObject.NULL)
                .put("execution_mode","manual_guidance").put("executed",false).put("fallback_reason",if(autoGui) "该动作不支持自动执行" else "人工引导模式")
                .put("guidance_latency_ms",SystemClock.elapsedRealtime()-stepStarted))
        } catch(e:Exception) {
            history.add(token.step,"建议未展示", "${e.message ?: "解析失败"}；返回摘要：${raw.take(250)}")
            val detail=Diagnostic.describe(e,stage)
            if(diagnosticNodeMissing(e)) detail.put("error_code","NODE_NOT_IN_SNAPSHOT").put("rule","node_belongs_to_snapshot")
            logEvent("invalid_decision",merge(base,detail).put("model_content",raw))
            if(!hasImage && vision && flow.attempt<3) { needImage=true;flow.responded(token,Phase.OBSERVING) }
            else attention((e.message ?: "建议校验失败，请查看本步诊断后重试").take(95))
        }
    }
    private fun diagnosticNodeMissing(e:Exception)=e.message?.startsWith("目标节点不属于当前页面")==true
    fun retry() {
        if(!flow.active) return
        if(timing.waiting) {
            timing.release();stable.reset();logEvent("preparation_finished");show("正在读取测试起点",speak=false);return
        }
        pendingLaunch=null;pendingExecution=null
        cancelRequest("用户点击重试，取消上一请求");history.add(flow.step,"用户重试","上一轮没有完成，需根据当前页面调整方案");flow.retry();stable.reset();needImage=false;lastProgress=SystemClock.elapsedRealtime();stepStarted=lastProgress
        logEvent("manual_retry");show("正在重新看页面",speak=false)
    }
    fun pageEvent(packageName:String) {
        if(flow.active && task.observes(packageName,service.packageName,homePackage)) { pageEventVersion++;epoch.pageEvent() }
    }
    private fun attention(reason:String) { if(!flow.active) return;cancelRequest("任务暂停：$reason");flow.pause();logEvent("attention",JSONObject().put("error",reason).put("stage","attention").put("error_code","TASK_PAUSED").put("rule","pause_with_reason").put("expected","可继续的当前页面和操作").put("actual",reason));show(reason,retry=true) }
    private fun show(text:String,target:Box?=null,retry:Boolean=false,speak:Boolean=true) {
        if(!flow.active) return
        AppRuntime.status=text
        overlay.show(text,target,retry)
        if(speak) speaker?.speak(text)
    }
    private fun logEvent(type:String,data:JSONObject=JSONObject()) {
        val base=JSONObject(currentBase?.toString() ?: "{}").put("step",flow.step).put("attempt",flow.attempt)
        log?.let { safeEvent(it,type,merge(base,data)) }
    }
    fun awaitLogs() { logWorker.submit {}.get() }
    private fun safeEvent(destination:RunLog,type:String,data:JSONObject):Boolean {
        val copy=JSONObject(data.toString()).put("occurred_at",System.currentTimeMillis()).put("logged_monotonic_ms",SystemClock.elapsedRealtime())
        if(logWorker.isShutdown) return false
        EvidenceWork.submit(logWorker) {
            runCatching { destination.event(type,copy) }.onFailure {
                handler.post {
                    if(destination===log && flow.active) {
                        flow.finish(Phase.FAILED);provider?.cancel("日志保存失败");overlay.hide();speaker?.close();speaker=null
                        handler.removeCallbacks(ticker);AppRuntime.active=false;AppRuntime.status="日志保存失败，请导出并清理空间"
                    }
                }
            }
        }
        return true
    }
    fun stop(result:Phase=Phase.STOPPED,reason:String?=null) {
        successNotice.cancel()
        pendingLaunch=null;pendingExecution=null
        val wasActive=flow.active
        val reviewKey=key
        if(result!=Phase.COMPLETED) cancelReview(reason ?: "用户停止或服务关闭")
        if(wasActive) flow.finish(result) // Invalidate callbacks before cleanup or logging.
        cancelRequest(reason ?: if(result==Phase.STOPPED) "用户停止任务" else "任务结束：${result.name}");provider=null;handler.removeCallbacks(ticker);overlay.hide();speaker?.close();speaker=null;key=""
        if(wasActive && result==Phase.COMPLETED) successNotice.show()
        if(wasActive) logEvent("end",JSONObject().put("result",result.name).put("reason",reason ?: if(result==Phase.STOPPED) "用户停止任务" else "任务结束").put("duration_ms",timing.elapsed(SystemClock.elapsedRealtime()) ?: JSONObject.NULL))
        AppRuntime.active=false;AppRuntime.status=if(result==Phase.COMPLETED) task.completionMessage else "引导已停止，可重新开始"
        if(wasActive && result==Phase.COMPLETED) {
            if(task.fixed) log?.let { startReview(it,reviewKey,workflow) }
            else logEvent("workflow_review_not_applicable",JSONObject().put("reason",if(workflow==null) "当前自定义任务没有匹配到内置流程" else "内置高频任务流程暂不自动复盘或覆盖"))
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
        EvidenceWork.submit(worker) {
            var started:Long?=null
            val result=runCatching {
                awaitLogs()
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
            val data=JSONObject(providerContext?.toString() ?: "{}").put("reason",reason).put("stage","cancellation").put("error_code","APP_CANCELLED").put("rule","request_current").put("expected","有效会话与页面").put("actual",reason)
            log?.let { destination -> runCatching { safeEvent(destination,"request_cancelled_by_app",data) } }
        }
    }
    private fun openTargetApp() { launchedFromHome=true;openApplication("com.tencent.mm",JSONObject(currentBase?.toString() ?: "{}")) }
    private fun openApplication(packageName:String,base:JSONObject) {
        if(task.fixed && packageName!="com.tencent.mm") { attention("该应用不属于当前任务的允许范围");return }
        val app=apps.find { it.packageName==packageName }
        if(app==null) { logEvent("diagnostic",merge(base,Diagnostic.describe(IllegalStateException("未发现已安装的可启动应用：$packageName"),"execution")));attention("找不到可启动的应用，请确认已经安装");return }
        pendingLaunch=app;launchFallback=false;launchGuiAttempted=false;launchDeadline=SystemClock.elapsedRealtime()+4000
        automaticUntil=launchDeadline
        val result=TargetAppLauncher.open(service,packageName)
        logEvent("execution_requested",JSONObject(base.toString()).put("method","system_intent").put("package",packageName).put("result",if(result.isSuccess) "dispatched_not_verified" else "rejected")
            .put("fallback_reason",result.exceptionOrNull()?.message ?: JSONObject.NULL))
        if(result.isFailure) launchDeadline=SystemClock.elapsedRealtime()
        show("正在打开${app.label}，等待页面验证",speak=false)
    }
    private fun verifyLaunch(s:Snapshot):Boolean {
        val app=pendingLaunch ?: return false
        if(TargetAppLauncher.verifies(app.packageName,s.packageName)) {
            logEvent("execution_verification",JSONObject().put("method",if(launchFallback) "gui" else "system_intent").put("package",app.packageName)
                .put("result","foreground_verified_not_whole_task_completion").put("after_snapshot_id",s.id))
            pendingLaunch=null;pendingExecution=null;flow.retry();stable.reset();lastProgress=SystemClock.elapsedRealtime();return false
        }
        if(SystemClock.elapsedRealtime()<launchDeadline) return true
        if(!launchFallback && autoGui) {
            launchFallback=true
            if(s.packageName!=homePackage) {
                val ok=service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
                logEvent("execution_requested",JSONObject().put("method","system_home_for_gui").put("result",ok).put("fallback_reason","启动入口未通过前台验证"))
                launchDeadline=SystemClock.elapsedRealtime()+1500;return true
            }
        }
        if(autoGui && launchFallback && !launchGuiAttempted && s.packageName==homePackage) {
            val icons=s.nodes.filter { it.text==app.label || it.description==app.label }
            if(TargetAppLauncher.guiFallback(false,autoGui,launchGuiAttempted,s.packageName==homePackage,icons.size)) {
                val icon=icons.single()
                launchGuiAttempted=true
                pendingLaunch=null
                captureLaunchGui(app,icon.box,s)
                pendingLaunch=app;launchDeadline=SystemClock.elapsedRealtime()+4000
                // Prevent a second GUI retry if the first delivered tap has no effect.
                launchFallback=true;return true
            }
        }
        pendingLaunch=null
        logEvent("execution_verification",JSONObject().put("method","manual_fallback").put("package",app.packageName).put("result","not_verified")
            .put("error_code","APP_LAUNCH_NOT_VERIFIED").put("reason",if(autoGui) "未发现唯一可见图标或 GUI 后未切换应用" else "系统启动未验证，人工模式不自动点击"))
        attention("未能确认${app.label}已打开，请手动打开后重试");return true
    }
    private fun captureLaunchGui(app:InstalledApp,target:Box,s:Snapshot) {
        val destination=log ?: return
        val id="${destination.id}_launch_${flow.step}"
        val base=JSONObject().put("step",flow.step).put("attempt",0).put("purpose","app_launch_gui").put("request_id",id)
            .put("snapshot_id",s.id).put("ui_tree_ref","${id}_tree.json").put("launch_package",app.packageName)
            .put("suggestion_request_id",currentBase?.optString("request_id") ?: JSONObject.NULL)
        val version=flow.version
        screenshots.captureFrame(s,{ overlay.hide() },{ flow.active && destination===log && version==flow.version }) capture@{ result ->
            val frame=result.getOrNull()
            if(frame==null) {
                safeEvent(destination,"capture_error",merge(base,Diagnostic.describe(result.exceptionOrNull()!!,"capture")))
                pendingLaunch=null;attention("打开应用前截图失败，请手动打开后重试");return@capture
            }
            if(!SnapshotConsistency.matches(s,reader.capture())) { pendingLaunch=null;attention("打开应用前页面变化，请重试");return@capture }
            base.put("screenshot_ref","$id.jpg").put("screenshot_sent_to_model",false)
            EvidenceWork.submit(captureWorker) { runCatching {
                destination.artifact("${id}_tree.json",s.json().toString(2).toByteArray());destination.artifact("$id.jpg",frame.bytes)
                safeEvent(destination,"snapshot_saved",JSONObject(base.toString()).put("screenshot_saved",true))
            }.onFailure { e -> safeEvent(destination,"diagnostic",merge(base,Diagnostic.describe(e,"artifact"))) } }
            currentBase=base;lastSnapshot=s
            executeGui(Action.CLICK,target,s,base)
        }
    }
    private fun executeGui(action:Action,target:Box?,s:Snapshot,base:JSONObject) {
        if(!SnapshotConsistency.matches(s,reader.capture())) { logEvent("discarded",JSONObject(base.toString()).put("reason","执行前页面变化").put("error_code","EXECUTION_SNAPSHOT_CHANGED"));flow.retry();stable.reset();return }
        val destination=log
        val generation=flow.generation
        val version=flow.version
        if(target!=null) DecisionCodec.target(Decision("execute",Action.CLICK,null,target,"执行目标校验",1.0,false,emptyList()),s)
        val execution=JSONObject(base.toString()).put("method","gui_gesture").put("action",action.name).put("target",target?.json() ?: JSONObject.NULL)
            .put("before_snapshot_id",s.id).put("started_ms",SystemClock.elapsedRealtime())
        pendingExecution=execution;automaticUntil=SystemClock.elapsedRealtime()+1200
        logEvent("execution_requested",execution)
        show("自动操作中，正在验证页面",target,speak=false)
        overlay.hide()
        gui.execute(action,target,s) callback@{ result ->
            if(destination!==log || !flow.active || generation!=flow.generation || version!=flow.version) {
                destination?.let { safeEvent(it,"execution_result",JSONObject(execution.toString()).put("result",if(result.isSuccess) "dispatched_callback_stale" else "failed_callback_stale")) }
                return@callback
            }
            logEvent("execution_result",JSONObject(execution.toString()).put("result",if(result.isSuccess) "dispatched_not_verified" else "failed")
                .put("executed",result.isSuccess).put("error",result.exceptionOrNull()?.message ?: JSONObject.NULL))
            if(result.isFailure) {
                pendingExecution=null;logEvent("diagnostic",merge(execution,Diagnostic.describe(result.exceptionOrNull()!!,"execution")))
                attention("自动操作失败：${result.exceptionOrNull()?.message}，请手动操作后重试")
            }
        }
    }
    fun backKey(eventTime:Long) {
        if(!flow.active) return
        logEvent("behavior_observed",JSONObject().put("event_type","reported_back_key").put("system_event_time_ms",eventTime)
            .put("actor","user_or_external_input").put("actor_certain",false).put("before_snapshot_id",lastSnapshot?.id ?: JSONObject.NULL)
            .put("package",lastSnapshot?.packageName ?: JSONObject.NULL).put("evidence_source","android_key_event")
            .put("limitation","仅记录系统实际投递的返回键；全面屏返回手势没有可靠的返回键事件，不能由页面变化推断"))
    }
    fun accessibilityEvent(event:android.view.accessibility.AccessibilityEvent) {
        val pkg=event.packageName?.toString() ?: return
        if(event.eventType==android.view.accessibility.AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED) epoch.trackingVisual=true
        pageEvent(pkg)
        if(!flow.active || !task.observes(pkg,service.packageName,homePackage)) return
        behavior.record(event,lastSnapshot,SystemClock.elapsedRealtime()<automaticUntil)?.let { data ->
            if(data.optString("event_type") !in setOf("content_change","window_change"))
                history.add(flow.step,"系统报告操作","${data.optString("event_type")}，来源 ${data.optString("actor")}；不证明建议已完成")
            logEvent("behavior_observed",data.put("suggestion_request_id",currentBase?.optString("request_id") ?: JSONObject.NULL))
        }
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
    fun close() { stop(Phase.FAILED,"无障碍服务已断开或控制器关闭");closed=true;handler.removeCallbacksAndMessages(null);worker.shutdown();captureWorker.shutdown();logWorker.shutdown();overlay.hide() }
}
