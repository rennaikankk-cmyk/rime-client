package dev.guizhou.util.combat;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.*;

import java.io.InputStream;
import java.util.List;
import java.util.Set;

/** Actual vanilla projectile contracts and the aura dispatch route, without starting a game. */
public final class AuraDefenceHookTest {
    public static void main(String[] args) throws Exception {
        String sword = "dev/guizhou/features/modules/combat/AutoSwordModule";
        MethodNode defence = method(sword, "tryDeflect");
        Set<String> blocked = Set.of("active", "start", "getWeapon", "withSwap", "getLatestTPS", "getAverageTPS", "teleportPath");
        for (var insn : defence.instructions) {
            if (insn instanceof MethodInsnNode call) check(!blocked.contains(call.name), "Projectile defence must bypass " + call.name);
        }
        check(calls(defence, "aimPoint") && calls(defence, "strike"), "Defence retains reach/occlusion and the final guarded strike");
        MethodNode tick = method("dev/guizhou/features/modules/combat/KillAuraModule", "onPreTick");
        check(firstCall(tick, "tryDeflect") < firstCall(tick, "coolingDown"), "Melee cooldown must not gate projectile dispatch");
        MethodNode prepare = method(sword, "prepareAttackTick");
        check(!calls(prepare, "active") && calls(prepare, "advance"), "Shared tick updates do not require a ready weapon");
        MethodNode selector = method(sword, "deflectableProjectile");
        boolean tag = false, bullet = false;
        for (var insn : selector.instructions) {
            if (insn instanceof FieldInsnNode field && field.name.equals("REDIRECTABLE_PROJECTILE")) tag = true;
            if (insn instanceof TypeInsnNode type && type.desc.endsWith("/ShulkerBullet")) bullet = true;
        }
        check(tag && bullet && calls(selector, "getOwner"), "Use vanilla redirectable types plus bullets; exclude your own projectile");
        MethodNode nativeBullet = method("net/minecraft/world/entity/projectile/ShulkerBullet", "hurt");
        check(calls(nativeBullet, "destroy"), "Vanilla shulker bullets are destroyed by attacks");
        MethodNode nativeAttack = method("net/minecraft/world/entity/player/Player", "attack");
        check(calls(nativeAttack, "hurt"), "Native player attacks dispatch the projectile damage/deflection contract");
        check(calls(method(sword, "hitAllowed"), "aimPoint") && calls(method(sword, "hitAllowed"), "canHit"),
                "NoAC retains visibility while strict mode retains server-angle geometry");
        System.out.println("AuraDefenceHookTest passed: projectile contracts, cooldown independence, target and hit routing");
    }

    private static boolean calls(MethodNode method, String name) { return firstCall(method, name) >= 0; }
    private static int firstCall(MethodNode method, String name) {
        for (var insn : method.instructions) if (insn instanceof MethodInsnNode call && call.name.equals(name))
            return method.instructions.indexOf(insn);
        return -1;
    }
    private static MethodNode method(String owner, String name) throws Exception {
        try (InputStream input = AuraDefenceHookTest.class.getClassLoader().getResourceAsStream(owner + ".class")) {
            check(input != null, "Missing class " + owner);
            ClassNode node = new ClassNode();
            new ClassReader(input).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            List<MethodNode> methods = node.methods.stream().filter(m -> m.name.equals(name)).toList();
            check(methods.size() == 1, "Ambiguous method " + owner + "." + name);
            return methods.getFirst();
        }
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
