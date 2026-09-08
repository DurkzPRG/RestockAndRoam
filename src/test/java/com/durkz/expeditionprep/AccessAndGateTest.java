package com.durkz.expeditionprep;

import org.junit.jupiter.api.Test;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.function.Predicate;
import static org.junit.jupiter.api.Assertions.*;

class AccessAndGateTest {
    public enum Permission { INTERACT_CHEST }
    public static final class Party {
        final boolean allowed;
        Party(boolean allowed) { this.allowed = allowed; }
        public boolean isChestInteractEnabled() { return allowed; }
    }
    public static final class Registry {
        boolean allowed = true;
        public boolean isAllowedToInteract(UUID player, String world, int x, int z, Predicate<Party> predicate, Permission permission) {
            return permission == Permission.INTERACT_CHEST && world.equals("Test") && x == 2 && z == 3 && predicate.test(new Party(allowed));
        }
    }
    @Test void protectionChecksEveryCoordinateAndRevocationWithoutCaching() {
        var registry = new Registry(); UUID player = UUID.randomUUID();
        var position = new ProfileStore.Depot(2,80,3);
        assertTrue(ChestAccess.query(registry,Party.class,Permission.class,player,"Test",position));
        assertFalse(ChestAccess.query(registry,Party.class,Permission.class,player,"Test",new ProfileStore.Depot(4,80,3)));
        registry.allowed = false;
        assertFalse(ChestAccess.query(registry,Party.class,Permission.class,player,"Test",position));
    }
    @Test void incompatibleProtectionApiFailsClosed() {
        assertFalse(ChestAccess.query(new Object(),Party.class,Permission.class,UUID.randomUUID(),"Test",new ProfileStore.Depot(2,80,3)));
        assertFalse(ChestAccess.query(new Registry(),Object.class,Permission.class,UUID.randomUUID(),"Test",new ProfileStore.Depot(2,80,3)));
    }
    @Test void gesturesHave750msCooldownAndPlayersAreIndependent() {
        var gate = new OperationGate(); UUID a=UUID.randomUUID(),b=UUID.randomUUID();
        assertTrue(gate.gesture(a,0)); assertFalse(gate.gesture(a,749_999_999)); assertTrue(gate.gesture(b,1));
        assertTrue(gate.gesture(a,750_000_000)); assertFalse(gate.gesture(a,750_000_001));
        gate.disconnect(a); assertTrue(gate.gesture(a,750_000_002));
    }
    @Test void concurrentGuestsCannotEnterSameNetworkUntilReleased() throws Exception {
        var gate = new OperationGate(); var network = UUID.randomUUID();
        try (var executor = Executors.newFixedThreadPool(2)) {
            CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
            var first = executor.submit(() -> {
                boolean accepted=gate.enter(network); entered.countDown();
                try { release.await(5,TimeUnit.SECONDS); } finally { gate.leave(network); }
                return accepted;
            });
            assertTrue(entered.await(5,TimeUnit.SECONDS));
            try {
                assertFalse(executor.submit(() -> gate.enter(network)).get(5,TimeUnit.SECONDS));
                assertTrue(gate.enter(UUID.randomUUID()));
            } finally { release.countDown(); }
            assertTrue(first.get(5,TimeUnit.SECONDS)); assertTrue(gate.enter(network)); gate.leave(network);
        }
    }
}
