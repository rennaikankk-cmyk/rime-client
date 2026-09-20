package dev.guizhou.mixin.render.gui;

import dev.guizhou.util.BuildConfig;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.PlainTextButton;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import java.util.List;
import static dev.guizhou.util.render.ShadcnDraw.*;

/** Reuses vanilla button instances, actions, enabled gates and keyboard/narrator handling. */
@Mixin(TitleScreen.class)
public abstract class MixinTitleScreen extends Screen {
    @Shadow private Component getMultiplayerDisabledReason() {throw new AssertionError();}
    @Unique private float guizhouware$panelX,guizhouware$panelY,guizhouware$panelWidth,guizhouware$panelHeight;
    protected MixinTitleScreen(Component title) {super(title);}

    @Inject(method="init",at=@At("TAIL"))
    private void guizhouware$layout(CallbackInfo ci) {
        List<AbstractWidget> buttons=children().stream().filter(AbstractWidget.class::isInstance)
                .map(AbstractWidget.class::cast).filter(b->!(b instanceof PlainTextButton)).toList();
        List<AbstractWidget> primary=buttons.stream().filter(b->b.getWidth()>=180).toList();
        List<AbstractWidget> secondary=buttons.stream().filter(b->!primary.contains(b)).toList();
        int rows=primary.size()+(secondary.size()+1)/2;
        int bw=Math.min(286,width-56),rh=Math.clamp((height-132)/Math.max(1,rows)-5,18,28);
        int ph=68+rows*(rh+5)+12,px=(width-bw-32)/2,py=Math.max(10,(height-ph)/2);
        guizhouware$panelX=px;guizhouware$panelY=py;guizhouware$panelWidth=bw+32;guizhouware$panelHeight=ph;
        int yy=py+68;
        for(AbstractWidget b:primary){b.setRectangle(bw,rh,px+16,yy);yy+=rh+5;}
        int half=(bw-6)/2;
        for(int i=0;i<secondary.size();i++) secondary.get(i).setRectangle(half,rh,px+16+(i%2)*(half+6),yy+(i/2)*(rh+5));
        for(var child:children())if(child instanceof PlainTextButton b) {
            // Keep the original copyright/credits action reachable, including by keyboard.
            b.setPosition(Math.max(5,width-b.getWidth()-8),height-13);
        }
    }
    @Inject(method="render",at=@At("HEAD"),cancellable=true)
    private void guizhouware$render(GuiGraphics g,int mx,int my,float delta,CallbackInfo ci) {
        g.fill(0,0,width,height,BG);
        // Quiet static geometry: no blur pass, animated shader, or browser runtime.
        int grid=48;
        for(int xx=width%grid;xx<width;xx+=grid)g.fill(xx,0,xx+1,height,0xFF101010);
        for(int yy=height%grid;yy<height;yy+=grid)g.fill(0,yy,width,yy+1,0xFF101010);
        float px=guizhouware$panelX,py=guizhouware$panelY,pw=guizhouware$panelWidth;
        panel(g,px,py,pw,guizhouware$panelHeight,10);
        round(g,px+16,py+18,27,27,7,TEXT);text(g,"G",px+23,py+25,14,700,BG);
        text(g,"GuizhouWare",px+53,py+18,21,600,TEXT);
        text(g,"MINECRAFT 1.21.11",px+54,py+43,8,MUTED);
        for(var child:children())if(child instanceof AbstractWidget b && b.visible) {
            String label=b.getMessage().getString();
            if(b instanceof PlainTextButton){
                // Retain vanilla width so credits remain easy to activate.
                text(g,fit(label,b.getWidth(),8),b.getX(),b.getY(),8,MUTED);continue;
            }
            boolean hover=b.isMouseOver(mx,my),focus=b.isFocused();
            boolean main=b.getWidth()>pw*.6f && b.getY()==(int)py+68;
            int fill=!b.active?0xFF202020:main?TEXT:hover?0xFF383838:SURFACE;
            round(g,b.getX(),b.getY(),b.getWidth(),b.getHeight(),5,focus?MUTED:BORDER);
            round(g,b.getX()+1,b.getY()+1,b.getWidth()-2,b.getHeight()-2,4,fill);
            String visible=fit(label,b.getWidth()-18,10);
            text(g,visible,b.getX()+(b.getWidth()-width(visible,10))/2,b.getY()+(b.getHeight()-10)/2f,10,!b.active?0xFF737373:main?BG:TEXT);
            if(!b.active && (hover || focus)) {
                Component reason=getMultiplayerDisabledReason();
                if(reason!=null)g.setTooltipForNextFrame(reason,mx,my);
            }
        }
        text(g,"GuizhouWare "+BuildConfig.VERSION,8,height-13,8,MUTED);
        ci.cancel();
    }
    @Inject(method="mouseClicked",at=@At("HEAD"),cancellable=true)
    private void guizhouware$click(MouseButtonEvent event,boolean doubled,CallbackInfoReturnable<Boolean> cir) {
        // Only visible native controls receive input; the old Realms overlay used old coordinates.
        cir.setReturnValue(super.mouseClicked(event,doubled));
    }
}
