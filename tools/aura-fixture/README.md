# Aura and AerialCombat development integration fixtures

From this repository, set JAVA_HOME to JDK 21 and run:

```powershell
./tools/aura-fixture/run.ps1
```

The script compiles an isolated test mod and runs a new local Minecraft world. Its explicit development build flag activates the existing development-only license lease. Production JAR packaging is prohibited in that mode. The script then restores the normal generated flag; use the ordinary `build` task for production artifacts.

The test mod, world, classpath and runtime logs stay under `build/aura-fixture/`. This fixture is not a dependency of ordinary builds or any distributed client JAR. Fabric's test network synchronizer is disabled because these tests deliberately cancel low-level packets. Test assertions cover local packet submission and client state, not acceptance by a remote server.

The same run also covers AerialCombat packet replacement/cancellation, received-position visibility, unsupported ground flags, slot retries, spear charge ownership and setback cleanup.
