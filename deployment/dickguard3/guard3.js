'use strict';
const crypto = require('crypto');

// Process-local challenges; existing signed capabilities remain the source of authorization.
// A service restart starts a fresh chain. Proof is continuity evidence, not an unforgeable client identity.
function createGuard3(now = Date.now) {
  const sessions = new Map();
  const ttl = 24 * 3600 * 1000;
  return function guard3(q, account, subject, rimeAllowed) {
    const strict = q.guard === 'dickguard3.0';
    if (q.guard !== undefined && !strict) return {error:'unsupported_guard'};
    const client = q.client === undefined ? 'superdickclient' : q.client;
    const entry = {guizhouware:'dev.guizhou.GuizhouWare',superdickclient:'com.dickclient.DickClient'}[client];
    if (typeof entry !== 'string' || (client === 'guizhouware' && (!strict || !rimeAllowed))) return {error:'guard_client_not_allowed'};
    if (q.integrity !== undefined && !['mismatch','unavailable','read_error'].includes(q.integrity)) return {error:'bad_integrity'};
    if (q.env !== undefined && (typeof q.env !== 'string' || q.env.length > 1024)) return {error:'bad_environment'};
    const timestamp = now();
    for (const [key,value] of sessions) if (timestamp-value.time > ttl) sessions.delete(key);
    const key = subject+'|'+q.session+'|'+client;
    const old = sessions.get(key);
    if (strict && old) {
      if (old.nonces.includes(q.nonce)) return {error:'replayed_nonce'};
      const valid = typeof q.challengeProof === 'string' && /^[0-9a-f]{64}$/.test(q.challengeProof)
        && old.challenges.some(c => crypto.timingSafeEqual(Buffer.from(q.challengeProof,'hex'),
          crypto.createHash('sha256').update('dick-guard-echo-v1|'+c+'|'+q.session+'|'+q.nonce).digest()));
      if (!valid) return {error:'bad_challenge_proof'};
    }
    if (!old && sessions.size >= 20000) return {error:'guard_capacity'};
    const challenge = crypto.randomBytes(16).toString('hex');
    const seed = crypto.createHash('sha256').update('dick-guard-entry-v1|'+q.session+'|'+q.nonce+'|'+challenge).digest();
    const cipher = Buffer.from(entry,'utf8');
    for (let i=0;i<cipher.length;i++) cipher[i] ^= seed[i];
    sessions.set(key,{time:timestamp,nonces:[...(old ? old.nonces : []),q.nonce].slice(-32),
      challenges:[challenge,...(old ? old.challenges : [])].slice(0,2)});
    const data = {entryChallenge:challenge,entryCipher:cipher.toString('base64url')};
    if (strict) {
      const previous = account.guard3 || {};
      const unavailable = q.integrity === 'unavailable';
      const count = unavailable ? Math.min(1000000,(Number(previous.unavailableCount)||0)+1) : 0;
      account.guard3 = {client,lastSeen:timestamp,unavailableCount:count,
        environment:q.env || previous.environment || '',observedAt:q.env ? timestamp : previous.observedAt || 0};
      if (count >= 3) data.integrityWarning = 'verification_unavailable';
    }
    return {data};
  };
}
module.exports = {createGuard3};
