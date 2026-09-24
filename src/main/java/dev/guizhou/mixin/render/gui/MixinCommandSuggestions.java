package dev.guizhou.mixin.render.gui;

import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.suggestion.Suggestion;
import com.mojang.brigadier.suggestion.Suggestions;
import dev.guizhou.GuizhouWare;
import dev.guizhou.features.commands.ui.CommandCompleter;
import dev.guizhou.features.commands.ui.CommandInputStyle;
import dev.guizhou.manager.CommandManager;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.CommandSuggestions;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientSuggestionProvider;
import net.minecraft.util.FormattedCharSequence;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;
import java.util.concurrent.CompletableFuture;

@Mixin(CommandSuggestions.class)
public abstract class MixinCommandSuggestions {
    @Shadow
    private CommandSuggestions.SuggestionsList suggestions;
    @Shadow
    private @Nullable CompletableFuture<Suggestions> pendingSuggestions;
    @Shadow
    private @Nullable ParseResults<ClientSuggestionProvider> currentParse;
    @Shadow
    boolean keepSuggestions;
    @Shadow
    @Final
    EditBox input;
    @Shadow
    @Final
    private Screen screen;
    @Shadow
    @Final
    boolean anchorToBottom;
    @Shadow
    @Final
    private List<FormattedCharSequence> commandUsage;
    @Shadow
    private int commandUsagePosition;
    @Shadow
    private int commandUsageWidth;

    @Unique
    private boolean guizhouware$clientCommand;

    @Shadow
    protected abstract void updateUsageInfo();

    @Inject(method = "updateCommandInfo", at = @At("HEAD"))
    private void guizhouware$resetCommandState(CallbackInfo ci) {
        guizhouware$clientCommand = false;
    }

    @SuppressWarnings("unchecked")
    @Inject(method = "updateCommandInfo", at = @At("RETURN"))
    public void updateCommandInfo(CallbackInfo ci, @Local StringReader stringReader) {
        String value = stringReader.getString();
        if (!(screen instanceof ChatScreen) || !CommandInputStyle.isClientCommand(value)) {
            return;
        }
        CommandManager manager = GuizhouWare.commandManager;
        int start = manager.getCommandPrefix().length();
        stringReader.setCursor(start);
        guizhouware$clientCommand = true;

        ParseResults<CommandManager> parse = manager.getDispatcher().parse(stringReader, manager);
        currentParse = (ParseResults<ClientSuggestionProvider>) (Object) parse;
        if (suggestions == null || !keepSuggestions) {
            CompletableFuture<Suggestions> pending = CommandCompleter.complete(manager, parse, value, start,
                    input.getCursorPosition());
            pendingSuggestions = pending;
            pending.thenRun(() -> {
                if (pendingSuggestions == pending && pending.isDone()) {
                    updateUsageInfo();
                }
            });
        }
    }

    @Inject(method = "sortSuggestions", at = @At("RETURN"), cancellable = true)
    private void guizhouware$rankSuggestions(Suggestions source, CallbackInfoReturnable<List<Suggestion>> cir) {
        if (guizhouware$clientCommand) {
            cir.setReturnValue(CommandCompleter.rank(input.getValue(), source.getList()));
        }
    }

    @Inject(method = "formatChat", at = @At("HEAD"), cancellable = true)
    private void guizhouware$formatChat(String visible, int offset, CallbackInfoReturnable<FormattedCharSequence> cir) {
        ParseResults<ClientSuggestionProvider> parse = currentParse;
        if (!guizhouware$clientCommand || parse == null || !parse.getReader().getString().equals(input.getValue())) {
            return;
        }
        cir.setReturnValue(CommandInputStyle.format(parse, GuizhouWare.commandManager.getCommandPrefix().length(),
                visible, offset));
    }

    @Inject(method = "renderUsage", at = @At("HEAD"), cancellable = true)
    private void guizhouware$renderUsage(GuiGraphics graphics, CallbackInfo ci) {
        if (!guizhouware$clientCommand || commandUsage.isEmpty()) {
            return;
        }
        ci.cancel();
        CommandInputStyle.renderUsage(graphics, commandUsage, commandUsagePosition, commandUsageWidth,
                anchorToBottom, screen.height);
    }
}
