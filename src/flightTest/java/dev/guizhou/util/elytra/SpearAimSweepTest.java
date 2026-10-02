package dev.guizhou.util.elytra;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Pins the charged-spear yaw sweep: bounded zero-mean periodic offset, and the
 * ChaseModule wiring that applies it only inside aimSpear while dropping the
 * velocity lead term (server-side calibration: packet offset alone predicts the
 * query-time target position better than offset + velocity lead).
 */
public final class SpearAimSweepTest {
    private static final String CHASE = "dev/guizhou/features/modules/combat/GuangxiElytraChaseModule";
    private static final String SWEEP = "dev/guizhou/util/elytra/SpearAimSweep";
    private static int checks;

    private SpearAimSweepTest() {
    }

    public static void main(String[] args) throws IOException {
        verifySweepMath();
        verifyWiring();
        System.out.println("SpearAimSweepTest passed: " + checks + " checks");
    }

    private static void verifySweepMath() {
        for (int tick = -2 * SpearAimSweep.PERIOD_TICKS; tick < 4 * SpearAimSweep.PERIOD_TICKS; tick++) {
            float offset = SpearAimSweep.sweepOffsetDeg(tick);
            check(Math.abs(offset) <= SpearAimSweep.AMPLITUDE_DEG + 1.0e-4f,
                    "sweep offset must stay bounded at tick " + tick);
            check(Math.abs(SpearAimSweep.applySweep(137.5f, tick) - (137.5f + offset)) < 1.0e-4f,
                    "applySweep must preserve the base yaw at tick " + tick);
            check(Math.abs(SpearAimSweep.sweepOffsetDeg(tick + SpearAimSweep.PERIOD_TICKS) - offset) < 1.0e-4f,
                    "sweep must be periodic at tick " + tick);
        }
        double sum = 0.0;
        for (int tick = 0; tick < SpearAimSweep.PERIOD_TICKS; tick++) {
            sum += SpearAimSweep.sweepOffsetDeg(tick);
        }
        check(Math.abs(sum) < 0.5, "sweep must be zero-mean over one period, sum=" + sum);
        double maxAbs = 0.0;
        for (int tick = 0; tick < SpearAimSweep.PERIOD_TICKS; tick++) {
            maxAbs = Math.max(maxAbs, Math.abs(SpearAimSweep.sweepOffsetDeg(tick)));
        }
        check(maxAbs >= SpearAimSweep.AMPLITUDE_DEG - 3.0,
                "sweep must use its amplitude budget, max=" + maxAbs);
        for (int tick = 0; tick < 3 * SpearAimSweep.PERIOD_TICKS; tick++) {
            float rate = Math.abs(SpearAimSweep.sweepOffsetDeg(tick + 1) - SpearAimSweep.sweepOffsetDeg(tick));
            check(rate <= 25.0f, "sweep rate must stay sweepable per tick, rate=" + rate + " at " + tick);
        }
        // The ray lingers inside the box-sized window only if some consecutive ticks
        // move slowly; a pure triangle or sine satisfies this near the extremes.
        int slowTicks = 0;
        for (int tick = 0; tick < SpearAimSweep.PERIOD_TICKS; tick++) {
            float rate = Math.abs(SpearAimSweep.sweepOffsetDeg(tick + 1) - SpearAimSweep.sweepOffsetDeg(tick));
            if (rate <= 12.0f) slowTicks++;
        }
        check(slowTicks >= 2, "sweep must have slow phases for the ray to linger, slow=" + slowTicks);
    }

    private static void verifyWiring() throws IOException {
        // aimSpear/clearSpearAim live on the outer module class (shared by spear modes);
        // the sweep phase field is outer-class state so both levels can reset it.
        Map<String, Trace> chase = read(CHASE);
        Trace aim = method(chase, "aimSpear");
        check(aim.hasCall(CHASE, "targetPacketOffset"),
                "Spear aim must keep the packet-offset predictor (best calibrated basis)");
        check(aim.hasCall(SWEEP, "applySweep"),
                "aimSpear must route the final yaw through the charged sweep");
        check(!aim.hasCall(CHASE, "predictionVelocity") && !aim.hasCall("dev/guizhou/util/elytra/AerialCombatPolicy", "predictionVelocity"),
                "aimSpear must not add the velocity lead on top of the packet offset (calibrated worse)");
        Trace clear = method(chase, "clearSpearAim");
        check(clear.steps.stream().anyMatch(s -> s.opcode == Opcodes.PUTFIELD
                        && "spearSweepPhase".equals(s.name)),
                "clearSpearAim must reset the sweep phase");
    }

    private static Map<String, Trace> read(String owner) throws IOException {
        Map<String, Trace> methods = new HashMap<>();
        new ClassReader(readClass(owner)).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                             String signature, String[] exceptions) {
                Trace trace = new Trace();
                methods.put(name + descriptor, trace);
                return new MethodVisitor(Opcodes.ASM9) {
                    private void add(int opcode, String owner, String name, Object value, Label target) {
                        trace.steps.add(new Step(opcode, owner, name, value, target));
                    }

                    @Override
                    public void visitInsn(int opcode) { add(opcode, null, null, null, null); }

                    @Override
                    public void visitMethodInsn(int opcode, String owner, String name,
                                                String descriptor, boolean isInterface) {
                        add(opcode, owner, name, null, null);
                    }

                    @Override
                    public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
                        add(opcode, owner, name, null, null);
                    }
                };
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return methods;
    }

    private static InputStream readClass(String owner) throws IOException {
        String resource = "/" + owner + ".class";
        InputStream in = SpearAimSweepTest.class.getResourceAsStream(resource);
        if (in == null) throw new IOException("missing class resource " + resource);
        return in;
    }

    private static Trace method(Map<String, Trace> methods, String name) {
        List<Trace> matches = methods.entrySet().stream()
                .filter(entry -> entry.getKey().startsWith(name + "("))
                .map(Map.Entry::getValue).toList();
        check(matches.size() == 1, "Expected one method named " + name);
        return matches.getFirst();
    }

    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }

    private static final class Trace {
        final List<Step> steps = new ArrayList<>();

        boolean hasCall(String owner, String name) {
            return steps.stream().anyMatch(s -> name.equals(s.name) && owner.equals(s.owner));
        }
    }

    private record Step(int opcode, String owner, String name, Object value, Label target) {
    }
}
