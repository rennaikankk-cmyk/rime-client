package dev.guizhou.manager;

import dev.guizhou.GuizhouWare;

import dev.guizhou.event.impl.entity.player.PreTickEvent;
import dev.guizhou.event.system.Subscribe;
import dev.guizhou.util.PacketSendUtil;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.util.Mth;
import net.minecraft.client.player.Input;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static dev.guizhou.util.traits.Util.EVENT_BUS;
import static dev.guizhou.util.traits.Util.mc;

public class RotationManager {

    private static final float SPEED = 360f;

    private static final float EASE = 1.0f;

    private static final float THRESHOLD = 5f;

    private static final int HOLD_TICKS = 0;

    public enum JitterMode { NONE, GRIM, NORMAL }

    private float realYaw, realPitch;
    private float rotationYaw, rotationPitch;
    private float rotationYaw0, rotationPitch0;
    private float serverYaw, serverPitch;
    private float serverYaw0;
    private float serverDeltaYaw;

    private boolean bypassUseSpoof;

    private boolean silentSyncRequired;
    private final SilentArbitration silentWindow = new SilentArbitration();
    private int silentSentPriority;
    private long lastSilentMs;
    private Object sessionPlayer, sessionLevel, sessionConnection;
    private boolean serverKnown;
    private long serverRevision;
    private final java.util.Map<String, Integer> actionExpiry = new java.util.HashMap<>();

    /**
     * Arbitration window for silent packets: the first accepted send of a window owns it,
     * equal or lower priorities are rejected until the window closes. Windows close at the
     * sendPosition tail and at the tick boundary, so a post-movement submission (which runs
     * after sendPosition) can never gate the next tick's pre-movement requests. The pending
     * orientation sync and its priority live in the manager and outlive the window on
     * purpose; they are consumed by the next normal movement packet.
     */
    public static final class SilentArbitration {
        private boolean open;
        private int priority;

        public boolean blocks(int requestPriority) {
            return open && requestPriority <= priority;
        }

        public void accepted(int requestPriority) {
            open = true;
            priority = requestPriority;
        }

        public void close() {
            open = false;
        }
    }

    private final List<RotationRequest> requests = new ArrayList<>();
    private String lastActiveId;
    private float currentYawSpeed, currentPitchSpeed;
    private int ticksHolding;
    private boolean returning;
    private float returnYawStep = 180f;
    private boolean returnMoveFix = true;

    public void init() {
        EVENT_BUS.register(this, "RotationManager");
        PacketSendUtil.addSubmissionListener(this::onSubmitted);
    }

    private void ensureSession() {
        if (sessionPlayer == mc.player && sessionLevel == mc.level && sessionConnection == mc.getConnection()) return;
        sessionPlayer = mc.player;
        sessionLevel = mc.level;
        sessionConnection = mc.getConnection();
        requests.clear();
        actionExpiry.clear();
        silentWindow.close();
        silentSyncRequired = false;
        silentSentPriority = 0;
        lastSilentMs = 0;
        bypassUseSpoof = false;
        returning = false;
        lastActiveId = null;
        ticksHolding = 0;
        currentYawSpeed = currentPitchSpeed = 0;
        returnYawStep = 180f;
        returnMoveFix = true;
        serverKnown = false;
        serverRevision = 0;
        realYaw = mc.player == null ? 0 : mc.player.getYRot();
        realPitch = mc.player == null ? 0 : mc.player.getXRot();
        rotationYaw = rotationYaw0 = serverYaw = serverYaw0 = realYaw;
        rotationPitch = rotationPitch0 = serverPitch = realPitch;
        serverDeltaYaw = 0;
    }

    /** This sees the final packet after cancellation/replacement policies, not a server ACK. */
    private void onSubmitted(Packet<?> packet) {
        if (!mc.isSameThread() || !(packet instanceof ServerboundMovePlayerPacket move) || !move.hasRotation()) return;
        ensureSession();
        if (mc.player == null) return;
        float yaw = move.getYRot(serverYaw), pitch = move.getXRot(serverPitch);
        if (!RotationMath.finite(yaw, pitch)) return;
        setServerDeltaYaw(RotationMath.difference(yaw, serverYaw));
        setServerYaw(yaw);
        setServerPitch(pitch);
        serverKnown = true;
        serverRevision++;
    }

    /** Queue a short-lived turn when stepping is required. Callers retry/revalidate next tick;
     * no inventory, interaction or attack callback is retained by the manager. */
    public boolean tryAction(RotationRequest request) {
        if (!mc.isSameThread() || mc.player == null || mc.getConnection() == null) return false;
        ensureSession();
        if (!valid(request) || request.mode != RotationRequest.Mode.SILENT) return false;
        request.targetPitch = Mth.clamp(request.targetPitch, -90f, 90f);
        float limit = Float.isFinite(request.yawStep) ? Math.clamp(request.yawStep, 5f, 180f) : 180f;
        RotationRequest active = getActiveRequest();
        if (active != null && !active.id.equals(request.id) && active.priority > request.priority) return false;
        // Waiting for the exact submitted target prevents a final extra step in the same tick.
        if (limit < 180f && !isSynchronized(request.targetYaw, request.targetPitch)) {
            RotationRequest turn = new RotationRequest(request.id, request.priority,
                    request.targetYaw, request.targetPitch, RotationRequest.Mode.MOTION, true, true);
            turn.yawStep = limit;
            turn.moveFix = request.moveFix;
            turn.continueWhile = request.continueWhile;
            if (!trySubmit(turn)) return false;
            actionExpiry.put(request.id, mc.player.tickCount + 3);
            return false;
        }
        // Action geometry uses the requested angle, so never add optional display jitter here.
        RotationRequest exact = new RotationRequest(request.id, request.priority,
                request.targetYaw, request.targetPitch, RotationRequest.Mode.SILENT, false, true);
        if (!trySubmit(exact)) return false;
        cancel(request.id);
        return true;
    }

    private static boolean valid(RotationRequest request) {
        return request != null && request.id != null && !request.id.isBlank() && request.mode != null
                && RotationMath.finite(request.targetYaw, request.targetPitch) && request.continueWhile.getAsBoolean();
    }

    public boolean isSynchronized(float yaw, float pitch) {
        return serverKnown && RotationMath.matches(serverYaw, serverPitch, yaw, pitch, 0.01f);
    }

    public void submit(RotationRequest request) {
        trySubmit(request);
    }

    /** SILENT returns true only after an uncancelled local send; other modes are queued. */
    public boolean trySubmit(RotationRequest request) {
        if (!mc.isSameThread() || mc.player == null || mc.getConnection() == null) return false;
        ensureSession();
        if (!valid(request)) return false;
        request.targetPitch = Mth.clamp(request.targetPitch, -90f, 90f);
        if (request.mode == RotationRequest.Mode.SILENT) {
            if (silentWindow.blocks(request.priority)) return false;
            if (!performSilent(request)) return false;
            silentWindow.accepted(request.priority);
            silentSentPriority = request.priority;
            silentSyncRequired = true;
            lastSilentMs = System.currentTimeMillis();
            return true;
        }

        int index = requests.indexOf(request);
        if (index >= 0) requests.set(index, request);
        else requests.add(request);
        requests.sort(Comparator.comparingInt((RotationRequest r) -> r.priority).reversed());
        return true;
    }

    public void cancel(String id) {
        requests.removeIf(r -> r.id.equals(id));
        actionExpiry.remove(id);
    }

    public boolean hasRequest(String id) {
        return requests.stream().anyMatch(r -> r.id.equals(id));
    }

    public boolean isCompleted(String id) {
        RotationRequest active = getActiveRequest();
        return active != null && active.id.equals(id) && isSynchronized(active.targetYaw, active.targetPitch);
    }

    /** First listener of every tick: the post-movement silent window died with the old
     * tick, so its priority must not arbitrate this tick's pre-movement requests. The
     * pending orientation sync and its priority stay untouched until sendPosition. */
    @Subscribe(priority = Integer.MAX_VALUE)
    private void onTickBoundary(PreTickEvent event) {
        ensureSession();
        if (mc.player != null) {
            requests.removeIf(r -> !r.continueWhile.getAsBoolean()
                    || actionExpiry.containsKey(r.id) && actionExpiry.get(r.id) < mc.player.tickCount);
            actionExpiry.entrySet().removeIf(e -> e.getValue() < mc.player.tickCount);
        }
        silentWindow.close();
    }

    @Subscribe(priority = Integer.MIN_VALUE)
    private void onPreTick(PreTickEvent event) {
        if (mc.player == null) return;

        updateRealRotation(mc.player.getYRot(), mc.player.getXRot());

        rotationYaw0 = rotationYaw;
        rotationPitch0 = rotationPitch;

        RotationRequest active = getActiveRequest();
        if (active != null) {
            processRequest(active);
        } else if (returning) {
            returnToRealRotation();
        } else {
            idleReset();
        }
    }

    private void processRequest(RotationRequest request) {
        returnYawStep = Float.isFinite(request.yawStep) ? Math.clamp(request.yawStep, 5f, 180f) : 180f;
        returnMoveFix = request.moveFix;

        if (!request.id.equals(lastActiveId)) {
            resetRotationToReal();
            lastActiveId = request.id;
        }

        boolean updated = false;
        if (request.shouldUpdate()) {
            float oldYaw = request.targetYaw;
            float oldPitch = request.targetPitch;
            request.updateTarget();
            if (!valid(request)) { cancel(request.id); returning = true; return; }
            request.targetPitch = Mth.clamp(request.targetPitch, -90f, 90f);
            updated = Math.abs(yawDifference(oldYaw, request.targetYaw)) > 0.001f
                    || Math.abs(oldPitch - request.targetPitch) > 0.001f;
            if (updated) ticksHolding = 0;
        }

        float yawDiff = yawDifference(request.targetYaw, rotationYaw);
        float pitchDiff = request.targetPitch - rotationPitch;

        boolean reached = serverKnown && RotationMath.matches(serverYaw, serverPitch,
                request.targetYaw, request.targetPitch, request.noJitter ? 0.01f : THRESHOLD);

        if (reached && !updated) {
            if (request.sticky) {

                ticksHolding = 0;
                interpolateRotation(yawDiff, pitchDiff, !request.noJitter);
            } else if (++ticksHolding >= HOLD_TICKS) {
                removeActiveRequest();
                ticksHolding = 0;
                returning = true;
            }
        } else {
            ticksHolding = 0;
            interpolateRotation(yawDiff, pitchDiff, !request.noJitter);
        }
    }

    private void resetRotationToReal() {
        float targetYaw = alignYaw(realYaw, rotationYaw);
        realYaw = targetYaw;
        mc.player.setYRot(targetYaw);
        rotationYaw = serverKnown ? serverYaw : realYaw;
        rotationPitch = serverKnown ? serverPitch : realPitch;
        currentYawSpeed = 0f;
        currentPitchSpeed = 0f;
        ticksHolding = 0;
        returning = false;
    }

    private void returnToRealRotation() {
        float targetYaw = alignYaw(realYaw, rotationYaw);
        float yawDiff = targetYaw - rotationYaw;
        float pitchDiff = realPitch - rotationPitch;

        interpolateRotation(yawDiff, pitchDiff, true);

        boolean backReached = Math.abs(yawDiff) <= THRESHOLD && Math.abs(pitchDiff) <= THRESHOLD;
        if (backReached) {
            returning = false;
            realYaw = targetYaw;
            mc.player.setYRot(targetYaw);
            rotationYaw = realYaw;
            rotationPitch = realPitch;
            lastActiveId = null;
        }
    }

    private void interpolateRotation(float yawDiff, float pitchDiff, boolean jitter) {
        currentYawSpeed = lerp(currentYawSpeed, yawDiff, EASE);
        currentPitchSpeed = lerp(currentPitchSpeed, pitchDiff, EASE);

        RotationRequest active = getActiveRequest();
        float limit = active != null && Float.isFinite(active.yawStep) ? Math.clamp(active.yawStep, 5f, 180f)
                : returnYawStep;
        float pitchSpeed = Mth.clamp(currentPitchSpeed, -SPEED, SPEED);

        float newYaw = rotationYaw + currentYawSpeed;
        float newPitch = rotationPitch + pitchSpeed;

        JitterMode jitterMode = active == null ? JitterMode.NONE : active.jitter;
        if (jitter && jitterMode == JitterMode.NORMAL) {
            float minJitter = THRESHOLD / 4f;
            float maxJitter = THRESHOLD / 2f;
            float jitterYaw = minJitter + (float) (Math.random() * (maxJitter - minJitter));
            float jitterPitch = minJitter + (float) (Math.random() * (maxJitter - minJitter));
            jitterYaw *= Math.random() < 0.5 ? -1 : 1;
            jitterPitch *= Math.random() < 0.5 ? -1 : 1;
            newYaw += jitterYaw;
            newPitch = Mth.clamp(newPitch + jitterPitch, -90f, 90f);
        } else if (jitter && jitterMode == JitterMode.GRIM) {
            float f = (float) ((Math.random() * 2.0 - 1.0) * 0.001f);
            newPitch = Mth.clamp(newPitch + f, -90f, 90f);
        }

        rotationYaw = RotationMath.step(serverKnown ? serverYaw : rotationYaw, newYaw, limit);
        rotationPitch = Mth.clamp(newPitch, -90f, 90f);
    }

    private void idleReset() {
        lastActiveId = null;
        rotationYaw = realYaw;
        rotationPitch = realPitch;
        currentYawSpeed = 0f;
        currentPitchSpeed = 0f;
    }

    public Input computeMoveFixInput(Input real, float realYawNow) {
        float delta = Mth.wrapDegrees(realYawNow - rotationYaw);

        // 1.21.1 Input encodes movement as impulses: forwardImpulse > 0 = forward,
        // leftImpulse > 0 = left. KeyboardInput has already applied crouch slowdown.
        float inputX = Math.signum(real.leftImpulse) * -1.0f;
        float inputZ = Math.signum(real.forwardImpulse);
        if (inputX == 0 && inputZ == 0) return real;

        double moveAngle = Math.toDegrees(Math.atan2(inputX, inputZ));
        double finalAngle = moveAngle + delta;
        int sector = (int) Math.round(finalAngle / 45.0) & 7;

        boolean f = false, b = false, l = false, r = false;
        switch (sector) {
            case 0 -> f = true;
            case 1 -> { f = true; r = true; }
            case 2 -> r = true;
            case 3 -> { b = true; r = true; }
            case 4 -> b = true;
            case 5 -> { b = true; l = true; }
            case 6 -> l = true;
            case 7 -> { f = true; l = true; }
        }
        Input fixed = new Input();
        float magnitude = Math.max(Math.abs(real.leftImpulse), Math.abs(real.forwardImpulse));
        fixed.forwardImpulse = ((f ? 1 : 0) - (b ? 1 : 0)) * magnitude;
        // positive leftImpulse means strafing left
        fixed.leftImpulse = ((l ? 1 : 0) - (r ? 1 : 0)) * magnitude;
        fixed.left = l;
        fixed.right = r;
        fixed.up = f;
        fixed.down = b;
        fixed.jumping = real.jumping;
        fixed.shiftKeyDown = real.shiftKeyDown;
        return fixed;
    }

    private boolean performSilent(RotationRequest req) {
        var flight = GuizhouWare.moduleManager == null ? null : GuizhouWare.moduleManager.getModuleByClass(
                dev.guizhou.features.modules.movement.GuangxiElytraModule.class);
        if (flight != null && flight.gzBoostControlsLook()) return false;
        float targetYaw = req.targetYaw;
        float targetPitch = req.targetPitch;

        float f = req.noJitter || req.jitter == JitterMode.NONE
                ? 0f : (float) ((Math.random() * 2.0 - 1.0) * 0.001f);
        targetPitch = Mth.clamp(targetPitch + f, -90.0f, 90.0f);

        ServerboundMovePlayerPacket packet = new ServerboundMovePlayerPacket.PosRot(
                mc.player.getX(), mc.player.getY(), mc.player.getZ(),
                targetYaw, targetPitch, mc.player.onGround()
        );
        long before = serverRevision;
        boolean sent = PacketSendUtil.send(packet);
        // A replacement may have different angles, or may omit rotation entirely.
        if (serverRevision != before) silentSyncRequired = true;
        return sent && serverRevision != before && isSynchronized(targetYaw, targetPitch);
    }

    private RotationRequest getActiveRequest() {
        return requests.isEmpty() ? null : requests.get(0);
    }

    private void removeActiveRequest() {
        if (!requests.isEmpty()) requests.remove(0);
    }

    private void updateRealRotation(float yaw, float pitch) {
        realYaw = yaw;
        realPitch = Mth.clamp(pitch, -90f, 90f);
    }

    public boolean isRotating() {
        return getActiveRequest() != null || returning;
    }

    public boolean isActive(String id) {
        RotationRequest active = getActiveRequest();
        return active != null && active.id.equals(id);
    }

    public boolean isMoveFixEnabled() {
        RotationRequest active = getActiveRequest();
        return active == null ? returnMoveFix : active.moveFix;
    }

    public float getRealYaw() { return realYaw; }
    public float getRealPitch() { return realPitch; }

    public float getRotationYaw() { return rotationYaw; }
    public float getRotationPitch() { return rotationPitch; }

    public float getRenderYaw(float partialTicks) {
        return Mth.rotLerp(partialTicks, rotationYaw0, rotationYaw);
    }

    public float getRenderPitch(float partialTicks) {
        return rotationPitch0 + (rotationPitch - rotationPitch0) * partialTicks;
    }

    public float getServerYaw() { return serverYaw; }
    public float getServerPitch() { return serverPitch; }
    public float getServerYaw0() { return serverYaw0; }
    public float getServerDeltaYaw() { return serverDeltaYaw; }

    public void setServerYaw(float yaw) {
        this.serverYaw0 = this.serverYaw;
        this.serverYaw = yaw;
    }
    public void setServerPitch(float pitch) {
        this.serverPitch = pitch;
    }
    public void setServerDeltaYaw(float delta) {
        this.serverDeltaYaw = delta;
    }

    public boolean isBypassUseSpoof() { return bypassUseSpoof; }
    public void setBypassUseSpoof(boolean bypass) { bypassUseSpoof = bypass; }

    public boolean isSilentSyncRequired() { return silentSyncRequired; }

    public boolean isSilentSyncRequiredAtLeast(int priority) {
        return silentSyncRequired && silentSentPriority >= priority;
    }

    public void setSilentSyncRequired(boolean required) { silentSyncRequired = required; }

    public void resetSilentTick() { silentWindow.close(); }
    public boolean isSilentActive() { return System.currentTimeMillis() - lastSilentMs < 500; }

    private static float yawDifference(float target, float current) {
        return RotationMath.difference(target, current);
    }

    private static float alignYaw(float base, float reference) {
        int wraps = Math.round((reference - base) / 360f);
        return base + wraps * 360f;
    }

    private static float lerp(float from, float to, float factor) {
        return from + (to - from) * factor;
    }
}
