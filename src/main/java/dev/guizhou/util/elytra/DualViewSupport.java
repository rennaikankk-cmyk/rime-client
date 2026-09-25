package dev.guizhou.util.elytra;

import java.lang.reflect.Method;

import static dev.guizhou.util.traits.Util.mc;

/**
 * Protocol capability probe for duplicate-rotation (dual view) packets.
 *
 * The reference client ships dual look only on Via target protocols at or
 * below 1.20.6: on 1.21+ protocols the extra rotation packet that
 * separates the motion view from the sent view is not accepted the same way.
 * ViaFabricPlus is optional at runtime and absent from the compile classpath,
 * so detection is reflective and cached per connection.
 */
public final class DualViewSupport {
    public enum Status { SUPPORTED, NO_VIA, NEW_PROTOCOL, OFFLINE }

    private static volatile Status cached = Status.OFFLINE;
    private static volatile Object cachedConnection;
    private static volatile String cachedVersionName = "";

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

    public static boolean usesModernInput() {
        if (forceLegacyProtocol()) return false;
        Status current = status();
        if (current == Status.NO_VIA) return false; // Minecraft 1.21.1 native input.
        int[] protocol = range(cachedVersionName);
        return protocol != null && protocol[0] >= 2102;
    }

    public static boolean usesLegacyFireworkData() {
        return forceLegacyProtocol()
                || (status() == Status.SUPPORTED && legacyFireworkFamily(cachedVersionName));
    }

    public static boolean legacyFireworkFamily(String name) {
        return familySupported(name) && range(name)[1] <= 2004;
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
        cached = name == null ? Status.NO_VIA
                : familySupported(name) ? Status.SUPPORTED : Status.NEW_PROTOCOL;
        cachedConnection = connection;
        return cached;
    }

    public static String describe(Status status) {
        return switch (status) {
            case SUPPORTED -> "双视角可用";
            case NO_VIA -> "未检测到 ViaFabricPlus";
            case NEW_PROTOCOL -> "当前 Via 目标协议 " + cachedVersionName + " 不支持双视角";
            case OFFLINE -> "未连接服务器";
        };
    }

    public static String protocolDescription() {
        Status current = status();
        if (current == Status.OFFLINE) return "未连接";
        if (!cachedVersionName.isBlank()) return "Via 目标 " + cachedVersionName;
        // API method names differ between the two maintained Minecraft branches.
        try {
            Object version = net.minecraft.SharedConstants.getCurrentVersion();
            for (String method : new String[]{"name", "getName"}) {
                try {
                    Method getter = version.getClass().getMethod(method);
                    getter.setAccessible(true);
                    Object name = getter.invoke(version);
                    if (name instanceof String text) return "原生协议 " + text;
                } catch (ReflectiveOperationException ignored) { }
            }
        } catch (RuntimeException ignored) { }
        return "客户端原生协议";
    }

    /**
     * Accept stable Java release names and full release ranges only. Elytra
     * starts at 1.9; 1.20.6 is the last release before the 1.21 packet change.
     * Unknown names, reversed ranges and prereleases fail closed.
     */
    public static boolean familySupported(String versionName) {
        int[] bounds = range(versionName);
        return bounds != null && bounds[0] >= 900 && bounds[1] >= bounds[0] && bounds[1] <= 2006;
    }

    private static int[] range(String versionName) {
        if (versionName == null) return null;
        var match = java.util.regex.Pattern.compile("1\\.(\\d{1,2})(?:\\.(\\d{1,2}))?(?:-1\\.(\\d{1,2})(?:\\.(\\d{1,2}))?)?")
                .matcher(versionName);
        if (!match.matches()) return null;
        try {
            int low = Integer.parseInt(match.group(1)) * 100
                    + (match.group(2) == null ? 0 : Integer.parseInt(match.group(2)));
            int high = match.group(3) == null ? low : Integer.parseInt(match.group(3)) * 100
                    + (match.group(4) == null ? 0 : Integer.parseInt(match.group(4)));
            return new int[]{low, high};
        } catch (NumberFormatException e) {
            return null;
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
