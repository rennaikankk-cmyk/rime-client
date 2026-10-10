package dev.guizhou.manager;

import dev.guizhou.GuizhouWare;
import dev.guizhou.event.impl.ClientEvent;
import dev.guizhou.event.system.Subscribe;
import dev.guizhou.features.Feature;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

/** Marks the config dirty on every setting/toggle event and drives the debounced autosave. */
public final class ConfigAutosave extends Feature {
    public void init() {
        EVENT_BUS.register(this, "ConfigAutosave");
        // Client ticks also run on the title screen, so edits made there are persisted too.
        ClientTickEvents.END_CLIENT_TICK.register(client -> GuizhouWare.configManager.autosaveTick());
    }

    @Subscribe(priority = -1000)
    private void onClient(ClientEvent event) {
        if (!event.isCancelled()) GuizhouWare.configManager.markDirty();
    }
}
