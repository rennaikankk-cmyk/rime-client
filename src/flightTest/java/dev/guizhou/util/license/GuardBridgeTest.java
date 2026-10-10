package dev.guizhou.util.license;

public final class GuardBridgeTest {
    public static void main(String[] args) throws Throwable {
        var call = GuardStatus.bind(Bridge.class);
        Bridge.allowed = true;
        if (!(boolean) call.invokeExact()) throw new AssertionError("Valid guard must authorize");
        Bridge.allowed = false;
        if ((boolean) call.invokeExact()) throw new AssertionError("Revocation must be visible on the same cached handle");
        for (Class<?> invalid : new Class<?>[]{Boxed.class, Instance.class, Arguments.class}) {
            try { GuardStatus.bind(invalid); throw new AssertionError("Unexpected guard ABI accepted: " + invalid); }
            catch (ReflectiveOperationException expected) { }
        }
        System.out.println("GuardBridgeTest passed: typed ABI, live revocation, no authorization caching");
    }

    public static final class Bridge {
        static boolean allowed;
        public static boolean isAuthorized() { return allowed; }
    }
    public static final class Boxed { public static Boolean isAuthorized() { return true; } }
    public static final class Instance { public boolean isAuthorized() { return true; } }
    public static final class Arguments { public static boolean isAuthorized(int ignored) { return true; } }
}
