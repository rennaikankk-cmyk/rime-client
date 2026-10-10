package dev.guizhou.util.elytra;

import java.lang.reflect.Method;

import static dev.guizhou.util.traits.Util.mc;

/**
 * Protocol capability probe for duplicate-rotation (dual view) packets.
 *
 * The reference client ships dual look only on Via target protocols at or
 * below the 1.20.7 family: on 1.21+ protocols the extra rotation packet that
 * separates the motion view from the sent view is not accepted the same way.
 * ViaFabricPlus is optional at runtime and absent from the compile classpath,
 * so detection is reflective and cached per connection.
 */
public final class DualViewSupport {
    public enum Status { SUPPORTED, NO_VIA, NEW_PROTOCOL, OFFLINE }

    private static volatile Status cached = Status.OFFLINE;
    private static volatile Object cachedConnection;
    private static volatile String cachedVersionName = "";
    private static volatile boolean cachedLegacySpear;

    private DualViewSupport() {}

    /** ClickGUI 「1.12协议」. Default off; auto-detection stays in charge until it is on. */
    public static boolean forceLegacyProtocol() {
        dev.guizhou.features.modules.client.ClickGuiModule gui =
                dev.guizhou.features.modules.client.ClickGuiModule.getInstance();
        return gui != null && Boolean.TRUE.equals(gui.protocol112.getValue());
    }

    public static boolean isDupRotSupported() {
        return forceLegacyProtocol() || status() == Status.SUPPORTED;
    }

    /** Via may supply a modern default component for an old NBT-less rocket. */
    public static boolean usesLegacyFireworkData() {
        status();
        return forceLegacyProtocol() || legacyFireworkFamily(cachedVersionName);
    }

    /** Requires a real connected Via target; the manual dual-view override is insufficient. */
    public static boolean usesLegacySpearData() {
        Status current = status();
        return (current == Status.SUPPORTED || current == Status.NEW_PROTOCOL)
                && cachedLegacySpear;
    }

    static boolean legacySpearFamily(String name) {
        if (name == null) return false;
        var match = java.util.regex.Pattern.compile("1\\.(\\d{1,2})(?:\\.(\\d{1,2}))?(?:-1\\.(\\d{1,2})(?:\\.(\\d{1,2}))?)?").matcher(name);
        if (!match.matches()) return false;
        int low = Integer.parseInt(match.group(1)) * 100 + (match.group(2) == null ? 0 : Integer.parseInt(match.group(2)));
        int high = match.group(3) == null ? low : Integer.parseInt(match.group(3)) * 100 + (match.group(4) == null ? 0 : Integer.parseInt(match.group(4)));
        return low >= 900 && high >= low && high < 2111;
    }

    public static boolean legacyFireworkFamily(String name) {
        if (name == null) return false;
        var match = java.util.regex.Pattern.compile("1\\.(\\d{1,2})(?:\\.(\\d{1,2}))?(?:-1\\.(\\d{1,2})(?:\\.(\\d{1,2}))?)?").matcher(name);
        if (!match.matches()) return false;
        int low = Integer.parseInt(match.group(1)) * 100 + (match.group(2) == null ? 0 : Integer.parseInt(match.group(2)));
        int high = match.group(3) == null ? low : Integer.parseInt(match.group(3)) * 100 + (match.group(4) == null ? 0 : Integer.parseInt(match.group(4)));
        return low >= 900 && high >= low && high <= 2004;
    }

    /** Resolved once per connection; cheap on every later call. */
    public static Status status() {
        Object connection = mc == null ? null : mc.getConnection();
        if (connection == null) {
            cachedConnection = null;
            return Status.OFFLINE;
        }
        if (connection == cachedConnection) return cached;
        String name = viaTargetName();
        cachedVersionName = name == null ? "" : name;
        cachedLegacySpear = legacySpearFamily(cachedVersionName);
        cached = name == null ? Status.NO_VIA
                : familySupported(name) ? Status.SUPPORTED : Status.NEW_PROTOCOL;
        cachedConnection = connection;
        return cached;
    }

    public static String describe(Status status) {
        return switch (status) {
            case SUPPORTED -> dev.guizhou.util.lang.ClientLanguage.text("双视角可用");
            case NO_VIA -> dev.guizhou.util.lang.ClientLanguage.text("未检测到 ViaFabricPlus");
            case NEW_PROTOCOL -> dev.guizhou.util.lang.ClientLanguage.text("当前 Via 目标协议 ") + cachedVersionName + dev.guizhou.util.lang.ClientLanguage.text(" 高于 1.20.7");
            case OFFLINE -> dev.guizhou.util.lang.ClientLanguage.text("未连接服务器");
        };
    }

    /**
     * Reference-client SupportVersion semantics: a version name maps to the
     * last two numeric parts ("1.20" itself is (20,1), ranges take their high
     * end), and duplicate rotation is allowed while the family is at or below
     * (20,7). Unparsable names (snapshots, rc suffixes) are unsupported.
     */
    public static boolean familySupported(String versionName) {
        if (versionName == null || versionName.isEmpty()) return false;
        String[] parts = versionName.split("\\.");
        try {
            int major, minor;
            if (parts.length == 2 && "1".equals(parts[0])) {
                major = Integer.parseInt(parts[1]);
                minor = 1;
            } else if (parts.length >= 3) {
                major = Integer.parseInt(parts[parts.length - 2]);
                minor = Integer.parseInt(parts[parts.length - 1]);
            } else {
                return false;
            }
            if (major > 20) return false;
            if (major < 20) return true;
            return minor <= 7;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /** null when ViaFabricPlus is absent or its target version cannot be read. */
    private static String viaTargetName() {
        Object version = firstNonNull(
                invokeChain("com.viaversion.viafabricplus.ViaFabricPlus", "getImpl", "getTargetVersion"),
                invokeChain("de.florianmichael.viafabricplus.protocoltranslator.ProtocolTranslator", "getTargetVersion"));
        if (version == null) return null;
        try {
            Object name = version.getClass().getMethod("getName").invoke(version);
            return name instanceof String s ? s : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static Object firstNonNull(Object a, Object b) { return a != null ? a : b; }

    /** Walks a static-method chain (class, method, method...); null on any failure. */
    private static Object invokeChain(String... names) {
        try {
            Object target = Class.forName(names[0], false, DualViewSupport.class.getClassLoader());
            for (int i = 1; i < names.length; i++) {
                if (target == null) return null;
                Class<?> cls = target instanceof Class<?> c ? c : target.getClass();
                target = cls.getMethod(names[i]).invoke(target);
            }
            return target instanceof Class<?> ? null : target;
        } catch (Throwable t) {
            return null;
        }
    }
}
