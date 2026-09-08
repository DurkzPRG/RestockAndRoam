package com.durkz.expeditionprep;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.io.IOException;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import com.hypixel.hytale.server.core.inventory.container.SimpleItemContainer;

class ExpeditionStateTest {
    @TempDir Path directory;
    private static ExpeditionState.Network network(UUID owner, Set<UUID> guests) {
        return new ExpeditionState.Network(UUID.randomUUID(), owner,
                List.of(new ExpeditionState.Link(new ProfileStore.Depot(0, 80, 0), Turnaround.Rule.fallback())), guests);
    }
    @Test void ownerAndInvitedPlayersOnly() {
        UUID owner = UUID.randomUUID(), guest = UUID.randomUUID();
        var network = network(owner, Set.of(guest));
        assertTrue(network.allows(owner)); assertTrue(network.allows(guest)); assertFalse(network.allows(UUID.randomUUID()));
        assertFalse(new ExpeditionState.Network(network.id(), owner, network.chests(), Set.of()).allows(guest));
    }
    @Test void networkIsFoundFromPrimaryOrAnyAdditionalChest() {
        UUID owner = UUID.randomUUID();
        var primary = new ProfileStore.Depot(0, 80, 0);
        var additional = new ProfileStore.Depot(8, 80, 2);
        var network = new ExpeditionState.Network(UUID.randomUUID(), owner, List.of(
                new ExpeditionState.Link(primary, Turnaround.Rule.fallback()),
                new ExpeditionState.Link(additional, new Turnaround.Rule(Turnaround.Role.LOOT, 10,
                        List.of("Test_Other"), List.of(), List.of()))), Set.of());
        var data = new ExpeditionState.WorldData();
        data.networks.put(network.id(), network);

        assertEquals(network, data.atLinkedChest(primary));
        assertEquals(network, data.atLinkedChest(additional));
        assertNull(data.atLinkedChest(new ProfileStore.Depot(1, 80, 1)));
    }
    @Test void interactedSecondaryUsesItsOwnContainerInsteadOfMasqueradingAsPrimary() {
        var primaryPosition = new ProfileStore.Depot(0, 80, 0);
        var secondaryPosition = new ProfileStore.Depot(8, 80, 2);
        var primary = new SimpleItemContainer((short) 1);
        var secondary = new SimpleItemContainer((short) 1);
        var primaryLink = new ExpeditionState.Link(primaryPosition, Turnaround.Rule.fallback());
        var secondaryLink = new ExpeditionState.Link(secondaryPosition, Turnaround.Rule.fallback());

        assertSame(primary, ExpeditionPrepPlugin.linkedContainer(primaryLink, secondaryPosition, secondary,
                position -> position.equals(primaryPosition) ? primary : null));
        assertSame(secondary, ExpeditionPrepPlugin.linkedContainer(secondaryLink, secondaryPosition, secondary,
                position -> null));
    }
    @Test void rejectsDistanceDuplicatesAndTooManyChests() {
        var n = network(UUID.randomUUID(), Set.of());
        var links = new ArrayList<>(n.chests());
        links.add(new ExpeditionState.Link(new ProfileStore.Depot(16, 80, 0), Turnaround.Rule.fallback()));
        assertEquals(2, n.withChests(links).chests().size());
        links.add(new ExpeditionState.Link(new ProfileStore.Depot(16, 81, 0), Turnaround.Rule.fallback()));
        assertThrows(IllegalArgumentException.class, () -> n.withChests(links));
        assertThrows(IllegalArgumentException.class, () -> n.withChests(List.of(n.chests().getFirst(), n.chests().getFirst())));
        var many = new ArrayList<ExpeditionState.Link>();
        for (int i=0; i<18; i++) many.add(new ExpeditionState.Link(new ProfileStore.Depot(i % 6, 80, i / 6), Turnaround.Rule.fallback()));
        assertThrows(IllegalArgumentException.class, () -> n.withChests(many));
    }
    @Test void guestsKeepSeparateProfilesTripsAndPendingAfterRestart() throws IOException {
        UUID world = UUID.randomUUID(), a = UUID.randomUUID(), b = UUID.randomUUID();
        var n = network(UUID.randomUUID(), Set.of(a,b));
        var data = new ExpeditionState.WorldData(); data.networks.put(n.id(), n);
        data.active.put(a, new LinkedHashMap<>(Map.of(n.id(), "Mining")));
        data.active.put(b, new LinkedHashMap<>(Map.of(n.id(), "Building")));
        var id = new Turnaround.Identity("Ore", 2, null);
        data.trips.put(a, new ExpeditionState.Trip(n.id(), "Mining", List.of(new Turnaround.Count(id, 4)), List.of(new Turnaround.Count(id, 7)), "Ready", List.of("Pending loot"), false));
        data.trips.put(b, new ExpeditionState.Trip(n.id(), "Building", List.of(new Turnaround.Count(id, 10)), List.of(), "Ready", List.of(), false));
        new ExpeditionState(directory).save(world, data);
        var reloaded = new ExpeditionState(directory).load(world);
        assertEquals("Mining", reloaded.active(a,n.id())); assertEquals("Building", reloaded.active(b,n.id()));
        assertEquals(data.trips, reloaded.trips); assertEquals(7, reloaded.trips.get(a).pending().getFirst().quantity());
        assertTrue(new ExpeditionState(directory).load(UUID.randomUUID()).trips.isEmpty());
    }
    @Test void invalidFilesStayUntouchedAndMissingSchemaIsNotSilentlyReset() throws IOException {
        UUID world = UUID.randomUUID(); Path file = directory.resolve(world + ".json");
        for (String invalid : List.of("{broken", "{}", "{\"schema\":99,\"networks\":{},\"active\":{},\"trips\":{},\"migrated\":[]}")) {
            Files.writeString(file, invalid);
            assertThrows(IOException.class, () -> new ExpeditionState(directory).load(world));
            assertEquals(invalid, Files.readString(file));
        }
    }
    @Test void interruptedMarkerSurvivesAndAbandonDoesNotModifyNetwork() throws IOException {
        UUID world = UUID.randomUUID(), player = UUID.randomUUID();
        var n = network(player, Set.of()); var data = new ExpeditionState.WorldData(); data.networks.put(n.id(),n);
        data.trips.put(player, new ExpeditionState.Trip(n.id(), "Mining", List.of(), List.of(), "Interrupted", List.of(), true));
        var store = new ExpeditionState(directory); store.save(world,data);
        var loaded = store.load(world); assertTrue(loaded.trips.get(player).interrupted());
        loaded.trips.remove(player); store.save(world,loaded);
        assertTrue(store.load(world).trips.isEmpty()); assertEquals(n, store.load(world).networks.get(n.id()));
    }
    @Test void duplicateOwnerOrSharedChestCannotBeSaved() {
        UUID owner = UUID.randomUUID(); var data = new ExpeditionState.WorldData();
        var a = network(owner,Set.of()); var b = network(owner,Set.of());
        data.networks.put(a.id(),a); data.networks.put(b.id(),b);
        assertThrows(IllegalArgumentException.class, data::validate);
        data.networks.remove(b.id()); var c = network(UUID.randomUUID(),Set.of()); data.networks.put(c.id(),c);
        assertThrows(IllegalArgumentException.class, data::validate);
    }
    @Test void legacyProfileMigrationKeepsExactBackupAndMarksRecapture() throws IOException {
        var key = new ProfileStore.Key(UUID.randomUUID(),UUID.randomUUID());
        var path = directory.resolve(key.world().toString()).resolve(key.player()+".json"); Files.createDirectories(path.getParent());
        String legacy = "{\"profiles\":{\"Mining\":{\"targets\":[{\"itemId\":\"Test_Food\",\"quality\":0,\"quantity\":12}]}},\"depot\":{\"x\":1,\"y\":80,\"z\":2}}";
        Files.writeString(path,legacy);
        var migrated = new ProfileStore(directory).load(key);
        assertEquals(1,migrated.schema()); assertTrue(migrated.profiles().get("Mining").needsRecapture());
        assertEquals(legacy,Files.readString(path.resolveSibling(path.getFileName()+".v0.bak")));
        assertEquals(migrated,new ProfileStore(directory).load(key));
        assertEquals(12,migrated.profiles().get("Mining").targets().getFirst().quantity());
        assertEquals(new ProfileStore.Depot(1,80,2),migrated.depot());
    }
    @Test void tripOriginCanBeFoundAcrossWorldsAfterRestart() throws IOException {
        UUID origin = UUID.randomUUID(), other = UUID.randomUUID(), player = UUID.randomUUID();
        var data = new ExpeditionState.WorldData(); var network = network(player,Set.of()); data.networks.put(network.id(),network);
        data.trips.put(player,new ExpeditionState.Trip(network.id(),"Mining",List.of(),List.of(),"Ready",List.of(),false));
        var store = new ExpeditionState(directory); store.save(origin,data); store.save(other,new ExpeditionState.WorldData());
        assertEquals(origin,new ExpeditionState(directory).findTrip(player).world());
        assertEquals(network.id(),new ExpeditionState(directory).findTrip(player).trip().network());
        assertNull(store.findTrip(UUID.randomUUID()));
    }
}
