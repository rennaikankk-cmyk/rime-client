package dev.guizhou.features;

import dev.guizhou.event.impl.input.KeyInputEvent;
import dev.guizhou.event.impl.input.MouseInputEvent;
import dev.guizhou.event.system.Subscribe;
import dev.guizhou.features.gui.GuizhouWareGui;
import dev.guizhou.util.traits.Util;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractCommandBlockEditScreen;
import net.minecraft.client.gui.screens.inventory.AbstractSignEditScreen;
import net.minecraft.client.gui.screens.inventory.AnvilScreen;
import net.minecraft.client.gui.screens.inventory.StructureBlockEditScreen;
import org.lwjgl.glfw.GLFW;

public class GuiMove implements Util {

    private boolean up, down, left, right, jump, shift, sprint;

    public void init() {
        EVENT_BUS.register(this, "GuiMove");
        net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (exclusiveInput()) {
                clearMovement();
                return;
            }
            if (shouldRun()) {
                mc.options.keyUp.setDown(up);
                mc.options.keyDown.setDown(down);
                mc.options.keyLeft.setDown(left);
                mc.options.keyRight.setDown(right);
                mc.options.keyJump.setDown(jump);
                mc.options.keyShift.setDown(shift);
                mc.options.keySprint.setDown(sprint);
            }
        });
    }

    @Subscribe
    private void onKey(KeyInputEvent event) {
        if (event.getAction() != GLFW.GLFW_PRESS && event.getAction() != GLFW.GLFW_RELEASE) return;
        if (exclusiveInput()) { clearMovement(); return; }
        if (mc.screen instanceof GuizhouWareGui && isArrowKey(event.getKey())) return;
        
        boolean pressed = event.getAction() == GLFW.GLFW_PRESS;
        int key = event.getKey();

        if (mc.options.keyUp.matches(key, 0)) up = pressed;
        else if (mc.options.keyDown.matches(key, 0)) down = pressed;
        else if (mc.options.keyLeft.matches(key, 0)) left = pressed;
        else if (mc.options.keyRight.matches(key, 0)) right = pressed;
        else if (mc.options.keyJump.matches(key, 0)) jump = pressed;
        else if (mc.options.keyShift.matches(key, 0)) shift = pressed;
        else if (mc.options.keySprint.matches(key, 0)) sprint = pressed;
    }

    @Subscribe
    private void onMouse(MouseInputEvent event) {
        if (event.getAction() != GLFW.GLFW_PRESS && event.getAction() != GLFW.GLFW_RELEASE) return;
        if (exclusiveInput()) { clearMovement(); return; }
        
        boolean pressed = event.getAction() == GLFW.GLFW_PRESS;
        int button = event.getButton();

        if (mc.options.keyUp.matchesMouse(button)) up = pressed;
        else if (mc.options.keyDown.matchesMouse(button)) down = pressed;
        else if (mc.options.keyLeft.matchesMouse(button)) left = pressed;
        else if (mc.options.keyRight.matchesMouse(button)) right = pressed;
        else if (mc.options.keyJump.matchesMouse(button)) jump = pressed;
        else if (mc.options.keyShift.matchesMouse(button)) shift = pressed;
        else if (mc.options.keySprint.matchesMouse(button)) sprint = pressed;
    }

    private static boolean isArrowKey(int key) {
        return key == GLFW.GLFW_KEY_LEFT || key == GLFW.GLFW_KEY_RIGHT
                || key == GLFW.GLFW_KEY_UP || key == GLFW.GLFW_KEY_DOWN;
    }

    private boolean exclusiveInput() {
        return mc.screen instanceof GuizhouWareGui gui && gui.capturesMovementKeys()
                || mc.screen instanceof dev.guizhou.features.gui.HudEditorScreen;
    }

    private void clearMovement() {
        up=down=left=right=jump=shift=sprint=false;
        mc.options.keyUp.setDown(false);
        mc.options.keyDown.setDown(false);
        mc.options.keyLeft.setDown(false);
        mc.options.keyRight.setDown(false);
        mc.options.keyJump.setDown(false);
        mc.options.keyShift.setDown(false);
        mc.options.keySprint.setDown(false);
    }

    private boolean shouldRun() {
        return mc.screen != null 
                && !(mc.screen instanceof ChatScreen)
                && !(mc.screen instanceof AbstractSignEditScreen)
                && !(mc.screen instanceof AnvilScreen)
                && !(mc.screen instanceof AbstractCommandBlockEditScreen)
                && !(mc.screen instanceof StructureBlockEditScreen);
    }

}
