package dev.guizhou.event.impl.entity.player;

import dev.guizhou.event.Event;
import dev.guizhou.event.Stage;

public class UpdateWalkingPlayerEvent extends Event {
    private final Stage stage;

    public UpdateWalkingPlayerEvent(Stage stage) {
        this.stage = stage;
    }

    public Stage getStage() {
        return stage;
    }
}
