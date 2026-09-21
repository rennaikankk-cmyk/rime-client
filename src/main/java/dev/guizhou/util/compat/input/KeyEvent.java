package dev.guizhou.util.compat.input;

/**
 * 1.21.1 port shim for the 1.21.2+ input event record. GuizhouWare's GUI code is
 * written against the newer API surface (constructed at the Screen/GUI boundary and
 * by the keyboard mixin); on 1.21.1 the raw values come from the classic
 * keyPressed(long,int,int,int,int) / charTyped(long,int,int) handler signatures.
 */
public record KeyEvent(int key, int scanCode, int modifiers) {
    /** 1.21.2+ alias kept so both accessor styles compile. */
    public int input() {
        return this.key;
    }
}
