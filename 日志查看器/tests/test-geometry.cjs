const {test}=require('node:test'),assert=require('node:assert/strict'),g=require('../geometry.js'),c=require('../core.js');
const tree={screen_width:1200,screen_height:2670};
test('1200x2670 click projects exactly at native and half-size screenshots',()=>{
 const box=[972,2310,1112,2475],a=g.project(box,tree,{width:1200,height:2670}),b=g.project(box,tree,{width:600,height:1335});
 assert.deepEqual(a,b);assert.equal(a.left,81);assert.ok(Math.abs(a.width-140/1200*100)<1e-10);
 for(const scale of [.5,1,2.5])assert.ok(Math.abs((a.left/100*600*scale)-(972/1200*600*scale))<1e-8);
});
test('landscape rotation is explicit; mismatched or unknown orientation is rejected',()=>{
 assert.ok(g.project([0,0,120,267],tree,{width:2670,height:1200}).error);
 const b=g.project([0,0,120,267],tree,{width:2670,height:1200},{rotation_degrees:90});assert.equal(b.left,90);assert.equal(b.top,0);
 assert.ok(g.project([0,0,1300,100],tree,{width:1200,height:2670}).error);
 assert.ok(g.project([1,1,1,5],tree,{width:1200,height:2670}).error);
 assert.ok(g.project([1,1,5,5],{},{}).error);
});
test('empty containers and clickable filters do not remove actionable unlabeled icons',()=>{
 const nodes=[{id:'empty'},{id:'icon',clickable:true},{id:'text',text:'设置'},{id:'edit',editable:true}];
 assert.deepEqual(g.filtered(nodes).map(n=>n.id),['icon','text','edit']);
 assert.deepEqual(g.filtered(nodes,{clickable:true}).map(n=>n.id),['icon']);
});
test('structured diagnostics survive association, status and raw data',()=>{
 for(const [stage,status] of Object.entries({parse:'解析错误',target:'目标校验拒绝',capture:'截图失败',execution:'执行失败',completion:'完成验证拒绝'})){
 const data={request_id:'r',step:2,attempt:1,snapshot_id:'s',stage,error_code:'X',rule:'R',expected:'E',actual:'A',error:'明确原因',ui_tree_ref:'r_tree.json',screenshot_ref:'r.jpg'};
 const session=c.parseLog(JSON.stringify({session_id:'run',event:'diagnostic',data}),'events.jsonl').sessions[0];const req=session.requests[0];
 assert.equal(req.status,status);assert.equal(req.error.data.actual,'A');assert.equal(req.refs.screenshot_ref,'r.jpg');
 }
});
test('cancellation, stale response and dispatch remain distinct',()=>{
 const parse=events=>c.parseLog(events.map(([event,data])=>JSON.stringify({session_id:'s',event,data:{request_id:'r',...data}})).join('\n'),'x').sessions[0].requests[0];
 assert.equal(parse([['request_cancelled_by_app',{}]]).status,'App 已取消');
 assert.equal(parse([['discarded',{reason:'页面变化'}]]).status,'已丢弃');
 assert.equal(parse([['execution_result',{result:'dispatched_not_verified'}]]).status,'已派发，待验证');
});
test('preparation failure cannot masquerade as cancellation',()=>{
 const s=c.parseLog(JSON.stringify({session_id:'s',event:'request_prepare_error',data:{request_id:'r',stage:'artifact',error_code:'ARTIFACT_SAVE_ERROR',error:'截图写入失败'}}),'x').sessions[0];
 assert.notEqual(s.requests[0].status,'发送前取消');assert.equal(s.requests[0].error.data.error_code,'ARTIFACT_SAVE_ERROR');
});
