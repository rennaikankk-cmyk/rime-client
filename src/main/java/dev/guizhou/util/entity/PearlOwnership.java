package dev.guizhou.util.entity;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Attributes ender pearls to throwers when the live owner entity is gone, ported from
 * SlimefunHelper PearlESP: pearl spawn packets carry the owner entity id, and observations of
 * live players bind that id to a UUID and name. A recycled entity id overwrites whatever it
 * pointed at before, and everything expires so stale attributions die out.
 */
public final class PearlOwnership {

    /** Who a pearl belongs to, when either chain knows it. */
    public record Identity(UUID uuid, String name) {}

    private record Spawn(int ownerEid, long atMs) {}
    private record Observation(UUID uuid, String name, long atMs) {}

    private static final long PEARL_TTL_MS = 60_000;
    private static final long IDENTITY_TTL_MS = 60_000;

    private final Map<Integer, Spawn> pearls = new HashMap<>();
    private final Map<Integer, Observation> identities = new HashMap<>();

    /** Pearl spawn packet: pearl entity id -> owner entity id (0 = none the server knows of). */
    public void onPearlSpawn(int pearlId, int ownerEid, long nowMs) {
        if (pearlId < 0) return;
        pearls.put(pearlId, new Spawn(Math.max(0, ownerEid), nowMs));
    }

    /** Bind an entity id to a live identity; a recycled id overwrites the old binding. */
    public void observePlayer(int entityId, UUID uuid, String name, long nowMs) {
        if (entityId < 0 || uuid == null) return;
        identities.put(entityId, new Observation(uuid, name, nowMs));
    }

    /** The owner entity id recorded at spawn; -1 when the pearl was never seen spawning. */
    public int ownerEid(int pearlId) {
        Spawn spawn = pearls.get(pearlId);
        return spawn == null ? -1 : spawn.ownerEid();
    }

    /** The identity behind a pearl, when the owner id was bound to a player. */
    public Optional<Identity> resolve(int pearlId) {
        Spawn spawn = pearls.get(pearlId);
        if (spawn == null || spawn.ownerEid() == 0) return Optional.empty();
        Observation observation = identities.get(spawn.ownerEid());
        if (observation == null) return Optional.empty();
        return Optional.of(new Identity(observation.uuid(), observation.name()));
    }

    /** Drops pearls that left the world and identity bindings not refreshed within the TTL. */
    public void retain(Collection<Integer> alivePearls, long nowMs) {
        pearls.keySet().retainAll(alivePearls);
        pearls.values().removeIf(spawn -> nowMs - spawn.atMs() >= PEARL_TTL_MS);
        identities.values().removeIf(observation -> nowMs - observation.atMs() >= IDENTITY_TTL_MS);
    }

    public void clear() {
        pearls.clear();
        identities.clear();
    }
}
