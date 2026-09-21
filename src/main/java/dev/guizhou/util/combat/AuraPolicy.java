package dev.guizhou.util.combat;

import java.util.*;
import java.util.function.*;

/** Timing and candidate policy independent of rendering and Minecraft state. */
public final class AuraPolicy {
    private AuraPolicy() {}
    public enum TargetMode { SINGLE, SWITCH, MULTI }
    public enum SortPriority { DISTANCE, CROSSHAIR, HEALTH }
    public enum WeaponMode { NONE, REQUIRE, SWAP }
    public enum SwapAction { SILENT, NORMAL, SWAPBACK }
    public enum Weapon { ALL, SWORD, AXE, MACE, TRIDENT }
    public enum MaceMode { NONE, SMART, BREACH, DENSITY }
    public enum ShieldMode { NONE, BREAK, IGNORE }
    public enum MobAge { BOTH, ADULT, BABY }

    public static double cooldown(double attackSpeed, double threshold) {
        if (!Double.isFinite(attackSpeed) || attackSpeed <= 0) return Double.POSITIVE_INFINITY;
        return 20.0 / attackSpeed * Math.clamp(threshold, 0, 1);
    }

    /** Vanilla 1.21.11 armor effectiveness; Breach subtracts 0.15 per level from the fraction. */
    public static double afterArmor(double raw, double armor, double toughness, int breach) {
        if (!Double.isFinite(raw) || !Double.isFinite(armor) || !Double.isFinite(toughness)) return 0;
        raw = Math.max(0, raw); armor = Math.max(0, armor); toughness = Math.max(0, toughness);
        double absorbed = Math.clamp(armor - raw / (2 + toughness / 4), Math.min(armor * 0.2,20),20) / 25;
        return raw * (1 - Math.clamp(absorbed - 0.15 * Math.max(0,breach),0,1));
    }

    public static final class Clock {
        private double elapsed;
        private int lastTick = Integer.MIN_VALUE;
        public void reset(int tick, double initial) { lastTick = tick; elapsed = Math.max(0, initial); }
        public void tick(int tick, double tps) {
            if (tick == lastTick) return;
            lastTick = tick;
            elapsed = Math.min(10000, elapsed + (Double.isFinite(tps) ? Math.clamp(tps, 1, 20) : 20) / 20.0);
        }
        public void submitted() { elapsed = 0; }
        public boolean ready(double required) { return Double.isFinite(required) && elapsed + 1e-6 >= required; }
        public double elapsed() { return elapsed; }
    }

    public static <T> List<T> candidates(Collection<T> source, Predicate<T> eligible,
                                          Comparator<T> comparator, int limit) {
        return source.stream().filter(eligible).sorted(comparator).limit(Math.clamp(limit, 1, 16)).toList();
    }

    public static <T> T choose(List<T> sorted, T current, TargetMode mode, ToLongFunction<T> lastHit) {
        if (sorted.isEmpty()) return null;
        if (mode == TargetMode.SINGLE && sorted.contains(current)) return current;
        if (mode == TargetMode.MULTI) return sorted.stream().min(Comparator.comparingLong(lastHit)).orElse(null);
        return sorted.getFirst();
    }
}
