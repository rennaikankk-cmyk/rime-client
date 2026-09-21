package dev.guizhou.util.compat.input;

/** 1.21.1 port shim for the 1.21.2+ mouse click event record. */
public record MouseButtonEvent(double x, double y, MouseButtonInfo info) {
    public int button() {
        return this.info.button();
    }

    public int modifiers() {
        return this.info.modifiers();
    }
}
