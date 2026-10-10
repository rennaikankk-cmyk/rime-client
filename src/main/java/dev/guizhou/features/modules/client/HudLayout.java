package dev.guizhou.features.modules.client;

import java.util.ArrayList;
import java.util.List;

/** Places default cards near their anchor while reserving other HUD cards and the hotbar. */
public final class HudLayout {
    private HudLayout() {}
    public record Rect(int x,int y,int w,int h) {
        public boolean intersects(Rect r) {return x<r.x+r.w && x+w>r.x && y<r.y+r.h && y+h>r.y;}
    }
    public static Rect place(Rect preferred,int width,int height,List<Rect> occupied) {
        int maxX=Math.max(0,width-preferred.w-4),maxY=Math.max(0,height-preferred.h-4);
        Rect start=new Rect(Math.clamp(preferred.x,0,maxX),Math.clamp(preferred.y,0,maxY),preferred.w,preferred.h);
        if(clear(start,occupied))return start;
        List<Integer> xs=new ArrayList<>(List.of(start.x,4,maxX)),ys=new ArrayList<>(List.of(start.y,4,maxY));
        for(Rect r:occupied){xs.add(r.x-start.w-4);xs.add(r.x+r.w+4);ys.add(r.y-start.h-4);ys.add(r.y+r.h+4);}
        Rect best=null;long score=Long.MAX_VALUE;
        for(int x:xs)for(int y:ys){
            if(x<0||y<0||x>maxX||y>maxY)continue;
            Rect candidate=new Rect(x,y,start.w,start.h);
            long distance=Math.abs(x-start.x)+Math.abs(y-start.y);
            if(distance<score && clear(candidate,occupied)){best=candidate;score=distance;}
        }
        // Very small screens / oversized user lists can be impossible to pack. Keep the anchor stable.
        return best==null?start:best;
    }
    private static boolean clear(Rect rect,List<Rect> occupied){return occupied.stream().noneMatch(rect::intersects);}
}
