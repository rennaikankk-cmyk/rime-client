# Automatic bed probe

Runs a real Minecraft 1.21.11 client and integrated server against a **copy** of a supplied disposable world. The fixture changes blocks, inventory and dimensions only in that copy. Java 21 and cached Gradle dependencies are required.

```powershell
tools/bed-auto-probe/run-probe.ps1 -Name verified -WorldPath 'C:\path\to\test-world' -JavaHome 'C:\path\to\jdk-21'
```

Results and full logs go to the sibling `bed-auto-lab` directory. Require a successful process, `complete.txt`, **no `failure.txt`**, and every result's `success=true`. Gradle success alone does not prove fixture success. `-Baseline` records failures without rejecting the run.

The production module is enabled normally, with no injected pending coordinates and no invocation of private scan/place/detonate methods. A client-side RemotePlayer exercises normal target selection, aligned with a stationary server-owned zombie whose health records real explosion damage. The driver is in survival with full unenchanted netherite armor, no Resistance, and default minimum target damage / maximum self damage. This verifies automatic selection and vanilla server actions, not a real multiplayer duel, Grim compatibility or a win rate.

Seventeen scenarios cover flat terrain, an unsupported head, side support, interactive chest support (each with Rotate off/on), Overworld rejection, End explosions, missing material, self-damage rejection, offhand beds, inventory swaps, missing targets, occupied cells and foot-only detonation reach. Chest cases restrict VerticalScan to zero so alternative floor/ceiling positions cannot bypass the chest interaction. The foot-only case starts with a server-created bed and no bed items; other positive cases must place a bed first. TPS scaling is disabled only for fixture timing; normal cooldown, damage and confirmation logic remain active.

The report records actual submitted interactions and server-observed bed cells / damage, plus the client BED_RULE value and synchronization flag. In 1.21.11 the client attribute misleadingly reports non-explosive beds even in vanilla Nether and End. This is why the former private-action-only blast probe missed the automatic scan failure.

Damage is counted only when the server reports `BAD_RESPAWN_POINT`, excluding suffocation and other environmental damage. The End case removes dragons and rebuilds the arena after dragon-fight initialization, which otherwise generates an exit fountain over the target. Diagnostic runs can select `-PprobeStart=N -PprobeEnd=M` (end exclusive); acceptance uses all seventeen cases.

The fixture Jar contains only test classes. Build it without the development flag, then launch the client with explicit `-PgzwDevelopmentBuild=true`. Never package the development-authorized client. No fixture is included in the production artifact.
