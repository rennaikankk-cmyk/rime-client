package dev.guizhou.util.entity;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Headless checks of the pearl owner fallback ported from SlimefunHelper PearlESP. */
public final class PearlOwnershipTest {
    private static int checks;

    public static void main(String[] args) {
        attributionChain();
        idReuseAndTtl();
        System.out.println("PearlOwnershipTest passed: " + checks + " checks");
    }

    private static void attributionChain() {
        PearlOwnership ownership = new PearlOwnership();
        UUID thrower = UUID.randomUUID();
        ownership.onPearlSpawn(1001, 77, 1000L);
        check(ownership.ownerEid(1001) == 77, "spawn packet records the owner entity id");
        check(ownership.resolve(1001).isEmpty(), "no identity before the owner was observed");
        ownership.observePlayer(77, thrower, "Attacker", 1100L);
        Optional<PearlOwnership.Identity> identity = ownership.resolve(1001);
        check(identity.isPresent() && identity.get().name().equals("Attacker") && identity.get().uuid().equals(thrower),
                "observed owner binds to the pearl");

        ownership.onPearlSpawn(1002, 0, 1200L);
        check(ownership.resolve(1002).isEmpty(), "zero owner id means the server knows no owner");
        check(ownership.ownerEid(4242) == -1, "an unseen pearl has no owner id");
    }

    private static void idReuseAndTtl() {
        PearlOwnership ownership = new PearlOwnership();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        ownership.observePlayer(55, first, "First", 1000L);
        ownership.onPearlSpawn(2001, 55, 1000L);
        ownership.observePlayer(55, second, "Second", 2000L);
        Optional<PearlOwnership.Identity> identity = ownership.resolve(2001);
        check(identity.isPresent() && identity.get().uuid().equals(second),
                "a recycled entity id overwrites the old binding");

        ownership.onPearlSpawn(2002, 66, 1000L);
        ownership.observePlayer(66, first, "Gone", 1000L);
        ownership.retain(List.of(2001), 120_000L);
        check(ownership.ownerEid(2002) == -1, "despawned pearls drop with retain");
        check(ownership.resolve(2002).isEmpty(), "expired identity cannot resolve");
        check(ownership.resolve(2001).isEmpty(), "identity expires without refresh");

        ownership.observePlayer(66, first, "Back", 1000L);
        ownership.onPearlSpawn(2003, 66, 1000L);
        ownership.retain(List.of(2003), 20_000L);
        check(ownership.resolve(2003).isPresent(), "refreshed identity survives the ttl");
        ownership.clear();
        check(ownership.resolve(2003).isEmpty() && ownership.ownerEid(2003) == -1,
                "dimension or server change clears everything");
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}
