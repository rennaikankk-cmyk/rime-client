package dev.guizhou.features.modules.qinghua;

import dev.guizhou.event.impl.network.ChatEvent;
import dev.guizhou.event.system.Subscribe;
import dev.guizhou.features.modules.Module;
import dev.guizhou.features.modules.qinghua.util.NumberListUtil;
import dev.guizhou.features.settings.Setting;

import java.util.List;

/** Port of Pattern ChatSuffix — suffix append logic unchanged. */
public class ChatSuffixModule extends Module {
    private final Setting<String> value = str("Value", " PatternX")
            .described("Suffix list, one per line or comma-separated.");

    public ChatSuffixModule() {
        super("ChatSuffix", "ChatSuffix.", Category.CLIENT);
    }

    @Subscribe
    public void onMessageSend(ChatEvent event) {
        List<String> list = NumberListUtil.parseStrings(this.value.getValue());
        if (list.isEmpty()) return;
        int i = (int) (Math.random() * list.size());
        event.setMessage(event.getMessage() + list.get(i));
    }
}
