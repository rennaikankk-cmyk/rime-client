package dev.guizhou.features.modules.combat;

import dev.guizhou.GuizhouWare;
import dev.guizhou.event.impl.render.Render3DEvent;
import dev.guizhou.features.modules.Module;
import dev.guizhou.features.modules.client.TargetsModule;
import dev.guizhou.features.settings.Setting;
import dev.guizhou.manager.HeartbeatManager;
import dev.guizhou.util.PlaceUtil;
import dev.guizhou.util.render.RenderUtil;
import dev.guizhou.util.world.HoleClassifier;
import dev.guizhou.util.world.HoleScanner;
import dev.guizhou.util.player.ScreenGate;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Fills safe holes next to enemies so they cannot hop into cover. Placement goes through PlacementManager. */
public class HoleFillModule extends Module {
    public enum FillBlock { OBSIDIAN, COBWEB }

    private final Setting<FillBlock> block = mode("Block", FillBlock.OBSIDIAN).setPage("General");
    private final Setting<Double> placeRange = num("PlaceRange", 4.5, 1.0, 6.0).setPage("General");
    private final Setting<Double> enemyRange = num("EnemyRange", 3.0, 1.0, 8.0).setPage("General");
    private final Setting<Double> targetRange = num("TargetRange", 10.0, 3.0, 16.0).setPage("General");
    private final Setting<Boolean> doubles = bool("Double", true).setPage("General");
    private final Setting<Boolean> keepOwnHole = bool("KeepOwnHole", true).setPage("General");
    private final Setting<Integer> perTick = num("BlocksPerTick", 2, 1, 6).setPage("General");

    private final Setting<Boolean> render = bool("Render", true).setPage("Render");
    private final Setting<Color> color = color("Color", 224, 138, 108, 90).setPage("Render");

    private final Map<BlockPos, Long> placed = new HashMap<>();
    private TargetsModule targets;

    public HoleFillModule() {
        super("HoleFill", "Fills bedrock/obsidian holes next to enemies with obsidian or cobwebs so they cannot "
                + "get into cover. Your own hole is left alone.", Category.COMBAT);
    }

    @Override
    public void onDisable() {
        resetState();
    }

    @Override
    public void onTick() {
        if (!HeartbeatManager.authorized()) { HeartbeatManager.notifyDeniedOnce(); resetState(); return; }
        if (nullCheck() || !ScreenGate.allowsWorldUpkeep()) return;
        long now = System.currentTimeMillis();
        placed.values().removeIf(at -> now - at > 1000);
        List<Player> enemies = enemies();
        if (enemies.isEmpty()) return;
        List<BlockPos> wanted = new ArrayList<>();
        int range = (int) Math.ceil(placeRange.getValue()) + 1;
        for (HoleClassifier.Hole hole : HoleScanner.scan(mc.player.blockPosition(), range, 3, doubles.getValue())) {
            if (keepOwnHole.getValue() && touchesSelf(hole)) continue;
            if (!nearEnemy(hole, enemies)) continue;
            for (BlockPos cell : HoleScanner.cells(hole)) {
                if (!PlaceUtil.canPlace(cell, placeRange.getValue())) continue;
                wanted.add(cell);
            }
        }
        if (wanted.isEmpty()) return;
        Vec3 eye = mc.player.getEyePosition();
        wanted.sort(Comparator.comparingDouble(pos -> Vec3.atCenterOf(pos).distanceToSqr(eye)));
        fill(wanted.subList(0, Math.min(perTick.getValue(), wanted.size())));
    }

    private void fill(List<BlockPos> cells) {
        if (!HeartbeatManager.authorized()) return;
        Item item = block.getValue() == FillBlock.COBWEB ? Items.COBWEB : Items.OBSIDIAN;
        long now = System.currentTimeMillis();
        for (BlockPos pos : GuizhouWare.placementManager.placeBatch(cells, item)) placed.put(pos, now);
    }

    private List<Player> enemies() {
        if (targets == null) targets = GuizhouWare.moduleManager.getModuleByClass(TargetsModule.class);
        List<Player> out = new ArrayList<>();
        double rangeSq = targetRange.getValue() * targetRange.getValue();
        for (Player other : mc.level.players()) {
            if (other == mc.player || !other.isAlive() || other.isSpectator()) continue;
            if (GuizhouWare.friendManager.isFriend(other)) continue;
            if (targets != null && !targets.isValidTarget(other)) continue;
            if (other.distanceToSqr(mc.player) <= rangeSq) out.add(other);
        }
        return out;
    }

    private boolean touchesSelf(HoleClassifier.Hole hole) {
        AABB self = mc.player.getBoundingBox();
        for (BlockPos cell : HoleScanner.cells(hole)) {
            if (new AABB(cell).inflate(0.5, 0, 0.5).intersects(self)) return true;
        }
        return false;
    }

    /** An enemy close enough to step in, and not already standing inside (a filled cell would suffocate nothing). */
    private boolean nearEnemy(HoleClassifier.Hole hole, List<Player> enemies) {
        double rangeSq = enemyRange.getValue() * enemyRange.getValue();
        for (Player enemy : enemies) {
            for (BlockPos cell : HoleScanner.cells(hole)) {
                if (new AABB(cell).intersects(enemy.getBoundingBox())) return false;
                Vec3 center = Vec3.atBottomCenterOf(cell);
                double dx = center.x - enemy.getX(), dz = center.z - enemy.getZ();
                if (dx * dx + dz * dz <= rangeSq && Math.abs(center.y - enemy.getY()) <= 3.0) return true;
            }
        }
        return false;
    }

    private void resetState() {
        placed.clear();
    }

    @Override
    public void onRender3D(Render3DEvent event) {
        if (!render.getValue() || placed.isEmpty()) return;
        for (BlockPos pos : placed.keySet()) {
            RenderUtil.drawBoxFilled(event.getMatrix(), pos, color.getValue());
        }
    }

    @Override
    public String getDisplayInfo() {
        return block.getValue() == FillBlock.COBWEB ? "Web" : "Obby";
    }
}
