# Minecraft 1.21.1 port

This branch builds GuizhouWare for Minecraft 1.21.1, Java 21, Fabric Loader 0.18.4 or newer and Fabric API 0.116.17+1.21.1. Install the matching version in its own game instance. Do not install the 1.21.11 and 1.21.1 jars together.

## Spear compatibility

Vanilla 1.21.1 has no spear item. The compatibility adapter recognizes the original 1.21.11 item identity preserved by the supported ViaBackwards/ViaVersion translation metadata, including an external ViaProxy. Display names and generic integer IDs are not sufficient. Renamed ordinary swords retain ordinary sword behavior.

Seven material defaults and supported server component overrides determine charge delay, effect windows and attack range. Damage, knockback and dismount conditions are evaluated separately; held use lasts independently of the damage window so the controller can explicitly release an expired charge before recharging. This supports held-use collision attacks; it does not add the newer stabbing action to the older protocol. Native 1.21.1 servers do not gain spears merely by installing this client. Other translation stacks or servers which remove the original metadata require separate validation.

## Port implementation

The old inline glide physics captures incoming velocity before applying the native step, then adjusts the glide movement argument and stored velocity together. MoveFix preserves crouch input magnitude. Temporary sneak placement uses PRESS_SHIFT_KEY / RELEASE_SHIFT_KEY commands because native 1.21.1 player-input packets affect passengers only. NoSlow classifies food and drinks separately from shields and other held items.

The render adapter uses 1.21.1 RenderTypes, framebuffer post passes, resource reload callbacks and owned reusable buffers. It covers placement boxes and labels, custom sky modes, hand masks, world outlines, textured SeeThrough and custom particles. GUI clipping explicitly transforms custom pose coordinates because this version's scissors do not follow the PoseStack.

## Validation and release

The explicitly requested Rime 1.2.7 synchronization is described in [release-rime127-mc1211.md](release-rime127-mc1211.md). Earlier fixture results below are historical; the current port has separate native and signed-release acceptance records. Routine 1.21.11 releases do not automatically update this branch.

Use `gradlew.bat build`: regression tests, remapping, ProGuard and final obfuscated-jar verification are part of the build. Commit before the final build because the jar name embeds the source revision. Publish only the reviewed `-obfuscated.jar`; retain mappings for internal diagnostics.

An isolated development fixture exercised menu hit testing and screenshots at three scales, placement rendering, hand/world post effects, resizing and resource reload, custom particles, native server sneak placement, Aura, spear metadata and aerial behavior. A real 1.21.1 client connected through external ViaProxy to a local 1.21.11 server and verified all seven materials plus charge/expiry/recharge. These checks are not a server damage benchmark or a claim about public-server anti-cheat compatibility. Final production jars additionally require startup checks on both supported Java 21 runtimes.

Authorization, heartbeat signatures, HWID, watermark handling, AI notices and obfuscation remain in the production path. Development authorization is confined to the explicit development build and cannot be packaged as a release jar.
