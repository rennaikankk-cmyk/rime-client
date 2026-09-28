<p align="center">
  <img src="src/main/resources/assets/guizhouware/icon.png" alt="GuizhouWare" width="180" />
</p>

<h1 align="center">Rime · Minecraft 1.21.1</h1>

[![Java CI with Gradle](https://github.com/rennaikankk-cmyk/guizhouware/actions/workflows/gradle-upload.yml/badge.svg?branch=main)](https://github.com/rennaikankk-cmyk/guizhouware/actions/workflows/gradle-upload.yml)
[![Security Review](https://github.com/rennaikankk-cmyk/guizhouware/actions/workflows/security-review.yml/badge.svg?branch=main)](https://github.com/rennaikankk-cmyk/guizhouware/actions/workflows/security-review.yml)

**Rime** (formerly GuizhouWare) is a Minecraft 1.21.1 Fabric PvP client maintained by **renna**.
It is a fork of **Homovore**, originally developed by **Leonetic and WiderThanEurasia**,
with AutoCrystal enhancements ported from **AlienV4**.

This repository contains the GuizhouWare fork and its build configuration. See [LICENSE](LICENSE) for the AGPL license.

---

## Screenshots

<details>
<summary>View screenshots</summary>

<img width="3440" height="1440" alt="ClickGUI" src="screenshots/Screenshot%202026-06-22%20at%206.57.01%E2%80%AFPM.png" />
<img width="3440" height="1440" alt="ClickGUI module settings" src="screenshots/Screenshot%202026-06-22%20at%206.57.27%E2%80%AFPM.png" />
<img width="3440" height="1440" alt="In-game PVP" src="screenshots/Screenshot%202026-06-21%20at%207.55.50%E2%80%AFPM.png" />

</details>

---

## FAQ

<details>
<summary>How do I open the ClickGUI?</summary>

The default keybind is **Right Shift**. You can rebind it from the ClickGUI module's bind setting or with the `.bind` command.

</details>

<details>
<summary>What is the command prefix?</summary>

The default command prefix is `.`. For example, `.help` or `.bind`. You can change it with the `.prefix <new>` command.

</details>

<details>
<summary>How are modules organized?</summary>

Modules are grouped by category. Open the ClickGUI to browse and toggle them, or bind individual modules to a key.

</details>

<details>
<summary>How do I bind a module to a key?</summary>

Open the ClickGUI, right-click a module to expand its settings, and use the bind option, or use the `.bind` command.

</details>

<details>
<summary>Where are my settings saved?</summary>

Configs are stored in your Minecraft instance directory under `guizhouware/` and persist between sessions.
Named presets are stored in `guizhouware/configs/<name>/`; use `.configs save <name>`, `.configs load <name>`, `.configs list`, or `.configs delete <name>`.

</details>

---

## Current release

Rime **1.1.2** for Minecraft **1.21.1**, distributed with **Rimeguard 3.1** through the official launcher. See [alignment notes](docs/release-rime112-1.21.1.md) and [version-specific compatibility](docs/minecraft-1.21.1.md). The `main` branch maintains the separate 1.21.11 build.

## Requirements

- **JDK 21** for building; use Java 21 to launch the game.
- Minecraft **1.21.1**, Fabric Loader **0.18.4 or newer**, and the matching Fabric API.
- Git for cloning and embedding commit metadata in the build.
- The checked-in **Gradle Wrapper (9.2.0)**; a separate Gradle installation is not needed.

Dependency versions are defined in [gradle.properties](gradle.properties). The build uses official Mojang mappings.
The first build downloads Gradle and the dependencies, so it requires network access.
Set `JAVA_HOME` to your JDK 21 directory and check `java -version` if the build selects an older Java installation.

## How to Build

Clone this repository using a GitHub account with access if it is private:

```bash
git clone https://github.com/rennaikankk-cmyk/guizhouware.git
cd guizhouware
```

On Linux or macOS:

```bash
./gradlew clean build --no-daemon
```

On Windows PowerShell:

```powershell
.\gradlew.bat clean build --no-daemon
```

Build outputs are written to `build/libs/`:

- `guizhouware-<commit>-<version>-obfuscated.jar`: the verified release candidate. Production use requires the existing licensed launcher/credential process.
- `guizhouware-<commit>-<version>.jar`: the internal, unobfuscated mod JAR; do not distribute it as the release.
- `guizhouware-<commit>-<version>-sources.jar`: sources for development; do not install this as the mod.

The build runs regression checks, remapping, ProGuard and `verifyObfuscatedJar`.
Keep `build/proguard-map.txt` internal. Follow [AGENTS.md](AGENTS.md) for release protection and runtime validation.
Install the matching Fabric API separately alongside the mod.

To launch an authorized development client, run `./gradlew -PgzwDevelopmentBuild=true runClient`
or `.\gradlew.bat -PgzwDevelopmentBuild=true runClient` on Windows. Both the compile-time flag
and Fabric's development environment are required. Packaging tasks reject this flag; build
release artifacts without it. A runtime development flag alone cannot unlock a production JAR.

## Build and Security Checks

[Java CI with Gradle](https://github.com/rennaikankk-cmyk/guizhouware/actions/workflows/gradle-upload.yml)
builds on Linux and Windows for every push and for pull requests targeting `main`, and supports manual runs.
Download the `guizhouware-<runner OS>-<commit>` artifacts from a successful run.

[Security Review](https://github.com/rennaikankk-cmyk/guizhouware/actions/workflows/security-review.yml)
runs Gradle Wrapper validation, a clean Java 21 build, and Gitleaks secret scanning.
Its summary reports which optional checks were skipped. A green workflow does not mean a skipped check ran.

CodeQL and dependency review run automatically for public repositories.
For private repositories, first enable the required GitHub security features, then set the relevant
**repository Actions variable** under **Settings → Secrets and variables → Actions → Variables**:

| Variable | Value | Enables |
| --- | --- | --- |
| `ENABLE_CODEQL` | `true` | CodeQL analysis and upload to GitHub code scanning |
| `ENABLE_DEPENDENCY_REVIEW` | `true` | Dependency review on pull requests |

These variables do not activate a GitHub subscription or enable the repository feature themselves.
Without the opt-in, private repositories skip those jobs explicitly; build and secret checks still run.
Once opted in, scan or upload failures fail the job instead of being ignored.

See GitHub's [private-repository CodeQL requirements](https://docs.github.com/en/code-security/reference/code-scanning/troubleshoot-analysis-errors/private-repository-enablement)
and the [Dependency Review Action requirements](https://github.com/actions/dependency-review-action#installation).

Deterministic checks in `src/flightTest` cover flight, combat timing, item ownership and build invariants;
they run through `check` and `build`. They do not replace a real gameplay or server compatibility test.
Before releasing gameplay changes, launch
`runClient` and verify startup, ClickGUI, config save/load, and the affected modules in a test world.

---

## Project layout

The 1.21.11 flight candidate, legacy parameter comparison and opt-in profile are documented in [flight-1.21.11-rc.md](docs/flight-1.21.11-rc.md).

- `src/main/java/dev/guizhou/` — mod source
  - `features/modules/` — modules grouped by category (combat, movement, render, …)
  - `manager/` — core managers (modules, rotations, placement, swapping, …)
  - `event/` — the event bus and event definitions
  - `mixin/` — mixins into Minecraft classes
- `src/main/resources/` — `fabric.mod.json`, mixin configs, access widener, shaders, assets

---

## License

See [LICENSE](LICENSE).

---

## Special Thanks

- [kiriyaga](https://github.com/Kiriyaga7615) — **GuizhouWare would not have been possible without him.** Endless thanks for the foundation, guidance, and inspiration that made this client what it is.

- [Gonbler](https://github.com/gonbler) — some of GuizhouWare's PVP stuff is built on his code. Thanks for the work on GWare that made those modules possible.

- [Pawstar](https://github.com/pawztar) — moral support
