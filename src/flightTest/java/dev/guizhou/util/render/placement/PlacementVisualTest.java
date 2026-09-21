package dev.guizhou.util.render.placement;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import java.util.ArrayList;
import java.util.List;

public final class PlacementVisualTest {
    private static int checks;
    public static void main(String[] args) {
        double reference = response(60, 1, 10);
        for(int fps:new int[]{15,23,30,60,90,144,240,360}) {
            check(Math.abs(response(fps,1,10)-reference)<1e-12,"FPS-dependent smoothing");
            double atHalf=response(fps,2,10);
            check(atHalf>=reference && atHalf<=1,"nonmonotonic response");
        }
        check(PlacementAnimation.blend(0,10)==0,"zero delta moved");
        check(PlacementAnimation.blend(Double.NaN,10)==0,"NaN delta moved");
        check(PlacementAnimation.alpha(.1,.1,1)==1,"hold broken");
        check(Math.abs(PlacementAnimation.alpha(.6,.1,1)-.5)<1e-12,"fade timing");
        check(PlacementAnimation.alpha(1.1,.1,1)==0,"expired entry retained");
        for(double age:new double[]{-1,0,.1,.3,.9,3,100}) {
            double alpha=PlacementAnimation.alpha(age,.1,.3);
            check(alpha>=0 && alpha<=1,"invalid alpha");
        }
        for(Direction d:Direction.Plane.HORIZONTAL) {
            BlockPos p=new BlockPos(-15,64,101);
            var bed=PlacementShapes.bed(p,p.relative(d));
            check(bed.getYsize()==9.0/16,"bed not at actual height");
            check(bed.getXsize()*bed.getZsize()==2,"bed footprint wrong");
            check(bed.contains(p.getX()+.5,64.2,p.getZ()+.5),"foot absent");
            check(bed.contains(p.relative(d).getX()+.5,64.2,p.relative(d).getZ()+.5),"head absent");
        }
        for(int[] viewport:new int[][]{{320,180},{640,360},{1280,720}}) {
            List<LabelLayout.Rect> occupied=new ArrayList<>();
            for(int i=0;i<100;i++) {
                var box=LabelLayout.place(viewport[0]/2.0+(i%3),viewport[1]/2.0,140,26,viewport[0],viewport[1],occupied);
                if(box==null) continue;
                check(box.x()>=4 && box.y()>=4 && box.x()+box.width()<=viewport[0]-4
                        && box.y()+box.height()<=viewport[1]-4,"label escaped screen");
                for(var prior:occupied) check(!box.intersects(prior),"labels overlap");
                occupied.add(box);
            }
            check(occupied.size()>=3,"cannot fit three colocated modules");
        }
        check(LabelLayout.place(0,0,500,20,320,180,List.of())==null,"oversize label allowed");
        check(LabelLayout.place(Double.NaN,0,20,20,320,180,List.of())==null,"NaN position allowed");
        var edge=LabelLayout.place(-100,1000,100,20,320,180,List.of());
        check(edge!=null && edge.x()==4 && edge.y()==156,"edge clamp failed");
        System.out.println("PlacementVisualTest passed: "+checks+" checks (FPS, fade, four bed directions, crowded label layout)");
    }
    private static double response(int fps,int seconds,double smoothness) {
        double x=0;
        for(int i=0;i<fps*seconds;i++) x+=(1-x)*PlacementAnimation.blend(1.0/fps,smoothness);
        return x;
    }
    private static void check(boolean condition,String message) { checks++; if(!condition) throw new AssertionError(message); }
}
