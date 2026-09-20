package dev.guizhou.features.gui;

import dev.guizhou.GuizhouWare;
import dev.guizhou.features.modules.Module;
import dev.guizhou.features.modules.client.ClickGuiModule;
import dev.guizhou.features.modules.client.HudClientModule;
import dev.guizhou.features.settings.Bind;
import dev.guizhou.features.settings.Setting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.joml.Vector2f;
import java.awt.Color;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.IntConsumer;
import static dev.guizhou.util.render.ShadcnDraw.*;
import static org.lwjgl.glfw.GLFW.*;

/** Native shadcn-style module browser; all settings retain their normal event and persistence paths. */
public class GuizhouWareGui extends Screen {
    private static GuizhouWareGui instance;
    private static Color colorClipboard;
    private final ArrayList<Widget> legacyWidgets = new ArrayList<>();
    private final List<Hit> hits = new ArrayList<>();
    private Module.Category category = Module.Category.COMBAT;
    private Module selected;
    private String query="", page="General", hint="";
    private ShadcnTextField search, editor;
    private Setting<?> editing, binding, dragging, dropdown;
    private String validation="";
    private float scale=1, x,y,w,h, listX,listW, detailX,detailW, bodyTop,bodyBottom;
    private float moduleScroll,settingScroll,tabScroll,moduleMax,settingMax,tabMax;
    private float dragX,dragW,dropdownX,dropdownY,dropdownW;
    private int dropdownScroll,dropdownIndex,keyboardFocus=-1;
    private long opened;
    private record Hit(float x,float y,float w,float h,IntConsumer action) {
        boolean at(double mx,double my) { return contains(mx,my,x,y,w,h); }
    }

    public GuizhouWareGui() { super(Component.literal("GuizhouWare")); instance=this; }
    public static GuizhouWareGui getInstance() { if(instance==null) instance=new GuizhouWareGui(); return instance; }
    public static GuizhouWareGui getClickGui() { return getInstance(); }
    public static float getScale() { return instance==null?1:instance.scale; }
    public ArrayList<Widget> getComponents() { return legacyWidgets; }
    public int getTextOffset() { return -6; }
    public static Color getColorClipboard() {return colorClipboard;}
    public static void setColorClipboard(Color color) {colorClipboard=color;}
    public void reload() { selected=null; moduleScroll=settingScroll=0; }
    public void openHudAppearance(HudClientModule hud, String element) {
        category=Module.Category.CLIENT; query=""; select(hud); page=element;
        for(String p:hud.getSettings().stream().filter(Setting::isVisible).map(Setting::getPage).distinct().toList()) {
            if(p.equals(page))break;
            tabScroll+=width(p,9)+24;
        }
        if(search!=null)search=new ShadcnTextField("",s->{query=s;moduleScroll=0;});
    }
    public boolean capturesMovementKeys() {
        return editor!=null || binding!=null || dropdown!=null || keyboardFocus>=0 || search!=null && search.focused;
    }

    @Override public void init() {
        ClickGuiModule gui=ClickGuiModule.getInstance();
        if(gui!=null && !gui.isEnabled())gui.enable();
        opened=System.currentTimeMillis();
        search=new ShadcnTextField(query,s->{query=s;moduleScroll=0;});
        editing=binding=dragging=dropdown=null; editor=null; keyboardFocus=-1;
        layout();
    }
    private void layout() {
        float fit=Math.min(1,Math.min(width/712f,height/452f));
        w=Math.min(860,width/fit-24); h=Math.min(500,height/fit-24);
        double requested=ClickGuiModule.getInstance()==null?1:ClickGuiModule.getInstance().menuScale.getValue()/100;
        requested=Double.isFinite(requested)?Math.clamp(requested,.6,1.5):1;
        scale=Math.min(fit*(float)requested,Math.min((width-12)/w,(height-12)/h));
        x=(width/scale-w)/2; y=(height/scale-h)/2;
        if(ClickGuiModule.getInstance()!=null && ClickGuiModule.getInstance().smooth.getValue()) {
            double progress=Math.clamp((System.currentTimeMillis()-opened)/150d,0d,1d);
            y+=(float)(6*Math.pow(1-progress,3));
        }
        listX=x+128; listW=190; detailX=listX+listW+1; detailW=x+w-detailX;
        bodyTop=y+100; bodyBottom=y+h-32;
        search.bounds(listX+12,y+55,listW-24,28);
    }
    private List<Module> modules() {
        String q=query.toLowerCase(Locale.ROOT).trim();
        return GuizhouWare.moduleManager.stream().filter(m->!m.hidden)
                .filter(m->m.getCategory()!=Module.Category.HUD)
                .filter(m->m.getCategory()!=Module.Category.FUNNY || GuizhouWare.commandManager.isFunnyVisible())
                .filter(m->q.isEmpty()?m.getCategory()==category:(m.getName()+" "+m.getDisplayName()+" "+m.getDescription()).toLowerCase(Locale.ROOT).contains(q))
                .sorted(Comparator.comparing(Module::getName)).toList();
    }
    private void select(Module m) {selected=m;page="General";settingScroll=tabScroll=0;dropdown=binding=null;}
    private void hit(float x,float y,float w,float h,IntConsumer action) {hits.add(new Hit(x,y,w,h,action));}
    private void button(GuiGraphics g,String label,float bx,float by,float bw,float bh,double mx,double my,boolean primary,Runnable action) {
        boolean hover=contains(mx,my,bx,by,bw,bh);
        round(g,bx,by,bw,bh,5,primary?TEXT:hover?0xFF363636:SURFACE);
        text(g,fit(label,bw-14,10),bx+7,by+(bh-10)/2,10,primary?BG:TEXT);
        hit(bx,by,bw,bh,b->{if(b==0)action.run();});
    }
    private void toggle(GuiGraphics g,float bx,float by,boolean on,double mx,double my,Runnable action) {
        round(g,bx,by,26,14,7,on?accent():BORDER);
        round(g,bx+(on?14:2),by+2,10,10,5,on?BG:TEXT);
        hit(bx-4,by-5,34,24,b->{if(b==0)action.run();});
    }
    private int accent() {
        ClickGuiModule gui=ClickGuiModule.getInstance();
        return gui==null || gui.theme.getValue()==ClickGuiModule.Theme.GUIZHOUWARE ? ACCENT : gui.moduleAccent(category,0).getRGB()|0xFF000000;
    }
    @Override public void render(GuiGraphics g,int mouseX,int mouseY,float delta) {
        layout(); hits.clear();hint="右键设置恢复默认 · Ctrl+F 搜索 · Tab 键导航";
        double mx=mouseX/scale,my=mouseY/scale;
        g.fill(0,0,width,height,0xA0000000);
        g.pose().pushMatrix();g.pose().scale(scale,scale);
        round(g,x-4,y+5,w+8,h+2,13,0x35070F22);
        panel(g,x,y,w,h,10);
        round(g,x+1,y+1,w-2,3,2,0x486E9CC9);
        g.fill((int)x+128,(int)y+1,(int)x+129,(int)(y+h-1),BORDER);
        g.fill((int)(listX+listW),(int)y+42,(int)(listX+listW+1),(int)(y+h-32),BORDER);
        g.fill((int)x+1,(int)y+42,(int)(x+w-1),(int)y+43,BORDER);
        round(g,x+14,y+13,18,18,5,TEXT);text(g,"G",x+18,y+17,10,700,BG);
        text(g,"GuizhouWare",x+41,y+17,12,600,TEXT);
        text(g,"控制中心",x+150,y+18,10,MUTED);
        button(g,"关闭  ×",x+w-67,y+11,55,22,mx,my,false,this::onClose);
        float cy=y+60;
        for(Module.Category c:GuizhouWare.moduleManager.getCategories()) {
            if(c==Module.Category.HUD || c==Module.Category.FUNNY && !GuizhouWare.commandManager.isFunnyVisible()) continue;
            boolean active=c==category && query.isBlank();
            if(active || contains(mx,my,x+10,cy,108,29)) round(g,x+10,cy,108,29,7,active?SURFACE:0x702B3D59);
            text(g,categoryName(c),x+21,cy+9,10,active?TEXT:MUTED);
            if(active) round(g,x+10,cy+9,2,11,1,accent());
            final Module.Category target=c;
            hit(x+10,cy,108,29,b->{if(b==0){category=target;query="";search=new ShadcnTextField("",s->{query=s;moduleScroll=0;});moduleScroll=settingScroll=0;selected=null;}});
            cy+=33;
        }
        button(g,"编辑 HUD  ↗",x+12,y+h-77,104,28,mx,my,false,()->{save();GuizhouWare.moduleManager.getModuleByClass(HudClientModule.class).openEditor(this);});
        text(g,"MINECRAFT 1.21.11",x+14,y+h-36,8,MUTED);
        search.draw(g,"搜索模块…");
        hit(search.x,search.y,search.w,search.h,b->{if(b==0)search.focused=true;});
        List<Module> visible=modules();
        if(selected==null && !visible.isEmpty()) select(visible.getFirst());
        moduleMax=Math.max(0,visible.size()*43-(bodyBottom-bodyTop));
        moduleScroll=Math.clamp(moduleScroll,0,moduleMax);
        g.enableScissor((int)listX+4,(int)bodyTop,(int)(listX+listW-3),(int)bodyBottom);
        for(int i=0;i<visible.size();i++) {
            Module m=visible.get(i);float ry=bodyTop+i*43-moduleScroll;
            if(ry+40<=bodyTop || ry>=bodyBottom)continue;
            boolean active=m==selected;
            if(active || contains(mx,my,listX+8,ry,listW-16,39))round(g,listX+8,ry,listW-16,39,8,active?SURFACE:0x702B3D59);
            text(g,fit(m.getDisplayName(),listW-69,10),listX+18,ry+8,10,600,active?TEXT:MUTED);
            text(g,query.isBlank()?fit(m.getDescription(),listW-69,8):categoryName(m.getCategory()),listX+18,ry+24,8,MUTED);
            hit(listX+8,Math.max(ry,bodyTop),listW-56,Math.max(0,Math.min(ry+39,bodyBottom)-Math.max(ry,bodyTop)),b->{if(b==0)select(m);else if(b==1)m.toggle();});
            if(ry+27<=bodyBottom && ry+3>=bodyTop) toggle(g,listX+listW-44,ry+12,m.isEnabled(),mx,my,m::toggle);
        }
        if(visible.isEmpty())text(g,"没有匹配的模块",listX+20,bodyTop+22,10,MUTED);
        g.disableScissor();scrollbar(g,listX+listW-4,bodyTop,bodyBottom-bodyTop,moduleScroll,moduleMax);
        drawSettings(g,mx,my);
        g.fill((int)listX,(int)(y+h-32),(int)(x+w-1),(int)(y+h-31),BORDER);
        text(g,fit(hint,w-150,8),listX+12,y+h-19,8,MUTED);
        if(keyboardFocus>=0 && keyboardFocus<hits.size() && editor==null && dropdown==null) {
            Hit f=hits.get(keyboardFocus);g.renderOutline((int)f.x-1,(int)f.y-1,(int)f.w+2,(int)f.h+2,MUTED);
        }
        if(dropdown!=null)drawDropdown(g,mx,my);
        if(editor!=null)drawEditor(g,mx,my);
        if(binding!=null)drawBinding(g);
        g.pose().popMatrix();
    }
    private void drawSettings(GuiGraphics g,double mx,double my) {
        if(selected==null)return;
        text(g,fit(selected.getDisplayName(),detailW-85,17),detailX+18,y+58,17,600,TEXT);
        toggle(g,x+w-50,y+61,selected.isEnabled(),mx,my,selected::toggle);
        text(g,fit(selected.getDescription(),detailW-36,9),detailX+18,y+82,9,MUTED);
        List<Setting<?>> settings=selected.getSettings().stream().filter(s->s!=selected.enabled && s.isVisible()).toList();
        List<String> pages=settings.stream().map(Setting::getPage).distinct().toList();
        if(!pages.contains(page))page=pages.isEmpty()?"General":pages.getFirst();
        float tabsLeft=detailX+16,tabsRight=x+w-16,tx=tabsLeft-tabScroll;
        g.enableScissor((int)tabsLeft,(int)y+99,(int)tabsRight,(int)y+127);
        for(String p:pages) {
            float pw=width(p,9)+20;
            if(p.equals(page))round(g,tx,y+100,pw,24,5,SURFACE);
            text(g,p,tx+10,y+108,9,p.equals(page)?TEXT:MUTED);
            float left=Math.max(tx,tabsLeft),right=Math.min(tx+pw,tabsRight);
            if(right>left)hit(left,y+100,right-left,24,b->{if(b==0){page=p;settingScroll=0;dropdown=null;}});
            tx+=pw+4;
        }
        tabMax=Math.max(0,tx+tabScroll-tabsRight);tabScroll=Math.clamp(tabScroll,0,tabMax);
        g.disableScissor();
        float top=y+136, bottom=bodyBottom-8;
        List<Setting<?>> rows=settings.stream().filter(s->s.getPage().equals(page)).toList();
        float content=0;for(Setting<?> s:rows)content+=rowHeight(s);
        settingMax=Math.max(0,content-(bottom-top));settingScroll=Math.clamp(settingScroll,0,settingMax);
        g.enableScissor((int)detailX+8,(int)top,(int)(x+w-8),(int)bottom);
        float ry=top-settingScroll;
        for(Setting<?> s:rows) {
            float rh=rowHeight(s);
            if(ry+rh>top && ry<bottom)drawSetting(g,s,detailX+18,ry,detailW-36,rh,mx,my,top,bottom);
            ry+=rh;
        }
        g.disableScissor();scrollbar(g,x+w-5,top,bottom-top,settingScroll,settingMax);
    }
    private float rowHeight(Setting<?> s) {return s.isNumberSetting() && s.hasRestriction()?57:43;}
    private void drawSetting(GuiGraphics g,Setting<?> s,float sx,float sy,float sw,float sh,double mx,double my,float top,float bottom) {
        Object v=s.getValue();boolean hover=contains(mx,my,sx,sy,sw,sh) && my>=top && my<bottom;
        if(hover)hint=s.getName()+(s.getDescription().isBlank()?"":" · "+s.getDescription());
        g.fill((int)sx,(int)(sy+sh-1),(int)(sx+sw),(int)(sy+sh),0xFF252525);
        String label=s.getName();
        if(selected instanceof HudClientModule && label.startsWith(page+" "))label=label.substring(page.length()+1);
        text(g,fit(label,sw*.56f,10),sx,sy+8,10,TEXT);
        if(!s.getDescription().isBlank())text(g,fit(s.getDescription(),sw*.56f,8),sx,sy+24,8,MUTED);
        boolean fully=sy>=top && sy+sh<=bottom;
        if(v instanceof Boolean on) {
            if(fully)toggle(g,sx+sw-28,sy+9,on,mx,my,()->assign(s,!on));
            else {round(g,sx+sw-28,sy+9,26,14,7,on?accent():BORDER);}
        } else {
            String value=v instanceof Color c?String.format("#%02X%02X%02X%02X",c.getRed(),c.getGreen(),c.getBlue(),c.getAlpha()):v instanceof Number?number((Number)v):v.toString();
            float cw=Math.min(125,sw*.42f), cx=sx+sw-cw;
            round(g,cx,sy+3,cw,25,4,SURFACE);
            text(g,fit(value+(v instanceof Enum<?>?"  ▾":""),cw-14,9),cx+7,sy+11,9,MUTED);
            if(v instanceof Color c)round(g,cx+cw-14,sy+11,7,7,2,c.getRGB()|0xFF000000);
            if(fully)hit(cx,sy+3,cw,25,b->{
                if(b==1){s.reset();return;}if(b!=0)return;
                if(v instanceof Bind){binding=s;search.focused=false;}
                else if(v instanceof Enum<?> e){dropdown=s;dropdownX=cx;dropdownY=sy+30;dropdownW=cw;dropdownIndex=e.ordinal();dropdownScroll=Math.max(0,dropdownIndex-6);}
                else beginEdit(s);
            });
            if(v instanceof Number n && s.hasRestriction()) {
                float a=((Number)s.getMin()).floatValue(),b=((Number)s.getMax()).floatValue();
                float ratio=b<=a?0:Math.clamp((n.floatValue()-a)/(b-a),0,1);
                round(g,sx,sy+42,sw,3,1,BORDER);round(g,sx,sy+42,sw*ratio,3,1,accent());
                round(g,sx+sw*ratio-4,sy+39,8,8,4,TEXT);
                if(fully)hit(sx-4,sy+33,sw+8,21,button->{if(button==0){dragging=s;dragX=sx;dragW=sw;setSlider(mx);}else if(button==1)s.reset();});
            }
        }
        if(fully)hit(sx,sy,sw*.56f,sh,b->{if(b==1)s.reset();else if(b==0 && v instanceof Boolean)assign(s,!((Boolean)s.getValue()));});
    }
    private static String number(Number n) {
        if(n instanceof Integer || n instanceof Long)return n.toString();
        return String.format(Locale.ROOT,"%.3f",n.doubleValue()).replaceAll("0+$","").replaceAll("\\.$","");
    }
    @SuppressWarnings({"unchecked","rawtypes"}) private static void assign(Setting s,Object v){s.setValue(v);}
    private void setSlider(double mx) {
        if(dragging==null)return;
        double min=((Number)dragging.getMin()).doubleValue(),max=((Number)dragging.getMax()).doubleValue();
        double value=min+(max-min)*Math.clamp((mx-dragX)/dragW,0d,1d);
        Object old=dragging.getValue();
        if(old instanceof Integer)assign(dragging,(int)Math.round(value));
        else if(old instanceof Long)assign(dragging,Math.round(value));
        else if(old instanceof Float)assign(dragging,(float)(Math.round(value*1000)/1000d));
        else assign(dragging,Math.round(value*1000)/1000d);
    }
    private void drawDropdown(GuiGraphics g,double mx,double my) {
        Object[] values=dropdown.getValue().getClass().getEnumConstants();
        int count=Math.min(7,values.length);dropdownScroll=Math.clamp(dropdownScroll,0,Math.max(0,values.length-count));
        float dh=count*26+8,dy=Math.min(dropdownY,y+h-dh-15),dw=Math.max(150,dropdownW);
        dropdownY=dy;dropdownW=dw;dropdownX=Math.min(dropdownX,x+w-dw-12);
        panel(g,dropdownX,dy,dw,dh,6);
        for(int i=0;i<count;i++) {
            Object value=values[i+dropdownScroll];float yy=dy+4+i*26;
            if(i+dropdownScroll==dropdownIndex || contains(mx,my,dropdownX+4,yy,dw-8,25))round(g,dropdownX+4,yy,dw-8,25,4,SURFACE);
            text(g,fit(value.toString(),dw-24,10),dropdownX+12,yy+8,10,TEXT);
        }
    }
    private void beginEdit(Setting<?> s) {
        editing=s;validation="";search.focused=false;
        Object v=s.getValue();String value=v instanceof Color c?String.format("#%02X%02X%02X%02X",c.getRed(),c.getGreen(),c.getBlue(),c.getAlpha()):v instanceof Vector2f p?p.x+","+p.y:v.toString();
        editor=new ShadcnTextField(value,t->validation="");editor.focused=true;editor.selectAll();
    }
    private void drawEditor(GuiGraphics g,double mx,double my) {
        g.fill((int)x,(int)y,(int)(x+w),(int)(y+h),0xBB000000);
        float ex=x+(w-330)/2,ey=y+(h-162)/2;
        panel(g,ex,ey,330,162,9);
        text(g,editing.getName(),ex+18,ey+18,14,600,TEXT);
        String help=editing.isColorSetting()?"颜色格式 #RRGGBB 或 #RRGGBBAA":editing.hasRestriction()?"范围 "+editing.getMin()+" – "+editing.getMax():"Enter 应用 · Esc 取消";
        text(g,fit(help,292,9),ex+18,ey+43,9,MUTED);
        editor.bounds(ex+18,ey+64,294,30);editor.draw(g,"");
        text(g,fit(validation,294,8),ex+18,ey+101,8,0xFFF87171);
        button(g,"取消",ex+176,ey+123,62,24,mx,my,false,()->{editor=null;editing=null;});
        button(g,"应用",ex+248,ey+123,64,24,mx,my,true,this::commitEdit);
    }
    private void commitEdit() {
        try {
            String text=editor.value().trim();Object old=editing.getValue(),next;
            if(old instanceof Integer)next=Integer.valueOf(text);
            else if(old instanceof Long)next=Long.valueOf(text);
            else if(old instanceof Float){float v=Float.parseFloat(text);if(!Float.isFinite(v))throw new IllegalArgumentException();next=v;}
            else if(old instanceof Double){double v=Double.parseDouble(text);if(!Double.isFinite(v))throw new IllegalArgumentException();next=v;}
            else if(old instanceof Color) {
                text=text.replaceFirst("^#","");if(!text.matches("[a-fA-F0-9]{6}([a-fA-F0-9]{2})?"))throw new IllegalArgumentException();
                long rgba=Long.parseLong(text,16);if(text.length()==6)rgba=(rgba<<8)|255;
                next=new Color((int)(rgba>>24)&255,(int)(rgba>>16)&255,(int)(rgba>>8)&255,(int)rgba&255);
            } else if(old instanceof Vector2f) {
                String[] parts=text.split(",");if(parts.length!=2)throw new IllegalArgumentException();
                float a=Float.parseFloat(parts[0].trim()),b=Float.parseFloat(parts[1].trim());
                if(!Float.isFinite(a)||!Float.isFinite(b))throw new IllegalArgumentException();next=new Vector2f(a,b);
            } else next=editor.value();
            assign(editing,next);editor=null;editing=null;
        } catch(IllegalArgumentException exception){validation="格式无效，请检查输入";}
    }
    private void drawBinding(GuiGraphics g) {
        g.fill((int)x,(int)y,(int)(x+w),(int)(y+h),0xBB000000);
        float bx=x+(w-330)/2,by=y+(h-100)/2;
        panel(g,bx,by,330,100,9);text(g,"按下新的快捷键",bx+20,by+22,15,600,TEXT);
        text(g,"支持鼠标按键 · Delete 清除 · Esc 取消",bx+20,by+55,10,MUTED);
    }
    private static void scrollbar(GuiGraphics g,float sx,float sy,float height,float scroll,float max) {
        if(max<=0)return;float thumb=Math.max(18,height*height/(height+max));
        round(g,sx,sy+(height-thumb)*scroll/max,2,thumb,1,0xFF525252);
    }
    @Override public boolean mouseClicked(MouseButtonEvent e,boolean doubled) {
        double mx=e.x()/scale,my=e.y()/scale;int b=e.button();keyboardFocus=-1;
        if(binding!=null){assign(binding,Bind.fromMouseButton(b));binding=null;return true;}
        if(editor!=null) {
            if(b==0 && editor.click(mx,my,false))return true;
            // Only the two dialog actions can receive clicks while the modal is open.
            for(int i=Math.max(0,hits.size()-2);i<hits.size();i++)if(hits.get(i).at(mx,my)){hits.get(i).action.accept(b);return true;}
            return true;
        }
        if(dropdown!=null) {
            Object[] values=dropdown.getValue().getClass().getEnumConstants();int index=(int)((my-dropdownY-4)/26)+dropdownScroll;
            if(b==0 && contains(mx,my,dropdownX,dropdownY+4,dropdownW,Math.min(7,values.length)*26) && index>=0 && index<values.length)assign(dropdown,values[index]);
            dropdown=null;return true;
        }
        search.click(mx,my,false);
        // Later controls take precedence over a row's background.
        for(int i=hits.size()-1;i>=0;i--)if(hits.get(i).at(mx,my)){hits.get(i).action.accept(b);return true;}
        return true;
    }
    @Override public boolean mouseDragged(MouseButtonEvent e,double dx,double dy) {
        if(dragging!=null && e.button()==0){setSlider(e.x()/scale);return true;}return false;
    }
    @Override public boolean mouseReleased(MouseButtonEvent e){dragging=null;return true;}
    @Override public boolean mouseScrolled(double mx,double my,double horizontal,double vertical) {
        mx/=scale;my/=scale;if(editor!=null || binding!=null)return true;
        if(dropdown!=null){dropdownScroll-=(int)Math.signum(vertical);return true;}
        if(contains(mx,my,detailX,y+98,detailW,31)){tabScroll=Math.clamp((float)(tabScroll-vertical*36-horizontal*36),0,tabMax);return true;}
        if(contains(mx,my,listX,bodyTop,listW,bodyBottom-bodyTop))moduleScroll=Math.clamp((float)(moduleScroll-vertical*35),0,moduleMax);
        else if(contains(mx,my,detailX,y+132,detailW,bodyBottom-y-132))settingScroll=Math.clamp((float)(settingScroll-vertical*35),0,settingMax);
        return true;
    }
    @Override public boolean keyPressed(KeyEvent e) {
        int k=e.input();
        if(binding!=null){if(k==GLFW_KEY_UNKNOWN)return true;if(k!=GLFW_KEY_ESCAPE)assign(binding,k==GLFW_KEY_DELETE||k==GLFW_KEY_BACKSPACE?Bind.none():new Bind(k));binding=null;return true;}
        if(editor!=null){if(k==GLFW_KEY_ESCAPE){editor=null;editing=null;}else if(k==GLFW_KEY_ENTER||k==GLFW_KEY_KP_ENTER)commitEdit();else editor.key(e);return true;}
        if(dropdown!=null){
            Object[] values=dropdown.getValue().getClass().getEnumConstants();
            if(k==GLFW_KEY_ESCAPE)dropdown=null;
            else if(k==GLFW_KEY_ENTER||k==GLFW_KEY_KP_ENTER){assign(dropdown,values[dropdownIndex]);dropdown=null;}
            else if(k==GLFW_KEY_DOWN||k==GLFW_KEY_UP){
                dropdownIndex=Math.clamp(dropdownIndex+(k==GLFW_KEY_DOWN?1:-1),0,values.length-1);
                dropdownScroll=Math.max(dropdownIndex-6,Math.min(dropdownScroll,dropdownIndex));
            }
            return true;
        }
        if((e.modifiers()&GLFW_MOD_CONTROL)!=0 && k==GLFW_KEY_F){search.focused=true;search.selectAll();return true;}
        if(k==GLFW_KEY_ESCAPE){if(search.focused){search.focused=false;return true;}onClose();return true;}
        if(k==GLFW_KEY_TAB){search.focused=false;keyboardFocus=Math.floorMod(keyboardFocus+((e.modifiers()&GLFW_MOD_SHIFT)!=0?-1:1),Math.max(1,hits.size()));return true;}
        if(search.focused){if(k==GLFW_KEY_ENTER)search.focused=false;else search.key(e);return true;}
        if((k==GLFW_KEY_ENTER||k==GLFW_KEY_SPACE) && keyboardFocus>=0 && keyboardFocus<hits.size()){hits.get(keyboardFocus).action.accept(0);return true;}
        if(k==ClickGuiModule.getInstance().bind.getValue().getKey() && System.currentTimeMillis()-opened>100){onClose();return true;}
        return true;
    }
    @Override public boolean charTyped(CharacterEvent e){if(editor!=null)return editor.character(e);return search.character(e);}
    private static String categoryName(Module.Category c) {
        return switch(c){case COMBAT->"战斗 / Combat";case MOVEMENT->"移动 / Movement";case RENDER->"渲染 / Render";case PLAYER->"玩家 / Player";case WORLD->"世界 / World";case CLIENT->"客户端 / Client";case FUNNY->"趣味 / Fun";case HUD->"HUD";};
    }
    private void save(){if(GuizhouWare.configManager!=null)GuizhouWare.configManager.save();}
    @Override public void onClose(){save();minecraft.setScreen(null);}
    @Override public void removed(){dragging=binding=dropdown=null;save();}
    @Override public boolean isPauseScreen(){return false;}
    @Override public void renderBackground(GuiGraphics g,int mouseX,int mouseY,float delta){}
}
