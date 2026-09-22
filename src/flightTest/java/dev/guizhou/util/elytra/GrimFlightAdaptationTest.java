package dev.guizhou.util.elytra;

public final class GrimFlightAdaptationTest {
    private static int checks;
    public static void main(String[] args) {
        var policy = new GrimFlightAdaptation();
        check(policy.budget()==1, "new session begins with supported envelope");
        check(policy.corrected(10)==10 && near(policy.budget(),.7), "first correction backs off");
        check(policy.corrected(10)==10 && near(policy.budget(),.7), "duplicate delivery cannot punish twice");
        check(policy.corrected(30)==20, "repeated correction extends pause");
        check(policy.corrected(60)==40, "third correction extends pause");
        check(policy.corrected(100)==60 && policy.budget()==.25, "bounded floor and pause");
        for(int t=101;t<=300;t++)policy.applied(t,false);
        check(policy.budget()==.25, "idle or unsafe motion does not restore speed");
        for(int t=301;t<400;t++)policy.applied(t,true);
        check(policy.budget()==.25, "wait for full stable period");
        policy.applied(400,true);
        check(near(policy.budget(),.30), "gradual recovery starts after five stable seconds at 20 TPS");
        policy.applied(400,true);
        check(near(policy.budget(),.30), "repeated hook does not accelerate recovery");
        for(int t=401;t<=1000;t++)policy.applied(t,true);
        check(policy.budget()==1, "stable server can recover full headroom");
        check(policy.corrected(1001)==10, "isolated correction starts a fresh streak");
        policy.reset();
        check(policy.budget()==1, "different connection has independent budget");
        policy.corrected(20);policy.applied(1,true);
        check(policy.budget()==1, "tick rewind clears old session");
        System.out.println("GrimFlightAdaptationTest passed: "+checks+" checks");
    }
    private static boolean near(double a,double b){return Math.abs(a-b)<1e-9;}
    private static void check(boolean ok,String message){checks++;if(!ok)throw new AssertionError(message);}
}
