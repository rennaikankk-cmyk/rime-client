package dev.guizhou.util.combat;

public final class CrystalAttackWindowTest {
    public static void main(String[] args) {
        CrystalAttackWindow window = new CrystalAttackWindow();
        expect(true, window.canAttack(40, 100), "spawn may attack new crystal");
        expect(true, window.canAttack(40, 100), "rejected rotation does not consume attempt");
        window.markSent(40, 100);
        expect(false, window.canAttack(40, 100), "tick does not repeat spawn attack");
        expect(false, window.canAttack(40, 100), "reactor does not repeat spawn attack");
        expect(true, window.canAttack(41, 100), "distinct crystal remains eligible");
        window.markSent(41, 100);
        expect(false, window.canAttack(40, 100), "second crystal does not forget first");
        expect(false, window.canAttack(41, 100), "second crystal also deduplicated");
        expect(true, window.canAttack(40, 101), "surviving crystal may retry next tick");
        window.markSent(40, 101);
        window.clear();
        expect(true, window.canAttack(40, 101), "session reset clears reused entity id");
        window.markSent(40, 101);
        expect(true, window.canAttack(40, 0), "player tick reset cannot hold a stale attempt");
        System.out.println("CrystalAttackWindowTest: all checks passed");
    }

    private static void expect(boolean expected, boolean actual, String message) {
        if (expected != actual) throw new AssertionError(message + ": expected " + expected + ", got " + actual);
    }
}
