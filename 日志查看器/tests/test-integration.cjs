const fs=require('node:fs'),path=require('node:path'),os=require('node:os'),{spawn}=require('node:child_process'),assert=require('node:assert/strict'),zlib=require('node:zlib');
const delay=ms=>new Promise(r=>setTimeout(r,ms));
function crc(b){let c=0xffffffff;for(const v of b){c^=v;for(let i=0;i<8;i++)c=(c>>>1)^((c&1)?0xedb88320:0)}return (c^0xffffffff)>>>0}
function png(w,h){const chunk=(n,b)=>{const t=Buffer.from(n),len=Buffer.alloc(4),sum=Buffer.alloc(4);len.writeUInt32BE(b.length);sum.writeUInt32BE(crc(Buffer.concat([t,b])));return Buffer.concat([len,t,b,sum])};const hdr=Buffer.alloc(13);hdr.writeUInt32BE(w);hdr.writeUInt32BE(h,4);hdr[8]=8;hdr[9]=2;const raw=Buffer.alloc((w*3+1)*h);for(let y=0;y<h;y++)for(let x=0;x<w;x++){let i=y*(w*3+1)+1+x*3;raw[i]=230;raw[i+1]=240;raw[i+2]=248}return Buffer.concat([Buffer.from([137,80,78,71,13,10,26,10]),chunk('IHDR',hdr),chunk('IDAT',zlib.deflateSync(raw)),chunk('IEND',Buffer.alloc(0))])}
(async()=>{
 const temp=fs.mkdtempSync(path.join(os.tmpdir(),'elder-viewer-test-')),profile=path.join(temp,'chrome');
 const chrome=spawn('/Applications/Google Chrome.app/Contents/MacOS/Google Chrome',['--headless=new','--disable-gpu','--no-first-run','--no-default-browser-check','--remote-debugging-port=0','--user-data-dir='+profile,'about:blank'],{stdio:'ignore'});
 let socket;try{
  const portFile=path.join(profile,'DevToolsActivePort');for(let i=0;i<100&&!fs.existsSync(portFile);i++)await delay(100);
  const port=fs.readFileSync(portFile,'utf8').split('\n')[0],targets=await (await fetch('http://127.0.0.1:'+port+'/json')).json();
  socket=new WebSocket(targets.find(t=>t.type==='page').webSocketDebuggerUrl);await new Promise((r,j)=>{socket.onopen=r;socket.onerror=j});
  let seq=0;const pending=new Map(),errors=[];socket.onmessage=e=>{const m=JSON.parse(e.data);if(m.id){const p=pending.get(m.id);pending.delete(m.id);m.error?p?.reject(Error(JSON.stringify(m.error))):p?.resolve(m.result)}if(m.method==='Runtime.exceptionThrown')errors.push(m.params.exceptionDetails.text)};
  const call=(method,params={})=>new Promise((resolve,reject)=>{const id=++seq;pending.set(id,{resolve,reject});socket.send(JSON.stringify({id,method,params}))});
  const evaluate=async expression=>{const r=await call('Runtime.evaluate',{expression,returnByValue:true,awaitPromise:true});if(r.exceptionDetails)throw Error(JSON.stringify(r.exceptionDetails));return r.result.value};
  const wait=async expression=>{for(let i=0;i<100;i++){if(await evaluate(expression))return;await delay(50)}throw Error('等待超时：'+expression)};
  await call('Runtime.enable');await call('Page.enable');await call('Emulation.setDeviceMetricsOverride',{width:1500,height:1000,deviceScaleFactor:1,mobile:false});
  await call('Page.navigate',{url:'file://'+path.join(__dirname,'..','index.html')});await wait("typeof ingest==='function'");
  const tree={snapshot_id:'snap',screen_width:1200,screen_height:2670,package_name:'test.launcher',truncated:true,collection:{visited:3000,traversal_limit:3000,empty_containers_skipped:1200,truncation_reasons:['traversal_limit']},nodes:[{id:'snap_1',text:'设置',description:'系统设置',kind:'ImageView',clickable:true,editable:false,bounds:[972,2310,1112,2475]},{id:'snap_2',text:'标题',kind:'TextView',bounds:[50,50,400,150]},{id:'snap_3',kind:'FrameLayout',bounds:[0,0,1200,2670]}]};
  const decision={current_state:'桌面',next_action:'click',target_node:'snap_1',target_bbox:[972,2310,1112,2475],instruction:'点击设置',confidence:.75,task_completed:false,evidence:['设置图标']};
  const base={step:1,attempt:1,request_id:'r',snapshot_id:'snap',ui_tree_ref:'tree.json',screenshot_ref:'shot.png',coordinate_transform:{rotation_degrees:0}};
  const rows=[['snapshot_saved',{screenshot_saved:true,screenshot_sent_to_model:false}],['request',{}],['decision',{output_parsed:decision}],['guidance',{instruction:'点击设置',target:decision.target_bbox}],['behavior_observed',{event_type:'reported_click',actor:'user_or_external_app',actor_certain:false}],['diagnostic',{stage:'target',error_code:'BBOX_OUT_OF_BOUNDS',rule:'bounds_within_screen',expected:'right<=1200',actual:'right=1300',error:'坐标越界：right=1300'}]].map(([event,data])=>JSON.stringify({session_id:'test',event,data:{...base,...data}})).join('\n');
  fs.writeFileSync(path.join(temp,'events.jsonl'),rows);fs.writeFileSync(path.join(temp,'tree.json'),JSON.stringify(tree));fs.writeFileSync(path.join(temp,'shot.png'),png(600,1335));
  const document=await call('DOM.getDocument'),input=await call('DOM.querySelector',{nodeId:document.root.nodeId,selector:'#files'});
  await call('DOM.setFileInputFiles',{nodeId:input.nodeId,files:['events.jsonl','tree.json','shot.png'].map(n=>path.join(temp,n))});
  await wait("document.querySelector('#shot').naturalWidth===600");
  assert.match(await evaluate("document.querySelector('.request strong').textContent"),/第 1 步 · 点击设置/);
  assert.doesNotMatch(await evaluate("document.querySelector('.request strong').textContent"),/尝试/);
  for(const [width,height] of [[1500,1000],[1366,768],[1920,1080]]){
   await call('Emulation.setDeviceMetricsOverride',{width,height,deviceScaleFactor:1,mobile:false});await delay(100);
   const layout=await evaluate("(()=>{const p=document.querySelector('.imagepanel'),v=document.querySelector('.imageviewport'),s=$('shot').getBoundingClientRect(),r=v.getBoundingClientRect();return {panelBottom:p.getBoundingClientRect().bottom,h:innerHeight,pageH:document.documentElement.scrollHeight,imageH:s.height,viewportH:v.clientHeight,imageTop:s.top,viewportTop:r.top,imageBottom:s.bottom,viewportBottom:r.bottom,scroll:v.scrollHeight-v.clientHeight}})()");
   assert.ok(layout.panelBottom<=height+1,JSON.stringify(layout));assert.ok(layout.pageH<=height+1,JSON.stringify(layout));assert.ok(layout.imageH>60);assert.ok(layout.imageH<=layout.viewportH+1);assert.ok(layout.imageTop>=layout.viewportTop-1);assert.ok(layout.imageBottom<=layout.viewportBottom+1);assert.ok(layout.scroll<=1);
  }
  await call('Emulation.setDeviceMetricsOverride',{width:1500,height:1000,deviceScaleFactor:1,mobile:false});await delay(100);
  await evaluate("Object.defineProperty(navigator,'clipboard',{configurable:true,value:{writeText:async text=>{window.copied=text}}});$('copyStep').click()");
  await wait("!!window.copied");assert.match(await evaluate('window.copied'),/BBOX_OUT_OF_BOUNDS/);assert.match(await evaluate('window.copied'),/snap_1/);assert.match(await evaluate('window.copied'),/ui_tree/);assert.match(await evaluate("$('copyStep').textContent"),/已复制/);
  await evaluate("Object.defineProperty(navigator,'clipboard',{configurable:true,value:{writeText:async()=>{throw Error('denied')}}});document.execCommand=()=>false;$('copyStep').click()");await wait("$('copyFallback').open");assert.equal(await evaluate("$('copyText').value"),await evaluate('window.copied'));await evaluate("$('copyFallback').close()");
  const normalHeight=await evaluate("$('shot').getBoundingClientRect().height");await evaluate("$('expandShot').click()");await delay(80);assert.ok(await evaluate("$('shot').getBoundingClientRect().height")>normalHeight);await evaluate("$('expandShot').click()");
  await evaluate("$('nodeview').value='nodes';renderNodes()");assert.equal(await evaluate("$('nodelayer').children.length"),2);assert.match(await evaluate("$('nodestatus').textContent"),/traversal_limit/);
  await evaluate("document.querySelector('#nodelayer button').click()");assert.match(await evaluate("$('nodeinfo').textContent"),/snap_1/);assert.equal(await evaluate("document.querySelector('#nodelist .active').dataset.nodeId"),'snap_1');
  assert.equal(await evaluate("$('box').hidden"),false);
  for(const zoom of [50,75,100]){
   await evaluate(`$('zoom').value=${zoom};$('zoom').oninput()`);
   const rect=await evaluate("(()=>{const f=$('frame').getBoundingClientRect(),b=document.querySelector('#nodelayer button').getBoundingClientRect();return {ratio:(b.left-f.left)/f.width}})()");assert.ok(Math.abs(rect.ratio-.81)<.002);
  }
  await evaluate("$('clickonly').checked=true;renderNodes()");assert.equal(await evaluate("$('nodelayer').children.length"),1);
  await evaluate("tab='tree';renderDetail()");assert.match(await evaluate("$('detail').textContent"),/原始 UI Tree JSON/);
  await evaluate("tab='overview';renderDetail()");assert.match(await evaluate("$('detail').textContent"),/BBOX_OUT_OF_BOUNDS/);assert.match(await evaluate("$('detail').textContent"),/right=1300/);
  await evaluate("$('nodeview').value='raw';renderNodes()");assert.equal(await evaluate("$('nodelayer').children.length"),0);
  await evaluate("$('zoom').value=100;$('zoom').oninput();$('nodeview').value='nodes';renderNodes();$('nodeDetails').open=false");await delay(80);
  const screenshot=await call('Page.captureScreenshot',{format:'png'});fs.writeFileSync(path.join(temp,'integration-preview.png'),Buffer.from(screenshot.data,'base64'));
  await evaluate("reset()");assert.equal(await evaluate("$('shot').getAttribute('src')"),null);assert.equal(await evaluate("$('nodelayer').children.length"),0);assert.deepEqual(errors,[]);
  console.log('PASS: browser import, tree overlay, bidirectional selection, fit-to-window at 1366/1500/1920, 50/75/100% image size, action titles, full-step copy + denied-permission fallback, expanded image, zoom, filters, model highlight, truncation status, diagnostic propagation, raw JSON, raw image toggle, clear; zero uncaught JS errors.');
 }finally{socket?.close();chrome.kill()}
})().catch(e=>{console.error(e);process.exitCode=1});
