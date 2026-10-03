# Rime 1.2.10 — Minecraft 1.21.1 member port

Requested synchronization of main `d4a537866b6caeb3b4ae0a64a8fb9e7651ae6d10` onto port `09f4e6021ac646eb5fd8e54f6051a30b479f9a3e`.

Includes the 1.2.8–1.2.10 independent armor-flight synchronization and recovery, P40/optimized P40/firework auto-glide, travel cruise, observed target position/heading, eating/inventory synchronization, aerial combat refinements, bounded crystal self-damage rejection, empty-volume navigation shortcut, collision snapshot allocation reduction, and ClickGUI layout/winding changes.

Native adaptations retain 1.21.1 equipment attributes, packet fields, input and rendering APIs. Received heading reads native LivingEntity lerp fields; deferred crystal invalidation uses the native event-loop queue. The port's existing session-aware rotation submission observer, client-thread packet filtering and native surround centering remain intact. No 1.21.11 input packet or item component is introduced.

The separate port and test mods compile against Minecraft 1.21.1/Fabric API 0.116.17. Actual native fixtures cover placement/damage, beds/UI, fonts, mace recovery, offhand food conditions, the new ClickGUI and fixed-world performance equivalence. Release receipts record exact tested sources, production obfuscation, both signed protection channels on two Java 21 runtimes, and first-install binding/download checks. These fixtures do not establish a new live-server PvP win rate or guarantee FPS on arbitrary hardware.

Publish with independent Minecraft 1.21.1 native/legacy pointers. Preserve the current 1.21.11 templates and default launcher selection; launcher 1.2.2 and production protection custody are unchanged. Minecraft 1.21.1 remains opt-in maintenance only. Native 1.21.1 has no spear; Via compatibility still requires preserved original item metadata and a compatible server conversion path.
