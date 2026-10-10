package dev.guizhou.util.render;

public final class HudAppearanceTest {
    private static int checks;
    public static void main(String[] args) {
        HudAppearance original = new HudAppearance(0xFF171717,0xFFFAFAFA,0xFFE5E5E5,1);
        for(int color:new int[]{0xFF171717,0xFF262626,0xFF343434,0xFFFAFAFA,0xFFA1A1A1,0xFFE5E5E5,0x1AFFFFFF,0xFFF87171,0xFFFFFFFF})
            check(original.map(color)==color,"Default TargetHUD palette must stay unchanged");
        HudAppearance graphite = new HudAppearance(0xFF151820,0xFFECEEF4,0xFF8B7CFF,1);
        for(int color:new int[]{0xE6151820,0xFF1D212C,0xFF2B3140,0xFFECEEF4,0xFF8A92A6,0xFF5D6478,0xFF8B7CFF,0xFF5CC8FF,0xFF4ADE80,0xFFFBBF24,0xFFF87171})
            check(graphite.map(color)==color,"Default graphite palette must stay unchanged");
        HudAppearance custom = new HudAppearance(0xFF102030,0xFFF0F0F0,0xFF20C060,1);
        check(custom.map(0xFF8B7CFF)==0xFF20C060,"Accent follows the element accent");
        int secondStop=custom.map(0xFF5CC8FF);
        check(secondStop!=0xFF5CC8FF && (secondStop>>>24)==0xFF && ((secondStop>>8)&255)>0xC0,"Brand second stop follows a custom accent, lighter");
        int subtle=custom.map(0xFF5D6478);
        check(subtle!=0xFF5D6478 && (subtle&255)>0x30 && (subtle&255)<0xF0,"Subtle text derives from custom background/foreground");
        HudAppearance half = new HudAppearance(0xFF112233,0xFFAACCEE,0xFF42AA99,.5f);
        check(half.map(0xFF171717)==0x80112233,"Background opacity");
        check(half.map(0x80171717)==0x40112233,"Notification fade composes with opacity");
        check(half.map(0xFFFAFAFA)==0xFFAACCEE,"Text stays readable independently");
        check(half.map(0xFFF87171)==0xFFF87171,"Danger color stays semantic");
        check(half.map(0xFFFFFFFF)==0xFFFFFFFF,"Skin tint unchanged");
        check(new HudAppearance(0xFF171717,0xFFFAFAFA,0xFFE5E5E5,0).map(0x1AFFFFFF)==0x00FFFFFF,"No opaque border when transparent");
        check(new HudAppearance(0x80112233,0xFFFAFAFA,0xFFE5E5E5,.5f).map(0xFF171717)==0x40112233,"Color alpha composes");
        var prior=HudAppearance.use(half);
        check(HudAppearance.color(0xFF171717)==0x80112233,"Scoped palette active");
        HudAppearance.use(prior);
        check(HudAppearance.color(0xFF171717)==0xFF171717,"Does not tint menu or another HUD");
        for(float requested:new float[]{.5f,1,2,Float.NaN,Float.POSITIVE_INFINITY})
            for(int[] size:new int[][]{{204,197,427,240},{48,72,854,480},{600,400,320,200}}) {
                float scale=HudAppearance.fitScale(requested,size[0],size[1],size[2],size[3]);
                check(Float.isFinite(scale)&&scale>0,"Finite scale");
                check(Math.round(size[0]*scale)<=size[2]&&Math.round(size[1]*scale)<=size[3],"Scaled content/hit rectangle fits viewport");
            }
        System.out.println("HudAppearanceTest passed: "+checks+" checks");
    }
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);checks++;}
}
