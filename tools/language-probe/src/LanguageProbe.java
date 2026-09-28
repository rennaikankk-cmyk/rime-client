package probe;

import dev.guizhou.GuizhouWare;
import dev.guizhou.features.modules.client.ClickGuiModule;
import dev.guizhou.features.gui.GuizhouWareGui;
import dev.guizhou.features.gui.HudEditorScreen;
import dev.guizhou.util.lang.ClientLanguage;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import com.google.gson.JsonObject;

/** Test-only mod: use with an isolated world and the existing development gate. */
public final class LanguageProbe implements ClientModInitializer {
    private int tick,stage,wait,checks;
    private volatile boolean captured;
    private final List<String> failures = new ArrayList<>();
    @Override public void onInitializeClient() { ClientTickEvents.END_CLIENT_TICK.register(this::tick); }
    private void check(boolean value, String text) { if (!value) throw new AssertionError(text); checks++; }
    private void english(String value, String where) {
        if (value.codePoints().anyMatch(c -> c >= 0x4e00 && c <= 0x9fff)) failures.add(where + ": " + value);
        checks++;
    }
    private void tick(Minecraft mc) {
        if(stage==99)return;
        try {
            if (++tick>1000) throw new AssertionError("Language probe timed out");
            if(mc.player==null||mc.level==null)return;
            var gui=ClickGuiModule.getInstance();
            if(stage==0) {
                mc.options.pauseOnLostFocus=false;
                for(var module:GuizhouWare.moduleManager.getModules())if(module.isEnabled())module.disable();
                check(!ClientLanguage.isChinese(),"Fresh config defaults to English");
                var old=new JsonObject();old.addProperty("Language","CHINESE");old.addProperty("Prefix","keepme");
                gui.fromJson(old);
                check(!ClientLanguage.isChinese(),"Old config forced to English");
                check(gui.prefix.getValue().equals("keepme"),"Unrelated config preserved");
                gui.language.setValue(ClientLanguage.Language.CHINESE);
                var saved=gui.toJson();
                check(saved.getAsJsonObject().get("LanguageRevision").getAsInt()==1,"Migration revision persisted");
                gui.language.setValue(ClientLanguage.Language.ENGLISH);gui.fromJson(saved);
                check(ClientLanguage.isChinese(),"Saved Chinese survives reload after migration");
                var hover=gui.menuScale.hoverHint();
                check(hover.contains("右键"),"Chinese hover");
                gui.language.setValue(ClientLanguage.Language.ENGLISH);
                check(gui.menuScale.hoverHint().contains("Right-click"),"Hover cache follows language");
                check(gui.menuScale.getName().equals("菜单大小 (%)"),"Stable setting ID unchanged");
                for(var module:GuizhouWare.moduleManager.getModules()) {
                    english(module.getDisplayName(),module.getName()+" name");
                    english(module.getDescription(),module.getName()+" description");
                    for(var setting:module.getSettings()) {
                        english(setting.getDisplayName(),module.getName()+" setting");
                        english(setting.getDescription(),module.getName()+" description/"+setting.getName());
                        english(setting.hoverHint(),module.getName()+" hover/"+setting.getName());
                    }
                }
                if(!failures.isEmpty())throw new AssertionError(String.join("\n",failures));
                gui.prefix.setValue("rime");
                openGui(mc,gui);stage=1;wait=0;return;
            }
            if(stage==1 && ++wait>45) { capture(mc,"language-english.png");stage=2;return; }
            if(stage==2 && captured) {
                gui.language.setValue(ClientLanguage.Language.CHINESE);
                check(ClientLanguage.text("编辑 HUD").equals("编辑 HUD"),"Live Chinese switch");
                wait=0;stage=3;return;
            }
            if(stage==3 && ++wait>35) {capture(mc,"language-chinese.png");stage=4;return;}
            if(stage==4 && captured) {
                gui.language.setValue(ClientLanguage.Language.ENGLISH);
                mc.setScreen(new HudEditorScreen(null));wait=0;stage=5;return;
            }
            if(stage==5 && ++wait>35){capture(mc,"language-hud.png");stage=6;return;}
            if(stage==6 && captured) {
                mc.setScreen(null);GuizhouWare.configManager.save();
                Files.writeString(mc.gameDirectory.toPath().resolve("complete.txt"),"LanguageProbe passed: "+checks+" checks\n");
                stage=99;mc.stop();
            }
        } catch(Throwable e) {
            e.printStackTrace();
            try {Files.writeString(mc.gameDirectory.toPath().resolve("failure.txt"),e.toString());}catch(Exception ignored){}
            stage=99;mc.stop();
        }
    }
    @SuppressWarnings("unchecked") private void openGui(Minecraft mc,ClickGuiModule module)throws Exception {
        var screen=GuizhouWareGui.getClickGui();mc.setScreen(screen);
        var field=GuizhouWareGui.class.getDeclaredField("hachimi");field.setAccessible(true);var panel=field.get(screen);
        var expanded=panel.getClass().getDeclaredField("expanded");expanded.setAccessible(true);
        ((java.util.Set<dev.guizhou.features.modules.Module>)expanded.get(panel)).add(module);
    }
    private void capture(Minecraft mc,String name) {
        captured=false;
        Screenshot.grab(mc.gameDirectory,name,mc.getMainRenderTarget(),1,msg->captured=true);
    }
}
