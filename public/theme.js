(()=>{
const root=document.documentElement,MODE_KEY='fuguang-theme',DURATION=720,ICON_DURATION=500,EASING='cubic-bezier(.22,.7,.2,1)',reduced=matchMedia('(prefers-reduced-motion: reduce)');let busy=false,serial=0;
for(const selector of ['.letters-actions']){const group=document.querySelector(selector);if(!group)continue;const b=document.createElement('button');b.type='button';b.className='theme-toggle';group.insertBefore(b,group.querySelector('.close'))}
const buttons=[...document.querySelectorAll('.theme-toggle')];
for(const b of buttons){const id='theme-moon-mask-'+(++serial);b.style.viewTransitionName='theme-control-'+serial;b.innerHTML='<svg viewBox="0 0 32 32" aria-hidden="true"><defs><mask id="'+id+'"><rect width="32" height="32" fill="white"/><circle class="moon-cutout" cx="21" cy="10" r="9" fill="black"/></mask></defs><g class="sun-rays"><path d="M16 2v3m0 22v3M2 16h3m22 0h3M6 6l2 2m16 16l2 2M6 26l2-2M24 8l2-2"/></g><circle class="theme-disc" cx="16" cy="16" r="9" mask="url(#'+id+')"/></svg>';b.onclick=()=>toggle(b)}
function sync(){const light=root.dataset.theme==='light';for(const b of buttons){b.setAttribute('aria-label',light?'切换到深色模式':'切换到浅色模式');b.setAttribute('aria-pressed',String(light));b.title=light?'深色模式':'浅色模式'}}
function apply(theme){root.dataset.theme=theme;try{localStorage.setItem(MODE_KEY,theme)}catch{}sync()}
async function toggle(button){if(busy)return;busy=true;const next=root.dataset.theme==='light'?'dark':'light';const r=button.getBoundingClientRect(),x=r.left+r.width/2,y=r.top+r.height/2;const radius=Math.ceil(Math.hypot(Math.max(x,innerWidth-x),Math.max(y,innerHeight-y)));let vt;
try{if(reduced.matches||document.hidden){apply(next);return}
if(document.startViewTransition){root.classList.add('theme-capturing');vt=document.startViewTransition(()=>apply(next));await vt.ready;root.classList.remove('theme-capturing');const anim=root.animate({clipPath:['circle(0px at '+x+'px '+y+'px)','circle('+radius+'px at '+x+'px '+y+'px)']},{duration:DURATION,easing:EASING,pseudoElement:'::view-transition-new(root)',fill:'both'});await anim.finished;await vt.finished}
else{apply(next);const targets=[document.querySelector('.page'),...document.querySelectorAll('dialog[open]')].filter(Boolean);await Promise.all(targets.map(el=>el.animate([{opacity:.45},{opacity:1}],{duration:ICON_DURATION,easing:EASING}).finished))}
}catch{if(vt)vt.skipTransition();apply(next)}finally{root.classList.remove('theme-capturing');busy=false}}
sync();
})();
