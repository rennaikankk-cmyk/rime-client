# rc22 font recovery

Custom font initialization, glyph creation, text measurement and drawing failures now switch menus, HUD, ClickGUI and nametags to Minecraft's native font for the rest of the process. The original failure is logged once. All three HUD font weights must initialize successfully before being published.

Native fallback uses matching measurement and drawing scales and bypasses the custom font mixins. Drawing restores matrix scopes and clears queued glyphs even when an upload/draw fails. AWT probe graphics are disposed on failure. Fatal VM errors still propagate.

For renderer faults that return normally but display incorrect pixels, start Java with `-Dguizhouware.vanillaFonts=true` to force native text. This affects text rendering only; it does not change any authorization or integrity checks.

The actual failing user's environment has not been supplied. This fixes the confirmed recovery gap but is not proof that every graphics driver or mobile runtime issue has been reproduced.
