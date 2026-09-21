package dev.guizhou.util.render.placement;

import dev.guizhou.event.impl.render.Render3DEvent;
import dev.guizhou.util.render.RenderUtil;
import dev.guizhou.util.traits.Util;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import java.awt.Color;
import java.util.*;

public final class PlacementVisuals implements Util {
    private static final class Entry {
        AABB box, smooth, labelBounds;
        BlockPos labelBlock;
        Vec3 label;
        String stage, info = "";
        float damage,self;
        boolean main;
        Color accent, fillOverride, lineOverride;
        long stamp, previous, animationStart;
    }
    private final String module;
    private final Map<String,Entry> entries=new LinkedHashMap<>();
    private Object level,player;
    public PlacementVisuals(String module) { this.module=module; }
    public void clear() { entries.clear(); level=mc.level; player=mc.player; }
    public void mark(String id,AABB box,BlockPos block,Vec3 label,float damage,float self,String stage,boolean main,Color accent) {
        if(level!=mc.level || player!=mc.player) clear();
        if(entries.size()>=16 && !entries.containsKey(id)) entries.remove(entries.keySet().iterator().next());
        Entry e=entries.computeIfAbsent(id,k->new Entry());
        if (e.labelBlock == null || !e.labelBlock.equals(block)) e.animationStart=System.nanoTime();
        e.box=box; e.labelBlock=block.immutable(); e.label=label; e.damage=damage; e.self=self;
        e.stage=stage; e.main=main; e.accent=accent; e.stamp=System.nanoTime();
    }
    public void main(BlockPos block,AABB box,Vec3 label,float damage,float self,String stage) {
        mark("main",box,block,label,damage,self,stage,true,null);
    }
    public void touch(String id) { Entry e=entries.get(id); if(e!=null) e.stamp=System.nanoTime(); }
    public void stageAt(BlockPos pos,String stage) { for(Entry e:entries.values()) if(e.labelBlock.equals(pos)) e.stage=stage; }
    public void info(String id,String info) { Entry e=entries.get(id); if(e!=null) e.info=info; }
    public void colors(String id,Color fill,Color line) { Entry e=entries.get(id); if(e!=null) { e.fillOverride=fill; e.lineOverride=line; } }
    public void labelBounds(String id,AABB bounds) { Entry e=entries.get(id); if(e!=null) e.labelBounds=bounds; }
    public void stage(String id,String stage) { Entry e=entries.get(id); if(e!=null) e.stage=stage; }
    public void render(Render3DEvent event,PlacementVisualOptions options,Color fill,Color line,float width,
                       double fade,PlacementVisualOptions.Style style,double smoothing,double gradientHeight,
                       Color pulseColor,double pulseSpeed,double pulseExpand) {
        if(mc.level==null || mc.player==null || mc.level!=level || mc.player!=player) { clear(); return; }
        long now=System.nanoTime();
        Iterator<Entry> it=entries.values().iterator();
        while(it.hasNext()) {
            Entry e=it.next();
            double age=(now-e.stamp)/1e9;
            double alpha=PlacementAnimation.alpha(age,options.hold.getValue(),fade);
            if(alpha<=0) { it.remove(); continue; }
            if(e.smooth==null || smoothing<=1 || e.smooth.getCenter().distanceToSqr(e.box.getCenter())>64) e.smooth=e.box;
            else {
                double f=PlacementAnimation.blend((now-e.previous)/1e9,smoothing);
                e.smooth=lerp(e.smooth,e.box,f);
            }
            e.previous=now;
            AABB box=e.smooth;
            Color fc=e.fillOverride!=null?e.fillOverride:e.accent==null?fill:new Color(e.accent.getRed(),e.accent.getGreen(),e.accent.getBlue(),fill.getAlpha()*e.accent.getAlpha()/255);
            Color lc=e.lineOverride!=null?e.lineOverride:e.accent==null?line:new Color(e.accent.getRed(),e.accent.getGreen(),e.accent.getBlue(),line.getAlpha()*e.accent.getAlpha()/255);
            if(style==PlacementVisualOptions.Style.PULSE) {
                double grow=(Math.sin((now-e.animationStart)/1e9*pulseSpeed*Math.PI*2)+1)*.5*pulseExpand;
                box=box.inflate(grow);
                if(e.accent==null && e.fillOverride==null) { fc=pulseColor; lc=new Color(pulseColor.getRed(),pulseColor.getGreen(),pulseColor.getBlue(),line.getAlpha()); }
            }
            switch(options.occlusion.getValue()) {
                case THROUGH -> draw(event,box,fc,lc,width,style,gradientHeight,alpha,true);
                case VISIBLE_ONLY -> draw(event,box,fc,lc,width,style,gradientHeight,alpha,false);
                case FADED -> {
                    draw(event,box,fc,lc,width,style,gradientHeight,alpha*options.hiddenAlpha.getValue(),true);
                    draw(event,box,fc,lc,width,style,gradientHeight,alpha,false);
                }
            }
            Vec3 shift=e.smooth.getCenter().subtract(e.box.getCenter());
            PlacementLabels.submit(module,e.labelBlock.getX()+":"+e.labelBlock.getY()+":"+e.labelBlock.getZ(),
                    e.labelBounds==null?e.smooth:e.labelBounds.move(shift),e.label.add(shift),e.damage,e.self,e.stage,e.info,e.main,alpha,options);
        }
    }
    public void render(Render3DEvent event,PlacementVisualOptions options,Color fill,Color line,float width,double fade) {
        render(event,options,fill,line,width,fade,options.style.getValue(),options.smooth.getValue()?options.smoothness.getValue():1,
                .7,line,2,.12);
    }
    private static void draw(Render3DEvent event,AABB box,Color fill,Color line,float width,PlacementVisualOptions.Style style,
                             double height,double alpha,boolean through) {
        Color f=alpha(fill,alpha),l=alpha(line,alpha);
        if(style==PlacementVisualOptions.Style.GRADIENT) RenderUtil.placementGradient(event.getMatrix(),box,f,l,width,height,through);
        else {
            if(style!=PlacementVisualOptions.Style.OUTLINE && f.getAlpha()>0) RenderUtil.placementBox(box,f,l,width,through,true,false);
            if(style!=PlacementVisualOptions.Style.FILL && l.getAlpha()>0) RenderUtil.placementBox(box,f,l,width,through,false,true);
        }
    }
    public void arrow(Vec3 from, Vec3 toward, Color color, float width, PlacementVisualOptions options) {
        Vec3 direction=toward.subtract(from).normalize();
        Vec3 start=from.add(direction.scale(.2)), end=toward.subtract(direction.scale(.2));
        if(start.distanceToSqr(end)<.01) return;
        Vec3 side=new Vec3(-direction.z,0,direction.x).scale(.14);
        Vec3 back=end.subtract(direction.scale(.25));
        if(options.occlusion.getValue()!=PlacementVisualOptions.Occlusion.VISIBLE_ONLY) {
            Color c=options.occlusion.getValue()==PlacementVisualOptions.Occlusion.FADED?alpha(color,options.hiddenAlpha.getValue()):color;
            RenderUtil.placementLine(start,end,c,width,true);
            RenderUtil.placementLine(end,back.add(side),c,width,true);
            RenderUtil.placementLine(end,back.subtract(side),c,width,true);
        }
        if(options.occlusion.getValue()!=PlacementVisualOptions.Occlusion.THROUGH) {
            RenderUtil.placementLine(start,end,color,width,false);
            RenderUtil.placementLine(end,back.add(side),color,width,false);
            RenderUtil.placementLine(end,back.subtract(side),color,width,false);
        }
    }
    private static Color alpha(Color c,double a) { return new Color(c.getRed(),c.getGreen(),c.getBlue(),(int)Math.clamp(c.getAlpha()*a,0,255)); }
    private static AABB lerp(AABB a,AABB b,double f) {
        return new AABB(a.minX+(b.minX-a.minX)*f,a.minY+(b.minY-a.minY)*f,a.minZ+(b.minZ-a.minZ)*f,
                a.maxX+(b.maxX-a.maxX)*f,a.maxY+(b.maxY-a.maxY)*f,a.maxZ+(b.maxZ-a.maxZ)*f);
    }
    public static AABB bed(BlockPos foot,BlockPos head) { return PlacementShapes.bed(foot,head); }
}
