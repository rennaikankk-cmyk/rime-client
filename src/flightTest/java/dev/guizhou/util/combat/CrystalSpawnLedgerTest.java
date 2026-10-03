package dev.guizhou.util.combat;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

public final class CrystalSpawnLedgerTest {
    public static void main(String[] args) {
        var ledger = new CrystalSpawnLedger();
        var air = new BlockPos(2,65,4);
        var center = new Vec3(2.5,65,4.5);
        ledger.spawned(1, center); ledger.spawned(2,center);
        check(air.equals(ledger.removed(1)) && ledger.occupied(air), "one removal cannot free a duplicate crystal cell");
        check(ledger.removed(99) == null && ledger.occupied(air), "unrelated removal cannot free a cell");
        check(air.equals(ledger.removed(2)) && !ledger.occupied(air), "last confirmed removal frees cell");
        check(CrystalSpawnLedger.matchesBlast(air,center), "exact bottom-center explosion matches");
        check(!CrystalSpawnLedger.matchesBlast(air,center.add(0,1,0)), "old incorrect Y must not match");
        check(!CrystalSpawnLedger.matchesBlast(air,center.add(3,0,0)), "nearby unrelated explosion rejected");
        ledger.spawned(1,center);ledger.spawned(1,new Vec3(20.5,65,4.5));
        check(!ledger.occupied(air), "entity id reuse replaces stale cell");
        ledger.spawned(3,new Vec3(Double.NaN,0,0));check(ledger.removed(3)==null,"invalid coordinate ignored");
        for (int id=4;id<600;id++) ledger.spawned(id,new Vec3(id,65,0));
        check(ledger.removed(1)==null,"bounded history evicts old identity");
        ledger.clear();check(ledger.removed(599)==null,"world/reset discards confirmations");
        System.out.println("CrystalSpawnLedgerTest passed: 10 checks");
    }
    static void check(boolean value,String message) { if (!value) throw new AssertionError(message); }
}
