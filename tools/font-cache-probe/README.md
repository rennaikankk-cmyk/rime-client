# Font renderer native comparison

Separate development test mod. Build with JDK 21:

```powershell
./gradlew.bat --offline -I tools/font-cache-probe/probe.gradle fontProbeJar
./gradlew.bat --offline -PgzwDevelopmentBuild=true -I tools/font-cache-probe/probe.gradle -PprobeRun=baseline runClient
```

Run the same probe jar against the candidate with a fresh run directory. It opens only a fixture screen, creates no gameplay world, uses the existing development gate and stops its own client. The fixture does not use production credentials or connect to a game server; Minecraft's normal title initialization may still contact Mojang services.

Compare `../font-lab/<run>/results.json` and the four real screenshots. The strings cover Chinese, Latin, RTL, color/reset codes, unknown formatting, supplementary characters, empty text and a trailing section sign. Requested GUI scales are 1→2→3→2. Each stage warms the paths, then records five batches of 100,000 width calls and 30,000 visual-layout calls. This measures repeated text work, not overall FPS. Reflection lookup cost is present in both layout paths.

Before optimization the probe calls the original private parser; afterwards it calls `visualText`, and checks its code points, source indices and styles against fresh parsing. Pixel comparisons must use full RGB/RGBA images (not only an alpha-channel bounding box). Keep the input strings and probe jar identical across both runs. Width/layout results must match exactly. Font page record counts should stop accumulating after scale changes.
