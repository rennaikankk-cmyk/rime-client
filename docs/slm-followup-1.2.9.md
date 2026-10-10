# SLM follow-up — 1.2.9 candidate

This records the 1.2.9 implementation and the scope of its verification. On
2026-10-02 the owner authorized pushing and publishing the demonstrated gains,
without requiring superiority in every mode. Tuning results remain qualified.

## Reference

Reviewed `m1919810/SlimefunHelper`, branch `1.21.11`, commit
`fe5ed1545d7b607746d038a8523da08f0b6753aa` (checked again 2026-10-02).
The unmodified 1.1.7.2 reference jar has SHA-256
`8ab9137b491435379ebb01c22dee01bad420e3203556ca27bdb255fbe7cca451`.
Upstream declares CC0-1.0. `Pitch40Waveform` retains the source attribution for
the optimized pitch table. The upstream speed claims have not been adopted.

## Controls and behavior

- `GuangxiElytra.TravelMode`: default `Off`; `FireworkGlide`, `Pitch40`, and
  `Pitch40Optimized` add automatic pitch control and fuel-aware rocket requests.
  `TravelHeightWindow` bounds the intended altitude excursion; collision safety,
  corrections, eating, manual movement, and aerial combat take priority.
  Without fuel, the controller requests a shallow glide. It cannot promise an
  indefinite altitude hold or terrain clearance when the physical glide cannot supply it.
- With GZBoost disabled, travel preserves vanilla glide velocity. With GZBoost
  enabled, it uses the existing protocol-gated coupled flight path. Selecting a
  travel mode does not enable GZBoost or change the Via target version.
- Mace catch-up now approaches a descending target's charge altitude instead of
  parking at an abandoned apex. Catch-up intent preserves its requested direction
  and speed through GZBoost, rather than being reshaped into another climb.
  Natural fall, reach, rotation, and terrain checks continue to authorize the hit.
- NoAC keeps horizontal and vertical speed limits independent. Precision and
  collision avoidance requests still pass through the terrain checks.
  MXS tracks vertical motion while charging or retreating. A ready attack keeps
  the spear planner's kinetic direction; a near-vertical retreat makes horizontal room rather than dropping
  below a rising opponent and immediately switching weapons again. Ordinary mace pressure has a bounded handoff to the
  mixed weapon phase. NoAC spear aim may be refreshed after movement while
  preserving the existing uncertainty sweep. These changes require real duels.
- Owned spear charges are exempt from generic main-hand-use deferral in spear
  counter-aim, counter-passes, post-movement aim, and approach preservation.
  Exact held-stack ownership, inventory/latch checks, and readiness remain
  required; food and manual item use do not acquire this exemption.
- Rotation state follows the final accepted movement packet. Normal camera
  restoration can no longer leave the preceding silent pitch cached forever.
  Cancelled packets do not update it, and a forwarded replacement's actual
  angles are not overwritten by the original packet's planned angles.
- `GuangxiElytraChase.NoACPressure`: default `true`. With `AdaptiveCombat`, a
  prolonged rising stalemate can use an ordinary cooled mace attack if the
  natural-drop solver has no setup. Actual reach, line of sight, sent rotation,
  authorization, food-use exclusion, and attack cooldown remain mandatory.
  A committed dive takes priority; an ordinary hit does not count as a smash.
- Delayed armor flight gives recovery priority over a held rocket window and
  clears stale metadata after a totem pop. Inventory ownership remains local to
  the actual pair of equipment slots; manual changes release ownership.
- Attack geometry and motion history observe received interpolation endpoints.
  Same-direction packet bursts are filtered, while a sharp horizontal or vertical
  reversal invalidates the previous prediction immediately. The experiment that
  restored rendered-position velocity sampling for Grim was rejected after duels. Predicted feet and bounding-box centers are handled
  separately so packet displacement is not applied twice.
- Identical main/offhand server stack updates preserve the held object while
  applying the authoritative inventory revision. Changed count/components use
  the normal update path. No revision forgery or inventory resync bypass was ported.
- `SelfShield` on crystals and anchors is opt-in. It evaluates at most eight
  nearby obsidian cells, requires at least 30% self-damage reduction and retention
  of 90% of each candidate target's damage, excludes active mining, and waits for
  the server block update. Existing explosive safety limits still apply.
- Crystal placement confirmation tracks received crystal identities and exact
  blast positions. Unrelated explosions/removals no longer clear another pending placement.

## Verification status

The development check suite passed 120 Gradle tasks after the rotation, owned
spear, MXS handoff, and AutoSword accepted-rotation fixes. This includes 383
direct-control checks and 616 combat wiring checks.
A real integrated-server probe (`slm129-rotation1`) passed 76 checks: both-hand
inventory identity/revision/count synchronization, actual obsidian cover and
damage retention, 18 explosion damage oracles, five module placement/attack
chains, and accepted/cancelled/replaced rotation dispatch. These establish
behavior, not competitive strength.

`slm129-ledger2` passed 46 crystal checks, six real explosion damage cases, and
a real placement/attack chain. Actual server crystal spawns populate the ledger;
removing another crystal preserves the owned cell until its own removal arrives.
The first ledger run retained a failed stale probe assertion: it expected RUSHER
to reproduce a lock bug already fixed in `9c18b2c5`. The corrected probe checks
that both modes admit an executable challenger only after it beats remembered
damage; production lock rules were not relaxed to pass the test.

Travel testing includes actual Paper 1.21.11, active Grim 2.3.73, ViaVersion and
ViaBackwards 5.12.0, ViaFabricPlus 4.4.15 with target protocol 765. The first
platform takeoff failed and is retained. A subsequent airborne seven-case run
completed its functional assertions but observed position corrections. After
the vanilla-velocity fix, `travel129-grim3` passed all seven airborne cases with
zero flight corrections. The ground-start failures exposed a separate late
wing-opening problem and the absence of a powered takeoff recovery candidate.
`travel129-ground3` then passed all six fueled ground-start combinations, also
with zero flight corrections. Staging teleports are excluded from that counter;
manual input and physical collision/rocket-arrival checks remain active.
Compact results and candidate source digests are in `verification/1.2.9-candidate`.
`armor129-via1` passed ten armor-flight inventory/recovery cases with active Grim
and Via 765. Its correction counter includes preparation and is not a claim of
zero flight corrections.

All competitive runs use real client inputs and server deaths, equal shared
settings/equipment, paired spawn sides, and recorded source/runtime hashes.
Candidate-only settings are explicitly audited. NoAC runs remove the anticheat
plugin; Grim runs retain it without exemption permissions. A timeout is a draw.
Tuning scenes are not held-out validation. Failed setup runs, losses, and draws
remain in the evidence directories.

The 18-round `diagnostic-noac4` tuning batch yielded MACE 0W/0L/6D,
SPEAR 4W/2L/0D, MXS 0W/6L/0D against Rime 1.2.8. It preceded the pressure,
post-movement NoAC aim, and MXS vertical-lane changes. It does not satisfy a
stronger-than-old-client claim. `diagnostic-noac8` reproduced the stale rotation
cache in two paired MACE draws. After that fix, `diagnostic-noac9` completed all
11 rounds it started (of 18 planned): MACE 0W/0L/3D, SPEAR 2W/2L/0D, and MXS
0W/0L/4D. Scheduling stopped after the owned-spear deferral bug was identified;
the active round finished naturally and all results remain recorded. Neither
batch constitutes held-out acceptance. Separately declared three-minute tests
do not replace the original one-minute draws or satisfy their acceptance plan.
`diagnostic-noac10` completed six paired SPEAR rounds after the owned-use fix:
3W/3L/0D, with no recorded quality flags. This does not establish superiority.
The initial complete official-SLM batch (`slm5`) yielded MACE 6W and SPEAR 6W,
but native automatic flight restart was disabled. Its results apply only to
that configuration. `slm6` explicitly enables both native auto-flight restart
and automatic takeoff, repeats the same scenes, and retains the unmodified
reference artifact. A reviewed source pin is not a reproducible-build proof
for that jar. The artifact hash is the opponent identity.

The complete `slm6` restart-enabled sensitivity batch yielded MACE 2W/0L/4D
and SPEAR 6W/0L/0D. All scenes and sides were retained. After the catch-up fix,
`slm7` repeated all six MACE rounds with the same scenes and settings: 6W/0L/0D,
all final damage sources `mace_smash`, no quality flags. The control and candidate
were each source/runtime frozen throughout their batch. These are tuning results,
not a held-out population win-rate estimate. SLM has no native MXS mode.
The current candidate's complete `slm8` regression repeats all `slm6` scenes
with automatic restart enabled: MACE 6W/0L/0D and SPEAR 6W/0L/0D, no quality
flags. Its source/runtime and official opponent artifact remained unchanged
throughout all twelve rounds. This is still tuning-scene regression, not a
population win-rate estimate or held-out acceptance.
Four mace wins ended with `mace_smash`, two with `flyIntoWall`; all spear
wins ended with `spear`. Environmental deaths count under the declared rule,
but the two wall deaths are not described as mace kills.

`diagnostic-noac-endurance1` completed all six separately declared three-minute
rounds against 1.2.8: MACE 2W (1652/2384 ticks), SPEAR 2W (198/152 ticks),
MXS 1W/1L (723/1538 ticks). All were actual deaths, with no quality flags.
The MXS loss remains recorded: completed charge led into a downward pull-over,
then the target-above threshold immediately switched back to mace. A subsequent
lane revision retains vertical tracking while charged and makes horizontal room
for a near-vertical pull-over. `endurance2` repeated the same paired MXS scene,
including the loss, with the same old-first join order: 2W/0L/0D at 982/325 ticks.
Both deaths occurred within the original one-minute duration, though this pair
remains a tuning test and does not replace independent held-out acceptance.
The catch-up revision passed all 120 development tasks; the subsequent lane
revision passed 386 direct-control and 616 combat wiring checks.
`endurance3` repeated the complete six-round three-minute matrix on the later
candidate: MACE 2W (2537/2507 ticks), SPEAR 1W/1L (104/176 ticks), MXS 1W/1D
(1398/3600 ticks), with no quality flags. Its full development check then passed
120 tasks. This exposed a remaining MXS conflict: unconditional height tracking
can turn a ready downward spear pass into upward motion, making motion projected
along the attack ray negative. The follow-up restricts this tracking to charge
and retreat; the regression reproduces the sign reversal and checks that the
ready pass retains its planned velocity. This change passed 391 direct-control
and 616 combat wiring checks. `endurance4` repeated both MXS sides and finished
2W/0L/0D (3117/1490 ticks), with no quality flags. Actual server observations
recorded two spear damage events in the first round and five in the second;
both final deaths were caused by spear damage. The former draw became a win,
but the first win slowed from 1398 to 3117 ticks. This is evidence of restored
kinetic passes, not a general speedup or held-out superiority.
The final MXS revision then passed the complete development check (120 actionable
Gradle tasks, 117 executed, 3 up-to-date, 69 seconds). Its business sources match
`endurance4`; `verification/1.2.9-candidate/development-check.json` records the
source and log digests. Empty standard test-source tasks are listed separately;
the regression programs run through the `flightTest` JavaExec tasks.

The fresh Grim `tune4` matrix against 1.2.8 yielded MACE 2W/4L and SPEAR 3W/3L.
The complete same-scene abrupt-reversal experiment (`tune5`) yielded MACE 1W/5L
and SPEAR 4W/2L. Both are retained; the reversal change was not a general Grim
improvement. `tune6` restored the established velocity sampling for Grim and yielded
MACE 0W/6L and SPEAR 3W/3L; this experiment was rejected and removed. The current
`tune7` candidate retains endpoint sampling and limits precise catch-up to
descending/non-rising pursuit. Nearby rising-target staging preserves an acquired
height advantage; excessive altitude still closes down toward the target. Its
regressions passed 3037 engagement checks, 117 target-motion assertions, and
616 combat wiring checks. `tune7` completed MACE 3W/2L/1D and SPEAR 4W/2L with old-first join order.
`tune8` rejected the join-order comparison before any round: generated build
timestamps changed two runtime classes. A byte-level replacement/hash check
confirmed timestamp-only differences. Its failure is retained. The replacement
`tune9` fixes generated timestamps in its test-only Gradle init script, repeats
every scene and side with new-first join order, and requires exact matching
business-source and runtime-class snapshots before any round. Its initial
configuration failures are also retained; neither failure started a duel.
`tune9` completed with MACE 2W/3L/1D and SPEAR 4W/2L/0D, no quality flags.
Combined with `tune7`, the unchanged candidate yielded MACE 5W/5L/2D and
SPEAR 8W/4L/0D across both join orders. These repeated tuning scenes do not
establish held-out superiority; in particular, mace has not improved overall.

Held-out competitive acceptance was not achieved and is not claimed. Grim mace
has not demonstrated superiority over 1.2.8, and NoAC spear remains inconsistent
in these paired runs. The owner accepted these limits and authorized release;
production packaging, signed dual-JDK starts and distribution hashes are recorded
separately by the release workflow. 1.21.1 remains unchanged. The final MXS-only revision
does not alter standalone mace/spear or Grim movement branches; the older batch
hashes nevertheless identify those exact tested revisions, not the final tree.
