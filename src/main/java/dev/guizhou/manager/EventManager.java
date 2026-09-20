package dev.guizhou.manager;

import dev.guizhou.GuizhouWare;
import dev.guizhou.event.Stage;
import dev.guizhou.event.impl.entity.DeathEvent;
import dev.guizhou.event.impl.entity.player.TickEvent;
import dev.guizhou.event.impl.entity.player.UpdateWalkingPlayerEvent;
import dev.guizhou.event.impl.input.KeyInputEvent;
import dev.guizhou.event.impl.input.MouseInputEvent;
import dev.guizhou.event.impl.network.ChatEvent;
import dev.guizhou.event.impl.render.Render2DEvent;
import dev.guizhou.event.impl.render.Render3DEvent;
import dev.guizhou.event.system.Subscribe;
import dev.guizhou.features.Feature;
import dev.guizhou.util.diagnostics.ClientDiagnostics;
import net.minecraft.world.entity.player.Player;

public class EventManager extends Feature {
    public void init() {
        EVENT_BUS.register(this, "EventManager");
    }

    public void onUnload() {
        EVENT_BUS.unregister(this);
    }

    @Subscribe
    public void onTick(TickEvent event) {
        if (nullCheck())
            return;
        GuizhouWare.moduleManager.onTick();
        for (Player player : mc.level.players()) {
            if (player == null || player.getHealth() > 0.0F)
                continue;
            EVENT_BUS.post(new DeathEvent(player));
        }
    }

    @Subscribe(priority = 100)
    public void onUpdateWalkingPlayer(UpdateWalkingPlayerEvent event) {
        if (nullCheck())
            return;
        if (event.getStage() == Stage.PRE) {
            GuizhouWare.positionManager.updatePosition();
        }
        if (event.getStage() == Stage.POST) {
            GuizhouWare.positionManager.restorePosition();
        }
    }

    @Subscribe
    public void onWorldRender(Render3DEvent event) {
        GuizhouWare.moduleManager.onRender3D(event);
    }

    @Subscribe
    public void onRenderGameOverlayEvent(Render2DEvent event) {
        ClientDiagnostics.recordFrame();
        GuizhouWare.moduleManager.onRender2D(event);
    }

    @Subscribe
    public void onKeyInput(KeyInputEvent event) {
        if (event.getAction() == 1) {
            GuizhouWare.moduleManager.onKeyPressed(event.getKey());
        } else if (event.getAction() == 0) {
            GuizhouWare.moduleManager.onKeyReleased(event.getKey());
        }
    }

    @Subscribe
    public void onMouseInput(MouseInputEvent event) {
        if (event.getAction() == 1) {
            GuizhouWare.moduleManager.onMousePressed(event.getButton());
        } else if (event.getAction() == 0) {
            GuizhouWare.moduleManager.onMouseReleased(event.getButton());
        }
    }

    @Subscribe
    public void onChatSent(ChatEvent event) {
        String message = event.getMessage();
        if (!message.startsWith(GuizhouWare.commandManager.getCommandPrefix())) {
            return;
        }
        event.cancel();
        GuizhouWare.commandManager.onChatSent(message);
    }
}
