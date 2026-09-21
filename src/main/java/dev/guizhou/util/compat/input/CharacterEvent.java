package dev.guizhou.util.compat.input;

/** 1.21.1 port shim for the 1.21.2+ character input event record. */
public record CharacterEvent(int codePoint, int modifiers) {
    public String codepointAsString() {
        return new String(Character.toChars(this.codePoint));
    }
}
