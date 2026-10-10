# HUD state / allocation fixture

Runs real HudClientModule, config and setting event paths in a disposable development client, with no game server or production credentials. It restores the test instance's HUD settings and stops its own client afterwards.

```powershell
./gradlew.bat --offline -I tools/hud-state-probe/probe.gradle hudStateProbeJar
./gradlew.bat --offline -PgzwDevelopmentBuild=true -I tools/hud-state-probe/probe.gradle -PprobeRun=baseline runClient
./gradlew.bat --offline -PgzwDevelopmentBuild=true -I tools/hud-state-probe/probe.gradle -PprobeRun=candidate runClient
```

Build the same test jar before changing the HUD implementation. Each run needs a fresh directory under `../hud-state-lab/`. Require `results.json` and no `failure.txt`; inspect the full startup log too.

Checks: every exact-type lookup and live toggle; first-match behavior for the HUD base class; immediate color, alpha and opacity changes; custom colors survive migration; color events rerun legacy migration; imported legacy/custom settings; disabled silent edits followed by re-enabling. The candidate should reuse unchanged appearance instances. Five warmed batches of 50,000 stable HUD state passes are recorded as a microbenchmark, not whole-game frame time or FPS.
