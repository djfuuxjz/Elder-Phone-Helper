const {test}=require('node:test'),assert=require('node:assert/strict'),core=require('../core.js');
const req=events=>({step:12,attempt:2,id:'r',purpose:'guidance',status:'已派发，待验证',refs:{screenshot_ref:'r.jpg'},events});
test('automatic steps display actual recorded decision instruction, not attempt number',()=>{
 const r=req([{event:'decision',data:{output_parsed:{next_action:'click',instruction:'点击微信图标'}}},{event:'execution_requested',data:{action:'CLICK'}}]);
 assert.equal(core.actionLabel(r),'点击微信图标');assert.match(core.actionSource(r),/是否生效见验证/);
});
test('a suggestion without execution is not described as an executed click',()=>{
 const r=req([{event:'response',data:{content:JSON.stringify({next_action:'click',instruction:'点击设置'})}}]);
 assert.equal(core.actionLabel(r),'点击设置');assert.match(core.actionSource(r),/未记录执行/);
});
test('displayed guidance takes precedence over model text and unknown steps are not guessed',()=>{
 assert.equal(core.actionLabel(req([{event:'decision',data:{output_parsed:{instruction:'点击旧按钮'}}},{event:'guidance',data:{instruction:'点击返回'}}])),'点击返回');
 assert.equal(core.actionLabel(req([{event:'capture_started',data:{}}])),'观察页面');
});
test('copy includes exact raw events, model response, attempt and full tree',()=>{
 const events=[{event:'response',data:{content:'{"instruction":"点击设置"}'}},{event:'diagnostic',data:{error_code:'X',expected:'E',actual:'A'}}];
 const r=req(events),session={id:'s',source:'folder/events.jsonl',events:[{goal:'打开设置'}]},tree={nodes:[{id:'node',text:'设置'}]};
 const text=core.stepText(session,r,tree),data=JSON.parse(text.slice(text.indexOf('{')));
 assert.deepEqual(data.events,events);assert.deepEqual(data.ui_tree,tree);assert.equal(data.attempt,2);assert.equal(data.attachment_refs.screenshot_ref,'r.jpg');assert.equal(data.goal,'打开设置');
});
test('whole-run copy retains events without a request ID',()=>{
 const session={id:'s',source:'events.jsonl',events:[{event:'start',data:{}},{event:'end',data:{result:'STOPPED'}}]};
 assert.match(core.stepText(session,null,null),/STOPPED/);
});
