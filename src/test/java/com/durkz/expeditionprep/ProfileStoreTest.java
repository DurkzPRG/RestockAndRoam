package com.durkz.expeditionprep;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ProfileStoreTest {
    @Test void removesLegacyEditorTargetsAndSlotsWithBackupButKeepsSurvivalTools() throws IOException {
        var key = new ProfileStore.Key(UUID.randomUUID(), UUID.randomUUID());
        var editor = new ProfileStore.Target("EditorTool_Paint", null, 2, 1);
        var tool = new ProfileStore.Target("Tool_Pickaxe_Iron", null, 0, 1);
        var profile = new ProfileStore.Profile(List.of(editor), List.of(
                new ProfileStore.Slot(ProfileStore.Section.TOOLS, (short)0, editor),
                new ProfileStore.Slot(ProfileStore.Section.HOTBAR, (short)1, tool)), false);
        var store = new ProfileStore(directory);
        store.save(key, new ProfileStore.Data(Map.of("Mining", profile), null));
        var file = directory.resolve(key.world().toString()).resolve(key.player()+".json");
        String before = Files.readString(file);
        var loaded = store.load(key).profiles().get("Mining");
        assertTrue(loaded.targets().isEmpty()); assertEquals(1,loaded.slots().size());
        assertEquals(tool,loaded.slots().getFirst().target());
        assertEquals(before,Files.readString(file.resolveSibling(file.getFileName()+".before-editor-filter.bak")));
        assertEquals(loaded,store.load(key).profiles().get("Mining"));
    }
    @TempDir Path directory;

    @Test void persistsProfilesAndDepotAcrossReloadsWithoutMixingPlayersOrWorlds() throws IOException {
        var key = new ProfileStore.Key(UUID.randomUUID(), UUID.randomUUID());
        var profile = new ProfileStore.Profile(List.of(new ProfileStore.Target("Test_Food", null, 0, 12)));
        var data = new ProfileStore.Data(Map.of("Mining", profile), new ProfileStore.Depot(-12, 80, 24));
        new ProfileStore(directory).save(key, data);
        var reloaded = new ProfileStore(directory);
        assertEquals(data, reloaded.load(key));
        assertTrue(reloaded.load(new ProfileStore.Key(key.world(), UUID.randomUUID())).profiles().isEmpty());
        assertTrue(reloaded.load(new ProfileStore.Key(UUID.randomUUID(), key.player())).profiles().isEmpty());
        reloaded.save(key, data.withoutProfile("Mining"));
        assertTrue(new ProfileStore(directory).load(key).profiles().isEmpty());
    }

    @Test void corruptFileIsReportedAndKept() throws IOException {
        var key = new ProfileStore.Key(UUID.randomUUID(), UUID.randomUUID());
        Path file = directory.resolve(key.world().toString()).resolve(key.player() + ".json");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "{broken");
        assertThrows(IOException.class, () -> new ProfileStore(directory).load(key));
        assertEquals("{broken", Files.readString(file));
    }

    @Test void rejectsUnsafeNamesAndInvalidQuantities() {
        assertThrows(IllegalArgumentException.class, () -> ProfileStore.validateName("../other"));
        assertThrows(IllegalArgumentException.class, () -> ProfileStore.validateName(""));
        assertThrows(IllegalArgumentException.class, () -> new ProfileStore.Target("Food", null, 0, -1));
        assertThrows(IllegalArgumentException.class, () -> new ProfileStore.Profile(List.of()));
    }
}
