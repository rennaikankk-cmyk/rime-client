# Minecraft 1.21.1 alignment probes

Development-only fixtures, excluded from the shipped JAR. Use JDK 21 and copy an existing **1.21.1** test world into `tools/port-probe/runs/<name>/saves/ProbeWorld`. Never run these against your original world: fixtures replace terrain and entities in the copied world.

First package only the fixture with the ordinary build flag:

```powershell
./gradlew.bat -I tools/port-probe/probe.gradle portProbeJar
./gradlew.bat -PgzwDevelopmentBuild=true -I tools/port-probe/probe.gradle -PportRun=flat1 -PportProbe=flat runClient
```

Use a separate fresh run directory per scenario. `portProbe` accepts `flat`, `font`, `ui`, or `mace`. The font scenario requires launcher-downloaded `guizhouware/fonts/anthropic-serif-{regular,semibold,bold}.ttf` and the optional `ib_zh.ttf` in the isolated instance. A failed fixture may still exit Minecraft normally: inspect `failure.txt`, `ui-failure.txt`, complete markers and the full log, not just Gradle's exit status. Always restore `./gradlew.bat compileJava` afterward; production packaging refuses development-authorized classes.

- Flat: same-plane target six blocks away, minimum damage/reach rejection, unchanged Rusher search, actual obsidian placement, crystal placement/attack and server health loss.
- UI: enabled-module list and watchlist, damage snapshot expiry, TP damage overlay and all four bed orientations compared with vanilla shapes, with screenshots.
- Font: all three real font family names, custom renderer and nametag metrics, with screenshot.
- Mace: real integrated server, fixed zombie, ordinary extra attacks enabled to exercise the automatic smash gate. Counts smash only when the server's `MaceItem.canSmashAttack` gate is true before damage and health subsequently decreases. Native 1.21.1 labels this damage `player`, so the newer `mace_smash` string must not be used as the oracle. Equipment/cursor restoration is checked. The unused spear slot holds a trident; this fixture intentionally supports MACE/ARMOR only and does not claim native spear behavior.

These fixtures validate local vanilla-server behavior, not public-server compatibility or PvP win rates. Production signing and both Java startup checks remain separate.
