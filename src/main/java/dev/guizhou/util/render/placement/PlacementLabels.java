package dev.guizhou.util.render.placement;

import dev.guizhou.util.render.MatrixCapture;
import dev.guizhou.util.traits.Util;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.TextGizmo;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import java.awt.Color;
import java.util.*;

/** Compact annotations stay inside their projected block, never displaced into floating panels. */
public final class PlacementLabels implements Util {
    private record Label(String module, String key, AABB box, Vec3 position, List<String> rows,
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
        double distance = mc.gameRenderer.getMainCamera().getPosition().distanceTo(point);
        if (distance > options.maxDistance.getValue()) return;
        boolean visible = options.occlusion.getValue() == PlacementVisualOptions.Occlusion.THROUGH || visible(box, point);
        if (!visible && options.occlusion.getValue() == PlacementVisualOptions.Occlusion.VISIBLE_ONLY) return;
        if (!visible && options.occlusion.getValue() == PlacementVisualOptions.Occlusion.FADED) fade *= options.hiddenAlpha.getValue();
        String prefix = "";
        StringJoiner values = new StringJoiner(" ");
        if (main && options.damage.getValue() && Float.isFinite(damage)) values.add(String.format(Locale.ROOT,"%.1f",damage));
        if (main && options.selfDamage.getValue() && Float.isFinite(self)) values.add(String.format(Locale.ROOT,"S%.1f",self));
        if (options.distance.getValue()) values.add(String.format(Locale.ROOT,"%.1fm",distance));
        List<String> rows = new ArrayList<>(2);
        if (values.length() > 0) rows.add((prefix+" "+values).trim());
        else if (info != null && !info.isBlank()) rows.add((prefix+" "+info).trim());
        if ((options.stage.getValue() || !main) && stage != null && !stage.isBlank())
            rows.add(rows.isEmpty() ? (prefix+" "+stage).trim() : stage);
        if (rows.isEmpty()) return;
        Color c = options.textColor.getValue();
        int a = (int)Math.clamp(c.getAlpha()*fade,0,255);
        if (a < 5) return;
        labels.add(new Label(module,key,box,point,List.copyOf(rows),options.textScale.getValue(),
                (a<<24)|(c.getRGB()&0xffffff),options.background.getValue(),distance,main));
    }
    private static String abbreviation(String module) {
        return switch(module) {
            case "水晶" -> "C"; case "锚" -> "A"; case "床" -> "B"; case "TP水晶" -> "TC";
            case "活塞水晶" -> "PC"; case "发包挖" -> "PM"; case "自动挖" -> "AM"; case "补玻璃" -> "G";
            default -> module;
        };
    }
    private static boolean visible(AABB ownBox, Vec3 point) {
        Vec3 eye = mc.gameRenderer.getMainCamera().getPosition();
        var hit = mc.level.clip(new ClipContext(eye,point,ClipContext.Block.COLLIDER,ClipContext.Fluid.NONE,mc.player));
        return hit.getType() == HitResult.Type.MISS || ownBox.inflate(.002).contains(hit.getLocation())
                || hit.getBlockPos().equals(net.minecraft.core.BlockPos.containing(point));
    }
    private static Vec3 vector(org.joml.Vector3fc v) { return new Vec3(v.x(),v.y(),v.z()); }
    private static LabelLayout.Rect screenRect(Vec3 center,Vec3 left,Vec3 up,double w,double h) {
        double minX=Double.POSITIVE_INFINITY,minY=minX,maxX=Double.NEGATIVE_INFINITY,maxY=maxX;
        for(int x:new int[]{-1,1}) for(int y:new int[]{-1,1}) {
            Vec3 corner=center.add(left.scale(x*w/2)).add(up.scale(y*h/2));
            float[] p=MatrixCapture.worldToScreen(corner.x,corner.y,corner.z);
            if(p==null) return null;
            minX=Math.min(minX,p[0]); maxX=Math.max(maxX,p[0]);
            minY=Math.min(minY,p[1]); maxY=Math.max(maxY,p[1]);
        }
        return new LabelLayout.Rect(minX,minY,maxX-minX,maxY-minY);
    }
    public static void draw() {
        try {
            if (mc.level == null || mc.player == null || level != mc.level || player != mc.player
                    || mc.options.hideGui || System.nanoTime()-frame > 250_000_000L) return;
            labels.sort(Comparator.comparing((Label x) -> !x.main).thenComparingDouble(Label::distance)
                    .thenComparing(Label::module).thenComparing(Label::key));
            Map<String,List<Label>> groups = new LinkedHashMap<>();
            for (Label label : labels) groups.computeIfAbsent(label.key,k -> new ArrayList<>()).add(label);
            List<LabelLayout.Rect> occupied = new ArrayList<>();
            var camera=mc.gameRenderer.getMainCamera();
            Vec3 left=vector(camera.rotation().transform(new org.joml.Vector3f(1f,0f,0f))),up=vector(camera.rotation().transform(new org.joml.Vector3f(0f,1f,0f))),forward=vector(camera.rotation().transform(new org.joml.Vector3f(0f,0f,1f)));
            for (List<Label> group : groups.values()) {
                Label label=group.getFirst();
                List<String> rows=new ArrayList<>();
                if(group.size()==1) rows.addAll(label.rows);
                else for(Label member:group) rows.add(abbreviation(member.module)+" "+member.rows.getFirst());
                if(rows.size()>3) rows=rows.subList(0,3);
                // Fixed world units, just like vanilla nameplates: perspective handles distance and FOV.
                // Bound long combined labels to the owning block instead of moving them outside it.
                double blockWidth=Math.min(label.box.getXsize(),label.box.getZsize())*.75;
                double blockHeight=Math.max(.18,label.box.getYsize()*.55);
                var interior=new LabelLayout.Rect(0,0,blockWidth,blockHeight);
                double unit=LabelLayout.fitScale(width(rows),rows.size()*9,interior,.025*label.scale);
                double w=width(rows)*unit,h=rows.size()*9*unit;
                LabelLayout.Rect rect=screenRect(label.position,left,up,w,h);
                if(rect==null) continue;
                if((rect.height()<3 || overlaps(rect,occupied)) && rows.size()>1) {
                    rows=List.of(rows.getFirst()); h=9*unit; w=width(rows)*unit;
                    rect=screenRect(label.position,left,up,w,h);
                }
                if(rect==null || rect.height()<2 || overlaps(rect,occupied)) continue;
                occupied.add(rect);
                if(label.background) {
                    int color=(((label.color>>>24)*100/255)<<24)|0x101520;
                    Vec3 a=label.position.add(left.scale(w/2)).add(up.scale(h/2));
                    Vec3 b=label.position.subtract(left.scale(w/2)).add(up.scale(h/2));
                    Vec3 c=label.position.subtract(left.scale(w/2)).subtract(up.scale(h/2));
                    Vec3 d=label.position.add(left.scale(w/2)).subtract(up.scale(h/2));
                    Gizmos.rect(a,b,c,d,GizmoStyle.fill(color)).setAlwaysOnTop();
                }
                for(int i=0;i<rows.size();i++) {
                    Vec3 at=label.position.add(up.scale(h/2-i*9*unit));
                    int shadow=(label.color&0xff000000)|0x151515;
                    Gizmos.billboardText(rows.get(i),at.subtract(left.scale(unit*.6)).subtract(up.scale(unit*.6)),
                            TextGizmo.Style.forColorAndCentered(shadow).withScale((float)(unit*16))).setAlwaysOnTop();
                    Gizmos.billboardText(rows.get(i),at.subtract(forward.scale(.002)),
                            TextGizmo.Style.forColorAndCentered(label.color).withScale((float)(unit*16))).setAlwaysOnTop();
                }
            }
        } finally { labels.clear(); }
    }
    private static boolean overlaps(LabelLayout.Rect rect,List<LabelLayout.Rect> occupied) { return occupied.stream().anyMatch(rect::intersects); }
    private static int width(List<String> rows) { return rows.stream().mapToInt(mc.font::width).max().orElse(0); }
}
