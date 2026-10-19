# 数据契约

固定目标只有 TaskSpec.GOAL 一处。会话 id 形式 run_时间_UUID，不与历史用户账号关联。

Snapshot 包含 id、package、window、屏幕像素宽高、可见节点、采集时刻、截断标记；节点 id 仅本快照有效。bounds 为全屏像素 [left,top,right,bottom]。模型使用原尺寸截图，避免隐藏缩放差异。快照指纹不包含时间与 id，包含会影响目标的节点文字和坐标。

Decision 必须返回 current_state、next_action、target_node、target_bbox、instruction、confidence、task_completed、evidence。目标字段可以为空，click 必须有有效目标。evidence 是完成判断引用的当前页面可见文字数组。图片场景的完成需要二次观察确认，不能仅凭一次 completion 标记。

RunLog 每个事件都有 timestamp、session_id、task_key、goal。data 记录 step、attempt、request_id、snapshot_id、input/output/ref/latency/error 等。请求体中的图片使用本机引用替代大段 base64，图片本体保留，与实际发送字节一致。

step 为新有效页面决策；同页面补图或重试递增 attempt。所有实际发出请求（含失败、过期和重试）计入调用次数。请求时长与引导等待使用单调时钟。


`request_prepared` 表示已保存请求材料，不计为调用；只有网络执行开始的 `request` 计数。执行前取消记 `request_cancelled`，执行后失败仍计一次请求。

`measurement_start` 标识第一份稳定有效观察；`end.duration_ms` 从此时计算，包含后续等待和人工重试，不包含准备起点的时间。准备阶段停止时 duration_ms 为 null。Debug 可选择下一次任务等待人工准备起点，点击“从当前页开始”后才开始观察。


V0.1.2：prompt_version=2；step_context 为最多 40 条本任务摘要（每条最多 400 字符），包含未展示/失败/取消/重试。evidence 接受字符串数组或字符串，字符串作为一条证据保留。confidence 仍验证数值格式和范围，但不作为接受门槛。request_cancelled_by_app 记录 reason 与 request_id；response 可携带 error_code、exception_type、exception_message、cancel_reason。所有请求使用共享的至少 1000 ms 发送间隔；准备及节流等待不算网络 latency_ms。


V0.1.3：prompt_version=3；UI节点增加editable/focused/hint；请求workflow携带版本与current_phase。INPUT也展示真实目标框。grounding_corrected记录定位纠正前后坐标。成功可由稳定呼叫控件确认，completed_evidence携带success_tree.json引用；end之后可能有purpose=workflow_review的复盘request/response，request_id为运行ID加_review，step=0、attempt=1。workflow_candidate_saved引用version_id；workflow_review_error保留验证/网络错误。calls包括所有模型请求，guidance_calls/review_calls分别统计。新目录ID及ZIP名称包含本地日期时间，事件包含time_zone，旧ID仍支持。

V0.1.4：prompt_version=4；完成仅检查complete/true契约，completed_evidence.method=model_decision，不再本地词语确认。workflow_candidate_saved.status表示已自动启用或保留当前选择；模型复盘新版本默认原子保存并启用，锁定/停用/中途切换时只保存。无文字目标不再业务拦截，仍保留有效坐标/节点检查。

V0.1.5：prompt_version=5；BACK支持target高亮，目标有效性/面积检查与CLICK一致，允许无目标系统返回。return_location_requested记录补充定位原因及with_screenshot，成功补充后按同一步的新attempt记录请求。
