package dev.guizhou.features.modules.qinghua;

import dev.guizhou.event.impl.entity.player.TickEvent;
import dev.guizhou.event.impl.network.PacketEvent;
import dev.guizhou.event.system.Subscribe;
import dev.guizhou.features.modules.Module;
import dev.guizhou.features.modules.qinghua.util.MSTimer;
import dev.guizhou.features.modules.qinghua.util.NumberListUtil;
import dev.guizhou.features.settings.Setting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Port of qinghuayuci misc AutoLogin — keyword match + command send logic preserved.
 * Reconstructed from class settings / methods (no java source in tree).
 */
public class AutoLoginModule extends Module {
    public enum MatchMode { Contains, Equals, Regex }

    private final Setting<String> password = str("Password", "mypassword123")
            .described("Password for register/login.");
    private final Setting<Integer> delay = num("Delay", 20, 0, 200)
            .described("Ticks to wait before sending command (auto mode).");
    private final Setting<Boolean> keyBindEnabled = bool("KeyBindEnabled", false)
            .described("Only send commands when the module's bound key is pressed.");
    private final Setting<MatchMode> matchMode = mode("MatchMode", MatchMode.Contains)
            .described("How to match the trigger keywords.");
    private final Setting<Boolean> autoRegister = bool("AutoRegister", true)
            .described("Automatically register if needed.");
    private final Setting<String> registerCommand = str("RegisterCommand", "/register %password% %password%")
            .described("Registration command template. Use %password% for password.");
    private final Setting<String> registerKeywords = str("RegisterKeywords", "/register")
            .described("Keywords that indicate registration is required.");
    private final Setting<Boolean> autoLogin = bool("AutoLogin", true)
            .described("Automatically login if needed.");
    private final Setting<String> loginCommand = str("LoginCommand", "/login %password%")
            .described("Login command template. Use %password% for password.");
    private final Setting<String> loginKeywords = str("LoginKeywords", "/login")
            .described("Keywords that indicate login is required.");

    private boolean needRegister;
    private boolean needLogin;
    private boolean registerJustSent;
    private final MSTimer delayTimer = new MSTimer();
    private int waitTicks;

    public AutoLoginModule() {
        super("AutoLogin", "Automatically logs in or registers on servers.", Category.CLIENT);
    }

    @Override
    public void onEnable() {
        needRegister = false;
        needLogin = false;
        registerJustSent = false;
        waitTicks = 0;
        delayTimer.reset();
    }

    @Subscribe
    private void onPacket(PacketEvent.Receive event) {
        if (!(event.getPacket() instanceof ClientboundSystemChatPacket chat)) return;
        Component content = chat.content();
        if (content == null) return;
        String text = content.getString();
        if (matches(text, NumberListUtil.parseStrings(registerKeywords.getValue()))) {
            needRegister = true;
            waitTicks = delay.getValue();
            delayTimer.reset();
        }
        if (matches(text, NumberListUtil.parseStrings(loginKeywords.getValue()))) {
            needLogin = true;
            waitTicks = delay.getValue();
            delayTimer.reset();
        }
    }

    @Subscribe
    private void onTick(TickEvent event) {
        if (mc.player == null || mc.getConnection() == null) return;
        if (keyBindEnabled.getValue() && !getBind().isDown()) return;
        if (waitTicks > 0) {
            waitTicks--;
            return;
        }
        if (needRegister && autoRegister.getValue()) {
            sendCommand(registerCommand.getValue());
            needRegister = false;
            registerJustSent = true;
            waitTicks = delay.getValue();
            return;
        }
        if (needLogin && autoLogin.getValue()) {
            if (registerJustSent) {
                registerJustSent = false;
                waitTicks = delay.getValue();
                return;
            }
            sendCommand(loginCommand.getValue());
            needLogin = false;
        }
    }

    private boolean matches(String text, List<String> keywords) {
        if (text == null || keywords.isEmpty()) return false;
        String lower = text.toLowerCase();
        for (String keyword : keywords) {
            if (keyword.isBlank()) continue;
            boolean hit = switch (matchMode.getValue()) {
                case Contains -> lower.contains(keyword.toLowerCase());
                case Equals -> lower.equals(keyword.toLowerCase());
                case Regex -> {
                    try {
                        yield Pattern.compile(keyword, Pattern.CASE_INSENSITIVE).matcher(text).find();
                    } catch (Exception e) {
                        yield false;
                    }
                }
            };
            if (hit) return true;
        }
        return false;
    }

    private void sendCommand(String template) {
        String cmd = template.replace("%password%", password.getValue());
        if (cmd.startsWith("/")) cmd = cmd.substring(1);
        if (mc.getConnection() != null) mc.getConnection().sendCommand(cmd);
    }
}
