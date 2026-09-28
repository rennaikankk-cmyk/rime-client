package dev.guizhou.util.elytra;

public final class GlideMetadataDelayTest {
    private static int checks;
    public static void main(String[] args) {
        var delay = new GlideMetadataDelay();
        check(delay.accept(128, 33, 100, 1, 4, true) == 161, "Keep glide while applying new invisibility/fire bits");
        check(!delay.due(102) && delay.due(103), "Original start command defines deadline");
        delay.accept(128, 0, 102, 0, 4, true);
        check(delay.due(103), "Repeated starts cannot extend pending stop forever");
        check(delay.accept(128, 2, 103, 0, 4, true) == 2 && !delay.pending(), "Deadline applies newest flags");
        delay.accept(128, 0, 200, 1, 4, true);
        check(delay.accept(128, 160, 201, 2, 4, true) == 160 && !delay.pending(), "Newer true supersedes old false");
        check(delay.accept(128, 0, 210, 0, 4, false) == 0 && !delay.pending(), "Dive or correction cannot delay metadata");
        check(delay.accept(128, 0, 211, Integer.MAX_VALUE, 4, true) == 0, "No accepted start means no delay");
        check(delay.accept(128, 0, 212, 0, 0, true) == 0, "Zero preserves vanilla");
        check(delay.accept(0, 0, 213, 0, 4, true) == 0, "Never invent gliding");
        delay.accept(128, 0, 300, 1, 4, true); delay.reset();
        check(!delay.pending(), "Respawn drops old pending state");
        for (int flags = 0; flags < 128; flags++) {
            delay.reset();
            check((delay.accept(128, flags, 100, 0, 4, true) & 127) == flags, "All non-glide flag combinations pass intact");
        }
        System.out.println("GlideMetadataDelayTest passed: " + checks + " checks");
    }
    private static void check(boolean yes, String message) { checks++; if (!yes) throw new AssertionError(message); }
}
