package dev.guizhou.features.modules.qinghua;

import dev.guizhou.event.impl.entity.player.PreTickEvent;
import dev.guizhou.event.system.Subscribe;
import dev.guizhou.features.modules.Module;
import dev.guizhou.features.modules.qinghua.util.BlockUtil;
import dev.guizhou.features.modules.qinghua.util.TPUtil;
import dev.guizhou.features.settings.Setting;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/** Port of eternal Vclip — clip search / TP path unchanged. */
public class VclipModule extends Module {
    public final Setting<Integer> clipValue = num("ClipValue", 10, 0, 100000)
            .described("The height to clip.");
    public final Setting<Integer> protectY = num("ProtectY", -70, -128, 256)
            .described("The max height this VClip module can reach.");
    public final Setting<Boolean> downClipWhenSneak = bool("DownClipWhenSneaking", true)
            .described("Whether to clip down when sneaking.");
    public final Setting<Boolean> voidProtect = bool("VoidProtect", false)
            .described("Prevents you from clipping into the void.");
    public final Setting<Double> moveDistance = num("MoveDistance", 10.0, 1.0, 50.0)
            .described("Max distance for a packet to move.");

    public VclipModule() {
        super("VClip", "vclip.", Category.MOVEMENT);
    }

    @Subscribe
    private void onPre(PreTickEvent event) {
        if (mc.player == null) return;
        double clipY = this.clipValue.getValue();
        Vec3 finalVec = mc.player.position();
        int offsetY = 0;
        for (int n = (int) clipY; offsetY <= n; offsetY++) {
            double targetY = this.downClipWhenSneak.getValue() && mc.player.isShiftKeyDown()
                    ? finalVec.y - (clipY - offsetY)
                    : finalVec.y + (clipY - offsetY);
            Vec3 vec = new Vec3(finalVec.x, targetY, finalVec.z);
            if (vec.y > this.protectY.getValue() && BlockUtil.isSafeBlock(BlockPos.containing(vec))) {
                finalVec = vec;
                break;
            }
            if (offsetY == n) break;
        }
        Vec3 startVec = mc.player.position();
        if (this.voidProtect.getValue() && finalVec.y <= -128.0) {
            finalVec = new Vec3(finalVec.x, -128.0, finalVec.z);
        }
        TPUtil.doTp(startVec, finalVec, this.moveDistance.getValue(), true);
        mc.player.setPos(finalVec);
        disable();
    }
}
