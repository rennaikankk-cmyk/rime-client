package dev.guizhou.features.commands;

import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.guizhou.features.commands.argument.ColorArgumentType;
import dev.guizhou.features.commands.ui.CommandCategory;
import dev.guizhou.features.commands.ui.CommandHelp;
import dev.guizhou.features.modules.Module;
import dev.guizhou.features.modules.ModuleNames;
import dev.guizhou.features.settings.Setting;
import dev.guizhou.manager.CommandManager;

import java.awt.*;

import static com.mojang.brigadier.arguments.BoolArgumentType.getBool;
import static com.mojang.brigadier.arguments.StringArgumentType.getString;
import static com.mojang.brigadier.arguments.StringArgumentType.greedyString;
import static dev.guizhou.features.commands.argument.ColorArgumentType.getColor;
import static dev.guizhou.features.commands.argument.EnumArgumentType._enum;
import static dev.guizhou.features.commands.argument.EnumArgumentType.getEnum;
import static dev.guizhou.features.commands.argument.NumberArgumentType.*;

public class ModuleCommand extends Command {
    private final Module module;

    public ModuleCommand(Module module) {
        super(ModuleNames.commandAliases(module.getName()));
        setDescription("配置模块「" + module.getDisplayName() + "」的设置");
        category(CommandCategory.MODULE_SETTINGS);
        this.module = module;
    }

    @Override public String getDescription() { return dev.guizhou.util.lang.ClientLanguage.text("配置模块「") + module.getDisplayName() + dev.guizhou.util.lang.ClientLanguage.text("」的设置"); }
    @Override public String getSummary() { return getDescription(); }

    @SuppressWarnings("unchecked")
    @Override
    public void createArgumentBuilder(LiteralArgumentBuilder<CommandManager> builder) {
        for (Setting<?> setting : module.getSettings()) {
            Class<?> type = setting.getDefaultValue().getClass();

            if (Boolean.class.isAssignableFrom(type)) {
                registerBooleanArgument(builder, (Setting<Boolean>) setting);
            } else if (Number.class.isAssignableFrom(type)) {
                registerNumberArgument(builder, (Setting<? extends Number>) setting);
            } else if (Enum.class.isAssignableFrom(type)) {
                registerEnumArgument(builder, (Setting<Enum<?>>) setting);
            } else if (String.class.isAssignableFrom(type)) {
                registerStringArgument(builder, (Setting<String>) setting);
            } else if (Color.class.isAssignableFrom(type)) {
                registerColorArgument(builder, (Setting<Color>) setting);
            }
        }
        // Keep legacy saved/typed setting identifiers accepted in both languages.
        for (Setting<?> setting : module.getSettings()) {
            String legacy = setting.getName().toLowerCase(java.util.Locale.ROOT).replaceAll("\\s+", "");
            String canonical = key(setting);
            var node = builder.build().getChild(canonical);
            if (!legacy.equals(canonical) && node != null) builder.then(literal(legacy).redirect(node));
        }
    }

    private void registerColorArgument(LiteralArgumentBuilder<CommandManager> builder,
                                       Setting<Color> setting) {
        builder.then(literal(key(setting))
                .then(argument("value", ColorArgumentType.color())
                        .executes((ctx) -> {
                            setting.setValue(getColor(ctx, "value"));
                            return settingChanged(module.getDisplayName(), setting.getName(),
                                    CommandHelp.formatValue(setting.getValue()));
                        })));
    }

    private void registerStringArgument(LiteralArgumentBuilder<CommandManager> builder,
                                        Setting<String> setting) {
        builder.then(literal(key(setting))
                .then(argument("value", greedyString())
                        .executes((ctx) -> {
                            setting.setValue(getString(ctx, "value"));
                            return settingChangeReturn(setting);
                        })));
    }

    @SuppressWarnings("unchecked")
    private void registerEnumArgument(LiteralArgumentBuilder<CommandManager> builder,
                                      Setting<Enum<?>> setting) {
        Class<Enum<?>> type = (Class<Enum<?>>) setting.getDefaultValue().getClass();
        builder.then(literal(key(setting))
                .then(argument("value", _enum(type))
                        .executes((ctx) -> {
                            setting.setValue(getEnum(ctx, "value"));
                            return settingChangeReturn(setting);
                        })));
    }

    @SuppressWarnings("unchecked")
    private <T extends Number> void registerNumberArgument(LiteralArgumentBuilder<CommandManager> builder,
                                                           Setting<T> setting) {
        Class<T> type = (Class<T>) setting.getDefaultValue().getClass();
        builder.then(literal(key(setting))
                .then(argument("value", number(type, minMax(setting.getMin(), setting.getMax())))
                        .executes((ctx) -> {
                            setting.setValue(get(type, ctx, "value"));
                            return settingChangeReturn(setting);
                        })));
    }

    private void registerBooleanArgument(LiteralArgumentBuilder<CommandManager> builder,
                                         Setting<Boolean> setting) {
        builder.then(literal(key(setting))
                .then(argument("value", BoolArgumentType.bool())
                        .executes((ctx) -> {
                            boolean value = getBool(ctx, "value");
                            if (setting == module.enabled) {
                                // Toggling must run the lifecycle (listener registration, onEnable/onDisable).
                                module.setEnabledByUser(value);
                            } else {
                                setting.setValue(value);
                            }
                            return settingChangeReturn(setting);
                        })));
    }

    /** Brigadier literals cannot contain spaces; "Module Toggle" is typed as "moduletoggle". */
    private static String key(Setting<?> setting) {
        return CommandHelp.settingLiteral(setting);
    }

    private int settingChangeReturn(final Setting<?> setting) {
        return settingChanged(module.getDisplayName(), setting.getName(), CommandHelp.formatValue(setting.getValue()));
    }

    @Override
    public boolean isShown() {
        return false;
    }
}
