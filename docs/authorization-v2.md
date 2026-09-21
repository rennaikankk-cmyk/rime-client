# Authorization protocol v2 rollout

The backend must be deployed and tested before publishing clients that require per-copy inventories.
Old login, HWID, legacy heartbeat and legacy direct nonce protocols remain accepted. No account
migration, binding reset, forced concurrent-session limit, or signing-key rotation is required.

New clients use HTTPS with a dedicated trust store containing the existing server certificate
(SHA-256 B836788B4FE0A75F7D324B551445DC109B3AEAF2FC440E3DDC9EAD0A38FE36FD).
Hostname checking stays enabled; there is no HTTP fallback or global trust override. Before any
future TLS certificate rotation, distribute an overlapping trust set. The current certificate
expires 2036-09-14. Legacy HTTP remains available only for compatibility with older clients.

Every new heartbeat includes protocol=2, a new random nonce, a process-local random session and
the existing credential/HWID. Ed25519 signatures cover the nonce, credential SHA-256, session,
issuedAt, freshness duration, grace duration and decision. The client checks bindings and bounds
and subtracts round-trip time from signed durations using a monotonic clock. No dependency on a
member's wall clock is introduced for heartbeat freshness.

Ordinary clients refresh every 120 seconds and retry failures after 30 seconds. Freshness lasts
at most 15 minutes. The pre-existing 24-hour offline allowance is retained for this compatibility
rollout, bounded by the server-observed subscription and capability expiration. Direct clients
retain their 30-second heartbeat / 120-second maximum allowance. Explicit signed refusals destroy
the current lease; errors or replayed/misbound replies cannot create or renew a lease. This does
not promise immediate offline revocation: an unreachable ordinary client can use its remaining
signed grace period. On process restart, successful online authorization is still required.

The downloader signs an inventory after adding per-account credentials and watermark entries.
New clients verify all their own JAR entries and the credential subject before renewal. Other
Fabric mods and transformed game classes are not hashed. Definitive modification stops the local
run. A temporary read error permits only an already-issued, unexpired grace period and is retried.
Owner-requested automatic enforcement (2026-09-21, backend-only update): an explicit
integrity=mismatch report on v2 or the legacy dedicated nonce protocol automatically bans
the reporting account only after validating its unexpired capability, same-account HMAC
watermark and existing device binding. Dedicated unbound accounts must use the dedicated
device marker. Reports may not create or migrate HWID bindings. Invalid/missing/cross-account
watermarks, network failures, read errors and old ordinary heartbeats do not auto-ban.

The backend persists integrityBan (reason, time, capability SHA-256, watermark issue time,
protocol) and banned=true, revokes outstanding login/download tickets and denies subsequent
login, heartbeat and downloads. An existing administrator can unban the account; the audit
record remains. Replace/restore the unmodified official copy before unbanning, or the same
modified copy can report again. A temporary database write failure does not create an
in-memory-only ban. Account credentials, membership expiry and bindings remain unchanged.

This is an explicit policy on an authenticated client report, not trusted remote attestation:
an attacker controlling a copy can suppress or forge its own report, and file corruption can
also cause an integrity mismatch. Possession of an account's token and watermark can be used
to target that same account. Offline decompilation is undetectable. Do not claim that every
crack is detected or that false positives are impossible. Existing offline grace still applies.

Current combat.3 / mc1211.3 / direct.8 clients already send the necessary report and watermark;
this backend change needs no JAR rebuild or member reinstall. Production tests must never
send a tamper report using an actual member credential; use ephemeral keys and isolated
in-memory accounts, including successful bans, invalid reports, ticket revocation and unban.

These checks raise tampering and credential-replay costs. They cannot prevent offline decompilation
or guarantee that code running on a fully controlled client cannot be patched.

Validation: run the Java lease, signed heartbeat, inventory and final obfuscated control-flow tests;
run backend-test.cjs with ephemeral keys and in-memory accounts; verify pinned HTTPS on both supported
Java 21 runtimes; smoke-test the exact final obfuscated JARs. Keep per-account secrets and production
private keys out of test output, source control, public downloads and logs.
