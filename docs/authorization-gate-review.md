# Authorization action-gate review (2026-09-22)

Reviewed the retained GLM patch against combat.9 / mc1211.9 / direct.14.
The 17 new module gates prevent their covered actions when a lease is unavailable.
This is a bounded source/control-flow review, not a claim that all client-side protection
is unpatchable or that every module in the product has been exhaustively audited.

Additional review fixes:
- Spear TP rechecks authorization in packet-freeze and pre-tick callbacks and disables
  the module on refusal, so outstanding freezing cannot keep cancelling vanilla movement.
- Probe disable sends ABORT for its outstanding dig before discarding the pending action.
- Sprint releases its own rotation request on refusal without cancelling manual sprint.
- Middle-click authorization notices apply only to the actual middle-button press.
- Regression tests check denied-path cleanup reachability, including SwordGap slot release.
- Final artifact tests now preserve the complete guarded control flow for all 17 modules;
  existing stricter prefix checks remain unchanged. New callbacks use the source CFG tests
  because some correctly check null/UI state before authorization.

SwapManager.withSwap runs callbacks inline and restores slots in finally; it does not
queue the reviewed actions for a future tick. No global gate was added to packet or
inventory cleanup utilities. No authentication protocol, membership, hardware binding,
watermark, grace duration, backend enforcement or telemetry collection change is included.
The dedicated JAR retains its existing credential and no-HWID policy.

GLM rebuilt local files under the previous release filename. Those files are not release
inputs. This release is committed first and rebuilt under new commit/version filenames;
deployment requires exact artifact hashes and dual-JDK startup/authorization acceptance.
Do not tamper-test a real credentialed JAR or send real-member mismatch reports.

Offline decompilation, suppression of client reports and a fully controlled local client
remain outside the guarantees of these gates. Automatic bans still depend on authenticated
reports and can also be triggered by actual file corruption. This patch does not change that policy.
