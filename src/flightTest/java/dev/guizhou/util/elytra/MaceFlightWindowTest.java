package dev.guizhou.util.elytra;

public final class MaceFlightWindowTest {
    private static int checks;
    public static void main(String[] args) {
        var window = new MaceFlightWindow();
        check(!window.ready(0, 1.5), "unobserved movement cannot charge");
        window.observe(1, 100, false);
        window.observe(2, 99, false);
        check(!window.ready(2, 1.5), "short descent is insufficient");
        window.observe(3, 98, false);
        check(window.ready(3, 1.5), "accepted descending positions accumulate");
        check(!window.ready(3, 3), "configured minimum remains meaningful");
        window.observe(3, 98, false);
        check(window.ready(3, 1.5), "duplicate rotation does not erase descent");
        check(!window.ready(6, 1.5), "stale submissions cannot strike");
        window.observe(4, 98.1, false);
        check(!window.ready(4, 1.5), "upward movement ends descent");
        window.observe(5, 95, false);
        check(window.ready(5, 1.5), "new descent charges again");
        window.clearCharge();
        check(!window.ready(5, 1.5), "one committed strike consumes charge");
        window.observe(6, 92, true);
        check(!window.ready(6, 1.5), "landing cannot charge");
        window.observe(7, -50, false);
        check(!window.ready(7, 1.5), "discontinuous movement cannot charge");
        window.observe(8, -53, false);
        window.reset();
        check(!window.ready(8, 1.5), "world change or correction clears evidence");
        window.observe(10, 100, false);
        window.observe(9, 95, false);
        check(!window.ready(9, 1.5), "clock reversal clears evidence");
        window.observe(10, Double.NaN, false);
        check(!window.ready(10, 1.5), "invalid movement clears evidence");
        var diagonal = MaceFlightWindow.approach(new net.minecraft.world.phys.Vec3(10,-80,10));
        check(diagonal.x == 10 && diagonal.z == 10 && diagonal.y == -10, "dive retains horizontal interception room");
        check(MaceFlightWindow.approach(new net.minecraft.world.phys.Vec3(2,-20,1)).y == -20, "close attack lane permits steep descent");
        check(MaceFlightWindow.approach(new net.minecraft.world.phys.Vec3(10,5,0)).y == 0, "follow does not climb after a rising target");
        check(MaceFlightWindow.approach(new net.minecraft.world.phys.Vec3(10,-2,0)).y == -2, "shallow approach remains unchanged");
        check(MaceFlightWindow.approach(null).lengthSqr() == 0, "missing route yields no intent");
        System.out.println("MaceFlightWindowTest passed: " + checks + " checks");
    }
    private static void check(boolean ok, String message) { checks++; if (!ok) throw new AssertionError(message); }
}
