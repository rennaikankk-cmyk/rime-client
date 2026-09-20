package dev.guizhou.features.modules.client;

import java.util.ArrayList;
import java.util.List;

public final class HudLayoutTest {
    public static void main(String[] args) {
        int checks=0;
        for(int[] viewport:new int[][]{{427,240},{480,270},{854,480}}) {
            int sw=viewport[0],sh=viewport[1];
            List<HudLayout.Rect> used=new ArrayList<>(List.of(new HudLayout.Rect(sw-210,(sh-197)/2,204,197),new HudLayout.Rect(sw/2-94,sh-42,188,42)));
            for(HudLayout.Rect preferred:List.of(new HudLayout.Rect(sw/2,sh-63,22,22),new HudLayout.Rect(sw/2+95,sh-112,48,72),new HudLayout.Rect(sw-150,sh-28,146,24),new HudLayout.Rect(sw-78,sh-56,74,24))) {
                HudLayout.Rect r=HudLayout.place(preferred,sw,sh,used);
                if(r.x()<0||r.y()<0||r.x()+r.w()>sw||r.y()+r.h()>sh)throw new AssertionError("Card outside viewport");checks++;
                if(used.stream().anyMatch(r::intersects))throw new AssertionError("Card overlaps reserved HUD: "+r);checks++;
                if(!r.equals(HudLayout.place(preferred,sw,sh,used)))throw new AssertionError("Unstable placement");checks++;
                used.add(r);
            }
        }
        System.out.println("HudLayoutTest passed: "+checks+" checks");
    }
}
