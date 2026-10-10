package dev.guizhou.util.elytra;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

public final class FlightCollisionSnapshotTest {
    private static final AABB BODY = new AABB(-0.3, -0.02, -0.3, 0.3, 0.62, 0.3);
    private static final AABB BOUNDS = new AABB(-12, -12, -12, 12, 12, 12);
    private static int checks;

    public static void main(String[] args) {
        collisionGeometry();
        boundsAndOffsets();
        immutableAndInvalidInputs();
        System.out.println("FlightCollisionSnapshotTest passed: " + checks + " checks");
    }

    private static void collisionGeometry() {
        FlightCollisionSnapshot wall = snapshot(List.of(new AABB(3, -10, -3, 4, 10, 3)));
        check(!wall.clear(Vec3.ZERO, new Vec3(8, 0, 0)), "Continuous sweep catches a wall between endpoints");
        check(wall.clear(Vec3.ZERO, new Vec3(2.69, 0, 0)), "Body fits before the wall");
        check(!wall.clear(Vec3.ZERO, new Vec3(2.71, 0, 0)), "Body width is included in wall collision");
        check(!wall.clear(new Vec3(3.5, 0, 0), new Vec3(3.5, 0, 0)), "Stationary embedded body is blocked");
        check(wall.clear(new Vec3(0, 0, 4), new Vec3(8, 0, 4)), "Clear parallel path passes wall");
        FlightCollisionSnapshot diagonal = snapshot(List.of(new AABB(0.2, -10, 3.2, 0.8, 10, 3.8)));
        check(diagonal.clear(Vec3.ZERO, new Vec3(8, 0, 8)), "Swept hull overlap outside diagonal trajectory is not a collision");
        FlightCollisionSnapshot ceiling = snapshot(List.of(new AABB(-8, 0.7, -8, 8, 1, 8)));
        check(ceiling.clear(Vec3.ZERO, new Vec3(3, 0, 0)), "Gliding body fits below low ceiling");
        check(!ceiling.clear(Vec3.ZERO, new Vec3(3, 0.1, 0)), "Body height catches ceiling while rising");
        FlightCollisionSnapshot narrow = snapshot(List.of(new AABB(1, -1, -1, 1.01, 1, 1)));
        check(!narrow.clear(Vec3.ZERO, new Vec3(10, 0, 0)), "Thin obstacles cannot be skipped by high-speed motion");
    }

    private static void boundsAndOffsets() {
        FlightCollisionSnapshot open = snapshot(List.of());
        check(open.clear(Vec3.ZERO, new Vec3(11.69, 0, 0)), "Full body remains within captured space");
        check(!open.clear(Vec3.ZERO, new Vec3(11.71, 0, 0)), "Unknown space beyond full-body boundary is blocked");
        check(!open.clear(new Vec3(20, 0, 0), Vec3.ZERO), "Unknown starting position is blocked");
        Vec3 origin = new Vec3(100, 60, -40);
        FlightCollisionSnapshot translated = new FlightCollisionSnapshot(origin, BODY, BOUNDS.move(origin),
                List.of(new AABB(3, -10, -3, 4, 10, 3).move(origin)));
        check(!translated.clearOffsets(Vec3.ZERO, new Vec3(8, 0, 0)), "Relative trajectories share snapshot origin");
        check(translated.clearOffsets(Vec3.ZERO, new Vec3(0, 0, 8)), "Relative open trajectory remains clear");
        check(translated.origin().equals(origin), "Captured origin remains available to result freshness checks");
        AABB navigation = open.navigationBounds();
        check(Math.abs(navigation.maxX - 11.7) < 1.0E-9 && Math.abs(navigation.maxY - 11.38) < 1.0E-9,
                "Navigation bounds account for complete captured body dimensions");
    }

    private static void immutableAndInvalidInputs() {
        List<AABB> input = new ArrayList<>();
        FlightCollisionSnapshot snapshot = snapshot(input);
        input.add(new AABB(-1, -1, -1, 1, 1, 1));
        check(snapshot.clear(Vec3.ZERO, Vec3.ZERO), "Mutating source collection cannot change an in-flight snapshot");
        check(!snapshot.clear(null, Vec3.ZERO), "Missing position fails closed");
        check(!snapshot.clear(Vec3.ZERO, new Vec3(Double.NaN, 0, 0)), "NaN path fails closed");
        check(!snapshot.clearOffsets(Vec3.ZERO, new Vec3(Double.POSITIVE_INFINITY, 0, 0)), "Infinite offset fails closed");
        expectInvalid(() -> new FlightCollisionSnapshot(new Vec3(Double.NaN, 0, 0), BODY, BOUNDS, List.of()));
        expectInvalid(() -> new FlightCollisionSnapshot(Vec3.ZERO, BODY, new AABB(0, 0, 0, 0.1, 0.1, 0.1), List.of()));
        expectInvalid(() -> snapshot(java.util.Collections.nCopies(FlightCollisionSnapshot.MAX_BOXES + 1,
                new AABB(1, 1, 1, 2, 2, 2))));
    }

    private static FlightCollisionSnapshot snapshot(List<AABB> obstacles) {
        return new FlightCollisionSnapshot(Vec3.ZERO, BODY, BOUNDS, obstacles);
    }

    private static void expectInvalid(Runnable action) {
        try { action.run(); } catch (IllegalArgumentException expected) { checks++; return; }
        throw new AssertionError("Invalid snapshot geometry must be rejected");
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}
