package dev.guizhou.mixin.render.gui;

import dev.guizhou.util.BuildConfig;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.PlainTextButton;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
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
    @Unique private float guizhouware$scale=1;
    @Unique private int guizhouware$firstY;
    protected MixinTitleScreen(Component title) {super(title);}

    @Inject(method="init",at=@At("TAIL"))
    private void guizhouware$onInit(CallbackInfo ci) { guizhouware$layout(); }

    /** Mods append buttons after init. Place every labelled button again each frame. */
    @Unique
    private void guizhouware$layout() {
        List<AbstractWidget> buttons=children().stream().filter(AbstractWidget.class::isInstance)
                .map(AbstractWidget.class::cast).filter(b->!(b instanceof PlainTextButton)).toList();
        List<AbstractWidget> primary=buttons.stream().filter(b->b.getWidth()>=180).toList();
        List<AbstractWidget> secondary=buttons.stream().filter(b->!primary.contains(b)).toList();
        int rows=primary.size()+(secondary.size()+1)/2;
        var gui=dev.guizhou.features.modules.client.ClickGuiModule.getInstance();
        float requested=gui==null?1:gui.menuScale.getValue().floatValue()/100f;
        requested=Float.isFinite(requested)?Math.clamp(requested,.6f,1.5f):1;
        float s=Math.min(requested,Math.min((width-20)/318f,(height-34)/(80f+rows*33)));
        guizhouware$scale=Math.max(.1f,s);
        s=guizhouware$scale;
        int bw=Math.round(286*s),rh=Math.round(28*s),gap=Math.max(2,Math.round(5*s)),pad=Math.round(16*s);
        int ph=Math.round(80*s)+rows*(rh+gap),px=(width-bw-pad*2)/2,py=Math.max(6,(height-ph)/2);
        guizhouware$panelX=px;guizhouware$panelY=py;guizhouware$panelWidth=bw+pad*2;guizhouware$panelHeight=ph;
        int yy=py+Math.round(68*s);guizhouware$firstY=yy;
        for(AbstractWidget b:primary){b.setRectangle(bw,rh,px+pad,yy);yy+=rh+gap;}
        int half=(bw-gap)/2;
        for(int i=0;i<secondary.size();i++) secondary.get(i).setRectangle(half,rh,px+pad+(i%2)*(half+gap),yy+(i/2)*(rh+gap));
        for(var child:children())if(child instanceof PlainTextButton b) {
            // Keep the original copyright/credits action reachable, including by keyboard.
            b.setPosition(Math.max(5,width-b.getWidth()-8),height-13);
        }
    }
    @Inject(method="render",at=@At("HEAD"),cancellable=true)
    private void guizhouware$render(GuiGraphics g,int mx,int my,float delta,CallbackInfo ci) {
        guizhouware$layout();
        g.fill(0,0,width,height,BG);
        // Quiet static geometry: no blur pass, animated shader, or browser runtime.
        int grid=48;
        for(int xx=width%grid;xx<width;xx+=grid)g.fill(xx,0,xx+1,height,0x242D4264);
        for(int yy=height%grid;yy<height;yy+=grid)g.fill(0,yy,width,yy+1,0x242D4264);
        float px=guizhouware$panelX,py=guizhouware$panelY,pw=guizhouware$panelWidth;
        float s=guizhouware$scale;
        round(g,px-5,py+6,pw+10,guizhouware$panelHeight+3,14*s,0x50091123);
        panel(g,px,py,pw,guizhouware$panelHeight,10*s);
        round(g,px+1,py+1,pw-2,3*s,2*s,0x5072A8D8);
        round(g,px+16*s,py+18*s,27*s,27*s,7*s,ACCENT);text(g,"G",px+23*s,py+25*s,14*s,700,BG);
        text(g,"Rime",px+53*s,py+18*s,21*s,600,TEXT);
        text(g,"MINECRAFT 1.21.1",px+54*s,py+43*s,8*s,MUTED);
        for(var child:children())if(child instanceof AbstractWidget b && b.visible) {
            String label=b.getMessage().getString();
            if(b instanceof PlainTextButton){
                // Retain vanilla width so credits remain easy to activate.
                text(g,fit(label,b.getWidth(),8),b.getX(),b.getY(),8,MUTED);continue;
            }
            boolean hover=b.isMouseOver(mx,my),focus=b.isFocused();
            boolean main=b.getWidth()>pw*.6f && b.getY()==guizhouware$firstY;
            int fill=!b.active?0xFF253044:main?ACCENT:hover?0xFF344864:SURFACE;
            round(g,b.getX(),b.getY(),b.getWidth(),b.getHeight(),5,focus?MUTED:BORDER);
            round(g,b.getX()+1,b.getY()+1,b.getWidth()-2,b.getHeight()-2,4,fill);
            String visible=fit(label,b.getWidth()-18*s,10*s);
            text(g,visible,b.getX()+(b.getWidth()-width(visible,10*s))/2,b.getY()+(b.getHeight()-10*s)/2f,10*s,!b.active?MUTED:main?BG:TEXT);
            if(!b.active && (hover || focus)) {
                Component reason=getMultiplayerDisabledReason();
                if(reason!=null)g.renderComponentTooltip(net.minecraft.client.Minecraft.getInstance().font, java.util.List.of(reason), mx, my);
            }
        }
        text(g,"Rime "+BuildConfig.VERSION,8,height-13,8,MUTED);
        ci.cancel();
    }
    @Inject(method="mouseClicked",at=@At("HEAD"),cancellable=true)
    private void guizhouware$click(double mx,double my,int button,CallbackInfoReturnable<Boolean> cir) {
        // Only visible native controls receive input; the old Realms overlay used old coordinates.
        cir.setReturnValue(super.mouseClicked(mx,my,button));
    }
}
