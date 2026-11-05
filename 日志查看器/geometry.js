(function(root){
'use strict';
function project(bounds,tree,image,transform={}){
 const w=tree?.screen_width,h=tree?.screen_height,iw=image?.width,ih=image?.height;
 if(![w,h,iw,ih].every(v=>Number.isFinite(v)&&v>0))return {error:'缺少有效屏幕或截图尺寸'};
 if(!Array.isArray(bounds)||bounds.length!==4||!bounds.every(Number.isFinite))return {error:'坐标不是四个有效数字'};
 const [l,t,r,b]=bounds;
 if(l<0||t<0||r>w||b>h||r<=l||b<=t)return {error:`节点坐标越界：${bounds}，屏幕 ${w} × ${h}`};
 const rotation=transform.rotation_degrees??0;
 if(![0,90,180,270].includes(rotation))return {error:'未知坐标旋转信息'};
 const aspect=rotation%180?h/w:w/h;
 if(Math.abs(iw/ih-aspect)>.02)return {error:'截图与 UI 树方向或宽高比例不符；缺少可靠转换，已停止绘制'};
 const turn=([x,y])=>rotation===90?[1-y,x]:rotation===180?[1-x,1-y]:rotation===270?[y,1-x]:[x,y];
 const pts=[[l/w,t/h],[r/w,t/h],[l/w,b/h],[r/w,b/h]].map(turn);
 const xs=pts.map(p=>p[0]),ys=pts.map(p=>p[1]);
 return {left:Math.min(...xs)*100,top:Math.min(...ys)*100,width:(Math.max(...xs)-Math.min(...xs))*100,height:(Math.max(...ys)-Math.min(...ys))*100};
}
function meaningful(n){return Boolean(n.clickable||n.editable||n.text?.trim()||n.description?.trim()||n.hint?.trim())}
function filtered(nodes,{clickable=false,hideEmpty=true,query=''}={}){return (nodes||[]).filter(n=>(!clickable||n.clickable)&&(!hideEmpty||meaningful(n))&&JSON.stringify(n).toLowerCase().includes(query.toLowerCase()))}
const api={project,meaningful,filtered};root.NodeGeometry=api;if(typeof module!=='undefined')module.exports=api;
})(typeof globalThis!=='undefined'?globalThis:this);
