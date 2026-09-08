package com.durkz.expeditionprep;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

final class ProfileStore {
    static final int MAX_PROFILES = 12;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private final Path directory;

    record Key(UUID world, UUID player) {}
    record Target(String itemId, String metadata, int quality, int quantity) {
        Target {
            if (itemId == null || itemId.isBlank() || itemId.length() > 256
                    || quantity < 1 || quantity > 100_000 || quality < 0
                    || (metadata != null && metadata.length() > 65_536)) {
                throw new IllegalArgumentException("Invalid supply target");
            }
            if (metadata != null) {
                try { org.bson.BsonDocument.parse(metadata); }
                catch (RuntimeException error) { throw new IllegalArgumentException("Invalid item metadata", error); }
            }
        }
    }
    enum Section { HOTBAR, ARMOR, UTILITY, TOOLS, STORAGE, BACKPACK }
    record Slot(Section section, short index, Target target) {
        Slot {
            if (section == null || index < 0 || index > 255 || target == null)
                throw new IllegalArgumentException("Invalid kit slot");
        }
    }
    record Profile(List<Target> targets, List<Slot> slots, boolean needsRecapture) {
        Profile(List<Target> targets) { this(targets, List.of(), true); }
        Profile {
            if (targets == null || targets.size() > 36) {
                throw new IllegalArgumentException("A profile needs 1 to 36 item types");
            }
            targets = List.copyOf(targets);
            if (slots == null) { slots = List.of(); needsRecapture = true; }
            slots = List.copyOf(slots);
            if (slots.size() > 256 || (slots.isEmpty() && targets.isEmpty()))
                throw new IllegalArgumentException("An empty kit cannot be saved");
            var seen = new java.util.HashSet<String>();
            for (Slot slot : slots) if (!seen.add(slot.section() + ":" + slot.index()))
                throw new IllegalArgumentException("Duplicate kit slot");
        }
    }
    record Depot(int x, int y, int z) {
        String coordinates() { return x + ", " + y + ", " + z; }
    }
    record Data(Map<String, Profile> profiles, Depot depot, int schema) {
        Data(Map<String, Profile> profiles, Depot depot) { this(profiles, depot, 1); }
        Data {
            if (schema < 0 || schema > 1) throw new IllegalArgumentException("Unsupported profile schema");
            if (profiles == null || profiles.size() > MAX_PROFILES) {
                throw new IllegalArgumentException("Invalid profiles");
            }
            profiles.forEach((name, profile) -> {
                validateName(name);
                if (profile == null) throw new IllegalArgumentException("Missing profile");
            });
            profiles = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(profiles));
        }
        Data withProfile(String name, Profile profile) {
            validateName(name);
            var updated = new LinkedHashMap<>(profiles);
            updated.put(name, profile);
            return new Data(updated, depot);
        }
        Data withoutProfile(String name) {
            var updated = new LinkedHashMap<>(profiles);
            updated.remove(name);
            return new Data(updated, depot);
        }
    }

    ProfileStore(Path directory) { this.directory = directory; }

    static void validateName(String name) {
        if (name == null || !name.matches("[A-Za-z0-9][A-Za-z0-9 _-]{0,23}")) {
            throw new IllegalArgumentException("Use 1-24 letters, numbers, spaces, - or _ for the profile name.");
        }
    }

    synchronized Data load(Key key) throws IOException {
        Path file = file(key);
        if (!Files.exists(file)) return new Data(Map.of(), null);
        if (Files.size(file) > 1_048_576) throw new IOException("Profile file is too large: " + file);
        try {
            Data data = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), Data.class);
            if (data == null) throw new IOException("Empty profile file: " + file);
            if (data.schema() == 0) {
                Path backup = file.resolveSibling(file.getFileName() + ".v0.bak");
                if (!Files.exists(backup)) Files.copy(file, backup);
                data = new Data(data.profiles(), data.depot());
                save(key, data);
            }
            var cleaned = new LinkedHashMap<String, Profile>();
            data.profiles().forEach((name, profile) -> {
                var targets = profile.targets().stream().filter(t -> !ExpeditionItems.isExcluded(t.itemId())).toList();
                var slots = profile.slots().stream().filter(s -> !ExpeditionItems.isExcluded(s.target().itemId())).toList();
                if (!targets.isEmpty() || !slots.isEmpty()) cleaned.put(name, new Profile(targets, slots, profile.needsRecapture()));
            });
            if (!cleaned.equals(data.profiles())) {
                Path backup = file.resolveSibling(file.getFileName() + ".before-editor-filter.bak");
                if (!Files.exists(backup)) Files.copy(file, backup);
                data = new Data(cleaned, data.depot());
                save(key, data);
            }
            return data;
        } catch (JsonParseException | IllegalArgumentException e) {
            throw new IOException("Invalid profile file: " + file, e);
        }
    }

    synchronized void save(Key key, Data data) throws IOException {
        Path file = file(key);
        Files.createDirectories(file.getParent());
        Path temp = Files.createTempFile(file.getParent(), "profiles-", ".tmp");
        try {
            Files.writeString(temp, GSON.toJson(data), StandardCharsets.UTF_8);
            Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private Path file(Key key) {
        return directory.resolve(key.world().toString()).resolve(key.player() + ".json");
    }
}
