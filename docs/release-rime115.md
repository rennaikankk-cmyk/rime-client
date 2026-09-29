# Rime 1.1.5 / Rimeguard 3.1

Minecraft 1.21.11 only. Adds independent SLM ArmorFly with inventory equipment lookup, durability reserve, landing disable and cruise priority. The original ArmorFly and GZBoost defaults are preserved.

Optional read-only adapters let Rime flight, aerial weapon, armor, rocket, totem and food control yield to overlapping SlimefunHelper / RusherHack modules. Stale equipment and slot restoration are discarded when another client takes control. An airborne SLM flight resumes gliding after foreign ownership ends; landing, correction and session changes clear the resume state.

Seven local scenarios covered inventory/hotbar armor flight, original ArmorFly, low-durability rejection and actual co-loading with official SlimefunHelper 1.21.11 / 1.1.7.1. A takeover recovery failure found during testing was fixed and retested. RusherHack runtime testing was stopped at the user's request; its adapter has public-API fixture coverage only. No new win-rate, anti-cheat or peak-speed claim is made.

Full build, mixed-jar signed startup on two Java 21 runtimes, publication hashes and deployment receipts are recorded separately after release validation. Account/device binding, watermarking, signed integrity, authorization checks and rimeguard3.1 remain enabled. Rusher crystal code is unchanged.
