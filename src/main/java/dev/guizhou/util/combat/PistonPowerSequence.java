package dev.guizhou.util.combat;

/** A cleared redstone block alone is not confirmation that the piston has retracted. */
public final class PistonPowerSequence {
    public enum Action { BREAK, WAIT, READY, ABORT }
    private PistonPowerSequence() {}

    public static Action next(boolean redstoneBlock, boolean air, boolean powered, boolean extended) {
        if (redstoneBlock) return Action.BREAK;
        if (!air) return Action.ABORT;
        return powered || extended ? Action.WAIT : Action.READY;
    }
}
