(()=>{
const $=s=>document.querySelector(s),dialog=$('#letters'),scroller=dialog.querySelector('.letters-scroll'),expand=$('#letters-expand'),detail=$('#letter-detail'),detailScroll=detail.querySelector('.letter-detail-scroll'),cards=[...document.querySelectorAll('[data-letter]')];
let position=0,current=0;
function setExpanded(on){const top=scroller.scrollTop;dialog.classList.toggle('letters-expanded',on);expand.textContent=on?'还原 ↙':'全屏 ↗';expand.setAttribute('aria-pressed',String(on));requestAnimationFrame(()=>{scroller.scrollTop=top})}
$('#letters-open').onclick=()=>{if(dialog.open)return;dialog.showModal();video.pause();requestAnimationFrame(()=>{scroller.scrollTop=position})};
$('#letters-close').onclick=()=>dialog.close();expand.onclick=()=>setExpanded(!dialog.classList.contains('letters-expanded'));
dialog.addEventListener('close',()=>{if(detail.open)detail.close();position=scroller.scrollTop;setExpanded(false);syncPlayback()});
function showLetter(index){if(index<0||index>=cards.length)return;current=index;const card=cards[index];$('#letter-detail-text').textContent=card.querySelector('.letter-quote').textContent;$('#letter-detail-author').textContent=card.querySelector('.letter-author').textContent;$('#letter-count').textContent=String(index+1).padStart(2,'0')+' / '+String(cards.length).padStart(2,'0');$('#letter-back').disabled=index===0;$('#letter-forward').disabled=index===cards.length-1;if(!detail.open)detail.showModal();detailScroll.scrollTop=0}
cards.forEach((card,index)=>{card.onclick=()=>showLetter(index)});
$('#letter-detail-close').onclick=()=>detail.close();$('#letter-back').onclick=()=>showLetter(current-1);$('#letter-forward').onclick=()=>showLetter(current+1);
detail.addEventListener('click',e=>{if(e.target!==detail)return;const r=detail.getBoundingClientRect();if(e.clientX<r.left||e.clientX>r.right||e.clientY<r.top||e.clientY>r.bottom)detail.close()});
})();
