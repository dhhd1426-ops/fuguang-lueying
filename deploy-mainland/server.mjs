import http from 'node:http';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { realpath, stat } from 'node:fs/promises';
import { createReadStream } from 'node:fs';
import { Readable } from 'node:stream';
import { pipeline } from 'node:stream/promises';
import { gate } from './password-gate.mjs';

const publicRoot = await realpath(fileURLToPath(new URL('../public', import.meta.url)));
const origin = new URL(process.env.PUBLIC_ORIGIN || 'https://localhost');
if (origin.protocol !== 'https:' || origin.pathname !== '/' || origin.search || origin.hash || origin.username || origin.password) throw Error('PUBLIC_ORIGIN must be an HTTPS origin.');
const env = { PASSWORD_HASH: process.env.PASSWORD_HASH, SESSION_SECRET: process.env.SESSION_SECRET };
if (!/^[a-f0-9]{64}$/.test(env.PASSWORD_HASH || '') || (env.SESSION_SECRET || '').length < 32) throw Error('Configure PASSWORD_HASH and SESSION_SECRET first.');
const types = { '.html':'text/html; charset=utf-8', '.css':'text/css; charset=utf-8', '.js':'text/javascript; charset=utf-8', '.json':'application/json; charset=utf-8', '.jpg':'image/jpeg', '.png':'image/png', '.mp4':'video/mp4', '.svg':'image/svg+xml', '.woff2':'font/woff2' };
function readBody(req) {
  return new Promise((resolve,reject)=>{
    let size=0,failed=false;const parts=[];
    req.on('data',chunk=>{size+=chunk.length;if(size>2048){if(!failed){failed=true;reject(Object.assign(Error('too large'),{status:413}));}return;}parts.push(chunk);});
    req.on('end',()=>{if(!failed)resolve(Buffer.concat(parts));});req.on('error',reject);
  });
}
async function assets(request) {
  if (!['GET','HEAD'].includes(request.method)) return new Response('Method not allowed',{status:405,headers:{Allow:'GET, HEAD'}});
  let pathname;try{pathname=decodeURIComponent(new URL(request.url).pathname);}catch{return new Response('Bad path',{status:400});}
  if(pathname==='/')pathname='/index.html';
  if(pathname.includes('\\')||pathname.includes('\0')||pathname.split('/').some(s=>s.startsWith('.')))return new Response('Not found',{status:404});
  let file,info;try{file=await realpath(path.resolve(publicRoot,'.'+pathname));if(!file.startsWith(publicRoot+path.sep))return new Response('Not found',{status:404});info=await stat(file);if(!info.isFile())throw Error('not file');}catch{return new Response('Not found',{status:404});}
  const headers={'Content-Type':types[path.extname(file)]||'application/octet-stream','Accept-Ranges':'bytes','X-Content-Type-Options':'nosniff'};
  let start=0,end=info.size-1,status=200;
  const range=request.headers.get('range');
  if(range&&request.method==='GET') {
    const match=/^bytes=(\d*)-(\d*)$/.exec(range);
    if(!match||(!match[1]&&!match[2]))return new Response(null,{status:416,headers:{'Content-Range':'bytes */'+info.size}});
    if(!match[1]){start=Math.max(0,info.size-Number(match[2]));}else{start=Number(match[1]);if(match[2])end=Math.min(end,Number(match[2]));}
    if(!Number.isSafeInteger(start)||!Number.isSafeInteger(end)||start>end||start>=info.size)return new Response(null,{status:416,headers:{'Content-Range':'bytes */'+info.size}});
    status=206;headers['Content-Range']=`bytes ${start}-${end}/${info.size}`;
  }
  headers['Content-Length']=String(Math.max(0,end-start+1));
  const body=request.method==='HEAD'||!info.size?null:Readable.toWeb(createReadStream(file,{start,end}));
  return new Response(body,{status,headers});
}
const server=http.createServer(async(req,res)=>{
  try {
    const headers=new Headers();for(const [k,v]of Object.entries(req.headers)){if(v!==undefined)headers.set(k,Array.isArray(v)?v.join('; '):v);}
    const init={method:req.method,headers};if(!['GET','HEAD'].includes(req.method))init.body=await readBody(req);
    const request=new Request(new URL(req.url,origin),init);
    if(new URL(request.url).origin!==origin.origin){res.writeHead(400);res.end();return;}
    const response=await gate(request,env,()=>assets(request));
    res.writeHead(response.status,Object.fromEntries(response.headers));
    if(!response.body||req.method==='HEAD')res.end();else await pipeline(Readable.fromWeb(response.body),res);
  }catch(error){if(!res.headersSent){res.writeHead(error.status||500,{'Content-Type':'text/plain; charset=utf-8'});res.end(error.status===413?'请求过大':'服务暂时不可用');}else res.destroy();}
});
server.requestTimeout=15000;server.headersTimeout=10000;
server.listen(Number(process.env.PORT||3000),process.env.HOST||'127.0.0.1',()=>console.log('Fuguang server ready.'));
