# Render / movement injection compatibility

The 2026-09-21 report exposed an outline Redirect collision with EveryDay: the foreign
Redirect won and our required injection failed (0/1), aborting LevelRenderer transformation.
A startup-only fixture reproduces this on the previous unmodified mc1211.3 distribution.

Use MixinExtras operation chaining for outline processing, camera overlays, slime movement
and 1.21.11 SeeThrough. Disabled modules must forward to the supplied original operation;
do not call the vanilla method directly and bypass another mod's interceptor. Portal GUI
uses a chained expression value. The 1.21.11 FOV cap now uses the same scoped constructor
argument as 1.21.1, preserving other clients' constant injections and larger caps.

Only optional visuals allow a missing invocation at runtime. Default require=1 and required
movement, packet, rotation and license checks stay enforced. CompatibilityHookTest verifies
all these optional targets still exist in the vanilla build, so require=0 cannot conceal our
own mapping mistakes. Full overwrites by other mods may disable a visual effect; competing
shader effects are not guaranteed to render simultaneously.

Bundle MixinExtras 0.5.5, which fixes interoperability with Redirects compiled against
Fabric Mixin 0.17.4. Reference: https://github.com/LlamaLad7/MixinExtras/releases/tag/0.5.5
Chaining semantics: https://github.com/LlamaLad7/MixinExtras/wiki/WrapOperation

Test final obfuscated artifacts with a synthetic foreign Redirect/constant injector in
an isolated profile, including disabled-module forwarding and FOV bounds. Do not edit a
credentialed JAR for testing: integrity reports now trigger the owner's automatic-ban policy.
Test fixtures must never ship in the release. None of these changes modifies auth or HWID.
The supplied EveryDay JAR also reported missing Sodium compatibility classes; those are
outside this client's source and this fix does not claim to repair that separate artifact.
