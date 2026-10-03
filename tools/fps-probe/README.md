# Local combat workload probe

Build separately with `gradlew --offline -I tools/fps-probe/probe.gradle fpsProbeJar`.
Copy a disposable saved `ProbeWorld` to `../fps-lab/<run>/saves/`, then run
`gradlew --offline -PgzwDevelopmentBuild=true -I tools/fps-probe/probe.gradle -PprobeRun=<run> runClient`.

The fixture clears terrain in its isolated world, disables VSync, uses uncapped rendering,
calls actual combat/flight calculation paths, writes local numeric diagnostics and closes its
own client. Never point it at a personal world. It must not be included in production jars.

`BaselineCollisionSnapshot` is the pre-optimization 381cad8 implementation copied for geometry
equivalence, not production code. Performance comparison uses the same probe before/after
the business changes; real-world equivalence also compares the old segmented path algorithm
and exhaustive self damage, including precise damage after early safety rejection.

Add `-PprobeHud=true` to the native run for a HUD integration pass. This explicitly enables
the real HUD, checks that coordinates and ping rendered, and saves `screenshots/hud-world.png`.
The flag adds two render checks; the combat workload and decision output are unchanged.
