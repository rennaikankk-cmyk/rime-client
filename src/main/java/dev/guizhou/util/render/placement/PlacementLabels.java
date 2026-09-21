package dev.guizhou.util.render.placement;

import dev.guizhou.util.render.MatrixCapture;
import dev.guizhou.util.traits.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import java.awt.Color;
import java.util.*;

/** Frame-local world annotations, laid out together after all 3D module callbacks. */
public final class PlacementLabels implements Util {
    private record Label(String module, String key, Vec3 position, List<String> rows,
                         float scale, int color, boolean background, double distance, boolean main) {}
    private static final List<Label> labels = new ArrayList<>();
    private static Object level, player;
    private static long frame;
    private PlacementLabels() {}

    public static void beginFrame() {
        labels.clear(); level = mc.level; player = mc.player; frame = System.nanoTime();
    }
    public static void submit(String module, String key, AABB box, Vec3 point, float damage, float self,
                              String stage, String info, boolean main, double fade, PlacementVisualOptions options) {
        if (mc.level == null || mc.player == null || !options.info.getValue() || labels.size() >= 64) return;
        if (!main && !options.parts.getValue()) return;
        double distance = mc.gameRenderer.getMainCamera().position().distanceTo(point);
        if (distance > options.maxDistance.getValue()) return;
        boolean visible = options.occlusion.getValue() == PlacementVisualOptions.Occlusion.THROUGH || visible(box, point);
        if (!visible && options.occlusion.getValue() == PlacementVisualOptions.Occlusion.VISIBLE_ONLY) return;
        if (!visible && options.occlusion.getValue() == PlacementVisualOptions.Occlusion.FADED) fade *= options.hiddenAlpha.getValue();
        List<String> rows = new ArrayList<>();
        StringJoiner values = new StringJoiner(" · ");
        if (main && options.damage.getValue() && Float.isFinite(damage)) values.add(String.format(Locale.ROOT,(module.equals("水晶") || module.equals("锚")) ? "预计总伤 %.1f" : "预计伤害 %.1f",damage));
        if (main && options.selfDamage.getValue() && Float.isFinite(self)) values.add(String.format(Locale.ROOT,"自伤 %.1f",self));
        if (options.distance.getValue()) values.add(String.format(Locale.ROOT,"%.1fm",distance));
        if (values.length() > 0) rows.add(values.toString());
        if (info != null && !info.isBlank()) rows.add(info);
        if (options.stage.getValue() && stage != null && !stage.isBlank()) rows.add(stage);
        if (rows.isEmpty()) return;
        Color c = options.textColor.getValue();
        int a = (int)Math.clamp(c.getAlpha()*fade,0,255);
        if (a < 5) return;
        labels.add(new Label(module,key,point,List.copyOf(rows),options.textScale.getValue(),
                (a<<24)|(c.getRGB()&0xffffff),options.background.getValue(),distance,main));
    }
    private static boolean visible(AABB ownBox, Vec3 point) {
        Vec3 eye = mc.gameRenderer.getMainCamera().position();
        var hit = mc.level.clip(new ClipContext(eye,point,ClipContext.Block.COLLIDER,ClipContext.Fluid.NONE,mc.player));
        return hit.getType() == HitResult.Type.MISS || ownBox.inflate(.002).contains(hit.getLocation())
                || hit.getBlockPos().equals(net.minecraft.core.BlockPos.containing(point));
    }
    public static void draw(GuiGraphics g) {
        try {
            if (mc.level == null || mc.player == null || level != mc.level || player != mc.player
                    || mc.options.hideGui || System.nanoTime()-frame > 250_000_000L) return;
            labels.sort(Comparator.comparing((Label x) -> !x.main).thenComparingDouble(Label::distance)
                    .thenComparing(Label::module).thenComparing(Label::key));
            Map<String,List<Label>> groups = new LinkedHashMap<>();
            for (Label label : labels) groups.computeIfAbsent(label.key,k -> new ArrayList<>()).add(label);
            List<LabelLayout.Rect> occupied = new ArrayList<>();
            int sw=mc.getWindow().getGuiScaledWidth(), sh=mc.getWindow().getGuiScaledHeight();
            for (List<Label> group : groups.values()) {
                Label label=group.getFirst();
                float[] at=MatrixCapture.worldToScreen(label.position.x,label.position.y,label.position.z);
                if (at==null) continue;
                List<String> rows=new ArrayList<>();
                // Same block: one panel with per-module identity, retaining each module's own estimate.
                for (Label member : group) {
                    if (group.size()>1) rows.add(member.module+" · "+member.rows.getFirst());
                    else rows.addAll(member.rows);
                }
                if (rows.size()>5) rows=rows.subList(0,5);
                float scale=label.scale;
                double width=width(rows)*scale+8, height=rows.size()*11*scale+6;
                LabelLayout.Rect rect=LabelLayout.place(at[0],at[1],width,height,sw,sh,occupied);
                if (rect==null && rows.size()>1) {
                    rows=List.of(rows.getFirst()); width=width(rows)*scale+8; height=11*scale+6;
                    rect=LabelLayout.place(at[0],at[1],width,height,sw,sh,occupied);
                }
                if (rect==null) continue;
                occupied.add(rect);
                int alpha=(label.color>>>24);
                if (label.background) g.fill((int)rect.x(),(int)rect.y(),(int)Math.ceil(rect.x()+rect.width()),
                        (int)Math.ceil(rect.y()+rect.height()),((alpha*135/255)<<24)|0x101520);
                // A short connector points displaced panels back to their actual block.
                double cx=rect.x()+rect.width()/2, cy=rect.y()+rect.height()/2;
                if (Math.abs(cx-at[0])+Math.abs(cy-at[1])>height) {
                    int x=(int)at[0], y=(int)at[1], end=(int)(cy<y ? rect.y()+rect.height() : rect.y());
                    boolean crosses=false;
                    LabelLayout.Rect link=new LabelLayout.Rect(Math.min(x,cx),Math.min(y,end),Math.max(1,Math.abs(x-cx)),Math.max(1,Math.abs(y-end)));
                    for(int i=0;i<occupied.size()-1;i++) if(link.intersects(occupied.get(i))) { crosses=true; break; }
                    if(!crosses) {
                        g.fill(x,Math.min(y,end),x+1,Math.max(y,end)+1,label.color);
                        g.fill((int)Math.min(x,cx),end,(int)Math.max(x,cx)+1,end+1,label.color);
                    }
                }
                g.pose().pushMatrix();
                try {
                    g.pose().translate((float)rect.x()+4,(float)rect.y()+3);
                    g.pose().scale(scale,scale);
                    for(int i=0;i<rows.size();i++) g.drawString(mc.font,rows.get(i),0,i*11,label.color,true);
                } finally { g.pose().popMatrix(); }
            }
        } finally { labels.clear(); }
    }
    private static int width(List<String> rows) { return rows.stream().mapToInt(mc.font::width).max().orElse(0); }
}
