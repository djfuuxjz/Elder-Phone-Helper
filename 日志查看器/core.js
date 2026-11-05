(function(root){
'use strict';
function parseLog(text,source){
 const sessions=new Map(),warnings=[];
 text.replace(/^\uFEFF/,'').split(/\r?\n/).forEach((line,i)=>{
  if(!line.trim())return;
  try{
   const row=JSON.parse(line);
   if(!row||typeof row!=='object'||typeof row.event!=='string'||!row.data||typeof row.data!=='object'||Array.isArray(row.data))throw Error('缺少 event 或 data 对象');
   const sid=String(row.session_id||'未标识会话');
   if(!sessions.has(sid))sessions.set(sid,{id:sid,source,events:[],requests:[],warnings:[]});
   sessions.get(sid).events.push({...row,line:i+1});
  }catch(e){warnings.push(`${source} 第 ${i+1} 行：${e.message}`)}
 });
 for(const s of sessions.values()){
  const groups=new Map(),byStep=new Map();
  // Build the request map first, so incomplete event records can still be joined.
  for(const e of s.events){const d=e.data;if(d.request_id){const k=String(d.request_id);if(!groups.has(k))groups.set(k,{id:k,step:d.step,attempt:d.attempt,events:[]});const sk=`${d.step}:${d.attempt}`;if(!byStep.has(sk))byStep.set(sk,new Set());byStep.get(sk).add(k)}}
  for(const e of s.events){const d=e.data;let k=d.request_id?String(d.request_id):null;
   if(!k&&d.step!=null&&d.attempt!=null){const ids=byStep.get(`${d.step}:${d.attempt}`);if(ids?.size===1)k=[...ids][0];else if(!ids?.size)k=`未分配请求ID · ${d.step}:${d.attempt}`;else s.warnings.push(`第 ${e.line} 行有多个同 step/attempt 的请求，未猜测归属`)}
   if(k){if(!groups.has(k))groups.set(k,{id:k,step:d.step,attempt:d.attempt,events:[]});groups.get(k).step??=d.step;groups.get(k).attempt??=d.attempt;groups.get(k).events.push(e)}
  }
  s.requests=[...groups.values()].sort((a,b)=>a.events[0].line-b.events[0].line);
  for(const g of s.requests){
   g.purpose=g.events.find(e=>e.data.purpose)?.data.purpose||'guidance';const types=g.events.map(e=>e.event);g.error=g.events.find(e=>e.event==='invalid_decision'||e.event==='capture_error'||e.event==='workflow_review_error'||e.event==='diagnostic'||e.event==='request_prepare_error'||(e.event==='execution_result'&&e.data.result==='failed')||(e.event==='response'&&e.data.error&&e.data.error_code!=='APP_CANCELLED'));
   g.status=types.includes('workflow_review_cancelled')?'App 已取消':types.includes('workflow_candidate_saved')?(g.events.find(e=>e.event==='workflow_candidate_saved').data.status||'流程候选已保存'):types.includes('discarded')?'已丢弃':g.error?'被拒绝 / 出错':types.includes('guidance')?'已展示':types.includes('completed_evidence')?'完成候选已确认':types.includes('request_cancelled')?'发送前取消':types.includes('request_cancelled_by_app')?'App 已取消':types.includes('response')?'已返回':'未返回 / 未发送';
   if(g.error?.data.stage)g.status=({response_parse:'解析错误',parse:'解析错误',target:'目标校验拒绝',capture:'截图失败',execution:'执行失败',completion:'完成验证拒绝'})[g.error.data.stage]||g.status;
   if(!g.error&&types.includes('execution_result'))g.status=g.events.some(e=>e.event==='execution_verification'&&['foreground_verified_not_whole_task_completion','page_changed_not_task_completion'].includes(e.data.result))?'页面变化已验证':'已派发，待验证';
   g.refs={};for(const e of g.events)for(const k of ['ui_tree_ref','screenshot_ref'])if(e.data[k])g.refs[k]=String(e.data[k]);
   g.instruction=[...g.events].reverse().find(e=>e.data.instruction)?.data.instruction||'';
   g.search=JSON.stringify(g).toLowerCase();
  }
 }
 return {sessions:[...sessions.values()],warnings};
}
function modelDecision(events){
 for(const e of [...events].reverse()){
  if(e.data.output_parsed&&typeof e.data.output_parsed==='object')return e.data.output_parsed;
  if(e.event==='response')try{const v=typeof e.data.content==='string'?JSON.parse(e.data.content):e.data.content;if(v&&typeof v==='object'&&typeof v.next_action==='string')return v}catch{}
 }
 return null;
}
function actionLabel(request){
 if(request.purpose==='workflow_review')return '成功后复盘';
 const events=request.events||[],decision=modelDecision(events);
 const guide=[...events].reverse().find(e=>e.event==='guidance'&&e.data.instruction?.trim());
 if(guide)return guide.data.instruction.trim();
 if(typeof decision?.instruction==='string'&&decision.instruction.trim())return decision.instruction.trim();
 const launch=events.find(e=>e.event==='execution_requested'&&e.data.package);
 if(launch)return '打开应用 '+launch.data.package;
 const execution=events.find(e=>e.event==='execution_requested'&&e.data.action);
 if(execution)return ({CLICK:'点击目标',BACK:'返回上一页',SCROLL:'滚动页面',INPUT:'输入内容'})[execution.data.action]||'执行操作';
 if(events.some(e=>e.event==='completed_evidence'))return '确认任务完成';
 if(events.some(e=>e.event==='capture_error'))return '采集页面证据';
 return '观察页面';
}
function actionSource(request){
 const events=request.events||[];
 if(events.some(e=>e.event==='execution_requested'))return '自动操作记录，是否生效见验证结果';
 if(events.some(e=>e.event==='guidance'))return '已展示人工操作指引';
 if(modelDecision(events))return '模型建议，未记录执行';
 return '页面观察记录';
}
function stepText(session,request,tree){
 const events=request?request.events:session.events;
 const data={session_id:session.id,goal:session.events.find(e=>e.goal)?.goal??null,source:session.source,
  step:request?.step??null,attempt:request?.attempt??null,request_id:request?.id??null,
  action:request?actionLabel(request):'整次运行',action_source:request?actionSource(request):null,status:request?.status??null,
  attachment_refs:request?.refs??{},ui_tree:tree??null,events};
 return '手机助手日志 · '+(request?'第 '+(request.step??'?')+' 步 · '+actionLabel(request):'整次运行')+'\n'+
  '说明：模型建议、展示指引、自动派发、系统报告事件和验证结果应分别判断。截图为附件引用；分析图片时请另附对应截图。\n\n'+JSON.stringify(data,null,2);
}
function summary(s){const requests=s.events.filter(e=>e.event==='request'),responses=s.events.filter(e=>e.event==='response');const times=responses.map(e=>e.data.latency_ms).filter(Number.isFinite).sort((a,b)=>a-b);const n=times.length;const end=[...s.events].reverse().find(e=>e.event==='end');return {calls:requests.length,guidance:s.events.filter(e=>e.event==='guidance').length,invalid:s.events.filter(e=>e.event==='invalid_decision').length,discarded:s.events.filter(e=>e.event==='discarded').length,result:end?.data.result||'未记录结束',duration:end?.data.duration_ms??null,median:n?(n%2?times[(n-1)/2]:(times[n/2-1]+times[n/2])/2):null,p95:n?times[Math.ceil(n*.95)-1]:null,samples:n}}
function resolveRef(files,source,ref){
 if(!ref)return {error:'本次没有该附件引用'};
 ref=String(ref).replace(/^local:/,'').replace(/\\/g,'/');
 if(ref.startsWith('/')||ref.split('/').some(x=>x==='..'||x==='.')||ref.includes(':'))return {error:'附件引用不是有效的本地相对路径'};
 const dir=source.includes('/')?source.slice(0,source.lastIndexOf('/')+1):'';
 const matches=files.filter(f=>f.path===dir+ref);
 return matches.length===1?{file:matches[0]}:{error:matches.length?'存在多个同名附件，无法确定对应文件':`缺少附件：${ref}。请重新选择包含日志和附件的完整文件夹。`};
}
const api={parseLog,summary,resolveRef,modelDecision,actionLabel,actionSource,stepText};if(typeof module!=='undefined')module.exports=api;root.LogCore=api;
})(typeof globalThis!=='undefined'?globalThis:this);
