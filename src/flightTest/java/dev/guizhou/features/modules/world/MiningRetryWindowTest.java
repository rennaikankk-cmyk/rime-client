package dev.guizhou.features.modules.world;

public final class MiningRetryWindowTest {
    public static void main(String[] args) {
        MiningRetryWindow retry = new MiningRetryWindow();
        check(retry.ready(2), "first submission must be immediately eligible");
        // A cancelled packet/swap never calls submitted(), so it consumes no budget.
        for (int i = 0; i < 100; i++) retry.tick();
        check(!retry.exhausted(1), "failed sends consumed retry budget");
        retry.submitted();
        check(!retry.ready(2), "same-tick duplicate STOP");
        retry.tick();
        check(!retry.ready(2), "retry before configured interval");
        retry.tick();
        check(retry.ready(2), "retry not released at configured interval");
        retry.submitted();
        check(retry.exhausted(2), "attempt cap must terminate rejected STOP loop");
        retry.reset(); // observed air / new target / session reset
        check(retry.ready(2) && !retry.exhausted(2), "new break inherited stale retries");
        retry.submitted();
        check(!retry.ready(0), "zero delay allowed same-tick retry");
        retry.tick();
        check(retry.ready(0), "minimum one-tick delay should expire");
        System.out.println("MiningRetryWindowTest passed");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
