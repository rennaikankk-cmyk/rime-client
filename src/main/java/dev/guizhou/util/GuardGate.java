package dev.guizhou.util;

/**
 * Soft gate for mixin-injected business code.
 *
 * 2026-10-06 事故: 业务 mixin (如 MixinTitleScreen) 织入原版类后, 在守卫授权门
 * (d1.G) 拒绝或租约未就绪时仍会被 vanilla 触发——此时 custody 池未解锁, 池化字符
 * 串不可用, 曾导致主屏 ClientLanguage 静态初始化崩溃。所有直接织入 vanilla 类的
 * 业务 mixin 处理器必须在入口检查本门: 未授权时完全 no-op, 让 vanilla 原生行为
 * 兜底。开发环境(未合并守卫)恒为通过。
 */
public final class GuardGate {
    private static final boolean GUARD_PRESENT;

    static {
        boolean present;
        try {
            Class.forName("d1.G");
            present = true;
        } catch (ClassNotFoundException e) {
            present = false;
        }
        GUARD_PRESENT = present;
    }

    private GuardGate() {}

    /** True in dev builds (guard not merged) or when the merged guard holds a live authorization. */
    public static boolean businessAllowed() {
        if (!GUARD_PRESENT) return true;
        try {
            return (Boolean) Class.forName("d1.G").getMethod("isAuthorized").invoke(null);
        } catch (Throwable t) {
            return false;
        }
    }
}
