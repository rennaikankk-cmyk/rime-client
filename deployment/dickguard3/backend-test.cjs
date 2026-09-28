'use strict';
// Full production functions, ephemeral keys, in-memory accounts and synthetic JARs only.
const fs=require('fs'),vm=require('vm'),crypto=require('crypto'),assert=require('assert/strict');
const path=require('path'),AdmZip=require('/opt/gzwx-auth/node_modules/adm-zip');
const {capabilityForAccount}=require('/opt/gzwx-auth/telemetry-token');
const source=fs.readFileSync(process.argv[2],'utf8');
const keys=crypto.generateKeyPairSync('ed25519'), key=crypto.randomBytes(32);
const hwid='fixture-device-123456789', account={id:'1234abcd',hwid,modHwid:hwid,modHwidBoundAt:1,expiresAt:Date.now()+86400e3,banned:false};
const cfg={ed25519Priv:keys.privateKey.export({format:'pem',type:'pkcs8'}),wmKey:'fixture',versions:{'1.21.1':'jars/fixture.jar'},dbFile:'data.json',rateLimit:{windowMs:60000,max:300}};
account.salt='aabbccdd00112233445566778899aabbcc';account.hash=crypto.scryptSync('fixture-password',Buffer.from(account.salt,'hex'),64).toString('hex');
let saved=0,result;
const jar=new AdmZip();jar.addFile('fabric.mod.json',Buffer.from('{"id":"guizhouware"}'));jar.addFile('code.class',Buffer.from('fixture'));
const fakeFs={...fs,readFileSync(p){
 if(p==='/fixture/config.json')return JSON.stringify(cfg);
 if(p==='/fixture/data.json')return JSON.stringify({accounts:{fixture:account},cards:{}});
 if(p==='/opt/gzwx-telemetry/upload.key')return key;
 throw Error('Unexpected file read '+p);
},writeFileSync(p){assert.equal(p,'/fixture/data.json.tmp');saved++;},renameSync(a,b){assert.equal(a,'/fixture/data.json.tmp');assert.equal(b,'/fixture/data.json');}};
const context=vm.createContext({Buffer,URL,Date,Map,Set,setTimeout,clearTimeout,setInterval(){return {unref(){}};},console:{log(){}},__dirname:'/fixture',process:{env:{}},require(id){
 if(id==='fs')return fakeFs;
 if(id==='adm-zip')return function(p){assert.equal(p,'/fixture/jars/fixture.jar');return new AdmZip(jar.toBuffer());};
 if(id==='./telemetry-token')return {capabilityForAccount};
 if(id==='./guard3')return require('./guard3');
 return require(id);
}});
const end=source.indexOf('https.createServer(');assert(end>0);
vm.runInContext(source.slice(0,end)+`;this.api={handleHeartbeat,handleLogin,watermarkedJar,db,hbRate,hbRateOk};`,context);
const api=context.api, ac=api.db.accounts.fixture;
const token=capabilityForAccount(key,ac,Date.now());
const request={token,hwid}, nonce='a'.repeat(32), session='b'.repeat(32);
const modern={...request,nonce,session,protocol:'2'};
function res(){return {writeHead(code){this.code=code;},end(body){result={code:this.code,...JSON.parse(body)};const canonical=Object.keys(result.data).sort().map(k=>k+'='+result.data[k]).join('&');assert(crypto.verify(null,Buffer.from(canonical),keys.publicKey,Buffer.from(result.sig,'hex')));}};}
function beat(q){api.handleHeartbeat(q,res(),'203.0.113.8');return result;}
function ok(q){const r=beat(q);assert.equal(r.code,200);assert.equal(r.data.ok,'true');return r.data;}
const legacy=ok(request);assert(!('nonce'in legacy));assert(!('protocol'in legacy));assert.equal(legacy.next,'600');
assert.equal(ok({...request,nonce}).nonce,nonce);
let d=ok(modern);assert.equal(d.protocol,'2');assert.equal(d.session,session);assert.equal(d.nonce,nonce);assert.equal(d.subject,crypto.createHash('sha256').update(token).digest('hex'));assert(Number(d.issuedAt)>0);assert.equal(d.leaseSeconds,'900');assert(Number(d.graceSeconds)<=86400);
for(const q of [{...modern,nonce:''},{...modern,session:'x'},{...modern,protocol:'3'},{...request,protocol:'2'}])assert.equal(beat(q).code,400);
assert.equal(beat({...modern,integrity:'mismatch'}).data.reason,'integrity_mismatch');assert.equal(ac.banned,false);
ok({...modern,integrity:'read_error'});assert.equal(ac.banned,false);
ac.directIntegrity=true;assert.equal(beat(request).data.reason,'direct_protocol_required');assert.equal(ok(modern).graceSeconds,'120');
assert.equal(beat({...request,nonce,integrity:'mismatch'}).data.reason,'integrity_mismatch');assert.equal(ac.banned,false);delete ac.directIntegrity;
ac.banned=true;d=beat(modern).data;assert.equal(d.reason,'banned');assert.equal(d.session,session);ac.banned=false;
const expiry=ac.expiresAt;ac.expiresAt=Date.now()+5000;d=ok(modern);assert(Number(d.graceSeconds)<=5);assert(Number(d.leaseSeconds)<=5);
ac.expiresAt=Date.now()-1;assert.equal(beat(modern).data.reason,'expired');ac.expiresAt=expiry;
assert.equal(beat({...modern,hwid:'wrong-device-123456789'}).data.reason,'hwid');assert.equal(ac.hwid,hwid);assert.equal(ac.modHwid,hwid);
api.handleLogin({username:'fixture',password:'fixture-password',hwid},res());assert.equal(result.data.ok,'true');assert.equal(result.data.versions,'1.21.1');
const output=api.watermarkedJar('1.21.1',ac,hwid), packaged=new AdmZip(output.buf);
const envelope=packaged.getEntry('META-INF/gzwx-integrity.signature').getData().toString().split('\n');
const payload=Buffer.from(envelope[0],'base64');assert(crypto.verify(null,payload,keys.publicKey,Buffer.from(envelope[1],'hex')));
const lines=payload.toString().trimEnd().split('\n');assert.equal(lines[2],'license=account_1234abcd');
const issuedToken=JSON.parse(packaged.getEntry('META-INF/gzwx-telemetry.json').getData()).token;
assert.equal(lines[1],'subject='+crypto.createHash('sha256').update(issuedToken).digest('hex'));
assert.equal(lines.length-3,packaged.getEntries().filter(e=>!e.isDirectory).length-1);
for(const line of lines.slice(3)){const [digest,name]=line.split(' ');assert.equal(crypto.createHash('sha256').update(packaged.getEntry(Buffer.from(name,'base64url').toString()).getData()).digest('hex'),digest);}
assert.equal(crypto.createHash('sha256').update(output.buf).digest('hex'),output.sha256);
for(let i=0;i<31;i++)api.hbRateOk('first-device');assert.equal(api.hbRateOk('first-device'),false);assert.equal(api.hbRateOk('second-device'),true);
assert.equal(ac.banned,false);assert.equal(ac.hwid,hwid);assert.equal(ac.modHwid,hwid);assert.equal(ac.expiresAt,expiry);
console.log('PASS: legacy login and heartbeat, nonce/session/subject binding, expiry caps, deny/retry, integrity without account bans, signed per-copy inventory, isolated rate buckets; all account data in memory.');

cfg.versions['1.21.11']='jars/fixture.jar';
// The VM read a JSON copy of cfg; update its copy using the exposed source context.
vm.runInContext("CFG.versions['1.21.11']='jars/fixture.jar'",context);
api.hbRate.clear();
let step=100, previous;
const guarded={...modern,guard:'dickguard3.0',client:'guizhouware',session:'c'.repeat(32)};
function next(extra={}) {
  const q={...guarded,nonce:(step++).toString(16).padStart(32,'0'),...extra};
  if(previous)q.challengeProof=crypto.createHash('sha256').update('dick-guard-echo-v1|'+previous+'|'+q.session+'|'+q.nonce).digest('hex');
  return q;
}
function guardedOk(extra={}) {const q=next(extra);const d=ok(q);previous=d.entryChallenge;return {q,d};}
let g=guardedOk();
const seed=crypto.createHash('sha256').update('dick-guard-entry-v1|'+g.q.session+'|'+g.q.nonce+'|'+g.d.entryChallenge).digest();
const decoded=Buffer.from(g.d.entryCipher,'base64url');for(let i=0;i<decoded.length;i++)decoded[i]^=seed[i];
assert.equal(decoded.toString(),'dev.guizhou.GuizhouWare');
assert.equal(beat(g.q).data.reason,'replayed_nonce');
assert.equal(beat({...next(),challengeProof:'0'.repeat(64)}).data.reason,'bad_challenge_proof');
g=guardedOk({integrity:'unavailable',env:'agent:test'});assert(!g.d.integrityWarning);
guardedOk({integrity:'unavailable'});g=guardedOk({integrity:'unavailable'});
assert.equal(g.d.integrityWarning,'verification_unavailable');assert.equal(ac.banned,false);
assert.equal(ac.guard3.unavailableCount,3);assert.equal(ac.guard3.environment,'agent:test');
guardedOk();assert.equal(ac.guard3.unavailableCount,0);
assert.equal(beat(next({client:'__proto__'})).data.reason,'guard_client_not_allowed');
assert.equal(beat(next({guard:'other'})).data.reason,'unsupported_guard');
assert.equal(beat(next({integrity:'invented'})).data.reason,'bad_integrity');
assert.equal(beat(next({env:'x'.repeat(1025)})).data.reason,'bad_environment');
ac.freeTier=true;assert.equal(beat(next()).data.reason,'guard_client_not_allowed');delete ac.freeTier;
const lost=next();ok(lost); // Response lost: previous challenge is retained for one recovery.
guardedOk();
assert.equal(ac.banned,false);assert.equal(ac.hwid,hwid);assert.equal(ac.modHwid,hwid);
console.log('PASS GUARD3: signed Rime entry material, challenge chain/replay rejection, lost response recovery, tri-state integrity warnings without bans, env observation, free-tier boundary, malformed-client rejection.');
