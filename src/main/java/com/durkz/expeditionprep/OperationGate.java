package com.durkz.expeditionprep;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

final class OperationGate {
    private final Map<UUID, Long> lastGesture = new ConcurrentHashMap<>();
    private final Set<UUID> networks = ConcurrentHashMap.newKeySet();
    boolean gesture(UUID player, long now) {
        AtomicBoolean accepted = new AtomicBoolean();
        lastGesture.compute(player, (id, previous) -> {
            if (previous != null && now - previous < 750_000_000L) return previous;
            accepted.set(true); return now;
        });
        return accepted.get();
    }
    boolean enter(UUID network) { return networks.add(network); }
    void leave(UUID network) { networks.remove(network); }
    void disconnect(UUID player) { lastGesture.remove(player); }
    void clear() { lastGesture.clear(); networks.clear(); }
}
