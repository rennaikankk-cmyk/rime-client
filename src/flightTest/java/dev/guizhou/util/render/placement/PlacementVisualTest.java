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
        for(double width:new double[]{.15,.5,.75,1.5}) for(double height:new double[]{.1,.3,.55}) {
            var bounds=new LabelLayout.Rect(0,0,width,height);
            for(int textWidth:new int[]{12,35,80,140}) for(int lines=1;lines<=3;lines++) {
                double scale=LabelLayout.fitScale(textWidth,lines*9,bounds,.025);
                check(scale>0 && scale<=.025,"invalid world text scale");
                check(textWidth*scale<=width+1e-12 && lines*9*scale<=height+1e-12,"text escaped block interior");
            }
        }
        var bounds=new LabelLayout.Rect(0,0,.75,.55);
        check(LabelLayout.fitScale(Double.NaN,9,bounds,.025)==0,"NaN text width accepted");
        check(LabelLayout.fitScale(20,0,bounds,.025)==0,"zero-height text accepted");
        check(new LabelLayout.Rect(0,0,10,10).intersects(new LabelLayout.Rect(5,5,10,10)),"overlap missed");
        check(!new LabelLayout.Rect(0,0,10,10).intersects(new LabelLayout.Rect(14,0,10,10)),"disjoint labels overlap");
        System.out.println("PlacementVisualTest passed: "+checks+" checks (FPS, fade, four bed directions, world text bounds and overlap)");
    }
    private static double response(int fps,int seconds,double smoothness) {
        double x=0;
        for(int i=0;i<fps*seconds;i++) x+=(1-x)*PlacementAnimation.blend(1.0/fps,smoothness);
        return x;
    }
    private static void check(boolean condition,String message) { checks++; if(!condition) throw new AssertionError(message); }
}
