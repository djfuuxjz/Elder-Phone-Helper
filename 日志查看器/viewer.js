'use strict';
const $=id=>document.getElementById(id),core=LogCore;
let files=[],sessions=[],current=null,selected=null,tab='overview',tree=null,url=null,target=null,targetLabel='',generation=0,activeNode=null,imageTransform={};
const names={request_prepare_error:'请求准备失败（未发送，也非主动取消）',request_body_sent:'请求正文已上传（不代表服务已处理）',capture_started:'开始采集页面证据',snapshot_saved:'页面证据已保存',diagnostic:'详细诊断',execution_requested:'选择执行方式',execution_result:'操作派发结果（不是任务完成）',execution_verification:'执行后页面验证',behavior_observed:'系统报告操作（来源见详情）',behavior_page_link:'关联前后页面',success_displayed:'成功提示已展示',workflow_candidate_saved:'流程复盘已保存',workflow_review_error:'流程复盘失败',workflow_review_cancelled:'流程复盘取消',grounding_corrected:'定位已纠正',request_cancelled_by_app:'App 主动取消请求',app_launch:'请求系统打开微信',app_launch_error:'打开微信失败',request_prepared:'准备请求（不计调用）',request:'发送模型请求',response:'模型返回',decision:'解析模型建议',guidance:'实际展示指引',invalid_decision:'拒绝模型建议',discarded:'丢弃过期结果',attention:'暂停，等待处理',manual_retry:'手动重试',observation:'观察页面',start:'启动任务',end:'运行结束',measurement_start:'开始有效计时',capture_error:'截图失败',request_cancelled:'发送前取消',completed_evidence:'确认完成证据'};
function el(tag,text,cls){const n=document.createElement(tag);if(text!==undefined)n.textContent=String(text);if(cls)n.className=cls;return n}
function json(v){return typeof v==='string'?v:JSON.stringify(v,null,2)}
function parsed(v){if(typeof v!=='string')return v;try{return JSON.parse(v)}catch{return v}}
function pre(v){return el('pre',json(v))}
function stamp(t){return Number.isFinite(t)?new Date(t).toLocaleTimeString('zh-CN',{timeZone:'Asia/Shanghai',hour12:false}):'时间未记录'}
function boxClear(){activeNode=null;imageTransform={};$('nodelayer').replaceChildren();$('nodelist').replaceChildren();$('selectedbox').hidden=true;$('nodeinfo').textContent='';$('nodestatus').textContent='';url&&URL.revokeObjectURL(url);url=null;tree=null;target=null;targetLabel='';$('shot').hidden=true;$('shot').removeAttribute('src');$('box').hidden=true;$('original').hidden=true;$('original').removeAttribute('href');$('imageinfo').textContent='';$('imagehint').textContent=''}
function reset(){document.body.classList.remove('viewer-open');document.querySelector('.imagepanel').classList.remove('is-expanded');$('expandShot').textContent='放大查看';$('expandShot').setAttribute('aria-expanded','false');generation++;boxClear();files=[];sessions=[];current=null;selected=null;$('workspace').hidden=true;$('empty').hidden=false;$('notice').textContent='';$('warnings').hidden=true;$('search').value='';$('filter').value='';$('folder').value='';$('files').value='';$('requests').replaceChildren();$('detail').replaceChildren()}
async function ingest(list){const chosen=Array.from(list);reset();const gen=generation;files=chosen.map(f=>({file:f,path:f.webkitRelativePath||f.name}));$('notice').textContent='正在本机读取日志…';const logs=files.filter(f=>/\.jsonl$/i.test(f.path)),warnings=[];const loaded=[];
 for(const f of logs){try{if(f.file.size>30*1024*1024)throw Error('日志超过 30 MB，请拆分后查看');const out=core.parseLog(await f.file.text(),f.path);loaded.push(...out.sessions);warnings.push(...out.warnings,...out.sessions.flatMap(s=>s.warnings))}catch(e){warnings.push(`${f.path}：${e.message}`)}}
 if(gen!==generation)return;sessions=loaded;
 $('notice').textContent=sessions.length?`已导入 ${sessions.length} 次运行 · ${files.length} 个文件。内容仅保存在当前页面内存中。`:'没有可读取的运行日志，请选择含 .jsonl 的文件夹。';
 if(warnings.length){$('warnings').hidden=false;$('warnings').querySelector('summary').textContent=`导入提醒：${warnings.length} 项（错误行没有被静默忽略）`;$('warnings').querySelector('pre').textContent=warnings.join('\n')}
 if(!sessions.length)return;$('session').replaceChildren(...sessions.map((s,i)=>{const o=el('option',s.id+' · '+s.source);o.value=i;return o}));$('workspace').hidden=false;$('empty').hidden=true;document.body.classList.add('viewer-open');chooseSession(0)
}
function chooseSession(i){current=sessions[i];$('search').value='';$('filter').value='';const m=core.summary(current),start=current.events.find(e=>e.event==='start')?.data||{};$('runmeta').textContent=`${start.model||'模型未记录'} · Android ${start.android||'未记录'} · ${start.device||'设备未记录'}`;
 $('metrics').replaceChildren(...[[m.result,'日志结束状态'],[m.calls,'实际模型调用'],[m.guidance,'实际展示'],[m.invalid,'拒绝建议'],[m.median==null?'—':(m.median/1000).toFixed(2)+' s','请求耗时中位数（含错误）'],[m.duration==null?'—':(m.duration/1000).toFixed(1)+' s','任务计时']].map(([v,k])=>{const n=el('div',undefined,'metric');n.append(el('strong',v),el('span',k));return n}));selected=null;renderList();select(current.requests[0]||null)}
function renderList(){const q=$('search').value.trim().toLowerCase(),f=$('filter').value;const visible=current.requests.filter(r=>(!q||r.search.includes(q))&&(!f||r.status===f));$('count').textContent=`显示 ${visible.length} / ${current.requests.length} 次请求材料`;$('requests').replaceChildren(...visible.map(r=>{const b=el('button',undefined,'request'+(r===selected?' active':''));b.append(el('strong',r.purpose==='workflow_review'?'成功后复盘':`第 ${r.step??'?'} 步 · ${core.actionLabel(r)}`),el('span',r.status,'badge '+(r.error?'bad':r.status==='已展示'?'good':'')),el('small',core.actionSource(r)));b.onclick=()=>select(r);return b}));if(!visible.length)$('requests').append(el('p','没有匹配项，可清空搜索或切换筛选。'))}
async function select(r){selected=r;const gen=++generation;boxClear();tab='overview';renderList();renderDetail();if(!r){$('imageinfo').textContent='选择一次请求后查看附件。';return}
 const tr=core.resolveRef(files,current.source,r.refs.ui_tree_ref);if(tr.file){try{const t=JSON.parse(await tr.file.file.text());if(gen!==generation)return;tree=t}catch(e){if(gen!==generation)return;$('imagehint').textContent='UI Tree 无法解析：'+e.message}}else $('imagehint').textContent=tr.error;
 if(gen!==generation)return;
 imageTransform=r.events.find(e=>e.data.coordinate_transform)?.data.coordinate_transform||{};
 const decision=core.modelDecision(r.events);const guide=[...r.events].reverse().find(e=>e.event==='guidance')?.data;
 targetLabel=guide?.target?'实际展示目标':'模型建议目标（未必展示）';
 target=guide?.target||tree?.nodes?.find(n=>n.id===decision?.target_node)?.bounds||decision?.target_bbox||null;
 const im=core.resolveRef(files,current.source,r.refs.screenshot_ref);
 if(im.file&&/\.(jpe?g|png)$/i.test(im.file.path)){url=URL.createObjectURL(im.file.file);$('shot').src=url;$('shot').hidden=false;$('original').href=url;$('original').hidden=false;$('imageinfo').textContent=r.refs.screenshot_ref;$('shot').onload=()=>{if(gen===generation){fitImage();drawBox();renderNodes()}};$('shot').onerror=()=>{if(gen===generation){$('imageinfo').textContent='图片损坏或格式不支持';$('box').hidden=true}}}
 else {$('imageinfo').textContent=im.error||'图片格式不支持';if(!r.refs.screenshot_ref)$('imageinfo').textContent='本次未采集截图。'+(r.events.find(e=>e.data.screenshot_reason)?.data.screenshot_reason||'日志没有说明原因。')}
 renderDetail();drawBox();renderNodes()
}
function placement(element,bounds){
 const rect=NodeGeometry.project(bounds,tree,{width:$('shot').naturalWidth,height:$('shot').naturalHeight},imageTransform);
 element.hidden=Boolean(rect.error);if(rect.error){$('imagehint').textContent=rect.error;return false}
 for(const key of ['left','top','width','height'])element.style[key]=rect[key]+'%';return true
}
function drawBox(){const b=$('box');b.hidden=true;if(!$('mark').checked||!target||$('shot').hidden)return;if(placement(b,target))$('imagehint').textContent=`橙框 · ${targetLabel}：${target.join(', ')}。模型目标不代表已执行。`}
function chooseNode(n){$('nodeDetails').open=true;activeNode=n;showNode(n);renderNodes();if(tab==='tree')renderDetail();scrollNodeList()}
function showNode(n){$('nodeinfo').textContent=`${n.id}\n资源 ID：${n.resource_id||'—'}\n文字：${n.text||'—'}\n描述：${n.description||'—'}\n类型：${n.kind||'—'} · 可点击：${!!n.clickable} · 可编辑：${!!n.editable}\n坐标：${JSON.stringify(n.bounds)}`}
function renderNodes(){
 const layer=$('nodelayer'),list=$('nodelist');layer.replaceChildren();list.replaceChildren();$('selectedbox').hidden=true;
 if(!tree){$('nodestatus').textContent='UI 树缺失，无法绘制节点';return}
 const filters={clickable:$('clickonly').checked,hideEmpty:$('hideempty').checked,query:$('nodesearch').value};
 const nodes=NodeGeometry.filtered(tree.nodes,filters),all=tree.nodes||[];
 const stats=tree.collection||{};let invalid=0;
 nodes.forEach(n=>{
  const number=all.indexOf(n)+1,b=el('button',undefined,'nodeframe'+(activeNode?.id===n.id?' active':''));
  b.type='button';b.dataset.nodeId=n.id;b.setAttribute('aria-label',`节点 ${number} ${n.text||n.description||n.kind||''}`);b.append(el('span',String(number)));
  b.title=`${n.id}\n${n.text||''} ${n.description||''}\n${n.kind} · click=${!!n.clickable} · editable=${!!n.editable}\n${n.bounds}`;
  if(!$('shot').hidden&&$('shot').naturalWidth&&$('nodeview').value==='nodes'){
   if(placement(b,n.bounds))layer.append(b);else invalid++;
  }
  b.onclick=()=>chooseNode(n);b.onmouseenter=()=>showNode(n);b.onfocus=()=>showNode(n);
  const row=el('button',`${number} · ${n.text||n.description||n.kind||'空容器'} · ${n.id}`,activeNode?.id===n.id?'active':'');
  row.dataset.nodeId=n.id;row.onclick=()=>chooseNode(n);list.append(row);
 });
 if(activeNode&&!$('shot').hidden)placement($('selectedbox'),activeNode.bounds);
 $('nodestatus').textContent=`显示 ${nodes.length}/${all.length} 个节点`+(tree.truncated?` · 已截断：${(stats.truncation_reasons||['旧日志未说明原因']).join('、')}`:' · 未截断')+
  (stats.visited!=null?` · 遍历 ${stats.visited}/${stats.traversal_limit}，跳过空容器 ${stats.empty_containers_skipped}`:'')+
  (invalid?` · ${invalid} 个区域坐标或截图方向异常，未绘制`:'')+($('shot').hidden?' · 截图缺失，仍可查看列表和原始 JSON':'');
}
function disclosure(title,value,open=false){const d=el('details');d.open=open;d.append(el('summary',title),pre(value));return d}
function renderDetail(){const h=$('heading'),d=$('detail');h.replaceChildren();d.replaceChildren();document.querySelectorAll('[data-tab]').forEach(b=>{b.classList.toggle('active',b.dataset.tab===tab);b.setAttribute('aria-selected',String(b.dataset.tab===tab))});
 if(!selected){h.append(copyButton(null));h.append(el('h2','整次运行事件'),el('p','包括没有 request_id 的准备、观察、重试、暂停和结束事件。','muted'));renderEvents(current.events,d);return}
 const r=selected;h.append(copyButton(r));h.append(el('h2',r.purpose==='workflow_review'?'成功后复盘':`第 ${r.step??'?'} 步 · ${core.actionLabel(r)}`),el('p',`第 ${r.attempt??'?'} 次尝试 · ${r.id}`,'muted'));
 if(tab==='overview'){d.append(el('p',`结果：${r.status}`));if(r.error)d.append(el('p','原因：'+(r.error.data.error||'查看原始事件'),'badge bad'));renderEvents(r.events,d)}
 if(tab==='input'){const e=r.events.find(e=>e.event==='request')||r.events.find(e=>e.data.input);if(e)d.append(pre(e.data.input));else d.append(el('p','日志没有保存本次输入。'))}
 if(tab==='output'){const responses=r.events.filter(e=>e.event==='response');if(!responses.length)d.append(el('p','尚无模型响应记录。'));for(const e of responses){d.append(el('h2','模型正文'),pre(parsed(e.data.content??e.data.output_raw??e.data.error??'没有返回正文')),disclosure('完整 API 返回',parsed(e.data.output_raw??e.data)));}for(const e of r.events.filter(e=>e.event==='decision'))d.append(disclosure('App 解析后的建议',e.data.output_parsed,true))}
 if(tab==='raw')d.append(pre(r.events));
 if(tab==='tree'){if(!tree){d.append(el('p','未找到或无法读取本次 UI Tree。'));return}d.append(el('p',`${tree.package_name||''} · ${tree.screen_width} × ${tree.screen_height} · ${tree.nodes?.length||0} 个节点`));if(tree.truncated)d.append(el('p','注意：本次 UI Tree 已截断。'));const search=el('input');search.placeholder='筛选节点文字、描述、ID';search.setAttribute('aria-label','筛选 UI Tree 节点');d.append(search);const table=el('table');d.append(table);function rows(){const nodes=(tree.nodes||[]).filter(n=>JSON.stringify(n).toLowerCase().includes(search.value.toLowerCase()));table.replaceChildren();const head=el('tr');['节点 / 点击可看位置','文字 / 描述','坐标'].forEach(x=>head.append(el('th',x)));table.append(head);for(const n of nodes){const row=el('tr');row.tabIndex=0;row.append(el('td',n.id),el('td',[n.text,n.description].filter(Boolean).join(' / ')||'—'),el('td',json(n.bounds)));if(activeNode?.id===n.id)row.classList.add('selected');const choose=()=>chooseNode(n);row.onclick=choose;row.onkeydown=e=>{if(e.key==='Enter')choose()};table.append(row)}}search.oninput=rows;rows();d.append(disclosure('原始 UI Tree JSON',tree))}
}
function renderEvents(events,container){for(const e of events){const d=e.data,n=el('section',undefined,'event'+(d.error?' error':''));n.append(el('strong',`${stamp(e.timestamp)} · ${e.event==='response'&&d.error?(d.error_code==='APP_CANCELLED'?'请求结束（App 已取消）':'请求失败'):(names[e.event]||e.event)}`),el('p',`原日志第 ${e.line} 行 · ${e.event}`,'muted'));if(d.instruction)n.append(el('p',d.instruction));if(d.error||d.reason)n.append(el('p',d.error||d.reason));if(d.stage||d.error_code)n.append(el('p',`阶段：${d.stage||'未记录'} · 错误码：${d.error_code||'未记录'} · 规则：${d.rule||'未记录'}\n期望：${json(d.expected??'—')} · 实际：${json(d.actual??'—')}`));if(e.event==='behavior_observed')n.append(el('p',`事件：${d.event_type} · 来源：${d.actor} · ${d.limitation||''}`));if(d.screenshot_saved!=null)n.append(el('p',`截图已保存：${d.screenshot_saved} · 已发送给模型：${d.screenshot_sent_to_model}`));for(const key of ['capture_latency_ms','queue_latency_ms','evidence_to_confirmation_ms','confirmation_to_display_ms'])if(Number.isFinite(d[key]))n.append(el('p',`${key}：${d[key]} ms`));if(d.output_parsed?.instruction)n.append(el('p','模型建议：'+d.output_parsed.instruction));if(Number.isFinite(d.latency_ms))n.append(el('p',`调用耗时 ${d.latency_ms} ms`));if(d.result)n.append(el('p','结束状态：'+d.result));n.append(disclosure('查看此条完整记录',e));container.append(n)}}
$('folder').onchange=e=>ingest(e.target.files);$('files').onchange=e=>ingest(e.target.files);$('clear').onclick=reset;$('session').onchange=e=>chooseSession(+e.target.value);$('search').oninput=renderList;$('filter').onchange=renderList;$('runEvents').onclick=()=>select(null);$('mark').onchange=drawBox;document.querySelectorAll('[data-tab]').forEach(b=>b.onclick=()=>{tab=b.dataset.tab;renderDetail()});

for(const id of ['nodeview','hideempty','clickonly','nodesearch'])$(id).oninput=renderNodes;
$('zoom').oninput=()=>{fitImage();drawBox()};
$('fit').onclick=()=>{$('zoom').value='100';fitImage()};
$('expandShot').onclick=()=>{
 const expanded=document.querySelector('.imagepanel').classList.toggle('is-expanded');
 $('expandShot').textContent=expanded?'返回侧栏':'放大查看';$('expandShot').setAttribute('aria-expanded',String(expanded));fitImage();
};
window.addEventListener('keydown',event=>{if(event.key==='Escape'&&document.querySelector('.imagepanel').classList.contains('is-expanded'))$('expandShot').click()});
new ResizeObserver(fitImage).observe(document.querySelector('.imageviewport'));
$('nodeDetails').addEventListener('toggle',fitImage);
window.addEventListener('resize',fitImage);


function fitImage(){
 const viewport=document.querySelector('.imageviewport'),shot=$('shot'),frame=$('frame');
 if(shot.hidden||!shot.naturalWidth||!viewport.clientHeight)return;
 const factor=Math.min(1,Math.max(.5,Number($('zoom').value)/100));
 const width=Math.max(1,Math.min(viewport.clientWidth,viewport.clientHeight*shot.naturalWidth/shot.naturalHeight))*factor;
 frame.style.width=width+'px';frame.style.height=(width*shot.naturalHeight/shot.naturalWidth)+'px';
}
function scrollNodeList(){
 const list=$('nodelist'),row=list.querySelector('.active');if(!row)return;
 if(row.offsetTop<list.scrollTop)list.scrollTop=row.offsetTop;
 else if(row.offsetTop+row.offsetHeight>list.scrollTop+list.clientHeight)list.scrollTop=row.offsetTop+row.offsetHeight-list.clientHeight;
}
function copyButton(request){
 const wrap=el('div',undefined,'copy-actions'),button=el('button',request?'复制本步日志':'复制整次运行日志','primary');
 button.type='button';button.id='copyStep';const status=el('span',undefined,'muted');status.setAttribute('role','status');
 button.onclick=async()=>{
  const session=current;
  button.disabled=true;
  try{
   // Read the tree again to make a fast click immediately after selection reliable.
   let copiedTree=null;
   if(request){const ref=core.resolveRef(files,session.source,request.refs.ui_tree_ref);if(ref.file)try{copiedTree=JSON.parse(await ref.file.file.text())}catch{}}
   const text=core.stepText(session,request,copiedTree);let success=false;
   if(navigator.clipboard?.writeText)try{await navigator.clipboard.writeText(text);success=true}catch{}
   if(!success){
    const area=el('textarea');area.value=text;area.setAttribute('readonly','');area.style.cssText='position:fixed;left:-9999px;top:0';document.body.append(area);
    area.select();try{success=document.execCommand('copy')}catch{}area.remove();
   }
   if(success){status.textContent='已复制：完整事件、模型内容、诊断和 UI 树';button.textContent='已复制';}
   else{$('copyText').value=text;$('copyFallback').showModal();$('copyText').focus();$('copyText').select();status.textContent='请在弹窗中复制';}
  }catch(error){status.textContent='复制失败：'+error.message}
  finally{button.disabled=false}
 };
 wrap.append(button,status);return wrap;
}
