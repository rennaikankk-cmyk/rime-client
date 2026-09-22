package dev.guizhou.util.elytra;

/**
 * Version-family matrix for the duplicate-rotation capability gate. The parse
 * and threshold mirror the reference client's SupportVersion semantics.
 */
public final class DualViewSupportTest {
    private static int checks;

    public static void main(String[] args) {
        supportedFamilies();
        unsupportedFamilies();
        malformedNames();
        describeCoversAllStatuses();
        check(DualViewSupport.legacyFireworkFamily("1.20.3-1.20.4"), "old firework NBT default");
        check(!DualViewSupport.legacyFireworkFamily("1.20.5-1.20.6"), "modern components default");
        check(!DualViewSupport.legacyFireworkFamily(null), "unknown target is not assumed legacy");
        System.out.println("DualViewSupportTest passed: " + checks + " checks");
    }

    private static void supportedFamilies() {
        check(DualViewSupport.familySupported("1.20.6"), "1.20.6 supports duplicate rotation");
        check(DualViewSupport.familySupported("1.20.5-1.20.6"), "version ranges take the high end");
        check(DualViewSupport.familySupported("1.20.4"), "1.20.4");
        check(DualViewSupport.familySupported("1.20.2"), "1.20.2");
        check(DualViewSupport.familySupported("1.20"), "bare 1.20 is the (20,1) family");
        check(DualViewSupport.familySupported("1.12.2"), "1.12.2");
        check(DualViewSupport.familySupported("1.9"), "1.9 introduced elytra");
        check(DualViewSupport.familySupported("1.20.3-1.20.4"), "tested Via target range");
    }

    private static void unsupportedFamilies() {
        check(!DualViewSupport.familySupported("1.21"), "1.21 is out");
        check(!DualViewSupport.familySupported("1.21.4"), "1.21.4 is out");
        check(!DualViewSupport.familySupported("1.21.11"), "1.21.11 is out");
        check(!DualViewSupport.familySupported("1.20.7"), "no such stable release");
        check(!DualViewSupport.familySupported("1.8"), "no elytra");
        check(!DualViewSupport.familySupported("1.21-1.20.4"), "reversed range");
        check(!DualViewSupport.familySupported("2.20.4"), "unknown major");
        check(!DualViewSupport.familySupported("1.0.20.4"), "extra component");
        check(!DualViewSupport.familySupported("1.20.-1"), "negative patch");
    }

    private static void malformedNames() {
        check(!DualViewSupport.familySupported("22w13a"), "snapshot names are unsupported");
        check(!DualViewSupport.familySupported("1.20.x"), "non-numeric patch is unsupported");
        check(!DualViewSupport.familySupported("1.20.6-rc1"), "rc suffixes are unsupported");
        check(!DualViewSupport.familySupported(""), "empty is unsupported");
        check(!DualViewSupport.familySupported(null), "null is unsupported");
    }

    private static void describeCoversAllStatuses() {
        for (DualViewSupport.Status status : DualViewSupport.Status.values()) {
            String text = DualViewSupport.describe(status);
            check(text != null && !text.isEmpty(), "describe(" + status + ") is non-empty");
        }
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}
