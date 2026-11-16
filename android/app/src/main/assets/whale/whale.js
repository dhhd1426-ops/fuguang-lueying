
(()=>{
const SRC={kind:'app'},Q=new URLSearchParams(location.search),MODEL='/model/',APPDIR='/app/';
/* 往 App 报事：ready / editDone / error / glLost */
const bridge=(n,d)=>{try{if(window.Vg)Vg.event(n,typeof d==='string'?d:JSON.stringify(d||{}));}catch(_){}};
const $=id=>document.getElementById(id);
/* App 里她是用户自己请来的动态角色，动得多少由「待机动作」管；系统的减弱动效（省电模式也会打开）不再把她定成一张图 */
const reduced=false;
const load=$('load');
function fail(msg){bridge('error',String(msg));load.classList.add('err');load.classList.remove('done');load.textContent='载入失败：\n'+msg+'\n\n把这段截图发给我。';}
window.addEventListener('error',e=>fail((e.message||'')+(e.filename?'\n'+e.filename.split('/').slice(-3).join('/')+':'+e.lineno+':'+e.colno:'')+(e.error&&e.error.stack?'\n'+String(e.error.stack).split('\n').slice(0,5).join('\n'):'')));
window.addEventListener('unhandledrejection',e=>fail(String(e.reason&&e.reason.stack||e.reason)));
if(!window.Live2DCubismCore)return fail('Cubism Core 没加载');
if(!window.PIXI)return fail('PIXI 没加载');
if(!PIXI.live2d)return fail('pixi-live2d-display 没加载');

const stage=$('stage'),scr=$('screen');
try{PIXI.live2d.Live2DModel.registerTicker(PIXI.Ticker);}catch(_){}
const app=new PIXI.Application({resizeTo:stage,backgroundAlpha:0,antialias:true,autoDensity:true,resolution:Math.min(3,devicePixelRatio||1),powerPreference:'high-performance'});
/* 画质：渲染跟随屏幕密度（最高 3 倍）；遮罩图手机支持就开到 4096 */
const RES=Math.min(3,devicePixelRatio||1);let maskSize=2048,texHD=false,texSize=0;
try{const gl=app.renderer.gl,big=gl.getParameter(gl.MAX_TEXTURE_SIZE)>=8192;
  maskSize=big?(+Q.get('mask')||4096):2048;texHD=big&&Q.get('hd')==='1';}catch(_){}
/* 显存不够时浏览器会丢掉画布：记下来，下次自动退回标准画质 */
app.view.addEventListener('webglcontextlost',e=>{e.preventDefault();bridge('glLost',{});});
stage.appendChild(app.view);

/* 可调状态 */
let speed=1,clock=0,tasks=[];
const after=(ms,fn)=>tasks.push({at:clock+ms,fn});
const rnd=(a,b)=>a+Math.random()*(b-a);
/* 取景：S 当前值，St 目标值，每帧弹过去（换布局、开始聊天时平滑移动，拖动时直接跟手） */
const LAYOUT={A:{idle:{zoom:.8,dx:0,dy:-.06},chat:{zoom:.42,dx:.31,dy:.17}},B:{idle:{zoom:.74,dx:.24,dy:0},chat:{zoom:.74,dx:.24,dy:0}},C:{idle:{zoom:1.7,dx:0,dy:.74},chat:{zoom:1.45,dx:.27,dy:.78}}};
const LAY_DESC={A:'A 舞台：没说话时她站在正中间，问候在脚下；一开始聊天就缩到右下角当「同屏陪伴」，回复时照样张嘴、做表情。手机外屏、内屏都合适。',
  B:'B 同桌：她一直站在右侧，聊天在左边；适合折叠屏展开、平板这种宽屏（宽度 ≥ 780dp 时用，和项目页右栏的规则一致）。窄屏上会挤。',
  C:'C 探头：只露出头和肩膀，从输入框上方探出来；最不占地方、存在感最低，适合不想被打扰的时候。全身动作和尾巴基本看不到。'};
let lay='A',chatOn=false,editing=false;
try{const sv=JSON.parse(localStorage.getItem('fy-layout')||'null');if(sv)for(const k in sv)if(LAYOUT[k])Object.assign(LAYOUT[k],sv[k]);}catch(_){}
const S={...LAYOUT.A.idle},St={...S};
/* 叠加到模型上的量（每帧逼近目标，可打断） */
const P={mouth:0,smile:0,nod:0,lookX:0,lookY:0,eyeX:0,eyeY:0,brow:0,wide:0},T={...P};
/* 眼睛快、头慢：眼睛先看过去，头随后跟上 */
const RATE={mouth:26,smile:7,nod:12,lookX:3.2,lookY:3.2,eyeX:22,eyeY:22,brow:6,wide:10};
/* 头和身体再过一层平滑，起止都是缓的（两级一阶滤波） */
const H={x:0,y:0,z:0,bx:0,by:0,bz:0,x1:0,y1:0,z1:0};
const V={a:0,i:0,u:0,e:0,o:0},VT={...V};
/* 每帧合成：ADD 是叠加到默认值/基础值上的增量，HOLD 是在物理之前直接钉住的绝对值 */
let ADD={},HOLD={};const touched=new Set();
/* 动作库按通用参数写（ParamAngleX 等），这个模型的头/身体实际认 VTS 那组（AngleX、PositionX），这里换过去 */
const ALIAS={ParamAngleX:['AngleX',1.3],ParamAngleY:['AngleY',1.4],ParamAngleZ:['AngleZ',1.3],ParamBodyAngleX:['PositionX',1.6],ParamBodyAngleY:['PositionY',1.6],ParamBodyAngleZ:['PositionZ',1.6]};
const add=(k,v)=>{if(!v)return;const a=ALIAS[k];if(a){k=a[0];v*=a[1];}ADD[k]=(ADD[k]||0)+v;};
/* —— 以下动作库移植自 FULASKA/-live2d-（大肥鱼 Live2D 桌宠，MIT），数值按本模型参数写的 —— */
const ease=x=>x*x*(3-2*x),clamp=(v,a,b)=>v<a?a:v>b?b:v;
function sample(keys,p){if(p<=keys[0][0])return keys[0][1];for(let i=1;i<keys.length;i++){const[t1,v1]=keys[i];if(p<=t1){const[t0,v0]=keys[i-1];return v0+(v1-v0)*ease(clamp((p-t0)/(t1-t0||1),0,1));}}return keys[keys.length-1][1];}
const REST={Movement1:0,Movement2:0,Movement3:0,Param56:1,Param57:0,Param36:0,Param39:0,Param40:0,Param41:0,Param42:0,Param43:0,Param59:0,Param60:0,Param61:0,Param62:0};
const POSES={
  竖手指:{Movement1:0,Movement3:1,Param40:1,Param36:0,Param39:.322,Param41:.702,Param42:0},
  抚胸:{Movement2:1,Param62:.034,Param59:.476,Param60:.86,Param61:.022},
  背手:{Param36:-.64,Param62:-.64},
  狗尾草:{Movement1:1,Param36:.04,Param39:.2,Param41:-.732,Param42:0}};
const ROUTINES={
  重心换脚:{ms:5200,lines:['站久了…换个脚。','嗯…这边舒服点。','呼——'],lineChance:.8,
    tracks:{PositionX:[[0,0],[.25,16],[.45,14],[.62,-12],[.8,-10],[1,0]],PositionZ:[[0,0],[.25,-7],[.45,-6],[.62,6],[.8,5],[1,0]],
      AngleZ:[[0,0],[.28,7],[.48,6],[.64,-6],[.82,-5],[1,0]],AngleX:[[0,0],[.3,-5],[.65,4],[1,0]],HipAngleZ:[[0,0],[.25,-8],[.45,-7],[.62,7],[.8,6],[1,0]],
      HipAngleX:[[0,0],[.25,6],[.62,-6],[1,0]],ParamEyeLOpen:[[0,0],[.2,-.3],[.4,0],[1,0]],ParamEyeROpen:[[0,0],[.2,-.3],[.4,0],[1,0]]},bob:[[0,0],[.2,1],[.3,0],[.56,1],[.66,0],[1,0]]},
  东张西望:{ms:5600,lines:['咦，那边是什么？','有人吗……','嗯？刚才是谁。'],tracks:{ParamAngleX:[[0,0],[.22,9],[.46,8],[.72,-7],[1,0]],ParamAngleY:[[0,0],[.3,2.5],[.7,-1.5],[1,0]],ParamAngleZ:[[0,0],[.25,-2.5],[.7,2],[1,0]],ParamBodyAngleX:[[0,0],[.3,3],[.75,-2.5],[1,0]]},eyes:[[0,0],[.2,.8],[.46,.7],[.7,-.8],[1,0]]},
  伸懒腰:{ms:4200,lines:['伸个懒腰……','唔——','骨头要僵啦。'],face:'笑',tracks:{ParamAngleY:[[0,0],[.3,7],[.55,8],[.8,2],[1,0]],ParamBodyAngleY:[[0,0],[.3,6.5],[.6,7],[1,0]],ParamBodyAngleX:[[0,0],[.4,-2],[1,0]],ShoulderY:[[0,0],[.35,3.2],[.7,2.6],[1,0]],ParamEyeLOpen:[[0,0],[.3,-.35],[.7,-.25],[1,0]],ParamEyeROpen:[[0,0],[.3,-.35],[.7,-.25],[1,0]]},mouthK:[[0,0],[.3,.5],[.6,.6],[.8,0],[1,0]]},
  打瞌睡:{ms:6200,lines:['……zzZ','啊，我没睡！','就眯一下下……'],tracks:{ParamAngleY:[[0,0],[.35,-9],[.5,-10.5],[.58,3.5],[.72,-1.5],[1,0]],ParamAngleX:[[0,0],[.5,3.5],[.6,-1],[1,0]],ParamAngleZ:[[0,0],[.45,-3],[.62,1.5],[1,0]],ParamBodyAngleY:[[0,0],[.45,-5.5],[.6,2],[1,0]],ParamEyeLOpen:[[0,0],[.4,-.5],[.56,-.55],[.62,0],[1,0]],ParamEyeROpen:[[0,0],[.4,-.5],[.56,-.55],[.62,0],[1,0]]},wakeAt:.57},
  抖尾巴:{ms:2400,lines:['尾巴自己会动哦。'],lineChance:.25,tracks:{ParamBodyAngleZ:[[0,0],[.2,3],[.4,-3],[.6,2.4],[.8,-1.6],[1,0]],ParamBodyAngleX:[[0,0],[.25,2.2],[.55,-2],[1,0]],ParamAngleZ:[[0,0],[.25,-2.2],[.55,1.8],[1,0]]}},
  理理头发:{ms:4200,lines:['头发乱没乱？','嗯，还行。','甩一甩～'],lineChance:.8,gust:1.5,face:'笑',
    tracks:{AngleZ:[[0,0],[.15,12],[.3,-12],[.45,10],[.6,-8],[.75,4],[1,0]],AngleX:[[0,0],[.15,-6],[.3,6],[.45,-5],[.6,4],[1,0]],AngleY:[[0,0],[.1,4],[.7,3],[1,0]],
      PositionZ:[[0,0],[.15,4],[.3,-4],[.45,3],[.6,-2],[1,0]],ParamEyeLOpen:[[0,0],[.1,-.7],[.7,-.7],[.85,0],[1,0]],ParamEyeROpen:[[0,0],[.1,-.7],[.7,-.7],[.85,0],[1,0]]}},
  吹狗尾草:{ms:5600,lines:['呼——','草籽要飞走啦。','轻轻的……'],pose:'狗尾草',gust:2.2,mouth:{open:.3,funnel:.85,pucker:.6,form:-.2},tracks:{ParamAngleX:[[0,0],[.25,4],[.6,3],[1,0]],ParamAngleY:[[0,0],[.24,-5],[.6,-4],[1,0]],ParamAngleZ:[[0,0],[.3,2.6],[.62,-1.6],[1,0]],ParamBodyAngleX:[[0,0],[.3,2.2],[1,0]],ParamEyeLOpen:[[0,0],[.24,-.22],[.62,-.28],[1,0]],ParamEyeROpen:[[0,0],[.24,-.22],[.62,-.28],[1,0]]}},
  挥挥手:{ms:8200,lines:['这边这边～','嗨——','看到我了吗？'],pose:'竖手指',mouth:{smile:.3,open:.1},tracks:{ParamBrowLY:[[0,0],[.07,.5],[.16,-.1],[.26,.55],[.35,-.1],[.45,.6],[.55,-.05],[.64,.4],[.75,0],[1,0]],ParamBrowRY:[[0,0],[.07,.45],[.16,-.1],[.26,.5],[.35,-.1],[.45,.55],[.55,-.05],[.64,.35],[.75,0],[1,0]],ParamAngleX:[[0,0],[.3,4],[.75,5],[1,0]],ParamAngleY:[[0,0],[.3,3],[.75,3.5],[1,0]],ParamAngleZ:[[0,0],[.3,-3],[.7,3],[1,0]],ParamBodyAngleX:[[0,0],[.3,3],[1,0]],ParamEyeBallX:[[0,0],[.3,.15],[.8,.2],[1,0]]},
    holds:{Param44:[[0,0],[.14,-1],[.3,1],[.46,-1],[.62,1],[.78,-.6],[.9,0],[1,0]],LArm:[[0,0],[.18,.15],[.44,-.1],[.7,.1],[.86,-.04],[.95,0],[1,0]],LForearm:[[0,0],[.16,-.15],[.34,.2],[.5,-.18],[.66,.18],[.82,-.08],[.94,0],[1,0]],LWrist:[[0,0],[.22,.5],[.38,-.5],[.54,.5],[.7,-.5],[.84,.2],[.95,0],[1,0]],ShoulderZ:[[0,0],[.26,2.4],[.66,-1.8],[.95,0],[1,0]],ShoulderY:[[0,0],[.3,-1.2],[.7,1],[.95,0],[1,0]],HipAngleZ:[[0,0],[.26,1.8],[.7,-1.5],[1,0]]}},
  戴铁盆:{ms:5400,lines:['铛——','这样就不怕撞头啦。','稳不稳？'],fx:['pan'],mouth:{smile:.22,open:.06},tracks:{ParamAngleZ:[[0,0],[.12,-7],[.28,7],[.44,-6],[.6,6],[.76,-4],[.9,3],[1,0]],ParamAngleX:[[0,0],[.18,4],[.5,-4],[.82,3],[1,0]],ParamBodyAngleZ:[[0,0],[.14,-4],[.34,4],[.56,-3.5],[.76,3],[1,0]],ParamBodyAngleX:[[0,0],[.3,2],[1,0]]}},
  打哈欠:{ms:3800,lines:['哈——欠……','有点困了……'],lineAt:.82,fxAt:[[.78,'tear',1400]],tail:{amp:.04,f:.3,bias:-.3,mute:.5},
    tracks:{ParamMouthOpenY:[[0,0],[.25,.3],[.45,.95],[.7,.9],[.85,.1],[1,0]],MouthFunnel:[[0,0],[.45,.35],[.75,.3],[1,0]],
      ParamEyeLOpen:[[0,0],[.3,-.5],[.45,-1],[.75,-1],[.88,-.2],[1,0]],ParamEyeROpen:[[0,0],[.3,-.5],[.45,-1],[.75,-1],[.88,-.2],[1,0]],
      ParamBrowLY:[[0,0],[.45,.6],[.75,.5],[1,0]],ParamBrowRY:[[0,0],[.45,.6],[.75,.5],[1,0]],AngleY:[[0,0],[.4,9],[.75,8],[1,0]],AngleZ:[[0,0],[.45,6],[1,0]],PositionY:[[0,0],[.45,4],[1,0]]}},
  哼小曲:{ms:6400,lines:['嗯哼～哼～♪','啦啦～啦～','（小声哼歌）'],tail:{amp:.3,f:1.15,bias:.1,mute:.4},
    tracks:{AngleZ:[[0,0],[0.071,3.5],[0.143,-7],[0.214,7],[0.286,-7],[0.357,7],[0.429,-7],[0.5,7],[0.571,-7],[0.643,7],[0.714,-7],[0.786,7],[0.857,-7],[0.929,3.5],[1,0]],PositionZ:[[0,0],[0.071,1.5],[0.143,-3],[0.214,3],[0.286,-3],[0.357,3],[0.429,-3],[0.5,3],[0.571,-3],[0.643,3],[0.714,-3],[0.786,3],[0.857,-3],[0.929,1.5],[1,0]],AngleX:[[0,0],[0.143,-4],[0.286,4],[0.429,-4],[0.571,4],[0.714,-4],[0.857,4],[1,0]],EyeSmile:[[0,0],[.1,.7],[.9,.7],[1,0]],
      ParamEyeLOpen:[[0,0],[.1,-.55],[.9,-.55],[1,0]],ParamEyeROpen:[[0,0],[.1,-.55],[.9,-.55],[1,0]],MouthSmile_L:[[0,0],[.1,.4],[.9,.4],[1,0]],MouthSmile_R:[[0,0],[.1,.4],[.9,.4],[1,0]],
      ParamMouthOpenY:[[0,0],[0.071,0.04],[0.143,-0.08],[0.214,0.08],[0.286,-0.08],[0.357,0.08],[0.429,-0.08],[0.5,0.08],[0.571,-0.08],[0.643,0.08],[0.714,-0.08],[0.786,0.08],[0.857,-0.08],[0.929,0.04],[1,0]]}},
  偷看你:{ms:4400,lines:['嘿嘿，被发现了','……才没在看你'],lineAt:.68,fxAt:[[.6,'blush',1800]],tail:{amp:.12,f:1.3,bias:-.2,mute:.4},
    tracks:{AngleX:[[0,0],[.15,-14],[.6,-13],[.8,-4],[1,0]],AngleZ:[[0,0],[.2,-4],[.7,3],[1,0]],ParamEyeBallX:[[0,0],[.15,-.9],[.42,-.7],[.55,.6],[.85,.4],[1,0]],
      EyeSmile:[[0,0],[.58,0],[.72,.55],[1,0]],MouthSmile_L:[[0,0],[.6,0],[.72,.5],[1,0]],MouthSmile_R:[[0,0],[.6,0],[.72,.5],[1,0]]}},
  追尾巴:{ms:4600,lines:['尾巴又自己在动……','别跑！'],whipAt:[.18,.68],tail:{amp:.35,f:1.8,bias:0,mute:.4},
    tracks:{AngleX:[[0,0],[.2,18],[.4,14],[.55,-6],[.75,16],[1,0]],AngleY:[[0,0],[.2,-6],[.75,-6],[1,0]],PositionX:[[0,0],[.25,8],[.55,-3],[.75,8],[1,0]],
      ParamEyeBallX:[[0,0],[.2,1],[.45,.9],[.55,-.4],[.75,1],[1,0]],ParamEyeBallY:[[0,0],[.2,-.6],[.8,-.5],[1,0]]}},
  叹气:{ms:3800,lines:['唉——','好无聊呀……','你什么时候来呀'],lineAt:.5,tail:{amp:.03,f:.3,bias:-.45,mute:.6},
    tracks:{AngleY:[[0,0],[.3,4],[.55,-9],[.8,-7],[1,0]],PositionY:[[0,0],[.3,3],[.55,-5],[1,0]],ParamEyeLOpen:[[0,0],[.5,-.45],[.85,-.35],[1,0]],ParamEyeROpen:[[0,0],[.5,-.45],[.85,-.35],[1,0]],
      ParamMouthOpenY:[[0,0],[.45,.28],[.6,.05],[1,0]],BrowInnerUp:[[0,0],[.5,.6],[1,0]]}},
  歪头卖萌:{ms:3200,lines:['嘿嘿～','你看我干嘛～'],pose:'竖手指',mouth:{smile:.4,open:.1},tail:{amp:.36,f:2,bias:.1,mute:.5},
    tracks:{AngleZ:[[0,0],[.25,13],[.8,12],[1,0]],AngleX:[[0,0],[.25,5],[1,0]],ParamEyeLOpen:[[0,0],[.35,0],[.42,-1.1],[.56,-1.1],[.63,0],[1,0]],EyeSmile:[[0,0],[.25,.4],[.8,.4],[1,0]]}},
  要抱抱:{ms:4800,lines:['抱抱～','要抱抱！','过来嘛～'],lineAt:.3,moodK:'喜欢',
    holds:{Param41:[[0,0],[.12,-1],[.85,-1],[1,0]],Param60:[[0,0],[.12,-1],[.85,-1],[1,0]],LArm:[[0,0],[.12,.5],[.85,.5],[1,0]],RArm:[[0,0],[.12,.5],[.85,.5],[1,0]],
      LeanInput:[[0,0],[.2,-16],[.5,-12],[.75,-16],[1,0]]},
    tracks:{AngleY:[[0,0],[.2,5],[.85,5],[1,0]],AngleZ:[[0,0],[.25,6],[.55,-4],[.85,5],[1,0]],EyeSmile:[[0,0],[.2,.5],[.85,.5],[1,0]],ParamBrowLY:[[0,0],[.2,.5],[.85,.5],[1,0]],ParamBrowRY:[[0,0],[.2,.5],[.85,.5],[1,0]],
      PositionY:[[0,0],[.3,-2],[.5,1],[.7,-2],[1,0]]}},
  欢呼:{ms:3600,lines:['好耶！','万岁～','太棒啦！'],lineAt:.25,moodK:'开心',hops:[.28,.55],
    holds:{Param36:[[0,0],[.08,.35],[.18,1],[.82,1],[.92,.35],[1,0]],Param62:[[0,0],[.08,.35],[.18,1],[.82,1],[.92,.35],[1,0]]},
    tracks:{AngleY:[[0,0],[.2,8],[.8,7],[1,0]],AngleZ:[[0,0],[.3,-5],[.5,5],[.7,-4],[1,0]],ParamMouthOpenY:[[0,0],[.2,.55],[.75,.5],[1,0]],EyeSmile:[[0,0],[.18,.8],[.85,.8],[1,0]]}},
  高举打招呼:{ms:4400,lines:['嗨——！','这里这里！','你来啦～'],lineAt:.2,moodK:'开心',
    holds:{Param41:[[0,0],[.1,-.5],[.2,-1],[.85,-1],[1,0]],RArm:[[0,0],[.1,-.5],[.2,-1],[.85,-1],[1,0]],
      Param36:[[0,0],[.1,.2],[.2,.45],[.3,.3],[.4,.6],[.5,.3],[.6,.6],[.7,.3],[.8,.5],[.9,.2],[1,0]]},
    tracks:{AngleZ:[[0,0],[.2,-6],[.8,-5],[1,0]],AngleX:[[0,0],[.2,-4],[1,0]],EyeSmile:[[0,0],[.2,.5],[.85,.5],[1,0]],MouthSmile_L:[[0,0],[.2,.6],[.85,.6],[1,0]],MouthSmile_R:[[0,0],[.2,.6],[.85,.6],[1,0]],
      ParamBrowLY:[[0,0],[.3,.5],[.5,0],[.7,.5],[1,0]],ParamBrowRY:[[0,0],[.3,.5],[.5,0],[.7,.5],[1,0]]}},
  比心:{ms:4200,lines:['比心～','送你小心心！','喜欢你～'],lineAt:.3,moodK:'喜欢',fxAt:[[.12,'heart',3200],[.2,'blush',3000]],hearts:[.2,.35,.5,.65],
    holds:{Param41:[[0,0],[.12,-1],[.85,-1],[1,0]],Param60:[[0,0],[.12,-1],[.85,-1],[1,0]],LArm:[[0,0],[.12,.3],[.85,.3],[1,0]],RArm:[[0,0],[.12,.3],[.85,.3],[1,0]]},
    tracks:{AngleZ:[[0,0],[.2,9],[.85,8],[1,0]],AngleY:[[0,0],[.2,4],[1,0]],MouthSmile_L:[[0,0],[.2,.7],[.85,.7],[1,0]],MouthSmile_R:[[0,0],[.2,.7],[.85,.7],[1,0]]}},
  凑近摇摆:{ms:2800,lines:['嗨呀～','看这边！','晃一晃～'],pose:'竖手指',mouth:{smile:.35,open:.08},tracks:{ParamBodyAngleY:[[0,0],[.16,-6],[.34,1],[.5,7.5],[.66,1],[.84,-3],[1,0]],ParamAngleY:[[0,0],[.16,-4],[.5,5],[.84,-2],[1,0]],ParamBodyAngleX:[[0,0],[.3,3],[.6,-2.5],[1,0]],ParamAngleZ:[[0,0],[.22,-4],[.5,4],[.78,-2],[1,0]],HipAngleZ:[[0,0],[.3,-3],[.6,3],[1,0]]},
    holds:{LeanInput:[[0,0],[.14,-26],[.6,-24],[.86,-10],[1,0]],Movement2:[[0,0],[.18,1],[.8,1],[1,0]],Param44:[[0,0],[.2,-1],[.34,1],[.48,-.9],[.62,.9],[.76,-.5],[1,0]],LWrist:[[0,0],[.26,.5],[.4,-.5],[.54,.45],[.68,-.4],[.84,.15],[1,0]]}}};
/* 随机小表情（同出处） */
const FACES={笑:{dur:1250,add:{MouthSmile_L:.8,MouthSmile_R:.8,ParamMouthForm:.34,ParamMouthOpenY:.12,EyeSmile:.55}},
  吐舌头:{dur:1500,add:{TongueOut:1,ParamMouthOpenY:.3,ParamMouthForm:-.08,ParamTongueUpDown:-.25,MouthRollLower:.35}},
  吐舌头眨眼:{dur:1600,add:{TongueOut:1,ParamMouthOpenY:.32,ParamMouthForm:.2,ParamTongueUpDown:-.3,MouthSmile_L:.5,MouthSmile_R:.5},wink:'ParamEyeLOpen'}};
/* 模型自带表情：都是一个开关参数，这里自己叠，可以同时开几个、各自淡入淡出 */
const FX={blush:{key7:1},heart:{key4:1},dizzy:{key3:1},xx:{key5:1},smug:{key6:1},tear:{key2:1},sweat:{key13:1},teeth:{key9:1},gloom:{key1:1},loading:{key8:1},
  pan:{key10:1},glasses:{key11:1},monocle:{key12:1},tail:{Clothes5:-1},skirt:{Clothes1:-1},shoes:{Clothes4:1}};
/* 体型：模型自带三档（默认 0 / 体型1 0.4 / 体型2 1.0，参数 Clothes2），切换时平滑过渡 */
let bodyT=0,bodyV=0;
const fxW={},fxT={},fxUntil={};for(const k in FX){fxW[k]=0;fxT[k]=0;fxUntil[k]=0;}
function flash(k,ms=1600){fxT[k]=1;fxUntil[k]=clock+ms;}
function toggleFx(k){fxT[k]=fxT[k]?0:1;fxUntil[k]=fxT[k]?Infinity:0;return !!fxT[k];}
let heldPose=null,pose=null,poseW=0,poseT=0,rt=null,face=null,nextRt=9000,nextFace=5000,autoIdle=true,gust=0,petting=0,dizzyT=0;
function setPose(n){if(n){pose=n;poseT=1;}else poseT=0;}
function playRoutine(name){const n=name||(()=>{const ks=Object.keys(ROUTINES).filter(k=>k[0]!=='@'&&(!rt||k!==rt.name));return ks[Math.floor(Math.random()*ks.length)];})();
  const r=ROUTINES[n];rt={name:n,r,t:0};bridge('act',{k:'routine',n});busy=Math.max(busy,clock+r.ms);if(r.pose)setPose(r.pose);if(r.fx)r.fx.forEach(k=>flash(k,r.ms-300));
  if(r.tail)Object.assign(tlT,{jit:0},r.tail);const me=rt;
  if(r.moodK)Object.assign(tlT,{jit:0},MOODS[r.moodK].tail);
  (r.hops||[]).forEach(f=>after(r.ms*f,()=>{if(rt===me)hopV=-230;}));(r.hearts||[]).forEach(f=>after(r.ms*f,()=>{if(rt===me)hearts(3);}));
  (r.fxAt||[]).forEach(([f,k,ms])=>after(r.ms*f,()=>{if(rt===me)flash(k,ms);}));(r.whipAt||[]).forEach(f=>after(r.ms*f,()=>{if(rt===me)whipTail();}));
  if(r.gust)gust=r.gust;if(r.face)after(r.ms*.3,()=>playFace(r.face));
  if(r.lines&&Math.random()<(r.lineChance??.6)&&clock>sayBusy)after(r.lineAt?r.ms*r.lineAt:400,()=>{if(rt===me)speak(r.lines[Math.floor(Math.random()*r.lines.length)],false);});}
function stopRoutine(){if(rt&&rt.r.pose)setPose(null);if(rt&&(rt.r.tail||rt.r.moodK))Object.assign(tlT,mood&&MOODS[mood]?{jit:0,...MOODS[mood].tail}:TAIL_CALM);rt=null;}
function playFace(n){face={n,f:FACES[n],t:0};}
let sayBusy=0;
/* 作者原版「脱捕」动画（VTS 丢失追踪时播的那段，13.15 秒）：播放时它管的参数让给它，首尾 0.6 秒交叉淡化 */
const BW={"bw_happy":{"ms":3570,"loop":false,"ids":["PositionZ","PositionX","AngleZ","ParamEyeLOpen","ParamEyeROpen","AngleY","AngleX","EyeSmile","ParamMouthForm","ParamMouthOpenY","ParamEyeBallX","ParamEyeBallY","ParamBrowLY","ParamBrowRY","PositionY","Param36","Param62","LeanInput","Movement1","Movement2","Movement3","Param39","Param40","Param41","Param42","Param43","Param59","Param60","Param61","key1","key2","key3","key4","key5","key6","key7","key8","key9","key10","key11","key12","TongueOut","CheeckPuff","Mouthpucker","MouthFunnel","MouthX","BrowInnerUp","JawOpen","ParamBreath"]},"bw_shy":{"ms":8000,"loop":false,"ids":["AngleX","ParamEyeBallX","ParamEyeLOpen","ParamEyeROpen","REyeSquint","BrowInnerUp","ParamEyeBallY","EyeSmile","TongueOut","PositionZ","PositionX","AngleY","CheeckPuff","key7","ParamMouthForm","ParamBrowLY","ParamBrowRY","PositionY","Movement2","Param62","Param59","Param60","Movement1","Movement3","Param36","Param39","Param40","Param41","Param42","Param43","Param61","key1","key2","key3","key4","key5","key6","key8","key9","key10","key11","key12","Mouthpucker","MouthFunnel","MouthX","JawOpen","LeanInput","ParamBreath","AngleZ","ShockR","ShockL","Param11","SweatBurst1","SweatBurst2","SweatBurst3","SweatBurst4","Divider22","Param31","Param32","Param27","Param28","RCry","Param29","Param30","LCry","Startwinkle","Param55","Param56","Param58","Param57","ParamMouthOpenY","MouthPuckerWiden","LEyeSquint","Paramshuiyin7"]},"bw_gentle":{"ms":2600,"loop":false,"ids":["BrowInnerUp","ParamBrowLY","ParamBrowRY","ParamEyeBallX","ParamEyeBallY","EyeSmile","ParamMouthForm","Movement2","Param62","Param59","Param60","PositionZ","PositionX","AngleZ","AngleX","key7","ParamEyeROpen","ParamEyeLOpen","TongueOut","AngleY","Movement1","Movement3","Param36","Param39","Param40","Param41","Param42","Param43","Param61","key1","key2","key3","key4","key5","key6","key8","key9","key10","key11","key12","CheeckPuff","Mouthpucker","MouthFunnel","MouthX","JawOpen","LeanInput","ParamBreath","PositionY","ShockR","ShockL","Param11","SweatBurst1","SweatBurst2","SweatBurst3","SweatBurst4","Divider22","Param31","Param32","Param27","Param28","RCry","Param29","Param30","LCry","Startwinkle","Param55","Param56","Param58","Param57","ParamMouthOpenY","MouthPuckerWiden","LEyeSquint","REyeSquint","Paramshuiyin7"]},"bw_surprise":{"ms":3570,"loop":false,"ids":["PositionY","CheeckPuff","ParamEyeLOpen","ParamEyeROpen","ParamEyeBallX","ParamEyeBallY","MouthFunnel","ParamMouthOpenY","Movement2","Param62","Param59","Param60","PositionZ","PositionX","AngleZ","EyeSmile","TongueOut","key7","Movement1","Movement3","Param36","Param39","Param40","Param41","Param42","Param43","Param61","key1","key2","key3","key4","key5","key6","key8","key9","key10","key11","key12","Mouthpucker","MouthX","BrowInnerUp","JawOpen","LeanInput","ParamBreath","AngleX","AngleY","ParamBrowLY","ParamBrowRY","ParamMouthForm","ShockR","ShockL","Param11","SweatBurst1","SweatBurst2","SweatBurst3","SweatBurst4","Divider22","Param31","Param32","Param27","Param28","RCry","Param29","Param30","LCry","Startwinkle","Param55","Param56","Param58","Param57","MouthPuckerWiden","LEyeSquint","REyeSquint","Paramshuiyin7"]},"bw_naughty":{"ms":15000,"loop":false,"ids":["Movement3","Param40","Param39","Param41","key11","ParamBrowLY","ParamBrowRY","ParamMouthForm","ParamMouthOpenY","AngleZ","AngleX","AngleY","PositionY","PositionX","PositionZ","Param36","LeanInput","ParamEyeLOpen","EyeSmile","TongueOut","key5","ParamEyeROpen","ParamEyeBallX","ParamEyeBallY","Movement1","Movement2","Param42","Param43","Param59","Param60","Param61","Param62","key1","key2","key3","key4","key6","key7","key8","key9","key10","key12","CheeckPuff","Mouthpucker","MouthFunnel","MouthX","BrowInnerUp","JawOpen","ParamBreath"]},"bw_love":{"ms":16000,"loop":false,"ids":["Movement1","Movement2","Movement3","Param36","Param39","Param40","Param41","Param42","Param43","Param59","Param60","Param61","Param62","key1","key2","key3","key4","key5","key6","key7","key8","key9","key10","key11","key12","EyeSmile","ParamEyeLOpen","ParamEyeROpen","ParamEyeBallX","ParamEyeBallY","ParamBrowLY","ParamBrowRY","BrowInnerUp","LEyeSquint","REyeSquint","ParamMouthForm","ParamMouthOpenY","JawOpen","TongueOut","CheeckPuff","Mouthpucker","MouthFunnel","MouthX","MouthPuckerWiden","Paramshuiyin7","AngleX","AngleY","AngleZ","PositionX","PositionY","PositionZ","LeanInput","ParamBreath","ShockR","ShockL","Param11","SweatBurst1","SweatBurst2","SweatBurst3","SweatBurst4","Divider22","Param31","Param32","Param27","Param28","RCry","Param29","Param30","LCry","Startwinkle","Param55","Param56","Param58","Param57"]},"bw_angry1":{"ms":16000,"loop":false,"ids":["Movement1","Movement2","Movement3","Param36","Param39","Param40","Param41","Param42","Param43","Param59","Param60","Param61","Param62","key1","key2","key3","key4","key5","key6","key7","key8","key9","key10","key11","key12","EyeSmile","ParamEyeLOpen","ParamEyeROpen","ParamEyeBallX","ParamEyeBallY","ParamBrowLY","ParamBrowRY","BrowInnerUp","LEyeSquint","REyeSquint","ParamMouthForm","ParamMouthOpenY","JawOpen","TongueOut","CheeckPuff","Mouthpucker","MouthFunnel","MouthX","MouthPuckerWiden","Paramshuiyin7","AngleX","AngleY","AngleZ","PositionX","PositionY","PositionZ","LeanInput","ParamBreath","ShockR","ShockL","Param11","SweatBurst1","SweatBurst2","SweatBurst3","SweatBurst4","Divider22","Param31","Param32","Param27","Param28","RCry","Param29","Param30","LCry","Startwinkle","Param55","Param56","Param58","Param57"]},"bw_angry2":{"ms":16000,"loop":false,"ids":["Movement1","Movement2","Movement3","Param36","Param39","Param40","Param41","Param42","Param43","Param59","Param60","Param61","Param62","key1","key2","key3","key4","key5","key6","key7","key8","key9","key10","key11","key12","EyeSmile","ParamEyeLOpen","ParamEyeROpen","ParamEyeBallX","ParamEyeBallY","ParamBrowLY","ParamBrowRY","BrowInnerUp","LEyeSquint","REyeSquint","ParamMouthForm","ParamMouthOpenY","JawOpen","TongueOut","CheeckPuff","Mouthpucker","MouthFunnel","MouthX","MouthPuckerWiden","Paramshuiyin7","AngleX","AngleY","AngleZ","PositionX","PositionY","PositionZ","LeanInput","ParamBreath","ShockR","ShockL","Param11","SweatBurst1","SweatBurst2","SweatBurst3","SweatBurst4","Divider22","Param31","Param32","Param27","Param28","RCry","Param29","Param30","LCry","Startwinkle","Param55","Param56","Param58","Param57"]},"bw_angry3":{"ms":16000,"loop":false,"ids":["Movement1","Movement2","Movement3","Param36","Param39","Param40","Param41","Param42","Param43","Param59","Param60","Param61","Param62","key1","key2","key3","key4","key5","key6","key7","key8","key9","key10","key11","key12","EyeSmile","ParamEyeLOpen","ParamEyeROpen","ParamEyeBallX","ParamEyeBallY","ParamBrowLY","ParamBrowRY","BrowInnerUp","LEyeSquint","REyeSquint","ParamMouthForm","ParamMouthOpenY","JawOpen","TongueOut","CheeckPuff","Mouthpucker","MouthFunnel","MouthX","MouthPuckerWiden","Paramshuiyin7","AngleX","AngleY","AngleZ","PositionX","PositionY","PositionZ","LeanInput","ParamBreath","ShockR","ShockL","Param11","SweatBurst1","SweatBurst2","SweatBurst3","SweatBurst4","Divider22","Param31","Param32","Param27","Param28","RCry","Param29","Param30","LCry","Startwinkle","Param55","Param56","Param58","Param57"]},"bw_sad":{"ms":16000,"loop":false,"ids":["Movement1","Movement2","Movement3","Param36","Param39","Param40","Param41","Param42","Param43","Param59","Param60","Param61","Param62","key1","key2","key3","key4","key5","key6","key7","key8","key9","key10","key11","key12","EyeSmile","ParamEyeLOpen","ParamEyeROpen","ParamEyeBallX","ParamEyeBallY","ParamBrowLY","ParamBrowRY","BrowInnerUp","LEyeSquint","REyeSquint","ParamMouthForm","ParamMouthOpenY","JawOpen","TongueOut","CheeckPuff","Mouthpucker","MouthFunnel","MouthX","MouthPuckerWiden","Paramshuiyin7","AngleX","AngleY","AngleZ","PositionX","PositionY","PositionZ","LeanInput","ParamBreath","ShockR","ShockL","Param11","SweatBurst1","SweatBurst2","SweatBurst3","SweatBurst4","Divider22","Param31","Param32","Param27","Param28","RCry","Param29","Param30","LCry","Startwinkle","Param55","Param56","Param58","Param57"]},"bw_awk1":{"ms":16000,"loop":false,"ids":["Movement1","Movement2","Movement3","Param36","Param39","Param40","Param41","Param42","Param43","Param59","Param60","Param61","Param62","key1","key2","key3","key4","key5","key6","key7","key8","key9","key10","key11","key12","EyeSmile","ParamEyeLOpen","ParamEyeROpen","ParamEyeBallX","ParamEyeBallY","ParamBrowLY","ParamBrowRY","BrowInnerUp","LEyeSquint","REyeSquint","ParamMouthForm","ParamMouthOpenY","JawOpen","TongueOut","CheeckPuff","Mouthpucker","MouthFunnel","MouthX","MouthPuckerWiden","Paramshuiyin7","AngleX","AngleY","AngleZ","PositionX","PositionY","PositionZ","LeanInput","ParamBreath","ShockR","ShockL","Param11","SweatBurst1","SweatBurst2","SweatBurst3","SweatBurst4","Divider22","Param31","Param32","Param27","Param28","RCry","Param29","Param30","LCry","Startwinkle","Param55","Param56","Param58","Param57"]},"bw_awk2":{"ms":16000,"loop":false,"ids":["Movement1","Movement2","Movement3","Param36","Param39","Param40","Param41","Param42","Param43","Param59","Param60","Param61","Param62","key1","key2","key3","key4","key5","key6","key7","key8","key9","key10","key11","key12","EyeSmile","ParamEyeLOpen","ParamEyeROpen","ParamEyeBallX","ParamEyeBallY","ParamBrowLY","ParamBrowRY","BrowInnerUp","LEyeSquint","REyeSquint","ParamMouthForm","ParamMouthOpenY","JawOpen","TongueOut","CheeckPuff","Mouthpucker","MouthFunnel","MouthX","MouthPuckerWiden","Paramshuiyin7","AngleX","AngleY","AngleZ","PositionX","PositionY","PositionZ","LeanInput","ParamBreath","ShockR","ShockL","Param11","SweatBurst1","SweatBurst2","SweatBurst3","SweatBurst4","Divider22","Param31","Param32","Param27","Param28","RCry","Param29","Param30","LCry","Startwinkle","Param55","Param56","Param58","Param57"]},"bw_pat":{"ms":3720,"loop":false,"ids":["PositionZ","AngleZ","AngleX","LeanInput","AngleY","key7","Param36","Param62","EyeSmile","ParamEyeROpen","ParamEyeLOpen","Movement1","Movement2","Movement3","Param39","Param40","Param41","Param42","Param43","Param59","Param60","Param61","key1","key2","key3","key4","key5","key6","key8","key9","key10","key11","key12","TongueOut","CheeckPuff","Mouthpucker","MouthFunnel","MouthX","BrowInnerUp","JawOpen","ParamBreath"]},"bw_talk_chest":{"ms":24000,"loop":false,"ids":["Movement1","Movement2","Movement3","Param36","Param39","Param40","Param41","Param42","Param43","Param59","Param60","Param61","Param62","key1","key2","key3","key4","key5","key6","key7","key8","key9","key10","key11","key12","EyeSmile","ParamEyeLOpen","ParamEyeROpen","ParamEyeBallX","ParamEyeBallY","ParamBrowLY","ParamBrowRY","BrowInnerUp","LEyeSquint","REyeSquint","ParamMouthForm","ParamMouthOpenY","JawOpen","TongueOut","CheeckPuff","Mouthpucker","MouthFunnel","MouthX","MouthPuckerWiden","Paramshuiyin7","AngleX","AngleY","AngleZ","PositionX","PositionY","PositionZ","LeanInput","ParamBreath","ShockR","ShockL","Param11","SweatBurst1","SweatBurst2","SweatBurst3","SweatBurst4","Divider22","Param31","Param32","Param27","Param28","RCry","Param29","Param30","LCry","Startwinkle","Param55","Param56","Param58","Param57"]},"bw_talk_grass":{"ms":24000,"loop":false,"ids":["Movement1","Movement2","Movement3","Param36","Param39","Param40","Param41","Param42","Param43","Param59","Param60","Param61","Param62","key1","key2","key3","key4","key5","key6","key7","key8","key9","key10","key11","key12","EyeSmile","ParamEyeLOpen","ParamEyeROpen","ParamEyeBallX","ParamEyeBallY","ParamBrowLY","ParamBrowRY","BrowInnerUp","LEyeSquint","REyeSquint","ParamMouthForm","ParamMouthOpenY","JawOpen","TongueOut","CheeckPuff","Mouthpucker","MouthFunnel","MouthX","MouthPuckerWiden","Paramshuiyin7","AngleX","AngleY","AngleZ","PositionX","PositionY","PositionZ","LeanInput","ParamBreath","ShockR","ShockL","Param11","SweatBurst1","SweatBurst2","SweatBurst3","SweatBurst4","Divider22","Param31","Param32","Param27","Param28","RCry","Param29","Param30","LCry","Startwinkle","Param55","Param56","Param58","Param57"]},"bw_talk_finger":{"ms":24000,"loop":false,"ids":["Movement1","Movement2","Movement3","Param36","Param39","Param40","Param41","Param42","Param43","Param59","Param60","Param61","Param62","key1","key2","key3","key4","key5","key6","key7","key8","key9","key10","key11","key12","EyeSmile","ParamEyeLOpen","ParamEyeROpen","ParamEyeBallX","ParamEyeBallY","ParamBrowLY","ParamBrowRY","BrowInnerUp","LEyeSquint","REyeSquint","ParamMouthForm","ParamMouthOpenY","JawOpen","TongueOut","CheeckPuff","Mouthpucker","MouthFunnel","MouthX","MouthPuckerWiden","Paramshuiyin7","AngleX","AngleY","AngleZ","PositionX","PositionY","PositionZ","LeanInput","ParamBreath","ShockR","ShockL","Param11","SweatBurst1","SweatBurst2","SweatBurst3","SweatBurst4","Divider22","Param31","Param32","Param27","Param28","RCry","Param29","Param30","LCry","Startwinkle","Param55","Param56","Param58","Param57"]},"bw_idle60":{"ms":60000,"loop":true,"ids":["AngleX","AngleY","AngleZ","PositionX","PositionZ","PositionY","LeanInput","ParamBreath","ParamEyeLOpen","ParamEyeROpen","ParamEyeBallX","ParamEyeBallY","ParamBrowLY","ParamBrowRY","ParamMouthForm","Mouthpucker","CheeckPuff","EyeSmile","ShockR","ShockL","Param11","SweatBurst1","SweatBurst2","SweatBurst3","SweatBurst4","Divider22","Param31","Param32","Param27","Param28","RCry","Param29","Param30","LCry","Startwinkle","Param55","Param56","Param58","Param57","Movement1","Movement2","Movement3","Param36","Param39","Param40","Param41","Param42","Param43","Param59","Param60","Param61","Param62","key1","key2","key3","key4","key5","key6","key7","key8","key9","key10","key11","key12","JawOpen","ParamMouthOpenY","TongueOut","MouthFunnel","MouthX","MouthPuckerWiden","BrowInnerUp","LEyeSquint","REyeSquint","Paramshuiyin7"]}};
const UNTRACKED_IDS=['key3','key8','Movement1','AngleX','AngleY','AngleZ','PositionX','PositionY','PositionZ','ParamEyeROpen','ParamEyeLOpen','ParamMouthForm','ParamMouthOpenY','JawOpen','Param11','Param55','Param56','Param58','Param57','Param36','Param39','Param41','Param42','Param43'];
let AUTHOR_IDS=UNTRACKED_IDS,authorT=0,authorW=0;
const MOUTH_KEEP=new Set(['ParamMouthOpenY','JawOpen','ParamA','ParamI','ParamU','ParamE','ParamO']);
/* —— 尾巴：物理算完以后再叠一层摆动（6 节链，节节相位滞后 = 一条波）——
   amp 摆幅（占 ±45° 的比例）、f 频率 Hz、bias 整体偏向、mute 压掉多少物理、jit 乱甩 */
const TAIL=['Param_Angle_Rotation84','Param_Angle_Rotation85','Param_Angle_Rotation86','Param_Angle_Rotation87','Param_Angle_Rotation88','Param_Angle_Rotation89'];
const TAIL_CALM={amp:.07,f:.35,bias:0,mute:0,jit:0};
const tl={...TAIL_CALM},tlT={...TAIL_CALM};let tailPh=0,whip=0,whipDir=1,tailIdx=[];
/* —— 情绪：一句话 → 表情 + 眼神 + 头 + 尾巴，说话时上脸，说完 2.5 秒淡回 ——
   词表参考 tequed232/DSH-L2D 的 emotion-router（MIT），按这个模型的表情重排 */
const MOODS={
  开心:{fx:[],smile:.8,brow:.25,head:{y:4},tail:{amp:.42,f:2.3,bias:.1,mute:.5},face:'笑',pose:'背手',
    beat:{ms:2400,tracks:{PositionY:[[0,0],[.14,12],[.28,0],[.44,9],[.6,0],[1,0]],AngleZ:[[0,0],[.2,-14],[.5,11],[.8,-6],[1,0]],PositionZ:[[0,0],[.2,-10],[.5,8],[.8,-4],[1,0]],ParamMouthOpenY:[[0,0],[.15,.45],[.7,.35],[1,0]]}}},
  喜欢:{fx:['heart','blush'],smile:.9,head:{z:5},tail:{amp:.34,f:1.7,bias:.15,mute:.5},pose:'抚胸',
    beat:{ms:2800,tracks:{AngleZ:[[0,0],[.25,12],[.55,-6],[.85,9],[1,0]],PositionZ:[[0,0],[.25,8],[.55,-5],[.85,6],[1,0]],AngleY:[[0,0],[.3,5],[1,0]]}}},
  得意:{fx:['smug'],smile:.3,head:{y:6,z:-5},tail:{amp:.26,f:.7,bias:.25,mute:.4},pose:'背手',
    beat:{ms:2200,tracks:{AngleY:[[0,0],[.25,13],[.8,12],[1,0]],AngleZ:[[0,0],[.25,-10],[.8,-9],[1,0]],AngleX:[[0,0],[.25,-8],[.8,-6],[1,0]]}}},
  害羞:{fx:['blush'],smile:.45,look:[-.6,-.5],head:{z:6,y:-3},tail:{amp:.12,f:1.3,bias:-.3,mute:.5},pose:'抚胸',
    beat:{ms:3200,tracks:{AngleX:[[0,0],[.15,-22],[.55,-20],[.8,-9],[1,0]],PositionZ:[[0,0],[.2,-10],[.8,-6],[1,0]],AngleY:[[0,0],[.2,-8],[1,0]],
      ParamEyeBallX:[[0,0],[.15,-.8],[.5,-.7],[.65,.6],[1,0]],TongueOut:[[0,0],[.62,0],[.7,.6],[.82,.6],[.9,0],[1,0]]}}},
  温柔:{fx:['blush'],smile:.55,brow:-.15,head:{z:-8,y:-2},tail:{amp:.16,f:.8,bias:0,mute:.4},pose:'抚胸',
    beat:{ms:3000,tracks:{AngleZ:[[0,0],[.35,-12],[1,-8]],AngleX:[[0,0],[.35,8],[1,5]],PositionZ:[[0,0],[.35,-8],[1,-5]],BrowInnerUp:[[0,0],[.3,.7],[1,.5]],EyeSmile:[[0,0],[.3,.4],[1,.4]]}}},
  调皮:{fx:['xx'],smile:.5,tail:{amp:.3,f:1.9,bias:.1,mute:.4},pose:'竖手指',
    beat:{ms:2800,tracks:{AngleZ:[[0,0],[.2,22],[.75,20],[1,0]],AngleX:[[0,0],[.2,8],[.5,-6],[.8,6],[1,0]],PositionX:[[0,0],[.3,8],[.6,-8],[.9,5],[1,0]],
      TongueOut:[[0,0],[.15,.9],[.8,.9],[1,0]],ParamMouthOpenY:[[0,0],[.15,.35],[.8,.35],[1,0]]}}},
  难过:{fx:['tear'],brow:-.8,eye:-.25,look:[0,-.6],head:{y:-9},tail:{amp:.04,f:.3,bias:-.55,mute:.7},
    beat:{ms:3000,tracks:{AngleY:[[0,0],[.35,-14],[1,-10]],PositionY:[[0,0],[.35,-6],[1,-4]],AngleZ:[[0,0],[.4,8],[1,6]]}}},
  生气:{fx:['gloom'],brow:-.5,puff:1,head:{y:-3},tail:{amp:.55,f:3.4,bias:0,mute:.7,jit:1},pose:'背手',
    beat:{ms:2200,tracks:{AngleZ:[[0,0],[.08,-9],[.16,9],[.24,-9],[.32,7],[.42,0],[1,0]],AngleX:[[0,0],[.5,-14],[1,-10]],PositionY:[[0,0],[.45,0],[.5,-6],[.6,0],[1,0]]}}},
  紧张:{fx:['sweat'],wide:.5,brow:.3,dart:1,tail:{amp:.13,f:5,bias:.1,mute:.6},
    beat:{ms:2200,tracks:{AngleX:[[0,0],[.15,-10],[.35,8],[.55,-6],[1,0]],PositionX:[[0,0],[.15,-5],[.35,4],[.55,-3],[1,0]],AngleY:[[0,0],[.2,-5],[1,0]]}}},
  思考:{fx:['loading'],brow:.5,look:[-.5,.7],head:{z:-5},tail:{amp:.18,f:.5,bias:0,mute:.3},pose:'竖手指',
    beat:{ms:2600,tracks:{AngleZ:[[0,0],[.3,-11],[1,-9]],AngleY:[[0,0],[.3,7],[1,6]],AngleX:[[0,0],[.3,-6],[1,-4]]}}},
  犯困:{fx:[],eye:-.55,head:{y:-7,z:4},tail:{amp:.03,f:.25,bias:-.45,mute:.7},
    beat:{ms:3400,tracks:{AngleY:[[0,0],[.5,-15],[.62,-4],[1,-9]],AngleZ:[[0,0],[.5,10],[1,7]],PositionY:[[0,0],[.5,-5],[.62,0],[1,-3]]}}},
  惊讶:{fx:[],wide:1,brow:.8,mouth:.45,tail:{amp:.08,f:6,bias:.6,mute:.8},pose:'抚胸',
    beat:{ms:1800,tracks:{PositionY:[[0,0],[.12,18],[.35,4],[1,0]],AngleZ:[[0,0],[.15,-12],[.5,5],[1,0]],PositionZ:[[0,0],[.15,-10],[.5,4],[1,0]],
      MouthFunnel:[[0,0],[.12,.8],[.7,.6],[1,0]],ParamMouthOpenY:[[0,0],[.12,.6],[.7,.45],[1,0]]}}}};
const MOOD_MOTION={开心:['bw_happy'],喜欢:['bw_love'],害羞:['bw_shy'],温柔:['bw_gentle'],调皮:['bw_naughty'],生气:['bw_angry1','bw_angry2','bw_angry3'],
  难过:['bw_sad'],紧张:['bw_awk1','bw_awk2'],惊讶:['bw_surprise']};
const MOOD_ALIAS={心动:'喜欢',尴尬:'紧张',喜悦:'开心',伤心:'难过',愤怒:'生气',困:'犯困'};
/* 情绪的身体动作登记成隐藏的成套动作（名字带 @，不进随机、不进列表） */
for(const k in MOODS)if(MOODS[k].beat)ROUTINES['@'+k]={lineChance:0,...MOODS[k].beat};
const MOOD_WORDS={
  开心:/哈哈|开心|太好了|好耶|真棒|好棒|高兴|真好|恭喜|耶[！!～~]/g,
  温柔:/别担心|没关系|慢慢来|辛苦了|乖|放心|陪着你|我在呢/g,
  调皮:/嘿嘿|略略|才不告诉你|猜猜|偷偷|嘻嘻|骗你的/g,
  喜欢:/喜欢|爱你|想你|抱抱|贴贴|最好了|❤|♡/g,
  得意:/那当然|本小姐|厉害吧|哼哼|不愧是我|小意思|交给我/g,
  害羞:/害羞|讨厌啦|人家|才没有|脸红|不好意思|羞/g,
  难过:/难过|伤心|呜+|哭|可惜|遗憾|心疼|委屈/g,
  生气:/生气|过分|不理你|气死|哼[！!]|可恶/g,
  紧张:/糟了|完了|啊这|紧张|尴尬|对不起|抱歉|慌/g,
  思考:/想想|让我看看|也许|或许|大概|思考|琢磨|嗯…|嗯\.\.\./g,
  犯困:/困|睡觉|晚安|好累|哈欠|眯一会/g,
  惊讶:/诶[？?]|欸|真的吗|什么[！!？?]|居然|竟然|！？|[!！][?？]|哇/g};
/* 先认【心情】标签（App 里让大模型在回复开头带上），没有就按词表数，都没有就平静 */
function moodOf(text){const m=/^[【\[]([^】\]]{1,4})[】\]]/.exec(text);if(m){const k=MOOD_ALIAS[m[1]]||m[1];if(MOODS[k])return k;}
  let best=null,n=0;for(const k in MOOD_WORDS){const c=(text.match(MOOD_WORDS[k])||[]).length;if(c>n){n=c;best=k;}}return best;}
let mood=null,moodW=0,moodUntil=0,dartAt=0;
function setMood(k,ms){if(!k||!MOODS[k]){return;}const M=MOODS[k];mood=k;moodUntil=clock+ms;
  for(const f of M.fx)flash(f,ms);if(M.hop)hopV=M.hop;if(M.face)after(200,()=>playFace(M.face));
  const MM=MOOD_MOTION[k];
  if(!(MM&&playBW(pick(MM),ms+600))){if(M.beat&&!reduced){if(rt&&rt.name[0]!=='@')stopRoutine();playRoutine('@'+k);}
    if(M.pose&&!(rt&&rt.r.pose)&&!authorT){setPose(M.pose);heldPose='mood';}}
  if(M.look){T.lookX=M.look[0];T.lookY=M.look[1];holdLook=clock+Math.min(ms,2500);}
  const t=M.tail||TAIL_CALM;Object.assign(tlT,t);if(!('jit' in t))tlT.jit=0;
  const el=$('moodV');if(el)el.textContent=k;}
function hearts(n=3){if(!model||reduced)return;const r=app.renderer.screen,mx=model.x,top=model.y-model.height*.5,hy=top+model.height*.16;
  for(let i=0;i<n;i++){const h=document.createElement('i');h.className='heart';h.textContent='♥';
    const side=i%2?1:-1,x=mx+side*model.width*rnd(.07,.15),y=hy+rnd(-20,20);
    h.style.left=x+'px';h.style.top=y+'px';h.style.setProperty('--dx',(side*rnd(10,40))+'px');h.style.setProperty('--s',rnd(.8,1.3));
    h.style.animationDelay=(i*120)+'ms';h.style.animationDuration=(1600/speed)+'ms';scr.appendChild(h);setTimeout(()=>h.remove(),(1800+i*120)/speed);}}
/* 跳舞：blue-whale 桌宠里那段带透明背景的视频（换了白 T 恤）。她先淡出，视频在她原位置播，播完淡回来 */
let danceOn=false;
/* 托管页不让 <video> 直接按地址拉文件，先 fetch 成 blob 再播（只取一次） */
let danceURL=null;
async function danceSrc(){if(danceURL)return danceURL;const r=await fetch(APPDIR+'dance.webm');if(!r.ok)throw new Error('跳舞视频下载失败 '+r.status);danceURL=URL.createObjectURL(await r.blob());$('dance').src=danceURL;return danceURL;}
async function dance(){if(!model||danceOn||reduced)return;
  if(!danceURL){speak('等我换个衣服……',false);try{await danceSrc();}catch(e){speak('衣服没找到……',false);return;}}stopRoutine();stopMotion();const v=$('dance'),H=model.height;
  v.style.height=(H*.9)+'px';v.style.left=model.x+'px';v.style.top=(model.y-H*.5+H*.055)+'px';
  danceOn=true;bridge('act',{k:'dance'});busy=Infinity;v.currentTime=0;v.muted=false;const pl=v.play();if(pl&&pl.catch)pl.catch(()=>{v.muted=true;v.play().catch(()=>{});});
  v.classList.add('on');stage.style.opacity=0;v.onended=endDance;v.onerror=endDance;
  after(2500,()=>{if(danceOn&&v.currentTime<.05){endDance();}});}
function endDance(){if(!danceOn)return;const v=$('dance');danceOn=false;bridge('act',{k:'danceEnd'});v.classList.remove('on');setTimeout(()=>v.pause(),200);
  stage.style.opacity=SET.show?1:0;busy=clock+1500;setMood('开心',2600);if(clock>sayBusy)after(300,()=>speak(pick(['跳完啦～','嘿嘿，好看吗？','累死鱼了……']),false));}
function whipTail(){whip=1;whipDir=Math.random()<.5?-1:1;}
/* 用户自己做的动作（blue-whale 仓库）+ 模型作者的「脱捕」：播放时把它们管的参数让出去，首尾 0.6 秒交叉淡化 */
function playMotion(g,ms,ids){if(!model||reduced)return false;if(rt)stopRoutine();if(heldPose){setPose(null);heldPose=null;}
  /* 说话手势只借手和身体，嘴归对口型管（不然字停了嘴还跟着动作曲线在动） */
  AUTHOR_IDS=/^bw_talk/.test(g)?ids.filter(k=>!MOUTH_KEEP.has(k)):ids;authorT=ms;busy=Math.max(busy,clock+ms+300);model.motion(g,0,3);return true;}
let idleMo=false;
function playBW(g,ms){const b=BW[g];idleMo=g==='bw_idle60';return b?playMotion(g,Math.min(ms||b.ms,b.ms),b.ids):false;}
function stopMotion(f=600){if(authorT>f)authorT=f;}
function playAuthor(){playMotion('untracked',13150,UNTRACKED_IDS);}
let blink=1,blinkT=0,nextBlink=2500,sacX=0,sacY=0,nextSac=1200,talk=0;
let quiet=false,covered=false,roomT=0,roomB=1,avS=1,avY=0,hideV=0,hideWas=false,dK=0,sayKind='';
let grab=false,grabK=0,grabTimer=0,gx=0,gy=0,ptrOn=false,talkLeft=0,talkOn=false,nextGesture=0;
let hop=0,hopV=0,busy=0,holdLook=0,pointerDown=false,model=null,expTimer=0,sayTok=0;

/* 她能站的范围（App 报：标题栏下面到输入框上面；换装时到面板上面），视口比例 */
let avWant={s:1,y:0,hide:0};
function avoidStep(dt){if(!model)return;const h=app.renderer.screen.height,w=app.renderer.screen.width,im=model.internalModel;
  const s0=Math.min(w/im.width,h/im.height)*S.zoom,H0=im.height*s0,py=h/2+h*S.dy,bot=py+H0/2,fT=roomT*h,fB=roomB*h-6;
  let ws=1,wy=0,wh=0;
  /* 被输入框或键盘盖住：放得下 55% 就缩小站上去（A），放不下就沉下去躲开（B）。拖着、编辑位置、换装时不管 */
  if(!editing&&!grab&&!dressing&&bot>fB+4){const fit=Math.min(1,(fB-fT)/H0);if(fit>=.55){ws=fit;wy=fB-H0*fit/2-py;}else wh=1;}
  avWant={s:ws,y:wy,hide:wh};
  const k=1-Math.exp(-dt*22);avS+=(ws-avS)*k;avY+=(wy-avY)*k;
  hideV+=(wh-hideV)*(1-Math.exp(-dt*(wh?20:10)));
  if(wh&&hideV>.9)hideWas=true;if(!wh&&hideWas&&hideV<.5){hideWas=false;hopV=-170;}
  model.alpha=1-Math.min(1,hideV*1.25);}
function layout(){if(!model)return;const w=app.renderer.screen.width,h=app.renderer.screen.height,im=model.internalModel;
  const s0=Math.min(w/im.width,h/im.height)*S.zoom;
  let sc=s0*avS*(1+.05*grabK),px=w/2+w*S.dx,py=h/2+h*S.dy+avY+hideV*46+hop;
  /* 换装：站到面板上方正中、整个露出来，头顶留 52px 给气泡 */
  if(dK>0){const fT=roomT*h+52,fB=roomB*h-14,ds=Math.min(w*.9/im.width,(fB-fT)/im.height),dx=w/2,dy=(fT+fB)/2+hop;
    sc+=(ds-sc)*dK;px+=(dx-px)*dK;py+=(dy-py)*dK;}
  model.scale.set(sc);model.position.set(Math.round(px*RES)/RES,Math.round(py*RES)/RES);placeSay(w,h);}
/* 气泡跟着头走：头顶约在模型高度 4% 处 */
function placeSay(w,h){const say=$('say');if(!say.classList.contains('on')&&!sayKind)return;
  const mh=model.height,top=model.y-mh*.5+mh*.04,side=top<64,flip=model.x>w/2;
  const x=side?model.x+(flip?-1:1)*mh*.09:model.x,y=side?top+mh*.07:top;
  const k=side?(flip?'side flip':'side'):'';if(k!==sayKind){sayKind=k||'up';say.classList.toggle('side',side);say.classList.toggle('flip',side&&flip);}
  if(!side){const bw=say.offsetWidth;let sh=0;if(x-bw/2<8)sh=8-(x-bw/2);else if(x+bw/2>w-8)sh=w-8-(x+bw/2);say.style.setProperty('--sh',sh+'px');}
  $('sayw').style.transform='translate('+Math.round(x)+'px,'+Math.round(y)+'px)';}
function hush(){sayTok++;$('say').classList.remove('on');T.mouth=0;}
new ResizeObserver(layout).observe(stage);

/* 模型主体文件以 gzip+base64 随页面带来（托管不认 .moc3 后缀），解压后拦下对 model.moc3 的请求直接交给它 */
async function mocBuffer(){if(!window.DecompressionStream)throw new Error('浏览器不支持 DecompressionStream');
  const s=atob(window.__MOC_GZ);const u=new Uint8Array(s.length);for(let i=0;i<s.length;i++)u[i]=s.charCodeAt(i);
  return new Response(new Blob([u]).stream().pipeThrough(new DecompressionStream('gzip'))).arrayBuffer();}
/* 拦下对 m/ 下文件的请求，直接给本地内容（托管不认 .moc3 后缀；本地 zip 时全部不走网络） */
function hookXHR(map){const open=XMLHttpRequest.prototype.open,send=XMLHttpRequest.prototype.send;
  XMLHttpRequest.prototype.open=function(m,u){const k=String(u).split('?')[0].replace(/^.*?\bm\//,'');this.__k=Object.prototype.hasOwnProperty.call(map,k)?k:null;return open.apply(this,arguments);};
  XMLHttpRequest.prototype.send=function(){if(!this.__k)return send.apply(this,arguments);const x=this,buf=map[x.__k];
    setTimeout(()=>{let res=buf;if(x.responseType==='json')res=JSON.parse(new TextDecoder().decode(buf));else if(x.responseType===''||x.responseType==='text')res=new TextDecoder().decode(buf);
      Object.defineProperty(x,'readyState',{value:4});Object.defineProperty(x,'status',{value:200});Object.defineProperty(x,'response',{value:res});
      if(typeof res==='string')Object.defineProperty(x,'responseText',{value:res});
      x.dispatchEvent(new Event('readystatechange'));x.dispatchEvent(new ProgressEvent('load'));x.dispatchEvent(new ProgressEvent('loadend'));},0);};}
$('ldt')&&($('ldt').textContent='正在摆好大肥鱼…');
/* 贴图：自己读成画布纹理交给 PIXI（托管页里 <img> 加载贴图会被拦） */
async function textures(){bridge('phase',{s:'读模型信息'});
  const r0=await fetch(MODEL+'model.model3.json');if(!r0.ok)throw new Error('模型信息读取失败 '+r0.status);
  const list=(await r0.json()).FileReferences.Textures,out=[],lim=texHD?4096:2048;
  for(let i=0;i<list.length;i++){/* 一张一张读、一张一张解，峰值内存小 */
    bridge('phase',{s:'贴图 '+(i+1)+'/'+list.length});
    const r=await fetch(MODEL+list[i]);if(!r.ok)throw new Error('贴图读取失败 '+r.status);
    const bm=await createImageBitmap(await r.blob());
    /* App 导入时已经把贴图缩到 4096 以内（原图 8192 五张，手机解不动）；这里只在标准画质、或漏网的大图上按比例再收一次 */
    const k=Math.min(1,lim/Math.max(bm.width,bm.height));
    const c=document.createElement('canvas');c.width=Math.max(1,Math.round(bm.width*k));c.height=Math.max(1,Math.round(bm.height*k));
    const g2=c.getContext('2d');if(!g2)throw new Error('贴图画布建不出来 '+c.width+'×'+c.height);
    g2.imageSmoothingQuality='high';g2.drawImage(bm,0,0,c.width,c.height);bm.close&&bm.close();
    const t=PIXI.Texture.from(c),bt=t.baseTexture;bt.mipmap=PIXI.MIPMAP_MODES.ON;bt.scaleMode=PIXI.SCALE_MODES.LINEAR;bt.anisotropicLevel=8;texSize=c.width;out.push(t);}
  return out;}
function hookTex(list){const pick=u=>{const m=typeof u==='string'&&/texture_0*(\d+)\.(png|webp)/.exec(u);return m?list[+m[1]]:null;};
  const from=PIXI.Texture.from,fromURL=PIXI.Texture.fromURL;
  PIXI.Texture.from=function(u){return pick(u)||from.apply(this,arguments);};
  if(fromURL)PIXI.Texture.fromURL=function(u){const t=pick(u);return t?Promise.resolve(t):fromURL.apply(this,arguments);};}
/* 部件染色（乘色/屏幕色）：0.4.0 的着色器没有，这个模型靠它把鼻尖等小部件染成肤色，不补就是黑块。
   做法同鲸伴 scripts/patch_renderer.py，公式取自 CubismWebFramework 5-r.1，只是改成运行时替换。 */
(function colorPatch(){const L=PIXI.live2d,SH=L.CubismShader_WebGL,RD=L.CubismRenderer_WebGL;if(!SH||!RD)return;
  const HELP='uniform vec4 u_multiplyColor;uniform vec4 u_screenColor;vec4 bridgeColor(vec2 uv){vec4 c=texture2D(s_texture0,uv);c.rgb*=u_multiplyColor.rgb;c.rgb=c.rgb+u_screenColor.rgb*c.a-c.rgb*u_screenColor.rgb;return c;}';
  const load=SH.prototype.loadShaderProgram;
  SH.prototype.loadShaderProgram=function(v,f){const re=/texture2D\(\s*s_texture0\s*,\s*v_texCoord\s*\)\s*\*\s*u_baseColor/g;
    if(re.test(f)){f=f.replace(re,'bridgeColor(v_texCoord) * u_baseColor').replace('void main()',HELP+'void main()');}return load.call(this,v,f);};
  RD.prototype.doDrawModel=function(){this.preDraw();if(this._clippingManager!=null)this._clippingManager.setupClippingContext(this.getModel(),this);
    const M=this.getModel(),n=M.getDrawableCount(),ro=M.getDrawableRenderOrders();
    for(let i=0;i<n;++i)this._sortedDrawableIndexList[ro[i]]=i;
    for(let i=0;i<n;++i){const d=this._sortedDrawableIndexList[i];if(!M.getDrawableDynamicFlagIsVisible(d))continue;
      this.setClippingContextBufferForDraw(this._clippingManager!=null?this._clippingManager.getClippingContextListForDraw()[d]:null);
      this.setIsCulling(M.getDrawableCulling(d));this._colorIndex=d;
      this.drawMesh(M.getDrawableTextureIndices(d),M.getDrawableVertexIndexCount(d),M.getDrawableVertexCount(d),M.getDrawableVertexIndices(d),
        M.getDrawableVertices(d),M.getDrawableVertexUvs(d),M.getDrawableOpacity(d),M.getDrawableBlendMode(d),M.getDrawableInvertedMaskBit(d));}};
  const setup=SH.prototype.setupShaderProgram;
  SH.prototype.setupShaderProgram=function(r){setup.apply(this,arguments);if(r.getClippingContextBufferForMask()!=null)return;
    const gl=this.gl,prog=gl.getParameter(gl.CURRENT_PROGRAM);if(!prog)return;
    if(prog.__mc===undefined){prog.__mc=gl.getUniformLocation(prog,'u_multiplyColor');prog.__sc=gl.getUniformLocation(prog,'u_screenColor');}
    const dr=r.getModel().getModel().drawables,i=(r._colorIndex|0)*4;
    if(prog.__mc&&dr.multiplyColors)gl.uniform4fv(prog.__mc,dr.multiplyColors.subarray(i,i+4));
    if(prog.__sc&&dr.screenColors)gl.uniform4fv(prog.__sc,dr.screenColors.subarray(i,i+4));};})();
textures().then(tx=>{hookTex(tx);bridge('phase',{s:'搭模型'});return PIXI.live2d.Live2DModel.from(MODEL+'model.model3.json',{autoInteract:false,motionPreload:'ALL'}).then(m=>{
  model=m;m.anchor.set(.5,.5);app.stage.addChild(m);layout();
  /* 这个模型按 VTube Studio 绑定：头是 AngleX/Y/Z，身体跟随是 PositionX/Y/Z，物理以它们为输入。
     在动作更新后、物理计算前写入（afterMotionUpdate），头发耳朵尾巴才会跟着甩。眨眼自己做。 */
  m.internalModel.eyeBlink=undefined;
  /* 该模型网格叠了多层遮罩，遮罩缓冲开大，边缘才裁得干净（鲸伴同样设 2048） */
  /* 遮罩：这个模型有 49 组遮罩，框架把它们 4×4 分格塞进一张遮罩图，每组只分到 1/16。
     2048 时每组 512px，头发衣服边缘发毛、一动就闪；4096 时每组 1024px。 */
  try{m.internalModel.renderer.setClippingMaskBufferSize(maskSize);}catch(_){}
  /* 物理：0.4.0 按每帧实际间隔算，掉帧时步长忽大忽小，头发细线会抖。把步长平滑并限在 1/120–1/30 秒 */
  try{const ph=m.internalModel.physics,ev=ph.evaluate.bind(ph);let sdt=1/60;
    ph.evaluate=(model,dt)=>{dt=clamp(dt,1/120,1/30);sdt+=(dt-sdt)*.25;ev(model,sdt);};}catch(_){}
  const cm=m.internalModel.coreModel,info={};
  const range=k=>{if(!(k in info)){const i=cm.getParameterIndex(k);info[k]=i<0?null:[cm.getParameterMinimumValue(i),cm.getParameterMaximumValue(i),cm.getParameterDefaultValue(i)];}return info[k];};
  /* 尾巴有哪些部件：关掉尾巴开关前后对比，消失的就是（用于点击判定） */
  try{const core=cm.getModel(),d=core.drawables,P=core.parameters,i5=Array.from(P.ids).indexOf('Clothes5');
    if(i5>=0){const vis=()=>{core.update();return Array.from({length:d.count},(_,i)=>(d.dynamicFlags[i]&1)&&d.opacities[i]>.01);};
      const a=vis(),dv=P.values[i5];P.values[i5]=0;const b=vis();P.values[i5]=dv;core.update();tailIdx=a.map((v,i)=>v&&!b[i]?i:-1).filter(i=>i>=0);}}catch(_){}
  m.internalModel.on('beforeModelUpdate',()=>{if(reduced)return;
    const amp=(tl.amp+whip*.9)*45,b=tl.bias*30;
    for(let i=0;i<6;i++){const id=TAIL[i],r=range(id);if(!r)continue;const phys=cm.getParameterValueById(id);
      const jit=tl.jit?Math.sin(tailPh*1.7+i)*Math.sin(tailPh*.37)*tl.jit*.5:0;
      const wave=Math.sin(tailPh-i*.6)*(.45+.11*i)*amp*(whip?whipDir:1)*(1+jit)+b*(.5+.1*i);
      cm.setParameterValueById(id,clamp(phys*(1-tl.mute)+wave,r[0],r[1]));}});
  m.internalModel.on('afterMotionUpdate',()=>{
    const open=(1+P.wide*.3)*blink*(1-P.smile*.35);
    const OUT={AngleX:H.x,AngleY:H.y,AngleZ:H.z,PositionX:H.bx,PositionY:H.by,PositionZ:H.bz,
      ParamEyeBallX:P.eyeX,ParamEyeBallY:P.eyeY,ParamEyeLOpen:open,ParamEyeROpen:open,
      EyeSmile:P.smile,ParamMouthForm:-.1+P.smile*1.1,MouthSmile_L:P.smile*.6,MouthSmile_R:P.smile*.6,
      ParamBrowLY:P.brow,ParamBrowRY:P.brow,BrowInnerUp:Math.max(0,-P.brow),
      ParamMouthOpenY:P.mouth,JawOpen:P.mouth*.55,ParamA:V.a*P.mouth,ParamI:V.i*P.mouth,ParamU:V.u*P.mouth,ParamE:V.e*P.mouth,ParamO:V.o*P.mouth};
    for(const k in ADD){const r=range(k);if(!r)continue;OUT[k]=(k in OUT?OUT[k]:r[2])+ADD[k];}
    for(const k in HOLD)OUT[k]=HOLD[k];
    if(authorW>.001)for(const k of AUTHOR_IDS)if(k in OUT&&!(MOUTH_KEEP.has(k)&&P.mouth>.05)){OUT[k]=OUT[k]*(1-authorW)+cm.getParameterValueById(k)*authorW;}
    const authorOn=authorW>.001||authorT>0;if(authorOn)AUTHOR_IDS.forEach(k=>touched.add(k));
    for(const k of touched)if(!(k in OUT)&&!(authorOn&&AUTHOR_IDS.includes(k))){const r=range(k);if(r)cm.setParameterValueById(k,r[2]);touched.delete(k);}
    for(const k in OUT){const r=range(k);if(!r)continue;cm.setParameterValueById(k,clamp(OUT[k],r[0],r[1]));touched.add(k);}
    const ph=m.internalModel.physics;if(ph&&ph._options){const t=clock/1000;ph._options.wind.x=(Math.sin(t*.9)*.08+Math.sin(t*2.3)*.04)*(1+gust*2);ph._options.wind.y=0;}
  });
  load.classList.add('done');applyLayout();Object.assign(S,St);bridge('ready',{mask:maskSize,tex:texSize});after(300,()=>{hopV=-200;});
});}).catch(e=>fail(e&&e.stack?String(e.stack).split('\n').slice(0,5).join('\n'):String(e)));

/* 省电：切到后台、或设置页整页盖住她时停帧 */
let paused=false;
function syncRun(){const hidden=document.hidden||paused||covered;if(hidden&&app.ticker.started)app.ticker.stop();else if(!hidden&&!app.ticker.started)app.ticker.start();}
document.addEventListener('visibilitychange',syncRun);
/* 每帧 */
app.ticker.add(()=>{
  const raw=Math.min(50,app.ticker.elapsedMS);const dt=raw*speed/1000;clock+=raw*speed;app.ticker.speed=speed;
  for(let i=tasks.length-1;i>=0;i--)if(tasks[i].at<=clock){const f=tasks[i].fn;tasks.splice(i,1);f();}
  if(!pointerDown&&clock>holdLook){T.lookX=0;T.lookY=0;}
  /* 眼珠 = 视线 + 小跳动（真人的眼睛不会一直定住） */
  if(clock>nextSac){nextSac=clock+rnd(700,2600);sacX=rnd(-.22,.22);sacY=rnd(-.12,.12);}
  T.eyeX=T.lookX*1.05+sacX;T.eyeY=T.lookY*1.05+sacY;
  for(const k in P)P[k]+=(T[k]-P[k])*(1-Math.exp(-dt*RATE[k]));
  for(const k in V)V[k]+=(VT[k]-V[k])*(1-Math.exp(-dt*22));
  /* 眨眼：合 70ms、睁 130ms，偶尔连眨 */
  if(blinkT>0){blinkT+=dt*1000;blink=blinkT<70?1-blinkT/70:blinkT<200?(blinkT-70)/130:1;if(blinkT>=200){blinkT=0;blink=1;}}
  else if(clock>nextBlink&&!reduced){blinkT=.001;nextBlink=clock+(Math.random()<.18?260:rnd(2200,5600));}
  /* 待机的自然晃动：几条不成倍数的慢正弦叠起来，看不出周期 */
  const t=clock/1000,q=reduced?0:1;
  const nx=q*(Math.sin(t*.31)*3.2+Math.sin(t*.73+1.3)*1.6),ny=q*(Math.sin(t*.27+.7)*1.8+Math.sin(t*.61+2.1)*1),nz=q*(Math.sin(t*.23+2.4)*3+Math.sin(t*.57)*1.2);
  talk=P.mouth>.05?talk+dt*9:talk*.9;
  const tx=P.lookX*24+nx+Math.sin(talk*.7)*1.5*P.mouth, ty=P.lookY*16+ny-P.nod*14+Math.sin(talk)*2.4*P.mouth, tz=-P.lookX*P.lookY*10+nz+P.smile*3;
  const k1=1-Math.exp(-dt*5),k2=1-Math.exp(-dt*5);
  H.x1+=(tx-H.x1)*k1;H.y1+=(ty-H.y1)*k1;H.z1+=(tz-H.z1)*k1;H.x+=(H.x1-H.x)*k2;H.y+=(H.y1*1.5-H.y)*k2;H.z+=(H.z1-H.z)*k2;
  /* 身体比头慢半拍、幅度小 */
  const kb=1-Math.exp(-dt*2.2);H.bx+=(H.x*.55-H.bx)*kb;H.by+=(H.y*.4-hop*.08-H.by)*kb;H.bz+=(H.z*.6-H.bz)*kb;
  hopV+=(-260*hop-16*hopV)*dt;hop+=hopV*dt;if(Math.abs(hop)<.05&&Math.abs(hopV)<.5){hop=0;hopV=0;}
  compose(dt);
  if(!dragging){const ks=1-Math.exp(-dt*6);for(const k in S){const d=St[k]-S[k];S[k]=Math.abs(d)<1e-4?St[k]:S[k]+d*ks;}}
  grabK+=((grab?1:0)-grabK)*(1-Math.exp(-dt*14));
  dK+=((dressing?1:0)-dK)*(1-Math.exp(-dt*(dressing?7:11)));if(Math.abs(dK-(dressing?1:0))<1e-3)dK=dressing?1:0;
  avoidStep(dt);
  if(model)layout();
});
function compose(dt){ADD={};HOLD={};const ms=dt*1000;
  if(authorT>0){authorT-=ms;if(authorT<=0){authorT=0;try{model.internalModel.motionManager.stopAllMotions();model.motion('Idle',0,1);}catch(_){}}}
  authorW+=((authorT>600?1:authorT>0?authorT/600:0)-authorW)*(1-Math.exp(-dt*6));
  /* 表情层 */
  for(const k in FX){if(fxT[k]&&clock>fxUntil[k])fxT[k]=0;fxW[k]+=(fxT[k]-fxW[k])*(1-Math.exp(-dt*(fxT[k]?9:6)));
    if(fxW[k]>.002)for(const id in FX[k])add(id,FX[k][id]*fxW[k]);}
  bodyV+=(bodyT-bodyV)*(1-Math.exp(-dt*4));if(Math.abs(bodyV)>.002)add('Clothes2',bodyV);
  /* 手的姿势层：默认手一直钉住，切姿势时平滑过渡 */
  poseW+=(poseT-poseW)*(1-Math.exp(-dt*5));if(poseW<.003&&!poseT)pose=null;
  const PP=pose?POSES[pose]:null;for(const k in REST)HOLD[k]=REST[k]+(PP&&k in PP?(PP[k]-REST[k])*ease(clamp(poseW,0,1)):0);
  if(pose==='竖手指'&&P.mouth>.05)HOLD.Param44=Math.sin(clock/1000*5.5)*.2*poseW;
  /* 成套动作层 */
  if(rt){rt.t+=ms;const r=rt.r;if(rt.t>=r.ms)stopRoutine();else{const p=rt.t/r.ms,k=Math.min(1,rt.t/450,(r.ms-rt.t)/450);
    for(const id in r.tracks)add(id,sample(r.tracks[id],p)*k);
    for(const id in r.holds||{})HOLD[id]=(id in HOLD?HOLD[id]:0)*(1-k)+sample(r.holds[id],p)*k;
    if(r.eyes)add('ParamEyeBallX',sample(r.eyes,p)*k);
    if(r.mouthK)add('ParamMouthOpenY',sample(r.mouthK,p)*k);
    if(r.bob)add('PositionY',-sample(r.bob,p)*k*4);
    const mo=r.mouth;if(mo){add('ParamMouthOpenY',(mo.open||0)*k);add('MouthFunnel',(mo.funnel||0)*k);add('Mouthpucker',(mo.pucker||0)*k);add('ParamMouthForm',(mo.form||0)*k+(mo.smile||0)*k);add('MouthSmile_L',(mo.smile||0)*k);add('MouthSmile_R',(mo.smile||0)*k);}
    if(r.wakeAt&&p>r.wakeAt&&!rt.woke){rt.woke=1;T.wide=1;after(350,()=>T.wide=0);hopV=-140;}}}
  gust*=Math.exp(-dt*.5);
  /* 情绪层 */
  if(mood&&clock>moodUntil){if(heldPose==='mood'){setPose(null);heldPose=null;}mood=null;Object.assign(tlT,TAIL_CALM);const el=$('moodV');if(el)el.textContent='平静';}
  moodW+=((mood?1:0)-moodW)*(1-Math.exp(-dt*(mood?5:2.5)));
  if(moodW>.005){const M=MOODS[mood]||{},w=moodW;
    if(M.smile){add('EyeSmile',M.smile*w*.7);add('MouthSmile_L',M.smile*w*.6);add('MouthSmile_R',M.smile*w*.6);add('ParamMouthForm',M.smile*w*.6);}
    if(M.brow){add('ParamBrowLY',M.brow*w);add('ParamBrowRY',M.brow*w);if(M.brow<0)add('BrowInnerUp',-M.brow*w);}
    if(M.eye){add('ParamEyeLOpen',M.eye*w);add('ParamEyeROpen',M.eye*w);}
    if(M.wide){add('ParamEyeLOpen',M.wide*.3*w);add('ParamEyeROpen',M.wide*.3*w);}
    if(M.puff)add('CheeckPuff',M.puff*w);
    if(M.mouth&&P.mouth<.05)add('ParamMouthOpenY',M.mouth*w);
    if(M.head){add('AngleY',(M.head.y||0)*w);add('AngleZ',(M.head.z||0)*w);}
    if(M.dart&&clock>dartAt){dartAt=clock+rnd(250,600);sacX=rnd(-.6,.6);sacY=rnd(-.3,.3);}}
  /* 尾巴参数平滑过渡到目标，换情绪时不突变 */
  const kt=1-Math.exp(-dt*3);for(const k in tl)tl[k]+=(tlT[k]-tl[k])*kt;
  tailPh+=dt*Math.PI*2*(tl.f+whip*2.6);whip*=Math.exp(-dt*2.2);if(whip<.01)whip=0;
  /* 小表情层 */
  if(face){face.t+=ms;const f=face.f,d=f.dur+220;if(face.t>d)face=null;else{const k=Math.min(1,face.t/140,(d-face.t)/220);
    for(const id in f.add)add(id,f.add[id]*k);if(f.wink)add(f.wink,-k*1.1);}}
  /* 摸头：头往手指那边偏、眯眼笑 */
  if(petting>0){add('ParamAngleZ',petX*8*petting);add('ParamBodyAngleZ',petX*3*petting);}
  /* 转晕：头画圈 */
  if(dizzyT>0){dizzyT-=ms;const q=clamp(dizzyT/2600,0,1),w=clock/1000*7;add('ParamAngleZ',Math.sin(w)*9*q);add('ParamAngleX',Math.cos(w)*7*q);}
  /* 自己找事做：没在说话、没被碰的时候 */
  if(model&&autoIdle&&!reduced&&!pointerDown&&!rt&&!authorT&&clock>busy){
    if(clock>nextRt){nextRt=clock+(SET.idle===1?rnd(20000,35000):SET.idle===3?rnd(5000,9000):rnd(9000,17000));const r=Math.random();if(r<.12)playAuthor();else if(r<.24)playBW('bw_idle60');else playRoutine();}
    else if(!face&&clock>nextFace){nextFace=clock+rnd(9000,19000);playFace(Object.keys(FACES)[Math.floor(Math.random()*3)]);}}
}

/* 视线：按住滑动跟手 */
function lookAt(e){if(!model)return;const r=stage.getBoundingClientRect();const head=r.top+r.height*.32;
  T.lookX=Math.max(-1,Math.min(1,(e.clientX-(r.left+r.width/2))/(r.width*.45)));
  T.lookY=Math.max(-1,Math.min(1,-(e.clientY-head)/(r.height*.45)));holdLook=clock+1200;}
/* 按下时记住按在哪儿；在头上来回滑 = 摸头；在身上按住不动 = 凑近摇摆 */
let downAt=null,petX=0,lastX=0,petMoved=0,pressTimer=0;
/* 点到的是不是尾巴：拿尾巴各部件当前的三角形逐个判 */
function onTail(q){if(!tailIdx.length)return false;const core=model.internalModel.coreModel.getModel(),d=core.drawables,ci=core.canvasinfo;
  const x=(q.x-ci.CanvasOriginX)/ci.PixelsPerUnit,y=(ci.CanvasOriginY-q.y)/ci.PixelsPerUnit;
  for(const i of tailIdx){if(!((d.dynamicFlags[i]&1)&&d.opacities[i]>.01))continue;const v=d.vertexPositions[i],ix=d.indices[i];
    for(let t=0;t<ix.length;t+=3){const a=ix[t]*2,b=ix[t+1]*2,c=ix[t+2]*2;
      const d1=(x-v[b])*(v[a+1]-v[b+1])-(v[a]-v[b])*(y-v[b+1]),d2=(x-v[c])*(v[b+1]-v[c+1])-(v[b]-v[c])*(y-v[c+1]),d3=(x-v[a])*(v[c+1]-v[a+1])-(v[c]-v[a])*(y-v[a+1]);
      if(!((d1<0||d2<0||d3<0)&&(d1>0||d2>0||d3>0)))return true;}}return false;}
function region(e){if(!model)return null;const r=stage.getBoundingClientRect(),sx=r.width/app.renderer.screen.width;
  const g=new PIXI.Point((e.clientX-r.left)/sx,(e.clientY-r.top)/sx),im=model.internalModel;let u,v;
  try{const q=model.toModelPosition(g);if(onTail(q))return 'tail';u=q.x/im.originalWidth;v=q.y/im.originalHeight;}catch(_){return null;}
  if(u<0||u>1||v<.05||v>1)return null;
  /* 位置按模型画布实测：头顶 v<0.135，脸 0.135–0.26，两侧是手臂，其余身体 */
  if(v<.135)return 'head';if(v<.26&&Math.abs(u-.5)<.13)return 'face';if(v<.26)return 'head';
  if(Math.abs(u-.5)>.15&&v<.6)return u<.5?'armR':'armL';return 'body';}
/* —— 编辑模式：拖动位置、双指缩放 —— */
const ptrs=new Map();let dragging=false,pinch0=0,zoom0=1,lastTap=0;
function cur(){return LAYOUT[lay][chatOn?'chat':'idle'];}
function readout(){$('readout').textContent='当前：'+(chatOn?'聊天中':'未聊天')+' · 左右 '+S.dx.toFixed(2)+' · 上下 '+S.dy.toFixed(2)+' · 大小 '+S.zoom.toFixed(2);}
function saveLayout(){Object.assign(cur(),{zoom:St.zoom,dx:St.dx,dy:St.dy});try{localStorage.setItem('fy-layout',JSON.stringify(LAYOUT));}catch(_){}readout();}
function setEdit(on){editing=on;$('edit').classList.toggle('on',on);scr.classList.toggle('editing',on);$('bEdit').classList.toggle('on',on);
  if(on){stopRoutine();busy=Infinity;}else{busy=clock+1000;saveLayout();}}
function editDown(e){ptrs.set(e.pointerId,{x:e.clientX,y:e.clientY});scr.setPointerCapture&&scr.setPointerCapture(e.pointerId);
  if(ptrs.size===1){dragging=true;if(clock-lastTap<300){Object.assign(St,LAYOUT_DEF[lay][chatOn?'chat':'idle']);Object.assign(S,St);readout();}lastTap=clock;}
  if(ptrs.size===2){const[a,b]=[...ptrs.values()];pinch0=Math.hypot(a.x-b.x,a.y-b.y);zoom0=St.zoom;}}
function editMove(e){if(!ptrs.has(e.pointerId))return;const p=ptrs.get(e.pointerId),r=stage.getBoundingClientRect();
  if(ptrs.size===1){St.dx+=(e.clientX-p.x)/r.width;St.dy+=(e.clientY-p.y)/r.height;}
  p.x=e.clientX;p.y=e.clientY;
  if(ptrs.size===2){const[a,b]=[...ptrs.values()],d=Math.hypot(a.x-b.x,a.y-b.y);if(pinch0>0)St.zoom=clamp(zoom0*d/pinch0,.3,5);}
  St.dx=clamp(St.dx,-.7,.7);St.dy=clamp(St.dy,-.9,1.2);Object.assign(S,St);readout();}
function editUp(e){ptrs.delete(e.pointerId);if(ptrs.size<2)pinch0=0;if(!ptrs.size)dragging=false;}
scr.addEventListener('wheel',e=>{if(!editing)return;e.preventDefault();St.zoom=clamp(St.zoom*Math.exp(-e.deltaY*.0015),.3,5);Object.assign(S,St);readout();},{passive:false});
scr.addEventListener('pointerdown',e=>{if(editing){if(!e.target.closest('.tools'))editDown(e);return;}},true);
scr.addEventListener('pointermove',e=>{if(editing)editMove(e);},true);
addEventListener('pointerup',e=>{if(editing)editUp(e);},true);addEventListener('pointercancel',e=>{if(editing)editUp(e);},true);
scr.addEventListener('pointerdown',e=>{if(editing||e.target.closest('.bar,.sheet,.hd,.pg:not(.dress)'))return;if(danceOn){endDance();return;}pointerDown=true;lookAt(e);
  const rg=region(e);downAt={rg,x:e.clientX,y:e.clientY,t:clock};lastX=e.clientX;petMoved=0;
  if(rg){if(rt&&rt.name!=='挥挥手')stopRoutine();stopMotion();busy=Math.max(busy,clock+2500);}
  clearTimeout(pressTimer);if(rg==='body')pressTimer=setTimeout(()=>{if(downAt&&petMoved<12){downAt.used=1;playRoutine('凑近摇摆');}},650);});
scr.addEventListener('pointermove',e=>{if(editing)return;if(pointerDown||e.pointerType==='mouse')lookAt(e);
  if(pointerDown&&downAt&&downAt.rg==='head'){const dx=e.clientX-lastX;lastX=e.clientX;petMoved+=Math.abs(dx);
    if(petMoved>18){if(!petting){fxT.xx=1;fxUntil.xx=Infinity;fxT.blush=1;fxUntil.blush=Infinity;T.smile=.9;}
      petting=1;petX=clamp(petX+dx*.012,-1,1);busy=clock+2500;}}});
function release(){if(editing||!pointerDown)return;pointerDown=false;holdLook=clock+1200;clearTimeout(pressTimer);
  const d=downAt;downAt=null;
  if(petting){petting=0;petX=0;fxUntil.xx=clock+500;fxUntil.blush=clock+1500;after(500,()=>T.smile=0);
    bridge('act',{k:'pet'});if(Math.random()<.6&&clock>sayBusy)speak(['嘿嘿……','头发要乱啦～','再摸一下下。'][Math.floor(Math.random()*3)]);return;}
  if(d&&d.rg&&!d.used&&clock-d.t<600&&SET.touch)tap(d.rg);}
addEventListener('pointerup',release);addEventListener('pointercancel',release);

const pick=a=>a[Math.floor(Math.random()*a.length)];
const line=a=>{if(Math.random()<.7&&clock>sayBusy)after(250,()=>speak(pick(a)));};
function nod(){busy=clock+800;T.nod=1;after(160,()=>T.nod=0);after(330,()=>T.nod=.8);after(500,()=>T.nod=0);}
function pat(){busy=clock+1800;if(!playBW('bw_pat')){T.smile=1;flash('xx',1300);after(1500,()=>T.smile=0);}flash('blush',2400);line(['嘿嘿','头发要乱啦～','嗯～']);}
function faceTap(){busy=clock+1800;const r=Math.random();
  if(r<.4){flash('heart',1500);T.smile=.8;after(1300,()=>T.smile=0);line(['干嘛呀','嗯？','看什么看～']);}
  else if(r<.7){playFace('吐舌头眨眼');line(['略略略','嘿～']);}
  else{flash('teeth',1400);T.smile=1;after(1200,()=>T.smile=0);line(['嘻嘻']);}}
function poke(){busy=clock+1400;hopV=-200;T.wide=1;T.brow=.5;flash('sweat',1400);after(500,()=>{T.wide=0;T.brow=0;});line(['呀！','别戳～','痒……']);}
/* 连点 5 下：钢盆从天而降砸在头上 → 一沉 → 圈圈眼转晕（点子来自 Rockau/DeepFish-desktop-pet） */
function dizzy(){bridge('act',{k:'dizzy'});busy=clock+4200;stopRoutine();stopMotion();const had=fxT.pan;fxT.pan=1;fxUntil.pan=had?Infinity:clock+3800;
  after(120,()=>{hopV=420;T.wide=1;flash('xx',300);});after(420,()=>{T.wide=0;flash('dizzy',2800);dizzyT=2600;});after(700,()=>speak('铛——别戳啦，头晕。'));}

/* 点尾巴：甩一下，回头看；短时间连着点会从吃惊变成不耐烦 */
let tailTaps=[];
function tailTap(){tailTaps=tailTaps.filter(t=>clock-t<4000);tailTaps.push(clock);whipTail();busy=clock+2200;
  T.lookX=rnd(.5,.8)*(Math.random()<.5?-1:1);T.lookY=-.4;holdLook=clock+900;
  const n=tailTaps.length;
  if(n===1){setMood('惊讶',1400);line(['呀！尾巴！','嗯？谁碰我尾巴','尾巴不能随便摸的！']);}
  else if(n===2){setMood('害羞',2200);line(['都说了别碰啦……','尾巴很敏感的']);}
  else{setMood('生气',2600);line(['哼！','再碰就甩你脸上！']);tailTaps=[];}}
/* 点哪儿：头顶摸头、脸、手（挥手）、身体（被戳）；1.6 秒内连点 5 下转晕 */
let taps=[];
function tap(rg){bridge('act',{k:'tap',rg});taps=taps.filter(t=>clock-t<1600);taps.push(clock);if(taps.length>=5){taps=[];dizzy();return;}
  if(rg==='tail'){tailTap();return;}
  if(rg==='head')pat();else if(rg==='face')faceTap();else if(rg==='armL'||rg==='armR'){if(!rt)playRoutine('挥挥手');}else poke();}

/* 说话：每字 90ms，嘴按“音量”开合，标点合上 */
function speak(text,withMood=true,show=true){if(show&&quiet)return;const mk=withMood&&SET.mood?moodOf(text):null;text=text.replace(/^[【\[][^】\]]{1,4}[】\]]/,'');bridge('speak',{text});
  if(mk)setMood(mk,text.length*90+2500);
  if(!authorT&&!heldPose&&!(rt&&rt.r.pose)&&!reduced&&text.length>=8&&Math.random()<.6)playBW(pick(['bw_talk_chest','bw_talk_grass','bw_talk_finger']),text.length*90+900);
  const tok=++sayTok,el=$('say');busy=Math.max(busy,clock+text.length*90+600);sayBusy=clock+text.length*90+2500;
  let sp={};if(show){el.innerHTML='<span></span><i class="caret"></i>';sp=el.firstChild;sayKind='';requestAnimationFrame(()=>{if(tok===sayTok)el.classList.add('on');});}
  [...text].forEach((ch,i)=>after(i*90,()=>{if(tok!==sayTok)return;sp.textContent=text.slice(0,i+1);
    const quiet=/[，。、？！…,.!? ]/.test(ch);T.mouth=quiet?0:rnd(.45,1);
    if(!quiet){for(const k in VT)VT[k]=0;VT['aiueo'[Math.floor(Math.random()*5)]]=rnd(.6,1);}}));
  after(text.length*90+60,()=>{if(tok===sayTok)T.mouth=0;});
  if(show){after(text.length*90+60,()=>{if(tok===sayTok&&el.lastChild&&el.lastChild.className==='caret')el.lastChild.remove();});
    after(text.length*90+1660,()=>{if(tok===sayTok)el.classList.remove('on');});}}
function think(reply){const tok=++sayTok;T.mouth=0;
  T.lookX=-.5;T.lookY=.7;T.brow=.6;holdLook=clock+1600;busy=clock+2400;after(1600,()=>T.brow=0);
  after(1600,()=>{if(tok!==sayTok)return;hopV=-120;speak(reply);});}


/* ===== App 版：取代原型的测试面板。App 通过 window.whale 调这些，页面通过 Vg.event 回报 ===== */
const SET={show:true,mood:true,touch:true,drag:true,idle:2};
let dressing=false;
const LAYOUT_DEF={idle:{zoom:.8,dx:0,dy:-.06},chat:{zoom:.42,dx:.31,dy:.17}};
function applyLayout(){if(!dressing)Object.assign(St,cur());}
function readout(){}
function setEdit(on){editing=on;$('edit').classList.toggle('on',on);
  if(on){stopRoutine();stopMotion();busy=Infinity;}else{busy=clock+1000;saveLayout();bridge('editDone',{});}}
$('eDone').onclick=()=>setEdit(false);
$('eReset').onclick=()=>{Object.assign(St,LAYOUT_DEF[chatOn?'chat':'idle']);Object.assign(S,St);};
const OUTFIT=['pan','glasses','monocle','skirt','shoes','tail'];
function applySet(o){o=o||{};Object.assign(SET,o);autoIdle=SET.idle>0;if(!autoIdle)stopRoutine();
  stage.style.transition='opacity .32s cubic-bezier(.2,0,0,1)';stage.style.opacity=SET.show?1:0;
  if(o.outfit)for(const k of OUTFIT){const on=o.outfit.indexOf(k)>=0;fxT[k]=on?1:0;fxUntil[k]=on?Infinity:0;}
  if('body' in o)bodyT=+o.body||0;
  if(o.theme)for(const k in o.theme)document.documentElement.style.setProperty('--'+k,o.theme[k]);}
let replyGesture=false;
/* 回复对口型：App 按「屏幕上打出来的字」报数，这里攒着一个字一个字念（每字 85ms，偶尔合一下嘴）；
   说话时配手势，一段手势做完、还在说就再来一段。攒的字最多 14 个，字停了嘴很快跟着停 */
function talkStep(){if(talkLeft<=0||editing||danceOn){talkOn=false;talkLeft=0;T.mouth=0;return;}
  talkLeft--;const rest=Math.random()<.14;T.mouth=rest?.05:rnd(.45,1);
  if(!rest){for(const k in VT)VT[k]=0;VT['aiueo'[Math.floor(Math.random()*5)]]=rnd(.6,1);}
  if(!authorT&&!heldPose&&!rt&&!grab&&clock>nextGesture){playBW(pick(['bw_talk_chest','bw_talk_grass','bw_talk_finger']),9000);nextGesture=clock+rnd(9000,14000);}
  busy=Math.max(busy,clock+700);after(85,talkStep);}
/* 手指：App 在她身上盖了一层接触摸再转过来（她在对话列表后面，WebView 自己收不到）。点、摸头照旧；
   开了「长按拖动」时按住 420ms 把她拎起来（放大 5%），跟着手指走，松手落下并记住位置 */
function startGrab(){if(!downAt)return;grab=true;dragging=true;downAt.used=1;if(petting){petting=0;petX=0;fxUntil.xx=clock;}
  stopRoutine();stopMotion();busy=Infinity;T.wide=1;T.brow=.5;flash('sweat',900);after(600,()=>{T.wide=0;T.brow=0;});bridge('grab',{});}
function pDown(x,y){if(editing||!model)return;if(danceOn){endDance();return;}const e={clientX:x,clientY:y};
  pointerDown=true;ptrOn=true;lookAt(e);const rg=region(e);downAt={rg,x,y,t:clock};lastX=x;petMoved=0;gx=x;gy=y;
  if(rg&&SET.touch){if(rt&&rt.name!=='挥挥手')stopRoutine();stopMotion();busy=Math.max(busy,clock+2500);}
  clearTimeout(pressTimer);clearTimeout(grabTimer);
  if(SET.drag)grabTimer=setTimeout(()=>{if(pointerDown&&downAt&&petMoved<18&&Math.hypot(gx-downAt.x,gy-downAt.y)<14)startGrab();},420);
  else if(rg==='body'&&SET.touch)pressTimer=setTimeout(()=>{if(downAt&&petMoved<12){downAt.used=1;playRoutine('凑近摇摆');}},650);}
function pMove(x,y){if(editing||!pointerDown)return;
  if(grab){const r=stage.getBoundingClientRect();St.dx=clamp(St.dx+(x-gx)/r.width,-.46,.46);St.dy=clamp(St.dy+(y-gy)/r.height,-.46,.46);S.dx=St.dx;S.dy=St.dy;gx=x;gy=y;return;}
  gx=x;gy=y;lookAt({clientX:x,clientY:y});
  if(SET.touch&&downAt&&downAt.rg==='head'){const dx=x-lastX;lastX=x;petMoved+=Math.abs(dx);
    if(petMoved>18){if(!petting){fxT.xx=1;fxUntil.xx=Infinity;fxT.blush=1;fxUntil.blush=Infinity;T.smile=.9;}
      petting=1;petX=clamp(petX+dx*.012,-1,1);busy=clock+2500;}}}
function pUp(){clearTimeout(grabTimer);ptrOn=false;lastBox='';
  if(grab){grab=false;dragging=false;pointerDown=false;downAt=null;busy=clock+1000;holdLook=clock+800;hopV=-150;saveLayout();bridge('act',{k:'moved'});return;}
  release();}
const LINES=["bw_talk_chest", "bw_talk_finger", "bw_talk_grass", "再摸一下下。", "再碰就甩你脸上！", "别戳～", "呀！", "呀！尾巴！", "哼！", "嗯？", "嗯？谁碰我尾巴", "嗯～", "嘻嘻", "嘿嘿", "嘿嘿……", "嘿嘿，好看吗？", "嘿～", "头发要乱啦～", "尾巴不能随便摸的！", "尾巴很敏感的", "干嘛呀", "换哪件呢～", "略略略", "痒……", "看什么看～", "等我换个衣服……", "累死鱼了……", "衣服没找到……", "跳完啦～", "都说了别碰啦……", "铛——别戳啦，头晕。"];
window.whale={
  set:applySet,
  /* 有没有在聊天：没聊天站中间，聊天时缩到右下角 */
  chat(on){on=!!on;if(chatOn===on)return;chatOn=on;applyLayout();},
  /* 输入框在打字：低头看 */
  typing(){if(editing||danceOn)return;T.lookX=0;T.lookY=-1;holdLook=clock+1600;busy=Math.max(busy,clock+1600);},
  /* 发出去了：点头 */
  sent(){if(!editing&&!danceOn)nod();},
  /* 等回复：抬眼想 */
  think(){if(editing||danceOn)return;T.lookX=-.5;T.lookY=.7;T.brow=.6;holdLook=clock+2600;busy=Math.max(busy,clock+2600);after(1800,()=>T.brow=0);},
  /* 回复流进来 n 个字：嘴跟着动；第一次配个说话手势 */
  talk(n){if(editing||danceOn||n<=0)return;if(authorT&&(AUTHOR_IDS===UNTRACKED_IDS||idleMo))stopMotion(400);if(rt&&rt.name[0]!=='@'&&!rt.keep)stopRoutine();talkLeft=Math.min(talkLeft+n,14);if(!talkOn){talkOn=true;talkStep();}},
  /* 回复完了：按整句定情绪 */
  replied(text){replyGesture=false;talkLeft=0;nextGesture=0;if(editing||danceOn)return;stopMotion(500);T.mouth=0;
    const mk=SET.mood?moodOf(String(text||'')):null;if(mk)setMood(mk,3600);else if(Math.random()<.4)playFace('笑');},
  /* 问候：嘴跟着字动、按句子配情绪 */
  say(text){if(!editing&&!danceOn)speak(String(text||''),true,false);},
  /* 助理在回复、在打字、在翻对话：她不说自己的话，正在说的立刻收掉 */
  quiet(on){quiet=!!on;if(quiet)hush();},
  room(t,b){roomT=clamp(+t||0,0,1);roomB=clamp(+b||1,roomT+.05,1);},
  cover(on){covered=!!on;syncRun();},
  mood(k,ms){k=MOOD_ALIAS[k]||k;if(MOODS[k])setMood(k,ms||3000);},
  routine(n){if(editing)return;if(danceOn)endDance();
    if(n==='@dance')dance();else if(n==='@author')playAuthor();else if(n==='@idle60')playBW('bw_idle60');else if(ROUTINES[n]){stopRoutine();playRoutine(n);if(rt)rt.keep=true;}},
  face(k){if(editing)return;busy=clock+2200;if(FACES[k])playFace(k);else if(FX[k])flash(k,2000);},
  ptr(kind,fx,fy){const x=fx*innerWidth,y=fy*innerHeight;if(kind==='down')pDown(x,y);else if(kind==='move')pMove(x,y);else pUp();},
  edit(on){setEdit(!!on);},
  /* 换装间：她站到上半屏 */
  dress(on){dressing=!!on;if(dressing){stopRoutine();after(500,()=>speak('换哪件呢～',false));}},
  pause(on){paused=!!on;syncRun();},
  dance(){dance();},
  /* 她自己会说的固定台词：App 预先合成好存在手机上，说的时候不用等 */
  lines(){const a=new Set(LINES);Object.values(ROUTINES).forEach(r=>(r.lines||[]).forEach(x=>a.add(x)));return JSON.stringify([...a]);},
  routines(){return JSON.stringify(Object.keys(ROUTINES).filter(k=>k[0]!=='@'));},
  faces(){return JSON.stringify(Object.keys(FACES));},
};
/* 把她占的范围（视口比例）报给 App：范围外的触摸交还给主页，范围内才归她 */
let lastBox='';
setInterval(()=>{if(!model||document.hidden||ptrOn)return;if(model.alpha<.3){if(lastBox!=='0'){lastBox='0';bridge('box',{l:0,t:0,r:0,b:0});}return;}try{const b=model.getBounds(),W=app.screen.width,H=app.screen.height;
  const r=[b.x/W,b.y/H,(b.x+b.width)/W,(b.y+b.height)/H].map(v=>Math.round(clamp(v,0,1)*200)/200),k=r.join(',');
  if(k!==lastBox){lastBox=k;bridge('box',{l:r[0],t:r[1],r:r[2],b:r[3]});}}catch(_){}},400);
applySet({});
})();
