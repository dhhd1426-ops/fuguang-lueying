import {createHash,randomBytes} from 'node:crypto';
import {writeFile} from 'node:fs/promises';
const domain=process.argv[2];
if(!domain||!/^(?=.{1,253}$)([a-z0-9](?:[a-z0-9-]*[a-z0-9])?\.)+[a-z]{2,63}$/i.test(domain)){throw Error('Usage: node deploy-mainland/init-config.mjs your-domain.example');}
const passwordHash=createHash('sha256').update('浮光掠影').digest('hex');
await writeFile(new URL('.env',import.meta.url),`DOMAIN=${domain}\nPUBLIC_ORIGIN=https://${domain}\nPASSWORD_HASH=${passwordHash}\nSESSION_SECRET=${randomBytes(32).toString('hex')}\n`,{flag:'wx',mode:0o600});
console.log('Deployment configuration created. Keep .env private.');
