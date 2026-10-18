/* 浮光掠影 · 作品套件（fg）
 * 每件网页作品打开时自动带上。她照着写几个属性，页面就有完整的交互：
 *   点卡片弹出详情（data-sheet）、弹窗确认（data-dialog / fg.dialog）、看大图（点图片）、
 *   外链在 App 里打开（普通 <a href>）、复制（data-copy）、分页签（.fg-tabs）、小提示（fg.toast）。
 *   图片加载不出来自动换成占位，不再弹「有个文件没加载上」。
 * 动效照 dongxiaosheji：进 decel 320ms、出 accel 200ms；按下 70ms 下沉；离开比进入快；尊重「减少动态效果」。
 */
(function () {
  if (window.fg) return;
  var D = document, R = D.documentElement;
  var reduce = matchMedia('(prefers-reduced-motion: reduce)').matches;
  var DECEL = 'cubic-bezier(.05,.7,.1,1)', ACCEL = 'cubic-bezier(.3,0,.8,.15)', STD = 'cubic-bezier(.2,0,0,1)';
  var T = function (ms) { return reduce ? 1 : ms; };

  var css = [
    ':root{--fg-acc:#e9b48e;--fg-ink:#faf5ee;--fg-muted:rgba(250,245,238,.6);--fg-sheet:#1c2028;--fg-line:rgba(255,255,255,.08);--fg-scrim:rgba(6,8,12,.55)}',
    /* 按下当帧就有回应 */
    'a,button,[data-sheet],[data-dialog],[data-copy],[data-open],.fg-card,[onclick]{-webkit-tap-highlight-color:transparent;touch-action:manipulation}',
    '.fg-press{transition:transform 180ms ' + STD + ',filter 180ms ' + STD + '}',
    '.fg-down{transform:scale(.97);filter:brightness(1.08);transition-duration:70ms}',
    '[data-sheet],[data-dialog],[data-copy],[data-open],.fg-card{cursor:pointer}',
    ':focus-visible{outline:2px solid var(--fg-acc);outline-offset:2px}',
    /* 图片 */
    'img{max-width:100%}',
    '.fg-noimg{display:flex;flex-direction:column;align-items:center;justify-content:center;gap:6px;background:linear-gradient(135deg,rgba(255,255,255,.06),rgba(255,255,255,.02));color:var(--fg-muted);font-size:12px;text-align:center;padding:8px;box-sizing:border-box;overflow:hidden;border-radius:inherit}',
    '.fg-noimg svg{opacity:.5}',
    '.fg-noimg b{font-weight:500;color:var(--fg-ink);opacity:.8;font-size:13px;line-height:1.3;display:-webkit-box;-webkit-line-clamp:3;-webkit-box-orient:vertical;overflow:hidden}',
    'img.fg-zoomable{cursor:zoom-in}',
    /* 遮罩 */
    '.fg-scrim{position:fixed;inset:0;background:var(--fg-scrim);opacity:0;z-index:2147483000;transition:opacity ' + T(320) + 'ms ' + DECEL + '}',
    '.fg-scrim.on{opacity:1}.fg-scrim.out{opacity:0;transition:opacity ' + T(200) + 'ms ' + ACCEL + '}',
    /* 详情：从下面升上来，能往下拖关掉 */
    '.fg-sheet{position:fixed;left:0;right:0;bottom:0;margin:0 auto;max-width:640px;max-height:88vh;display:flex;flex-direction:column;background:var(--fg-sheet);color:var(--fg-ink);border-radius:22px 22px 0 0;box-shadow:0 -10px 40px rgba(0,0,0,.4);z-index:2147483001;transform:translateY(100%);transition:transform ' + T(340) + 'ms ' + DECEL + ';padding-bottom:env(safe-area-inset-bottom,0px)}',
    '.fg-sheet.on{transform:none}.fg-sheet.out{transition:transform ' + T(220) + 'ms ' + ACCEL + '}',
    '.fg-sheet .fg-grab{width:40px;height:4px;border-radius:2px;background:rgba(255,255,255,.25);margin:10px auto 4px;flex:none}',
    '.fg-sheet .fg-head{display:flex;align-items:center;gap:8px;padding:6px 10px 6px 18px;flex:none}',
    '.fg-sheet .fg-head h3{flex:1;margin:0;font-size:17px;font-weight:600;line-height:1.35}',
    '.fg-x{width:36px;height:36px;border-radius:50%;border:0;background:rgba(255,255,255,.06);color:var(--fg-ink);display:grid;place-items:center;flex:none;transition:transform 180ms ' + STD + ',background 180ms}',
    '.fg-x:active{transform:scale(.9);background:rgba(255,255,255,.12);transition-duration:70ms}',
    '.fg-sheet .fg-body{overflow:auto;padding:4px 18px 22px;-webkit-overflow-scrolling:touch;overscroll-behavior:contain;line-height:1.75;font-size:14.5px}',
    '.fg-sheet .fg-body>*{animation:fg-rise ' + T(380) + 'ms ' + DECEL + ' both}',
    '.fg-sheet .fg-body>*:nth-child(2){animation-delay:40ms}.fg-sheet .fg-body>*:nth-child(3){animation-delay:80ms}.fg-sheet .fg-body>*:nth-child(n+4){animation-delay:120ms}',
    '.fg-sheet .fg-body img{border-radius:12px}',
    '@keyframes fg-rise{from{opacity:0;transform:translateY(8px)}}',
    /* 弹窗：中间放大淡入 */
    '.fg-dialog{position:fixed;left:50%;top:50%;width:min(320px,calc(100vw - 48px));background:var(--fg-sheet);color:var(--fg-ink);border-radius:20px;padding:20px 20px 14px;z-index:2147483002;transform:translate(-50%,-50%) scale(.92);opacity:0;transition:transform ' + T(320) + 'ms ' + DECEL + ',opacity ' + T(200) + 'ms ' + STD + ';box-shadow:0 20px 60px rgba(0,0,0,.5)}',
    '.fg-dialog.on{transform:translate(-50%,-50%);opacity:1}.fg-dialog.out{transform:translate(-50%,-50%) scale(.96);opacity:0;transition-duration:' + T(160) + 'ms;transition-timing-function:' + ACCEL + '}',
    '.fg-dialog h3{margin:0 0 8px;font-size:17px}.fg-dialog p{margin:0 0 16px;color:var(--fg-muted);font-size:14px;line-height:1.7}',
    '.fg-acts{display:flex;gap:8px;justify-content:flex-end}',
    '.fg-btn{border:0;border-radius:12px;padding:10px 16px;font:inherit;font-size:14px;background:rgba(255,255,255,.08);color:var(--fg-ink);transition:transform 180ms ' + STD + ',filter 180ms}',
    '.fg-btn.main{background:var(--fg-acc);color:#2a211b}',
    '.fg-btn:active{transform:scale(.96);filter:brightness(1.1);transition-duration:70ms}',
    /* 小提示 */
    '.fg-toast{position:fixed;left:50%;bottom:calc(24px + env(safe-area-inset-bottom,0px));transform:translate(-50%,16px);opacity:0;background:#3a404c;color:var(--fg-ink);padding:10px 16px;border-radius:12px;font-size:13.5px;z-index:2147483003;transition:transform ' + T(300) + 'ms ' + DECEL + ',opacity ' + T(200) + 'ms;pointer-events:none;max-width:calc(100vw - 48px)}',
    '.fg-toast.on{transform:translate(-50%,0);opacity:1}.fg-toast.out{transform:translate(-50%,8px);opacity:0;transition-timing-function:' + ACCEL + ';transition-duration:' + T(180) + 'ms}',
    /* 看大图：从原位放大 */
    '.fg-zoom{position:fixed;z-index:2147483002;object-fit:contain;transition:all ' + T(340) + 'ms ' + DECEL + ';border-radius:0;max-width:none}',
    '.fg-zoom.out{transition-duration:' + T(240) + 'ms;transition-timing-function:' + ACCEL + '}',
    /* 页签 */
    '.fg-tabs{display:flex;gap:4px;position:relative;background:rgba(255,255,255,.05);border-radius:14px;padding:4px;overflow-x:auto;scrollbar-width:none}',
    '.fg-tabs::-webkit-scrollbar{display:none}',
    '.fg-tabs [data-tab]{flex:1;white-space:nowrap;border:0;background:none;color:var(--fg-muted);font:inherit;font-size:13.5px;padding:8px 12px;border-radius:10px;position:relative;z-index:1;transition:color 200ms}',
    '.fg-tabs [data-tab][aria-selected=true]{color:var(--fg-ink)}',
    '.fg-tabs .fg-thumb{position:absolute;top:4px;bottom:4px;border-radius:10px;background:rgba(255,255,255,.1);transition:left ' + T(320) + 'ms cubic-bezier(.3,1.2,.45,1),width ' + T(320) + 'ms ' + STD + '}',
    '[data-panel][hidden]{display:none!important}',
    '[data-panel].fg-in{animation:fg-rise ' + T(320) + 'ms ' + DECEL + ' both}'
  ].join('\n');
  var st = D.createElement('style'); st.id = 'fg-kit'; st.textContent = css;
  (D.head || R).insertBefore(st, (D.head || R).firstChild);

  var ICON = {
    x: '<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round"><path d="M6 6l12 12M18 6L6 18"/></svg>',
    img: '<svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5"><rect x="3.5" y="4.5" width="17" height="15" rx="2.5"/><circle cx="9" cy="10" r="1.8"/><path d="M4 17l5-4.5 4 3.5 3-2.5 4 3.5"/></svg>'
  };
  var stack = [];   // 开着的层：最上面的先关
  function el(tag, cls, html) { var e = D.createElement(tag); if (cls) e.className = cls; if (html != null) e.innerHTML = html; return e; }
  function app() { return window.FGApp || null; }
  function scrim(onTap) {
    var s = el('div', 'fg-scrim'); D.body.appendChild(s);
    s.addEventListener('click', onTap); requestAnimationFrame(function () { s.classList.add('on'); });
    return s;
  }
  function leave(e, ms) { e.classList.remove('on'); e.classList.add('out'); setTimeout(function () { e.remove(); }, T(ms) + 30); }
  function lock(on) { R.style.overflow = on ? 'hidden' : ''; }

  /* ———— 详情 ———— */
  function sheet(o) {
    o = o || {};
    var back = D.activeElement;
    var s = scrim(function () { close(); });
    var p = el('div', 'fg-sheet'); p.setAttribute('role', 'dialog'); p.setAttribute('aria-modal', 'true');
    p.innerHTML = '<div class="fg-grab"></div><div class="fg-head"><h3></h3><button class="fg-x" aria-label="关闭">' + ICON.x + '</button></div><div class="fg-body"></div>';
    p.querySelector('h3').textContent = o.title || '';
    var body = p.querySelector('.fg-body');
    if (o.node) body.appendChild(o.node); else body.innerHTML = o.html || '';
    D.body.appendChild(p); lock(true);
    requestAnimationFrame(function () { requestAnimationFrame(function () { p.classList.add('on'); }); });
    p.querySelector('.fg-x').addEventListener('click', function () { close(); });
    setTimeout(function () { p.querySelector('.fg-x').focus({ preventScroll: true }); }, 60);
    // 往下拖关掉：内容在顶上时才拖整张
    var y0 = null, dy = 0, t0 = 0;
    p.addEventListener('pointerdown', function (e) { if (body.scrollTop > 0 && body.contains(e.target)) return; y0 = e.clientY; dy = 0; t0 = Date.now(); });
    p.addEventListener('pointermove', function (e) { if (y0 == null) return; dy = Math.max(0, e.clientY - y0); if (dy > 4) { p.style.transition = 'none'; p.style.transform = 'translateY(' + dy + 'px)'; s.style.opacity = String(1 - Math.min(1, dy / 400)); } });
    function up() { if (y0 == null) return; var v = dy / Math.max(1, Date.now() - t0); y0 = null; p.style.transition = ''; s.style.opacity = '';
      if (dy > 120 || v > .6) close(); else p.style.transform = ''; }
    p.addEventListener('pointerup', up); p.addEventListener('pointercancel', up);
    var done = false;
    function close() { if (done) return; done = true; p.style.transform = ''; leave(p, 220); leave(s, 200); stack.splice(stack.indexOf(close), 1); lock(stack.length > 0);
      if (o.onClose) o.onClose(); if (back && back.focus) setTimeout(function () { back.focus({ preventScroll: true }); }, T(220)); }
    stack.push(close);
    return { close: close, body: body };
  }

  /* ———— 弹窗 ———— */
  function dialog(o) {
    o = o || {};
    return new Promise(function (res) {
      var s = scrim(function () { close(false); });
      var d = el('div', 'fg-dialog'); d.setAttribute('role', 'alertdialog');
      d.innerHTML = '<h3></h3><p></p><div class="fg-acts"></div>';
      d.querySelector('h3').textContent = o.title || ''; if (!o.title) d.querySelector('h3').remove();
      var pp = d.querySelector('p'); if (o.html) pp.innerHTML = o.html; else pp.textContent = o.text || '';
      var acts = d.querySelector('.fg-acts');
      if (o.cancel !== false) { var c = el('button', 'fg-btn', ''); c.textContent = o.cancel || '取消'; c.onclick = function () { close(false); }; acts.appendChild(c); }
      var ok = el('button', 'fg-btn main', ''); ok.textContent = o.ok || '好'; ok.onclick = function () { close(true); }; acts.appendChild(ok);
      D.body.appendChild(d); requestAnimationFrame(function () { requestAnimationFrame(function () { d.classList.add('on'); ok.focus({ preventScroll: true }); }); });
      var done = false;
      function close(v) { if (done) return; done = true; leave(d, 160); leave(s, 200); stack.splice(stack.indexOf(cl), 1); res(v); }
      var cl = function () { close(false); }; stack.push(cl);
    });
  }

  /* ———— 小提示 ———— */
  var toastEl = null, toastT = 0;
  function toast(text) {
    if (!toastEl) { toastEl = el('div', 'fg-toast'); toastEl.setAttribute('role', 'status'); D.body.appendChild(toastEl); }
    toastEl.textContent = text; toastEl.classList.remove('out'); void toastEl.offsetWidth; toastEl.classList.add('on');
    clearTimeout(toastT); toastT = setTimeout(function () { toastEl.classList.remove('on'); toastEl.classList.add('out'); }, 2200);
  }

  /* ———— 复制 ———— */
  function copy(text) {
    var ok = function () { toast('已复制'); };
    if (app() && app().copy) { app().copy(String(text)); ok(); return; }
    if (navigator.clipboard) navigator.clipboard.writeText(String(text)).then(ok, function () { fallback(); }); else fallback();
    function fallback() { var t = el('textarea'); t.value = text; t.style.cssText = 'position:fixed;opacity:0'; D.body.appendChild(t); t.select(); try { D.execCommand('copy'); ok(); } catch (e) {} t.remove(); }
  }

  /* ———— 外链：在 App 里打开（App 外就新窗口） ———— */
  function open(url) {
    if (app() && app().open) app().open(String(url));
    else { var a = el('a'); a.href = url; a.target = '_blank'; a.rel = 'noopener'; a.click(); }
  }

  /* ———— 看大图 ———— */
  function zoom(img) {
    var r = img.getBoundingClientRect(), src = img.currentSrc || img.src;
    var s = scrim(function () { close(); }); s.style.background = 'rgba(0,0,0,.92)';
    var z = el('img', 'fg-zoom'); z.src = src; z.alt = img.alt || '';
    z.style.cssText = 'left:' + r.left + 'px;top:' + r.top + 'px;width:' + r.width + 'px;height:' + r.height + 'px;border-radius:' + getComputedStyle(img).borderRadius;
    D.body.appendChild(z); lock(true);
    requestAnimationFrame(function () { requestAnimationFrame(function () { z.style.left = '0px'; z.style.top = '0px'; z.style.width = '100vw'; z.style.height = '100vh'; z.style.borderRadius = '0'; }); });
    z.addEventListener('click', function () { close(); });
    var done = false;
    function close() { if (done) return; done = true; var r2 = img.getBoundingClientRect(); z.classList.add('out');
      z.style.left = r2.left + 'px'; z.style.top = r2.top + 'px'; z.style.width = r2.width + 'px'; z.style.height = r2.height + 'px';
      leave(s, 200); setTimeout(function () { z.remove(); }, T(260)); stack.splice(stack.indexOf(close), 1); lock(stack.length > 0); }
    stack.push(close);
  }

  /* ———— 图片：懒加载、不带来源；加载不出来换成占位，留着原来的大小 ———— */
  function noimg(img) {
    if (img.dataset.fgDone) return; img.dataset.fgDone = '1';
    var r = img.getBoundingClientRect(), cs = getComputedStyle(img);
    var box = el('div', 'fg-noimg ' + (img.className || ''), ICON.img + (img.alt ? '<b></b>' : ''));
    if (img.alt) box.querySelector('b').textContent = img.alt;
    box.style.width = img.getAttribute('width') ? img.getAttribute('width') + 'px' : (r.width ? r.width + 'px' : cs.width);
    box.style.height = img.getAttribute('height') ? img.getAttribute('height') + 'px' : (r.height > 8 ? r.height + 'px' : (cs.aspectRatio !== 'auto' ? '' : '120px'));
    if (cs.aspectRatio && cs.aspectRatio !== 'auto') box.style.aspectRatio = cs.aspectRatio;
    box.style.borderRadius = cs.borderRadius; box.style.margin = cs.margin; box.style.maxWidth = '100%';
    box.setAttribute('role', 'img'); box.setAttribute('aria-label', img.alt || '图片没加载出来');
    img.replaceWith(box);
  }
  function prep(img) {
    if (img.dataset.fgPrep) return; img.dataset.fgPrep = '1';
    if (!img.getAttribute('loading')) img.loading = 'lazy';
    img.decoding = 'async'; img.referrerPolicy = 'no-referrer';
    img.addEventListener('error', function () { noimg(img); });
    if (img.complete && img.naturalWidth === 0 && img.getAttribute('src')) noimg(img);
  }

  /* ———— 页签 ———— */
  function tabs(box) {
    if (box.dataset.fgPrep) return; box.dataset.fgPrep = '1';
    box.setAttribute('role', 'tablist');
    var th = el('span', 'fg-thumb'); box.appendChild(th);
    var btns = [].slice.call(box.querySelectorAll('[data-tab]'));
    function pick(b, first) {
      btns.forEach(function (x) { x.setAttribute('aria-selected', x === b ? 'true' : 'false'); });
      th.style.left = b.offsetLeft + 'px'; th.style.width = b.offsetWidth + 'px';
      var name = b.dataset.tab;
      D.querySelectorAll('[data-panel]').forEach(function (p) {
        if (!btns.some(function (x) { return x.dataset.tab === p.dataset.panel; })) return;
        var on = p.dataset.panel === name; p.hidden = !on;
        if (on && !first) { p.classList.remove('fg-in'); void p.offsetWidth; p.classList.add('fg-in'); }
      });
    }
    btns.forEach(function (b) { b.setAttribute('role', 'tab'); b.addEventListener('click', function () { pick(b); }); });
    var start = btns.filter(function (b) { return b.getAttribute('aria-selected') === 'true'; })[0] || btns[0];
    if (start) { th.style.transition = 'none'; pick(start, true); requestAnimationFrame(function () { th.style.transition = ''; }); }
  }

  /* ———— 按下 ———— */
  var pressable = 'a,button,[data-sheet],[data-dialog],[data-copy],[data-open],.fg-card,[onclick]';
  var down = null;
  D.addEventListener('pointerdown', function (e) {
    var t = e.target.closest && e.target.closest(pressable); if (!t || t.closest('.fg-sheet .fg-head,.fg-tabs')) return;
    t.classList.add('fg-press', 'fg-down'); down = t;
  }, true);
  function release() { if (down) { down.classList.remove('fg-down'); down = null; } }
  D.addEventListener('pointerup', release, true); D.addEventListener('pointercancel', release, true);
  D.addEventListener('pointerleave', release, true);

  /* ———— 点 ———— */
  D.addEventListener('click', function (e) {
    var t = e.target;
    if (!t.closest) return;
    var c = t.closest('[data-copy]');
    if (c) { e.preventDefault(); copy(c.getAttribute('data-copy') || c.textContent.trim()); return; }
    var sh = t.closest('[data-sheet]');
    if (sh) {
      e.preventDefault();
      var ref = sh.getAttribute('data-sheet'), src = ref && ref !== 'self' ? D.querySelector(ref) : sh.querySelector('.fg-detail, template');
      var node = null, html = '';
      if (src && src.tagName === 'TEMPLATE') node = src.content.cloneNode(true);
      else if (src) { node = src.cloneNode(true); node.hidden = false; node.removeAttribute('id'); node.style.display = ''; }
      else html = sh.getAttribute('data-body') || '';
      sheet({ title: sh.getAttribute('data-title') || (src && src.getAttribute && src.getAttribute('data-title')) || '', node: node, html: html });
      return;
    }
    var dg = t.closest('[data-dialog]');
    if (dg) { e.preventDefault(); dialog({ title: dg.getAttribute('data-title') || '', text: dg.getAttribute('data-dialog'), ok: dg.getAttribute('data-ok') || '好', cancel: dg.hasAttribute('data-cancel') ? dg.getAttribute('data-cancel') : false }); return; }
    var op = t.closest('[data-open]');
    if (op) { e.preventDefault(); open(op.getAttribute('data-open')); return; }
    var a = t.closest('a[href]');
    if (a) {
      var h = a.getAttribute('href');
      if (h.charAt(0) === '#') { var tg = h.length > 1 && D.getElementById(decodeURIComponent(h.slice(1))); if (tg) { e.preventDefault(); tg.scrollIntoView({ behavior: reduce ? 'auto' : 'smooth', block: 'start' }); } return; }
      if (/^https?:/i.test(a.href) && a.host !== location.host) { e.preventDefault(); open(a.href); }
      return;
    }
    if (t.tagName === 'IMG' && !t.hasAttribute('data-no-zoom') && !t.closest('a,button,[data-sheet],[data-open],.fg-card') && t.naturalWidth > 0) { zoom(t); }
  });
  D.addEventListener('keydown', function (e) { if (e.key === 'Escape' && stack.length) { e.preventDefault(); stack[stack.length - 1](); } });

  /* ———— 扫一遍，之后新加的也照顾到 ———— */
  function scan(root) {
    (root.querySelectorAll ? root : D).querySelectorAll('img').forEach(function (i) {
      prep(i);
      if (!i.hasAttribute('data-no-zoom') && !i.closest('a,button,[data-sheet],[data-open],.fg-card')) i.classList.add('fg-zoomable');
    });
    (root.querySelectorAll ? root : D).querySelectorAll('.fg-tabs').forEach(tabs);
    (root.querySelectorAll ? root : D).querySelectorAll('[data-sheet],[data-dialog],[data-copy],[data-open],.fg-card').forEach(function (x) {
      if (!x.hasAttribute('tabindex') && !/^(A|BUTTON)$/.test(x.tagName)) x.setAttribute('tabindex', '0');
      if (!x.hasAttribute('role') && !/^(A|BUTTON)$/.test(x.tagName)) x.setAttribute('role', 'button');
    });
  }
  D.addEventListener('keydown', function (e) {
    if ((e.key === 'Enter' || e.key === ' ') && e.target.matches && e.target.matches('[role=button][tabindex]')) { e.preventDefault(); e.target.click(); }
  });
  function boot() {
    scan(D);
    new MutationObserver(function (ms) { ms.forEach(function (m) { m.addedNodes.forEach(function (n) { if (n.nodeType === 1) { if (n.tagName === 'IMG') prep(n); scan(n); } }); }); })
      .observe(D.body, { childList: true, subtree: true });
  }
  if (D.readyState === 'loading') D.addEventListener('DOMContentLoaded', boot); else boot();
  // 图片在 DOMContentLoaded 前就失败的：捕获阶段兜住
  D.addEventListener('error', function (e) { if (e.target && e.target.tagName === 'IMG') noimg(e.target); }, true);

  window.fg = {
    sheet: sheet, dialog: dialog, toast: toast, copy: copy, open: open, zoom: zoom,
    /** App 的返回键先问这里：有开着的层就关最上面那层，返回 true。 */
    back: function () { if (!stack.length) return false; stack[stack.length - 1](); return true; }
  };
})();
