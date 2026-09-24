package dev.guizhou.features.modules.combat;

import dev.guizhou.GuizhouWare;
import dev.guizhou.event.impl.entity.player.PreTickEvent;
import dev.guizhou.event.impl.network.PacketEvent;
import dev.guizhou.event.impl.render.Render3DEvent;
import dev.guizhou.event.system.Subscribe;
import dev.guizhou.features.modules.Module;
import dev.guizhou.features.modules.client.TargetsModule;
import dev.guizhou.features.modules.world.GuangxiMineModule;
import dev.guizhou.features.settings.Setting;
import dev.guizhou.util.InteractionUtil;
import dev.guizhou.util.MathUtil;
import dev.guizhou.util.inventory.InventoryUtil;
import dev.guizhou.util.inventory.ResultType;
import dev.guizhou.util.inventory.SwapMode;
import dev.guizhou.util.render.RenderUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ClientboundBlockDestructionPacket;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;

import java.awt.Color;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class AutoMineModule extends Module {

    private static final double INVALID_SCORE = -1000;
    private static final Direction[] HORIZONTAL = {
            Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST
    };

    private final Setting<Double>  range = num("Range", 6.5, 0.0, 7.0);
    private final Setting<SortPriority> targetPriority = mode("Priority", SortPriority.Angle);
    private final Setting<Boolean> ignoreNakeds = bool("IgnoreNakeds", true);
    private final Setting<Boolean> checkSelf = bool("CheckSelf", true);
    private final Setting<Boolean> friendSync = bool("FriendSync", true);
    private final Setting<Boolean> targetFallback = bool("TargetFallback", true);
    private final Setting<ExtendBreakMode> extendBreakMode = mode("Extend", ExtendBreakMode.Long);
    private final Setting<Boolean> underMine = bool("UnderMine", true);
    private final Setting<Boolean> crawlMine = bool("CrawlMine", false);
    private final Setting<AntiSwimMode> antiSwim = mode("AntiSwim", AntiSwimMode.None);
    private final Setting<AntiSurroundMode> antiSurroundMode = mode("AntiSurround", AntiSurroundMode.Auto);
    private final Setting<Boolean> antiSurroundInnerSnap = bool("InnerSnap", true);
    private final Setting<Boolean> antiSurroundOuterSnap = bool("OuterSnap", true);
    private final Setting<Double>  antiSurroundOuterCooldown = num("OuterCooldown", 0.1, 0.0, 1.0);

    private final Setting<Boolean> glassPush     = bool("GlassPush", false);
    private final Setting<Integer> glassAttempts = num("GlassAttempts", 2, 1, 5);
    private final Setting<GlassSwap> glassSwap = mode("GlassSwap", GlassSwap.Inventory);
    private final Setting<Integer> glassWaitTicks = num("GlassWaitTicks", 10, 2, 40);

    private final Setting<Boolean> glassRender = bool("GlassRender", true).setPage("Render");
    private final Setting<Float> glassFadeTime = num("GlassFadeTime", 0.5f, 0.05f, 2.0f).setPage("Render")
            .setVisibility(v -> glassRender.getValue());
    private final Setting<Color> glassFillColor = color("GlassFillColor", 255, 255, 255, 50).setPage("Render")
            .setVisibility(v -> glassRender.getValue());
    private final Setting<Color> glassOutlineColor = color("GlassOutlineColor", 255, 255, 255, 255).setPage("Render")
            .setVisibility(v -> glassRender.getValue());
    private final Setting<Boolean> renderDebugScores = bool("DebugScores", false).setPage("Render");

    private Player targetPlayer = null;
    private CityBlock target1 = null;
    private CityBlock target2 = null;
    private BlockPos ignorePos = null;
    private long lastOuterPlaceTime = 0;

    private boolean isTerrainFight = false;

    private BlockPos glassTargetPos = null;
    private int glassUsedAttempts = 0;
    private BlockPos glassRenderPos = null;
    private long glassRenderStart = 0L;
    private GlassStage glassStage = GlassStage.Idle;
    private int glassStageAge;
    private final List<CityBlock> debugScores = new ArrayList<>();
    private final List<BlockPos> feetCells = new ArrayList<>();
    private int feetCacheTick = Integer.MIN_VALUE;
    private int feetCacheId = Integer.MIN_VALUE;
    private boolean singleFootCell;
    private boolean feetOnBedrock;
    private volatile long lifecycleGeneration;
    private net.minecraft.client.multiplayer.ClientLevel sessionLevel;
    private net.minecraft.client.player.LocalPlayer sessionPlayer;

    private final MiningBreakTracker enemyBreaking = new MiningBreakTracker();
    private static final long ENEMY_BREAK_TTL_MS = 1000;

    private final GuangxiMineModule.MineFinishListener finishListener = this::onMineFinish;

    public AutoMineModule() {
        super("AutoMine", "Automatically mines feasible target blocks. Requires GuangxiMine.", Category.COMBAT);
        antiSurroundInnerSnap.setVisibility(v -> antiSurroundMode.getValue() == AntiSurroundMode.Auto
                || antiSurroundMode.getValue() == AntiSurroundMode.Inner);
        antiSurroundOuterSnap.setVisibility(v -> antiSurroundMode.getValue() == AntiSurroundMode.Auto
                || antiSurroundMode.getValue() == AntiSurroundMode.Outer);
        antiSurroundOuterCooldown.setVisibility(v -> antiSurroundMode.getValue() == AntiSurroundMode.Auto
                || antiSurroundMode.getValue() == AntiSurroundMode.Outer);
        glassAttempts.setVisibility(v -> glassPush.getValue());
        glassSwap.setVisibility(v -> glassPush.getValue());
        glassWaitTicks.setVisibility(v -> glassPush.getValue());
    }

    @Override
    public void onEnable() {
        lifecycleGeneration++;
        ensureSession();
        GuangxiMineModule mine = GuizhouWare.moduleManager.getModuleByClass(GuangxiMineModule.class);
        if (mine != null) {
            mine.addSubmissionListener(finishListener);
            mine.addFinishListener(finishListener);
        }
        enemyBreaking.clear();
        lastOuterPlaceTime = 0;
    }

    @Override
    public void onDisable() {
        lifecycleGeneration++;
        GuangxiMineModule mine = GuizhouWare.moduleManager.getModuleByClass(GuangxiMineModule.class);
        if (mine != null) {
            mine.removeSubmissionListener(finishListener);
            mine.removeFinishListener(finishListener);
        }
        targetPlayer = null;
        target1 = target2 = null;
        ignorePos = null;
        enemyBreaking.clear();
        resetGlass();
        glassRenderPos = null;
        debugScores.clear();
        sessionLevel = null;
        sessionPlayer = null;
    }

    @Subscribe
    private void onPacket(PacketEvent.Receive event) {
        if (!dev.guizhou.manager.HeartbeatManager.authorized()) return;
        if (!(event.getPacket() instanceof ClientboundBlockDestructionPacket pkt)) return;
        var level = mc.level;
        var player = mc.player;
        long generation = lifecycleGeneration;
        int breakerId = pkt.getId();
        long position = pkt.getPos().asLong();
        int progress = pkt.getProgress();
        if (level == null || player == null) return;
        mc.execute(() -> {
            if (!dev.guizhou.manager.HeartbeatManager.authorized()) return;
            if (!isEnabled() || lifecycleGeneration != generation || mc.level != level || mc.player != player) return;
            ensureSession();
            if (breakerId == player.getId()) return;
            Entity breaker = level.getEntity(breakerId);
            boolean friend = breaker instanceof Player other && GuizhouWare.friendManager.isFriend(other);
            enemyBreaking.update(breakerId, position, progress, friend, System.currentTimeMillis(), ENEMY_BREAK_TTL_MS);
        });
    }

    /** Main-thread placement coordination, including selected operations not yet handed to PacketMine. */
    public boolean reservesPlacement(BlockPos pos) {
        return isEnabled() && pos != null && sessionLevel == mc.level && sessionPlayer == mc.player
                && (target1 != null && pos.equals(target1.blockPos)
                || target2 != null && pos.equals(target2.blockPos) || pos.equals(glassTargetPos));
    }

    private boolean isBlockBeingBroken(BlockPos pos) {
        return enemyBreaking.isBreaking(pos.asLong(), false, System.currentTimeMillis());
    }

    private void onMineFinish(BlockPos pos) {
        if (!dev.guizhou.manager.HeartbeatManager.authorized()) return;
        if (!isEnabled() || nullCheck() || targetPlayer == null || !targetPlayer.isAlive()
                || targetPlayer.level() != mc.level || sessionLevel != mc.level || sessionPlayer != mc.player) return;

        AntiSurroundMode swMode = antiSurroundMode.getValue();
        if (swMode == AntiSurroundMode.None) return;

        GuangxiCrystalModule crystal = GuizhouWare.moduleManager.getModuleByClass(GuangxiCrystalModule.class);
        if (crystal == null || !crystal.isEnabled()) return;

        if (swMode == AntiSurroundMode.Auto || swMode == AntiSurroundMode.Outer) {
            for (Direction dir : HORIZONTAL) {
                BlockPos playerSurroundBlock = targetPlayer.blockPosition().relative(dir);
                if (!pos.equals(playerSurroundBlock)) continue;

                boolean outerReady = (System.currentTimeMillis() - lastOuterPlaceTime)
                        > (antiSurroundOuterCooldown.getValue() * 1000.0);
                if (!outerReady) break;

                AABB checkBox = AABB.ofSize(Vec3.atCenterOf(playerSurroundBlock), 2.5, 3.0, 2.5);
                AABB blockHitbox = new AABB(playerSurroundBlock);

                for (BlockPos bp : iterate(checkBox)) {
                    if (!mc.level.getBlockState(bp).isAir()) continue;
                    BlockState downState = mc.level.getBlockState(bp.below());
                    if (!downState.is(Blocks.OBSIDIAN) && !downState.is(Blocks.BEDROCK)) continue;

                    AABB crystalPlaceHitbox = new AABB(bp.getX(), bp.getY(), bp.getZ(),
                            bp.getX() + 1, bp.getY() + 2, bp.getZ() + 1);
                    if (intersectsEntity(crystalPlaceHitbox, e -> !e.isSpectator())) continue;

                    Vec3 crystalPos = new Vec3(bp.getX() + 0.5, bp.getY(), bp.getZ() + 0.5);
                    AABB crystalHitbox = new AABB(crystalPos.x - 1, crystalPos.y, crystalPos.z - 1,
                            crystalPos.x + 1, crystalPos.y + 2, crystalPos.z + 1);

                    if (crystalHitbox.intersects(blockHitbox)) {

                        if (crystal.preplaceCrystal(bp.immutable(), antiSurroundOuterSnap.getValue())) {
                            lastOuterPlaceTime = System.currentTimeMillis();
                            return;
                        }
                    }
                }
            }
        }

        if (swMode == AntiSurroundMode.Auto || swMode == AntiSurroundMode.Inner) {
            for (Direction dir : HORIZONTAL) {
                BlockPos playerSurroundBlock = targetPlayer.blockPosition().relative(dir);
                if (playerSurroundBlock.equals(pos)) {
                    crystal.preplaceCrystal(playerSurroundBlock, antiSurroundInnerSnap.getValue());
                }
            }
        }
    }

    @Subscribe
    private void onPreTick(PreTickEvent event) {
        if (!dev.guizhou.manager.HeartbeatManager.authorized()) {
            targetPlayer = null;
            target1 = target2 = null;
            debugScores.clear();
            enemyBreaking.clear();
            resetGlass();
            glassRenderPos = null;
            return;
        }
        ensureSession();
        if (nullCheck()) return;
        enemyBreaking.prune(System.currentTimeMillis());
        update();
    }

    private void ensureSession() {
        if (sessionLevel == mc.level && sessionPlayer == mc.player) return;
        targetPlayer = null;
        target1 = target2 = null;
        ignorePos = null;
        enemyBreaking.clear();
        debugScores.clear();
        resetGlass();
        glassRenderPos = null;
        lastOuterPlaceTime = 0;
        sessionLevel = mc.level;
        sessionPlayer = mc.player;
    }

    private void update() {
        GuangxiMineModule mine = speedMine();
        if (mine == null) { targetPlayer = null; resetGlass(); debugScores.clear(); return; }

        if (tryCrawlMine(mine)) return;

        BlockState selfFeetBlock = mc.level.getBlockState(mc.player.blockPosition());
        BlockState selfHeadBlock = mc.level.getBlockState(mc.player.blockPosition().above());
        BlockPos selfHeadPos = mc.player.blockPosition().above();
        boolean shouldBreakSelfHeadBlock = canBreak(selfHeadPos, selfHeadBlock)
                && (selfHeadBlock.is(Blocks.OBSIDIAN) || selfHeadBlock.is(Blocks.CRYING_OBSIDIAN));

        boolean prioHead = false;

        if (antiSwim.getValue() == AntiSwimMode.Always && shouldBreakSelfHeadBlock) {
            mine.requestBreak(selfHeadPos);
            prioHead = true;
        }
        if (antiSwim.getValue() == AntiSwimMode.MineOrSwim && mc.player.isVisuallyCrawling()
                && shouldBreakSelfHeadBlock) {
            mine.requestBreak(selfHeadPos);
            prioHead = true;
        }
        if ((antiSwim.getValue() == AntiSwimMode.Mine || antiSwim.getValue() == AntiSwimMode.MineOrSwim)
                && isBlockBeingBroken(mc.player.blockPosition()) && shouldBreakSelfHeadBlock) {
            mine.requestBreak(selfHeadPos);
            prioHead = true;
        }

        targetPlayer = null;
        target1 = target2 = null;
        debugScores.clear();
        for (Player candidate : selectTargets()) {
            targetPlayer = candidate;
            findTargetBlocks();
            if (target1 != null || !targetFallback.getValue()) break;
            targetPlayer = null;
        }
        if (targetPlayer == null) { resetGlass(); return; }

        handleGlassPush(mine);

        if (mine.getSecondaryPos() != null && selfHeadBlock.is(Blocks.OBSIDIAN) && selfFeetBlock.isAir()
                && selfHeadPos.equals(mine.getRebreakPos())) {
            return;
        }

        if (prioHead) return;

        int free = 0;
        if (mine.hasFreePrimary()) free++;
        if (mine.hasFreeSecondary()) free++;
        if (free == 0) return;

        Deque<BlockPos> wanted = new ArrayDeque<>();
        if (target1 != null && !mine.isMining(target1.blockPos)) wanted.add(target1.blockPos);
        if (target2 != null && !mine.isMining(target2.blockPos)) wanted.add(target2.blockPos);

        for (int sent = 0; !wanted.isEmpty() && sent < free; sent++) {
            mine.requestBreak(wanted.remove());
        }
    }

    private boolean tryCrawlMine(GuangxiMineModule mine) {
        if (!crawlMine.getValue()) return false;
        if (mc.player.isCrouching()) return false;

        BlockPos feetPos = mc.player.blockPosition();
        BlockPos headPos = feetPos.above();
        BlockState feetBlock = mc.level.getBlockState(feetPos);
        BlockState headBlock = mc.level.getBlockState(headPos);

        if (!isBlocked(feetPos, feetBlock) || !isBlocked(headPos, headBlock)) return false;
        if (!InteractionUtil.canBreak(feetPos, feetBlock)) return false;

        return mine.requestBreak(feetPos);
    }

    private boolean isBlocked(BlockPos pos, BlockState state) {
        return !state.canBeReplaced() && state.getShape(mc.level, pos) != Shapes.empty();
    }

    private List<Player> selectTargets() {
        TargetsModule targets = GuizhouWare.moduleManager.getModuleByClass(TargetsModule.class);
        double rangeV = range.getValue();
        List<Player> candidates = new ArrayList<>();

        AABB area = mc.player.getBoundingBox().inflate(rangeV);
        for (Entity e : mc.level.getEntities(mc.player, area)) {
            if (!(e instanceof Player player)) continue;
            if (player == mc.player) continue;
            if (!player.isAlive() || player.isDeadOrDying() || player.isSpectator()) continue;

            if (targets != null && !targets.isValidTarget(player)) continue;
            if (player.position().distanceTo(mc.player.getEyePosition()) > rangeV) continue;
            if (ignoreNakeds.getValue() && isNaked(player)) continue;

            candidates.add(player);
        }
        candidates.sort(Comparator.comparingDouble(player -> switch (targetPriority.getValue()) {
            case Distance -> mc.player.distanceToSqr(player);
            case Angle -> angleTo(player);
        }));
        return candidates;
    }

    private double angleTo(Player player) {
        float[] need = MathUtil.calcAngle(mc.player.getEyePosition(), player.getEyePosition());
        double dYaw = MathUtil.wrapDegrees(need[0] - mc.player.getYRot());
        double dPitch = need[1] - mc.player.getXRot();
        return Math.sqrt(dYaw * dYaw + dPitch * dPitch);
    }

    private boolean isNaked(Player p) {
        return p.getItemBySlot(EquipmentSlot.HEAD).isEmpty()
                && p.getItemBySlot(EquipmentSlot.CHEST).isEmpty()
                && p.getItemBySlot(EquipmentSlot.LEGS).isEmpty()
                && p.getItemBySlot(EquipmentSlot.FEET).isEmpty();
    }

    private void handleGlassPush(GuangxiMineModule mine) {
        if (!glassPush.getValue()) { resetGlass(); return; }

        GuangxiCrystalModule crystal = GuizhouWare.moduleManager.getModuleByClass(GuangxiCrystalModule.class);
        if (crystal == null || !crystal.isEnabled()) { resetGlass(); return; }

        BlockPos pos = mine.getRebreakPos();
        if (pos == null) { resetGlass(); return; }

        if (!isGoodRebreak(pos, inBedrockCase())
                || (glassStage == GlassStage.Idle && !crystal.isDesirablePlacement(pos))) {
            resetGlass();
            return;
        }

        if (!pos.equals(glassTargetPos)) {
            resetGlass();
            glassTargetPos = pos.immutable();
        }

        BlockState state = mc.level.getBlockState(pos);
        if (glassStage == GlassStage.AwaitPlace) {
            if (state.is(Blocks.GLASS)) {
                // The server-visible glass has arrived; mining may now remove it.
                mine.holdRebreak(pos, 0);
                glassStage = GlassStage.AwaitBreak;
                glassStageAge = 0;
            } else if (++glassStageAge >= glassWaitTicks.getValue() || !state.isAir()) {
                mine.holdRebreak(pos, 0);
                glassStage = GlassStage.Idle;
            }
            return;
        }
        if (glassStage == GlassStage.AwaitBreak) {
            if (!state.isAir()) {
                if (++glassStageAge >= glassWaitTicks.getValue() * 4) {
                    glassStage = GlassStage.Idle;
                    glassUsedAttempts = glassAttempts.getValue();
                }
                return;
            }
            glassStage = GlassStage.Idle;
        }

        if (!itemInCrystalSpot(pos)) {
            if (glassUsedAttempts > 0 && state.isAir()) {
                if (crystal.preplaceCrystal(pos, true)) resetGlass();
            }
            return;
        }

        if (glassUsedAttempts >= glassAttempts.getValue()) return;

        if (!mc.level.getBlockState(pos).isAir()) return;

        if (placeGlass(pos)) {
            glassUsedAttempts++;
            glassStage = GlassStage.AwaitPlace;
            glassStageAge = 0;
            mine.holdRebreak(pos, glassWaitTicks.getValue());
            glassRenderPos = pos.immutable();
            glassRenderStart = System.currentTimeMillis();
        }
    }

    private boolean itemInCrystalSpot(BlockPos airPos) {
        AABB box = new AABB(airPos).expandTowards(0, 1, 0);
        for (Entity e : mc.level.getEntities((Entity) null, box)) {
            if (e instanceof ItemEntity) return true;
        }
        return false;
    }

    private boolean placeGlass(BlockPos pos) {
        if (!dev.guizhou.manager.HeartbeatManager.authorized()) return false;
        SwapMode swap = glassSwap.getValue() == GlassSwap.Inventory ? SwapMode.ALTSILENT : SwapMode.SILENT;
        // Count a submitted direct interaction, never a queue entry that might run much later.
        return GuizhouWare.placementManager.placeDirect(pos, null, Items.GLASS, swap,
                glassSwap.getValue() == GlassSwap.Inventory ? InventoryUtil.PLACE_SCOPE
                        : java.util.EnumSet.of(ResultType.HOTBAR));
    }

    private void resetGlass() {
        if (glassTargetPos != null) {
            GuangxiMineModule mine = GuizhouWare.moduleManager.getModuleByClass(GuangxiMineModule.class);
            if (mine != null) mine.holdRebreak(glassTargetPos, 0);
        }
        glassTargetPos = null;
        glassUsedAttempts = 0;
        glassStage = GlassStage.Idle;
        glassStageAge = 0;
    }

    private void findTargetBlocks() {
        target1 = null;
        target2 = null;
        ignorePos = null;
        debugScores.clear();

        boolean inBedrock = inBedrockCase();
        isTerrainFight = !inBedrock && computeTerrainFight();
        Set<CheckPos> positions = new HashSet<>();
        if (inBedrock) addBedrockCaseCheckPositions(positions);
        else addNormalCaseCheckPositions(positions);

        CityBlock best = findCityBlock(null, positions, inBedrock);
        if (best == null) return;

        target1 = best;
        ignorePos = target1.blockPos;

        if (best.type == CheckPosType.TerrainBase) {
            // mine the crystal base first, then the surround block above it
            CityBlock above = new CityBlock();
            above.blockPos = best.blockPos.above();
            above.score = best.score - 10;
            above.type = CheckPosType.Surround;
            if (evalCheckPos(new CheckPos(above.blockPos, above.type), inBedrock) != INVALID_SCORE)
                target2 = above;
        } else {
            target2 = findCityBlock(target1.blockPos, positions, inBedrock);
        }
    }

    private CityBlock findCityBlock(BlockPos exclude, Set<CheckPos> checkPositions, boolean inBedrock) {
        if (targetPlayer == null) return null;

        CityBlock bestBlock = new CityBlock();
        boolean set = false;
        for (CheckPos pos : checkPositions) {
            if (pos.blockPos.equals(exclude)) continue;
            double score = evalCheckPos(pos, inBedrock);
            if (score == INVALID_SCORE) continue;
            if (exclude == null && renderDebugScores.getValue()) {
                CityBlock debug = new CityBlock();
                debug.blockPos = pos.blockPos;
                debug.score = score;
                debug.type = pos.type;
                debugScores.add(debug);
            }
            if (score > bestBlock.score) {
                bestBlock.score = score;
                bestBlock.blockPos = pos.blockPos;
                bestBlock.type = pos.type;
                set = true;
            }
        }
        return set ? bestBlock : null;
    }

    private double evalCheckPos(CheckPos pos, boolean inBedrock) {
        GuangxiMineModule mine = speedMine();
        if (mine == null) return INVALID_SCORE;

        BlockPos blockPos = pos.blockPos;
        if (checkSelf.getValue() && isSelfProtection(blockPos)) return INVALID_SCORE;
        if (friendSync.getValue() && enemyBreaking.isBreaking(blockPos.asLong(), true, System.currentTimeMillis()))
            return INVALID_SCORE;
        BlockState block = mc.level.getBlockState(blockPos);
        boolean goodRebreak = isGoodRebreak(blockPos, inBedrock);

        if (block.isAir() && !goodRebreak) return INVALID_SCORE;
        if (!canBreak(blockPos, block) && !goodRebreak) return INVALID_SCORE;
        if (!mine.inMineRange(blockPos)) return INVALID_SCORE;

        double score = inBedrock ? scoreBedrock(pos) : scoreNormal(pos);
        if (score == INVALID_SCORE) return INVALID_SCORE;
        // keep the current rebreak block sticky so we don't thrash it away
        if (goodRebreak) score += inBedrock && block.isAir() ? 10 : 40;
        return score;
    }

    private boolean isSelfProtection(BlockPos candidate) {
        AABB feet = mc.player.getBoundingBox().deflate(0.01, 0.1, 0.01);
        for (BlockPos ownFeet : BlockPos.betweenClosed(Mth.floor(feet.minX), mc.player.getBlockY(), Mth.floor(feet.minZ),
                Mth.floor(feet.maxX), mc.player.getBlockY(), Mth.floor(feet.maxZ))) {
            if (candidate.equals(ownFeet) || candidate.equals(ownFeet.below())) return true;
            for (Direction dir : HORIZONTAL) if (candidate.equals(ownFeet.relative(dir))) return true;
        }
        return false;
    }

    private boolean isGoodRebreak(BlockPos blockPos, boolean inBedrock) {
        GuangxiMineModule mine = speedMine();
        if (mine == null) return false;
        BlockPos rebreak = mine.getRebreakPos();
        if (rebreak == null || !blockPos.equals(rebreak)) return false;

        if (inBedrock) {
            ensureFeetCache();
            boolean isSelfTrapBlock = false;
            for (Direction dir : HORIZONTAL) {
                if (targetPlayer.blockPosition().above().relative(dir).equals(blockPos)) {
                    isSelfTrapBlock = true;
                    break;
                }
            }
            boolean canFacePlace = mc.level.getBlockState(targetPlayer.blockPosition().above()).isAir();
            return singleFootCell
                    && (blockPos.equals(targetPlayer.blockPosition().above(2))
                            || (isSelfTrapBlock && canFacePlace));
        } else {
            if (blockPos.equals(targetPlayer.blockPosition()) || isBlockInFeet(blockPos)) return false;
            for (Direction dir : HORIZONTAL) {
                if (targetPlayer.blockPosition().relative(dir).equals(blockPos)
                        && isCrystalBlock(targetPlayer.blockPosition().relative(dir).below())) {
                    return true;
                }
            }
            return false;
        }
    }

    private void addNormalCaseCheckPositions(Set<CheckPos> checkPos) {
        AABB feetBox = targetFeetBox();
        for (BlockPos raw : iterate(feetBox)) {
            checkPos.add(new CheckPos(raw.immutable(), CheckPosType.Feet));
        }
        for (BlockPos raw : iterate(feetBox)) {
            BlockPos feet = raw.immutable();
            for (Direction dir : HORIZONTAL) {
                BlockPos surround = feet.relative(dir);

                if (isBlockInFeet(surround)) continue;
                checkPos.add(new CheckPos(surround, CheckPosType.Surround));

                // don't mine the block under the surround if it's a crystal base we want to keep
                if (underMine.getValue()) {
                    BlockPos base = surround.below();
                    if (!isCrystalBlock(base) && canBreak(base, mc.level.getBlockState(base))) {
                        checkPos.add(new CheckPos(base, CheckPosType.TerrainBase));
                    }
                }

                switch (extendBreakMode.getValue()) {
                    case None -> { }
                    case Long -> checkPos.add(new CheckPos(surround.relative(dir), CheckPosType.Extend));
                    case Corner -> {
                        Direction perpDir = getCornerPerpDir(dir);
                        if (perpDir != null) {
                            checkPos.add(new CheckPos(surround.relative(perpDir), CheckPosType.Extend));
                        }
                    }
                }
            }
        }
    }

    private boolean computeTerrainFight() {
        AABB feetBox = targetFeetBox();
        for (BlockPos feet : iterate(feetBox)) {
            for (Direction dir : HORIZONTAL) {
                BlockPos neighbor = feet.relative(dir);
                if (isBlockInFeet(neighbor)) continue;
                if (isCrystalBlock(neighbor.below())) return false;
            }
        }
        return true;
    }

    private void addBedrockCaseCheckPositions(Set<CheckPos> checkPos) {
        boolean isCrawling = targetPlayer.isVisuallyCrawling();

        for (BlockPos raw : iterate(targetFeetBox())) {
            BlockPos pos = raw.immutable();

            // face place into their head, else knock the block above it out to open one up
            BlockPos headPos = isCrawling ? pos : pos.above();
            BlockPos aboveHeadPos = headPos.above();
            if (canBreak(headPos, mc.level.getBlockState(headPos))) {
                checkPos.add(new CheckPos(headPos, CheckPosType.FacePlace));
            } else if (canBreak(aboveHeadPos, mc.level.getBlockState(aboveHeadPos))) {
                checkPos.add(new CheckPos(aboveHeadPos, CheckPosType.Head));
            }

            if (!mc.level.getBlockState(pos.below()).is(Blocks.BEDROCK)) {
                BlockPos center = pos.below();
                boolean plusNotFinished = false;
                for (Direction dir : HORIZONTAL) {
                    BlockPos surround = center.relative(dir);
                    if (!canBreak(surround, mc.level.getBlockState(surround))) continue;
                    // don't mine the sides below the player if they're crystal bases we want to keep
                    if (isCrystalBlock(surround) && mc.level.getBlockState(surround.above()).isAir()) continue;
                    checkPos.add(new CheckPos(surround, CheckPosType.Below));
                    plusNotFinished = true;
                }
                if (!plusNotFinished && canBreak(center, mc.level.getBlockState(center))) {
                    checkPos.add(new CheckPos(center, CheckPosType.Below));
                }
            }

            checkPos.add(new CheckPos(pos, CheckPosType.Surround));
            for (Direction dir : HORIZONTAL) {
                BlockPos surround = pos.relative(dir);
                if (isBlockInFeet(surround)) continue;
                checkPos.add(new CheckPos(surround, CheckPosType.Surround));
            }
        }
    }

    private double scoreNormal(CheckPos pos) {
        BlockPos blockPos = pos.blockPos;
        double score = 0;
        BlockState block = mc.level.getBlockState(blockPos);

        if (pos.type == CheckPosType.Feet) {
            BlockState headBlock = mc.level.getBlockState(blockPos.above());
            if (headBlock.is(Blocks.OBSIDIAN)) {
                score += 100;
            } else {
                if (block.is(Blocks.COBWEB)) return INVALID_SCORE;
                score += 50;
            }
        } else {
            BlockState selfHeadState = mc.level.getBlockState(mc.player.blockPosition().above());

            if (blockPos.equals(mc.player.blockPosition())
                    && (selfHeadState.is(Blocks.OBSIDIAN) || selfHeadState.is(Blocks.BEDROCK))) {
                return INVALID_SCORE;
            }

            if (pos.type == CheckPosType.Surround) {

                BlockState down = mc.level.getBlockState(blockPos.below());
                if (down.isAir()) {
                    score += 35;
                } else if (!down.is(Blocks.OBSIDIAN) && !down.is(Blocks.BEDROCK)) {
                    score += 10;
                } else {
                    score += 55;

                    boolean isPosAntiSurround = false;
                    for (Direction dir : HORIZONTAL) {
                        BlockPos antiSurroundBlockPos = blockPos.relative(dir);
                        if (targetPlayer.distanceToSqr(Vec3.atCenterOf(blockPos)) < 4.0
                                && getBlockStateIgnore(antiSurroundBlockPos).isAir()
                                && isCrystalBlock(antiSurroundBlockPos.below())) {
                            isPosAntiSurround = true;
                            break;
                        }
                    }
                    if (isPosAntiSurround) score += 25;
                }
            }

            if (pos.type == CheckPosType.Extend) score += 20;

            if (pos.type == CheckPosType.TerrainBase) {
                score += isTerrainFight ? 30 : 1;
            }
        }

        double d = targetPlayer.position().distanceTo(Vec3.atCenterOf(blockPos));
        score += 10 / d;

        Vec3 velocity = targetPlayer.getDeltaMovement();
        if (velocity.horizontalDistanceSqr() > 0.001) {
            Vec3 moveDir = new Vec3(velocity.x, 0, velocity.z).normalize();
            Vec3 toBlock = Vec3.atCenterOf(blockPos).subtract(targetPlayer.position()).normalize();
            double dot = moveDir.dot(toBlock);
            if (dot > 0) score += dot * 5;
        }

        return score;
    }

    private double scoreBedrock(CheckPos pos) {
        BlockPos blockPos = pos.blockPos;
        double score = 0;

        if (blockPos.getY() == targetPlayer.getBlockY() + 2
                || blockPos.getY() == targetPlayer.getBlockY() - 1) {
            score += 10;
        }

        ensureFeetCache();
        if (singleFootCell) {
            boolean canMineFaceBlock = !mc.level.getBlockState(targetPlayer.blockPosition().above()).is(Blocks.BEDROCK);
            if (canMineFaceBlock) {
                if (blockPos.equals(targetPlayer.blockPosition().above())) {
                    score += 20;
                } else {
                    boolean isSelfTrapBlock = false;
                    for (Direction dir : HORIZONTAL) {
                        if (targetPlayer.blockPosition().above().relative(dir).equals(blockPos)) {
                            isSelfTrapBlock = true;
                            break;
                        }
                    }
                    if (isSelfTrapBlock) score += 7.5;
                }
            }
        }

        if (pos.type == CheckPosType.Below) {
            score += blockPos.equals(targetPlayer.blockPosition().below()) ? 25 : 30;
        }

        double d = targetPlayer.position().distanceTo(Vec3.atCenterOf(blockPos));
        score += 10 / d;
        return score;
    }

    @Override
    public void onRender3D(Render3DEvent event) {
        if (nullCheck() || !glassRender.getValue() || glassRenderPos == null) return;

        long age = System.currentTimeMillis() - glassRenderStart;
        double fadeMs = glassFadeTime.getValue() * 1000.0;
        if (age > fadeMs) {
            glassRenderPos = null;
            return;
        }
        double t = age / fadeMs;
        Color fc = glassFillColor.getValue();
        Color oc = glassOutlineColor.getValue();
        RenderUtil.drawBoxFilled(event.getMatrix(), glassRenderPos,
                new Color(fc.getRed(), fc.getGreen(), fc.getBlue(), (int) (fc.getAlpha() * (1 - t))));
        RenderUtil.drawBox(event.getMatrix(), glassRenderPos,
                new Color(oc.getRed(), oc.getGreen(), oc.getBlue(), (int) (oc.getAlpha() * (1 - t))), 1.5f);
    }

    @Subscribe
    private void onRenderDebug(Render3DEvent event) {
        if (nullCheck() || !renderDebugScores.getValue() || targetPlayer == null) return;
        if (speedMine() == null) return;

        double bestScore = debugScores.stream().mapToDouble(cp -> cp.score).max().orElse(0);
        if (bestScore <= 0) return;

        for (CityBlock cp : debugScores) {
            double s = cp.score;
            int alpha = (int) Math.min(200, Math.max(20, 200 * (s / bestScore)));
            RenderUtil.drawBox(event.getMatrix(), cp.blockPos, new Color(255, 40, 40, alpha), 1.0f);
        }
    }

    private GuangxiMineModule speedMine() {
        GuangxiMineModule m = GuizhouWare.moduleManager.getModuleByClass(GuangxiMineModule.class);
        return (m != null && m.isEnabled()) ? m : null;
    }

    private AABB targetFeetBox() {
        AABB bb = targetPlayer.getBoundingBox().deflate(0.01, 0.1, 0.01);
        double feetY = targetPlayer.getY();
        return new AABB(bb.minX, feetY, bb.minZ, bb.maxX, feetY + 0.1, bb.maxZ);
    }

    private void ensureFeetCache() {
        if (targetPlayer == null || mc.player == null) return;
        int tick = mc.player.tickCount;
        int id = targetPlayer.getId();
        if (tick == feetCacheTick && id == feetCacheId) return;
        feetCacheTick = tick;
        feetCacheId = id;
        feetCells.clear();
        feetOnBedrock = false;
        int n = 0;
        for (BlockPos raw : iterate(targetFeetBox())) {
            BlockPos cell = raw.immutable();
            feetCells.add(cell);
            n++;
            if (mc.level.getBlockState(cell).is(Blocks.BEDROCK)) feetOnBedrock = true;
        }
        singleFootCell = n == 1;
    }

    private boolean inBedrockCase() {
        ensureFeetCache();
        return feetOnBedrock;
    }

    private boolean isBlockInFeet(BlockPos blockPos) {
        ensureFeetCache();
        for (int i = 0, n = feetCells.size(); i < n; i++) {
            if (blockPos.equals(feetCells.get(i))) return true;
        }
        return false;
    }

    private boolean isCrystalBlock(BlockPos blockPos) {
        BlockState s = mc.level.getBlockState(blockPos);
        return s.is(Blocks.OBSIDIAN) || s.is(Blocks.BEDROCK);
    }

    private BlockState getBlockStateIgnore(BlockPos blockPos) {
        if (blockPos == null) return Blocks.AIR.defaultBlockState();
        if (blockPos.equals(ignorePos)) return Blocks.AIR.defaultBlockState();
        return mc.level.getBlockState(blockPos);
    }

    private boolean canBreak(BlockPos pos, BlockState state) {
        if (state.isAir()) return false;
        if (state.is(Blocks.FIRE) || state.is(Blocks.SOUL_FIRE)) return false;
        return state.getDestroySpeed(mc.level, pos) >= 0;
    }

    private boolean intersectsEntity(AABB box, java.util.function.Predicate<Entity> pred) {
        for (Entity e : mc.level.getEntities((Entity) null, box)) {
            if (pred.test(e)) return true;
        }
        return false;
    }

    private static Iterable<BlockPos> iterate(AABB box) {
        return BlockPos.betweenClosed(
                Mth.floor(box.minX), Mth.floor(box.minY), Mth.floor(box.minZ),
                Mth.floor(box.maxX), Mth.floor(box.maxY), Mth.floor(box.maxZ));
    }

    private Direction getCornerPerpDir(Direction dir) {
        return switch (dir) {
            case NORTH -> Direction.EAST;
            case SOUTH -> Direction.WEST;
            case EAST  -> Direction.NORTH;
            case WEST  -> Direction.SOUTH;
            default    -> null;
        };
    }

    @Override
    public String getDisplayInfo() {
        return targetPlayer != null ? targetPlayer.getName().getString() : null;
    }

    private static class CityBlock {
        BlockPos blockPos;
        double score;
        CheckPosType type;
    }

    private static final class CheckPos {
        final BlockPos blockPos;
        final CheckPosType type;

        CheckPos(BlockPos blockPos, CheckPosType type) {
            this.blockPos = blockPos;
            this.type = type;
        }

        // equality on position only, so duplicate positions collapse regardless of type
        @Override
        public int hashCode() {
            return blockPos.hashCode();
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof CheckPos other && other.blockPos.equals(blockPos);
        }
    }

    private enum CheckPosType { Feet, Surround, Extend, FacePlace, Head, Below, TerrainBase }

    public enum SortPriority { Angle, Distance }

    private enum AntiSwimMode { None, Always, Mine, MineOrSwim }

    private enum AntiSurroundMode { None, Inner, Outer, Auto }

    private enum ExtendBreakMode { None, Long, Corner }
    public enum GlassSwap { Silent, Inventory }
    private enum GlassStage { Idle, AwaitPlace, AwaitBreak }
}
