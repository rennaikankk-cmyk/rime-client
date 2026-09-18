package dev.guizhou.util.combat;

import java.util.HashSet;
import java.util.Set;

/** Shares successful attack attempts across spawn, tick and block-update callbacks. */
public final class CrystalAttackWindow {
    private final Set<Integer> attacked = new HashSet<>();
    private int tick = Integer.MIN_VALUE;

    public boolean canAttack(int entityId, int currentTick) {
        advance(currentTick);
        return !attacked.contains(entityId);
    }

    public void markSent(int entityId, int currentTick) {
        advance(currentTick);
        attacked.add(entityId);
    }

    public void clear() {
        attacked.clear();
        tick = Integer.MIN_VALUE;
    }

    private void advance(int currentTick) {
        if (tick == currentTick) return;
        attacked.clear();
        tick = currentTick;
    }
}
