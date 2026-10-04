package dev.guizhou.util.elytra;

import net.minecraft.world.entity.player.Input;
import net.minecraft.world.phys.Vec3;

public final class GroundPassageTest {
    private static int checks;
    public static void main(String[] args) {
        for (int yaw = -180; yaw <= 180; yaw += 15) {
            for (int angle = 0; angle < 360; angle += 15) {
                Vec3 target = new Vec3(Math.cos(Math.toRadians(angle)), 0, Math.sin(Math.toRadians(angle)));
                Input keys = GroundPassage.steer(Input.EMPTY, target, yaw, (a,b)->true);
                check(world(keys, yaw).dot(target) >= Math.cos(Math.toRadians(22.6)), "Walking follows the world direction independently of view yaw");
                check(!keys.jump() && !keys.sprint(), "Passage walking never injects a jump or sprint");
            }
        }
        for (Input manual : new Input[]{new Input(true,false,false,false,false,false,false),
                new Input(false,false,false,false,true,false,false), new Input(false,false,false,false,false,true,false)}) {
            check(GroundPassage.steer(manual,new Vec3(0,0,5),0,(a,b)->true)==manual,"Manual movement/jump/sneak owns the input");
        }
        check(GroundPassage.steer(Input.EMPTY,new Vec3(0,0,5),0,(a,b)->false)==Input.EMPTY,"Blocked passage or unsupported floor cannot add movement");
        check(GroundPassage.steer(Input.EMPTY,new Vec3(Double.NaN,0,1),0,(a,b)->true)==Input.EMPTY,"Invalid heading cannot walk");
        check(GroundPassage.steer(Input.EMPTY,new Vec3(.02,0,0),0,(a,b)->true)==Input.EMPTY,"Reached waypoint does not oscillate");
        Input alternate = GroundPassage.steer(Input.EMPTY,new Vec3(1,0,1),0,(a,b)->Math.abs(b.x)<.01);
        check(alternate.forward() && !alternate.left(),"A blocked diagonal chooses an independently checked advancing direction");
        System.out.println("GroundPassageTest passed: "+checks+" checks");
    }
    private static Vec3 world(Input keys,float yaw) {
        int f=keys.forward()?1:keys.backward()?-1:0, l=keys.left()?1:keys.right()?-1:0;
        double r=Math.toRadians(yaw);
        return new Vec3(-f*Math.sin(r)+l*Math.cos(r),0,f*Math.cos(r)+l*Math.sin(r)).normalize();
    }
    private static void check(boolean b,String message) {checks++;if(!b)throw new AssertionError(message);}
}
