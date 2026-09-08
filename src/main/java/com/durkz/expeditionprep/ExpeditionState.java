package com.durkz.expeditionprep;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** One atomic document per world, including networks, personal trips and active selections. */
final class ExpeditionState {
    record Link(ProfileStore.Depot position, Turnaround.Rule rule) {
        Link { Objects.requireNonNull(position); Objects.requireNonNull(rule); }
    }
    record Network(UUID id, UUID owner, List<Link> chests, Set<UUID> guests) {
        Network {
            Objects.requireNonNull(id); Objects.requireNonNull(owner);
            chests = List.copyOf(chests); guests = Set.copyOf(guests);
            if (chests.isEmpty() || chests.size() > 17 || guests.size() > 128)
                throw new IllegalArgumentException("Invalid network size");
            Set<ProfileStore.Depot> positions = new HashSet<>();
            for (Link chest : chests) if (!positions.add(chest.position()) || !near(chests.getFirst().position(), chest.position()))
                throw new IllegalArgumentException("Chests must be distinct and within 16 blocks of the primary");
        }
        boolean allows(UUID player) { return owner.equals(player) || guests.contains(player); }
        Network withChests(List<Link> value) { return new Network(id, owner, value, guests); }
    }
    record Trip(UUID network, String profile, List<Turnaround.Count> baseline, List<Turnaround.Count> pending,
                String report, List<String> details, boolean interrupted) {
        Trip {
            Objects.requireNonNull(network); Objects.requireNonNull(profile);
            baseline = List.copyOf(baseline); pending = List.copyOf(pending); details = List.copyOf(details);
        }
    }
    static final class WorldData {
        int schema = 1;
        Map<UUID, Network> networks = new LinkedHashMap<>();
        Map<UUID, Map<UUID, String>> active = new LinkedHashMap<>();
        Map<UUID, Trip> trips = new LinkedHashMap<>();
        Set<UUID> migrated = new HashSet<>();
        Map<UUID, String> playerNames = new LinkedHashMap<>();
        String worldName;
        Network owned(UUID owner) { return networks.values().stream().filter(n -> n.owner().equals(owner)).findFirst().orElse(null); }
        Network atLinkedChest(ProfileStore.Depot position) {
            return networks.values().stream()
                    .filter(network -> network.chests().stream().anyMatch(chest -> chest.position().equals(position)))
                    .findFirst().orElse(null);
        }
        String active(UUID player, UUID network) { return active.getOrDefault(player, Map.of()).get(network); }
        void validate() {
            if (schema != 1 || networks == null || active == null || trips == null || migrated == null)
                throw new IllegalArgumentException("Unsupported or incomplete expedition state");
            Set<UUID> owners = new HashSet<>();
            Set<ProfileStore.Depot> positions = new HashSet<>();
            networks.forEach((id, n) -> {
                if (!id.equals(n.id()) || !owners.add(n.owner())) throw new IllegalArgumentException("Duplicate network owner");
                for (Link chest : n.chests()) if (!positions.add(chest.position())) throw new IllegalArgumentException("Chest belongs to multiple networks");
            });
        }
    }
    private final Path directory;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    ExpeditionState(Path directory) { this.directory = directory; }
    record StoredTrip(UUID world, Trip trip) {}
    synchronized StoredTrip findTrip(UUID player) throws IOException {
        if (!Files.isDirectory(directory)) return null;
        // Only state documents, never chunks or container searches.
        try (var files = Files.newDirectoryStream(directory, "*.json")) {
            for (Path file : files) {
                UUID world;
                try { world = UUID.fromString(file.getFileName().toString().replace(".json", "")); }
                catch (IllegalArgumentException error) { throw new IOException("Unexpected state filename: " + file, error); }
                Trip trip = load(world).trips.get(player);
                if (trip != null) return new StoredTrip(world, trip);
            }
        }
        return null;
    }
    synchronized WorldData load(UUID world) throws IOException {
        Path path = directory.resolve(world + ".json");
        if (!Files.exists(path)) return new WorldData();
        try {
            if (Files.size(path) > 16 * 1024 * 1024) throw new IOException("Expedition state too large: " + path);
            var json = com.google.gson.JsonParser.parseString(Files.readString(path)).getAsJsonObject();
            for (String field : List.of("schema", "networks", "active", "trips", "migrated"))
                if (!json.has(field)) throw new IllegalArgumentException("Missing field: " + field);
            var result = GSON.fromJson(json, WorldData.class);
            if (result == null) throw new IllegalArgumentException("Empty state");
            result.validate(); return result;
        } catch (RuntimeException error) { throw new IOException("Invalid expedition state, kept intact: " + path, error); }
    }
    synchronized void save(UUID world, WorldData data) throws IOException {
        data.validate();
        Files.createDirectories(directory);
        Path path = directory.resolve(world + ".json");
        Path temp = Files.createTempFile(directory, "expedition-", ".tmp");
        try {
            Files.writeString(temp, GSON.toJson(data));
            // Do not silently downgrade atomic persistence on unsupported filesystems.
            Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temp); }
    }
    static boolean near(ProfileStore.Depot a, ProfileStore.Depot b) {
        double x = (double)a.x() - b.x(), y = (double)a.y() - b.y(), z = (double)a.z() - b.z();
        return x*x + y*y + z*z <= 256;
    }
}
