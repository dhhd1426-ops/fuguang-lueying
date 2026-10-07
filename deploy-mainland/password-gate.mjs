const enc=new TextEncoder();
const headers={'Content-Type':'text/html; charset=utf-8','Cache-Control':'private, no-store','X-Content-Type-Options':'nosniff','Referrer-Policy':'same-origin'};
export const loginPage=(error='')=>`<!doctype html><html lang="zh-CN"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>进入浮光</title><style>*{box-sizing:border-box}body{margin:0;min-height:100svh;display:grid;place-items:center;background:radial-gradient(ellipse at 70% 20%,#59616b,transparent 65%),linear-gradient(145deg,#333e48,#403539);color:#faf5ee;font-family:Arial,"Microsoft YaHei",sans-serif;padding:24px}main{width:min(360px,100%);padding:36px 28px;border:1px solid #ffffff30;border-radius:22px;background:#ffffff0a}small{font-size:9px;letter-spacing:5px;color:#d6c2b1}h1{font-weight:400;font-size:32px;letter-spacing:7px;margin:18px 0}p,label{font-size:12px;color:#d5cfc8;line-height:1.8}label{display:block;margin:26px 0 8px}input,button{font:inherit;width:100%;padding:13px;border-radius:9px}input{border:1px solid #ffffff30;background:#ffffff09;color:#fff;font-size:16px}input:focus{outline:2px solid #eac3a5}button{border:0;background:#ebcfb4;color:#302d2c;margin-top:18px;cursor:pointer}.error{color:#ffb9ae;min-height:21px;margin:10px 0 0}</style><main><small>FUGUANG</small><h1>浮光</h1><p>输入访问密码，进入这片流动的光影。</p><form method="post" action="/unlock"><label for="password">访问密码</label><input id="password" name="password" type="password" autocomplete="current-password" required maxlength="128" autofocus><p class="error" role="status">${error}</p><button>进入此刻 →</button></form></main></html>`;
async function digest(value){return [...new Uint8Array(await crypto.subtle.digest('SHA-256',enc.encode(value)))].map(x=>x.toString(16).padStart(2,'0')).join('')}
async function key(secret){return crypto.subtle.importKey('raw',enc.encode(secret),{name:'HMAC',hash:'SHA-256'},false,['sign','verify'])}
async function sign(payload,secret){return [...new Uint8Array(await crypto.subtle.sign('HMAC',await key(secret),enc.encode(payload)))].map(x=>x.toString(16).padStart(2,'0')).join('')}
async function valid(request,secret){const token=request.headers.get('cookie')?.match(/(?:^|;\s*)__Host-fuguang=([^;]+)/)?.[1];if(!token)return false;const [expiry,nonce,signature,...rest]=token.split('.');if(rest.length||!/^\d{13}$/.test(expiry)||!/^\w{32}$/.test(nonce)||!/^[a-f0-9]{64}$/.test(signature)||Number(expiry)<=Date.now()||Number(expiry)>Date.now()+7*86400000+60000)return false;const bytes=new Uint8Array(signature.match(/../g).map(x=>parseInt(x,16)));return crypto.subtle.verify('HMAC',await key(secret),bytes,enc.encode(expiry+'.'+nonce))}
export async function gate(request,env,next){
 if(!env.PASSWORD_HASH||!env.SESSION_SECRET)return new Response('访问验证暂时不可用',{status:503,headers});
 const url=new URL(request.url);
 if(url.pathname==='/unlock'&&request.method==='POST'){
  if(request.headers.get('origin')!==url.origin)return new Response('请求来源无效',{status:403,headers});
  if(Number(request.headers.get('content-length')||0)>2048)return new Response('请求过大',{status:413,headers});
  let password;try{const form=await request.formData();password=form.get('password')}catch{return new Response(loginPage('请输入有效密码'),{status:400,headers})}
  if(typeof password!=='string'||password.length>128||await digest(password)!==env.PASSWORD_HASH)return new Response(loginPage('密码不正确，请重试。'),{status:401,headers});
  const nonce=[...crypto.getRandomValues(new Uint8Array(16))].map(x=>x.toString(16).padStart(2,'0')).join('');const payload=(Date.now()+7*86400000)+'.'+nonce;const token=payload+'.'+await sign(payload,env.SESSION_SECRET);
  return new Response(null,{status:303,headers:{Location:'/', 'Cache-Control':'no-store','Set-Cookie':'__Host-fuguang='+token+'; Path=/; Max-Age=604800; HttpOnly; Secure; SameSite=Lax'}});
 }
 if(!await valid(request,env.SESSION_SECRET)){if(url.pathname==='/'||url.pathname==='/index.html')return new Response(loginPage(),{headers});return new Response('请先输入访问密码',{status:401,headers})}
 const response=await next();const safe=new Response(response.body,response);safe.headers.set('Cache-Control','private, no-store');return safe;
}
