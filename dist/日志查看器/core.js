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
   g.purpose=g.events.find(e=>e.data.purpose)?.data.purpose||'guidance';const types=g.events.map(e=>e.event);g.error=g.events.find(e=>e.event==='invalid_decision'||e.event==='capture_error'||e.event==='workflow_review_error'||(e.event==='response'&&e.data.error&&e.data.error_code!=='APP_CANCELLED'));
   g.status=types.includes('workflow_review_cancelled')?'App 已取消':types.includes('workflow_candidate_saved')?'流程候选已保存':types.includes('discarded')?'已丢弃':g.error?'被拒绝 / 出错':types.includes('guidance')?'已展示':types.includes('completed_evidence')?'完成候选已确认':types.includes('request_cancelled')?'发送前取消':types.includes('request_cancelled_by_app')?'App 已取消':types.includes('response')?'已返回':'未返回 / 未发送';
   g.refs={};for(const e of g.events)for(const k of ['ui_tree_ref','screenshot_ref'])if(e.data[k])g.refs[k]=String(e.data[k]);
   g.instruction=[...g.events].reverse().find(e=>e.data.instruction)?.data.instruction||'';
   g.search=JSON.stringify(g).toLowerCase();
  }
 }
 return {sessions:[...sessions.values()],warnings};
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
const api={parseLog,summary,resolveRef};if(typeof module!=='undefined')module.exports=api;root.LogCore=api;
})(typeof globalThis!=='undefined'?globalThis:this);
