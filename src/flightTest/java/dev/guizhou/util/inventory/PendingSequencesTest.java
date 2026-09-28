package dev.guizhou.util.inventory;

public final class PendingSequencesTest {
    private static int checks;
    public static void main(String[] args) {
        var pending = new PendingSequences<String>();
        pending.submitted(1, "potion", 10); pending.submitted(2, "rocket", 10);
        check(pending.any("potion"::equals), "Consecutive actions retain separate snapshots");
        pending.acknowledge(1);
        check(pending.get(1) == null && pending.get(2).equals("rocket"), "Cumulative ACK does not consume later action");
        pending.acknowledge(0); pending.submitted(1, "late", 11);
        check(pending.size() == 1, "Stale ACK or late submission cannot revive acknowledged work");
        pending.expire(49); check(pending.size() == 1, "Pending action survives before timeout");
        pending.expire(50); check(pending.size() == 0, "Missing ACK has a bounded lifetime");
        for (int i = 3; i < 100; i++) pending.submitted(i, "v" + i, 60);
        check(pending.size() == 64 && pending.get(3) == null && pending.get(99).equals("v99"), "Capacity retains newest submissions");
        pending.clear(); pending.submitted(0, "new world", 1);
        check(pending.size() == 1, "World switch permits restarted sequences");
        pending.expire(0); check(pending.size() == 0, "Tick reset cannot retain old pending actions");
        System.out.println("PendingSequencesTest passed: " + checks + " checks");
    }
    private static void check(boolean yes, String message) { checks++; if (!yes) throw new AssertionError(message); }
}
