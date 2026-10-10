package dev.guizhou.features.modules.qinghua;

import dev.guizhou.event.impl.entity.player.TickEvent;
import dev.guizhou.event.system.Subscribe;
import dev.guizhou.features.modules.Module;
import dev.guizhou.features.settings.Setting;
import net.minecraft.world.entity.player.Abilities;
import net.minecraft.world.phys.Vec3;

/**
 * Port of eternal Orbit — circle math unchanged.
 * Meteor Flight toggle → abilities.flying (same fly-enable effect).
 */
public class OrbitModule extends Module {
    private final Setting<Double> range = num("Range", 128.0, 1.0, 500.0)
            .described("The diameter of the orbit circle.");
    private final Setting<Double> speed = num("Speed", 3.0, 0.1, 10.0)
            .described("The flight speed around the circle.");

    private Vec3 center;
    private double angle;
    private boolean flightWasOn;
    private boolean mayflyWasOn;

    public OrbitModule() {
        super("Orbit", "orbit.", Category.MOVEMENT);
    }

    @Override
    public void onEnable() {
        if (mc.player != null) {
            this.center = mc.player.position();
            this.angle = 0.0;
            Abilities abilities = mc.player.getAbilities();
            this.flightWasOn = abilities.flying;
            this.mayflyWasOn = abilities.mayfly;
            if (!abilities.flying) {
                abilities.mayfly = true;
                abilities.flying = true;
                mc.player.onUpdateAbilities();
            }
        }
    }

    @Override
    public void onDisable() {
        this.center = null;
        if (mc.player != null) {
            Abilities abilities = mc.player.getAbilities();
            if (!this.flightWasOn && abilities.flying) {
                abilities.flying = false;
                abilities.mayfly = this.mayflyWasOn;
                mc.player.onUpdateAbilities();
            }
        }
    }

    @Subscribe
    private void onTick(TickEvent event) {
        if (mc.player == null || mc.level == null) return;
        if (this.center == null) {
            this.center = mc.player.position();
            this.angle = 0.0;
            return;
        }
        double radius = this.range.getValue() / 2.0;
        double currentSpeed = this.speed.getValue();
        double angularSpeed = currentSpeed * 0.08;
        this.angle += angularSpeed;
        if (this.angle > Math.PI * 2) this.angle -= Math.PI * 2;

        double targetX = this.center.x + radius * Math.cos(this.angle);
        double targetZ = this.center.z + radius * Math.sin(this.angle);
        double targetY = this.center.y;
        double dx = targetX - mc.player.getX();
        double dz = targetZ - mc.player.getZ();
        double tangentX = -Math.sin(this.angle);
        double tangentZ = Math.cos(this.angle);
        double velocityX = dx * 0.3 + tangentX * currentSpeed * 0.4;
        double velocityZ = dz * 0.3 + tangentZ * currentSpeed * 0.4;
        double velocityY = (targetY - mc.player.getY()) * 0.2;
        mc.player.setDeltaMovement(velocityX, velocityY, velocityZ);
    }
}
