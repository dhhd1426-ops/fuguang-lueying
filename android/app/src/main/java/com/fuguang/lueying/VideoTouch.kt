package com.fuguang.lueying

/**
 * 内置浏览器里网页自己的视频播放器：按钮不动，只在画面上加一层看不见的手势（方案一，原型
 * https://claude.ai/artifact/8tDyaBb1wmWddnkSJJ62Jg）。
 * - 左右滑：跳进度（滑满一个画面宽 = 90 秒），画面中间显示跳到哪、差多少秒，松手落定。
 * - 点两下：左边三分之一快退 10 秒，右边三分之一快进 10 秒，中间暂停 / 继续。
 * - 按住 380ms：2 倍速，松手恢复原来的速度。
 * - 点一下：等 280ms 没有第二下，原样还给网页（网站自己显出按钮、暂停之类照旧）。
 * 画面底部那一条（高的 18%，至少 44px）不接，留给网站自己的进度条和按钮。
 * 另外记下最近点到的那个视频在网页里的位置（window.__fgLast），全屏从那里长出来、缩回那里。
 * 页面里不出现 $ 符号，好直接放进 Kotlin 字符串。
 */
object VideoTouch {
    val JS = """
(function(){if(window.__fgvt)return;window.__fgvt=1;
var DT=280,LONG=380,replay=false,st=null,lastTap=0,ate=0,lastXY=null,tapT=0,longT=0;
var css='position:fixed;z-index:2147483647;pointer-events:none;';
function el(s){var d=document.createElement('div');d.style.cssText=css+s;(document.body||document.documentElement).appendChild(d);return d}
var H=null,S=null;
function ui(){if(H&&H.isConnected)return;
 H=el('left:0;top:0;opacity:0;transform:translate(-50%,-50%) scale(.9);transition:opacity 160ms cubic-bezier(.2,0,0,1),transform 200ms cubic-bezier(.05,.7,.1,1);padding:9px 14px;border-radius:14px;background:rgba(26,29,35,.74);color:#ece7e1;font:13px/1.4 sans-serif;text-align:center;white-space:nowrap;font-variant-numeric:tabular-nums');
 S=el('left:0;top:0;opacity:0;transform:translate(-50%,-6px);transition:opacity 160ms,transform 220ms cubic-bezier(.05,.7,.1,1);padding:5px 11px;border-radius:12px;background:rgba(26,29,35,.74);color:#e9b48e;font:12px/1.4 sans-serif;white-space:nowrap');
 S.textContent='▶▶ 2 倍速';}
function fmt(t){t=Math.max(0,Math.floor(t||0));var h=Math.floor(t/3600),m=Math.floor(t%3600/60),s=t%60;return (h?h+':'+(m<10?'0':''):'')+m+':'+(s<10?'0':'')+s}
var hideH=0;
function hint(html,r,ms){ui();H.innerHTML=html;H.style.left=(r.left+r.width/2)+'px';H.style.top=(r.top+r.height/2)+'px';H.style.opacity=1;H.style.transform='translate(-50%,-50%) scale(1)';clearTimeout(hideH);if(ms)hideH=setTimeout(unhint,ms)}
function unhint(){if(!H)return;H.style.opacity=0;H.style.transform='translate(-50%,-50%) scale(.9)'}
function speed(on,r){ui();if(on){S.style.left=(r.left+r.width/2)+'px';S.style.top=(r.top+12)+'px'}S.style.opacity=on?1:0;S.style.transform=on?'translate(-50%,0)':'translate(-50%,-6px)'}
function ripple(r,side,txt){var w=r.width*.42,d=el('top:'+r.top+'px;height:'+r.height+'px;width:'+w+'px;'+(side<0?'left:'+r.left:'left:'+(r.right-w))+'px;overflow:hidden;display:flex;align-items:center;justify-content:center;color:#ece7e1;font:12.5px sans-serif;opacity:0');
 var c=document.createElement('div');c.style.cssText='position:absolute;width:'+(r.height*2.6)+'px;height:'+(r.height*2.6)+'px;border-radius:50%;background:rgba(236,231,225,.10);top:50%;margin-top:-'+(r.height*1.3)+'px;'+(side<0?'right:20%':'left:20%');d.appendChild(c);
 var t=document.createElement('span');t.style.position='relative';t.textContent=txt;d.appendChild(t);
 var a=d.animate([{opacity:0},{opacity:1,offset:.15},{opacity:1,offset:.7},{opacity:0}],{duration:520,easing:'cubic-bezier(.05,.7,.1,1)'});a.onfinish=function(){d.remove()}}
var PLAY='<svg width="26" height="26" viewBox="0 0 24 24" style="display:block"><path d="M8 5.5v13l11-6.5z" fill="#ece7e1"/></svg>',PAUSE='<svg width="26" height="26" viewBox="0 0 24 24" style="display:block"><path d="M7 5h3.6v14H7zM13.4 5H17v14h-3.6z" fill="#ece7e1"/></svg>';
function find(x,y,all){var best=null,area=0;document.querySelectorAll('video').forEach(function(v){var r=v.getBoundingClientRect();if(r.width<120||r.height<68)return;var bottom=all?r.bottom:r.bottom-Math.max(44,r.height*.18);if(x>=r.left&&x<=r.right&&y>=r.top&&y<=bottom){var a=r.width*r.height;if(a>area){area=a;best={v:v,r:r}}}});return best}
function remember(x,y){var f=find(x,y,true);if(f)window.__fgLast={l:f.r.left,t:f.r.top,w:f.r.width,h:f.r.height}}
function stop(e){e.stopImmediatePropagation()}
function dur(v){return isFinite(v.duration)?v.duration:0}
// 一下就还给网页：照原样补发一串事件
function giveBack(x,y){var t=document.elementFromPoint(x,y);if(!t)return;replay=true;
 try{var tc=new Touch({identifier:Date.now(),target:t,clientX:x,clientY:y});t.dispatchEvent(new TouchEvent('touchstart',{bubbles:true,cancelable:true,touches:[tc],targetTouches:[tc],changedTouches:[tc]}));t.dispatchEvent(new TouchEvent('touchend',{bubbles:true,cancelable:true,touches:[],targetTouches:[],changedTouches:[tc]}))}catch(_){}
 var o={bubbles:true,cancelable:true,clientX:x,clientY:y,pointerType:'touch',isPrimary:true,pointerId:1,button:0};
 ['pointerdown','pointerup'].forEach(function(n){try{t.dispatchEvent(new PointerEvent(n,o))}catch(_){}});
 ['mousedown','mouseup','click'].forEach(function(n){t.dispatchEvent(new MouseEvent(n,o))});
 replay=false}
window.addEventListener('pointerdown',function(e){if(replay||e.pointerType!=='touch'||!e.isPrimary)return;remember(e.clientX,e.clientY);
 var f=find(e.clientX,e.clientY,false);if(!f){st=null;return}
 stop(e);st={v:f.v,r:f.r,x:e.clientX,y:e.clientY,t0:f.v.currentTime,mode:null,nt:0};
 clearTimeout(longT);longT=setTimeout(function(){if(!st||st.mode)return;st.mode='long';st.rate=st.v.playbackRate;st.v.playbackRate=2;if(st.v.paused)st.v.play();speed(true,st.r);try{navigator.vibrate(8)}catch(_){}},LONG)},true);
window.addEventListener('pointermove',function(e){if(replay||!st||e.pointerType!=='touch')return;stop(e);if(st.mode==='long'||st.mode==='scroll')return;
 var dx=e.clientX-st.x,dy=e.clientY-st.y;
 if(!st.mode){if(Math.abs(dx)>12&&Math.abs(dx)>Math.abs(dy)&&dur(st.v)>0){st.mode='seek';clearTimeout(longT)}else if(Math.abs(dy)>12){st.mode='scroll';clearTimeout(longT);return}}
 if(st.mode==='seek'){var d=dur(st.v);st.nt=Math.min(d,Math.max(0,st.t0+dx/st.r.width*90));var dd=Math.round(st.nt-st.t0);hint('<div style="font:20px/1.3 serif;color:#e9b48e">'+fmt(st.nt)+'</div>'+(dd>=0?'+':'−')+Math.abs(dd)+' 秒 · 共 '+fmt(d),st.r,0)}},true);
function end(e,cancel){if(replay||!st||e.pointerType!=='touch')return;stop(e);clearTimeout(longT);var s=st;st=null;if(s.mode!=='scroll'&&!cancel)ate=Date.now();
 if(s.mode==='long'){s.v.playbackRate=s.rate||1;speed(false);return}
 if(s.mode==='seek'){if(!cancel)s.v.currentTime=s.nt;setTimeout(unhint,260);return}
 if(s.mode==='scroll'||cancel)return;
 var now=Date.now(),x=e.clientX,y=e.clientY;
 if(now-lastTap<DT&&lastXY&&Math.abs(x-lastXY[0])<60&&Math.abs(y-lastXY[1])<60){clearTimeout(tapT);lastTap=0;
  var rx=(x-s.r.left)/s.r.width,d=dur(s.v);
  if(rx<1/3&&d>0){s.v.currentTime=Math.max(0,s.v.currentTime-10);ripple(s.r,-1,'−10 秒')}
  else if(rx>2/3&&d>0){s.v.currentTime=Math.min(d,s.v.currentTime+10);ripple(s.r,1,'+10 秒')}
  else{if(s.v.paused){s.v.play();hint(PLAY,s.r,500)}else{s.v.pause();hint(PAUSE,s.r,500)}}
 }else{lastTap=now;lastXY=[x,y];tapT=setTimeout(function(){giveBack(x,y)},DT)}}
window.addEventListener('pointerup',function(e){end(e,false)},true);
window.addEventListener('pointercancel',function(e){if(st&&st.mode==='seek'){st.mode='seek';end(e,false)}else end(e,true)},true);
// 浏览器从这次触摸补出来的 touch / mouse / click：接住了就不往下传
['touchstart','touchend','mousedown','mouseup','click'].forEach(function(n){window.addEventListener(n,function(e){if(replay)return;if(st||Date.now()-ate<DT+60){if(n==='touchend'||n==='click'||n==='mousedown'||n==='mouseup'){stop(e);if(e.cancelable)e.preventDefault()}else stop(e)}},{capture:true,passive:false})});
window.addEventListener('touchmove',function(e){if(st){stop(e);if(st.mode==='seek'&&e.cancelable)e.preventDefault()}},{capture:true,passive:false});
})();
""".trimIndent()
}
