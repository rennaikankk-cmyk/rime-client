package dev.guizhou.features.commands.impl;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.guizhou.event.impl.input.KeyInputEvent;
import dev.guizhou.event.impl.input.MouseInputEvent;
import dev.guizhou.event.system.Subscribe;
import dev.guizhou.features.commands.Command;
import dev.guizhou.features.commands.ui.ChatFeed;
import dev.guizhou.features.commands.ui.ChatPalette;
import dev.guizhou.features.commands.ui.ChatTone;
import dev.guizhou.features.commands.ui.CommandCategory;
import dev.guizhou.features.modules.Module;
import dev.guizhou.features.settings.Bind;
import dev.guizhou.manager.CommandManager;
import dev.guizhou.util.KeyboardUtil;
import net.minecraft.network.chat.Component;

import static dev.guizhou.features.commands.argument.ModuleArgumentType.getModule;
import static dev.guizhou.features.commands.argument.ModuleArgumentType.module;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_UNKNOWN;

public class BindCommand extends Command {
    private static final String STATUS_KEY = "bind:status";
    private Module module;

    public BindCommand() {
        super("bind", "setbind");
        setDescription("Sets a key bind for a module");
        category(CommandCategory.MODULES).summary("为模块绑定按键")
                .usage("<模块>", "随后按下键盘或鼠标按键，Esc 取消");
        EVENT_BUS.register(this, "BindCommand");
    }

    @Override
    public void createArgumentBuilder(LiteralArgumentBuilder<CommandManager> builder) {
        builder.then(argument("module", module(true))
                .executes((ctx) -> {
                    module = getModule(ctx, "module");
                    ChatFeed.replace(STATUS_KEY, ChatTone.INFO, "⌨", Component.empty()
                            .append(ChatFeed.text("请按下要绑定到 ", ChatPalette.MUTED))
                            .append(ChatFeed.text(module.getDisplayName(), ChatPalette.TEXT))
                            .append(ChatFeed.text(" 的按键…", ChatPalette.MUTED))
                            .append(ChatFeed.text("   Esc 取消", ChatPalette.SUBTLE)));
                    return NO_OP;
                }));
    }

    @Subscribe
    public void onKey(final KeyInputEvent event) {
        if (nullCheck() || module == null || event.getKey() == GLFW_KEY_UNKNOWN || event.getAction() != 1) {
            return;
        }

        if (event.getKey() == GLFW_KEY_ESCAPE) {
            ChatFeed.replace(STATUS_KEY, ChatTone.MUTED, Component.literal("已取消绑定 " + module.getDisplayName()));
            module = null;
            return;
        }

        apply(new Bind(event.getKey()), KeyboardUtil.getKeyName(event.getKey()));
    }

    @Subscribe
    public void onMouse(final MouseInputEvent event) {
        if (nullCheck() || module == null || event.getAction() != 1) {
            return;
        }

        Bind bind = Bind.fromMouseButton(event.getButton());
        apply(bind, bind.toString());
    }

    private void apply(Bind bind, String label) {
        module.bind.setValue(bind);
        ChatFeed.replace(STATUS_KEY, ChatTone.SUCCESS, Component.empty()
                .append(ChatFeed.text(module.getDisplayName(), ChatPalette.TEXT))
                .append(ChatFeed.text("  ·  按键  →  ", ChatPalette.SUBTLE))
                .append(ChatFeed.text(label, ChatPalette.ACCENT_2)));
        module = null;
    }
}
