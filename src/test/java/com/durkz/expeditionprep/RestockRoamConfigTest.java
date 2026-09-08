package com.durkz.expeditionprep;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class RestockRoamConfigTest {
    @TempDir Path directory;

    @Test void newConfigEnablesUpdateChecks() throws Exception {
        var config = RestockRoamConfig.load(directory);
        assertTrue(config.checkForUpdates);
        assertTrue(Files.readString(directory.resolve("config.json")).contains("\"checkForUpdates\": true"));
    }

    @Test void missingUpdateSettingMigratesToEnabled() throws Exception {
        Files.writeString(directory.resolve("config.json"), "{}");
        var config = RestockRoamConfig.load(directory);
        assertTrue(config.checkForUpdates);
        assertTrue(Files.readString(directory.resolve("config.json")).contains("\"checkForUpdates\": true"));
    }

    @Test void explicitOptOutIsPreserved() throws Exception {
        Files.writeString(directory.resolve("config.json"), "{\"checkForUpdates\":false}");
        assertFalse(RestockRoamConfig.load(directory).checkForUpdates);
    }

    @Test void invalidConfigIsNotOverwritten() throws Exception {
        Files.writeString(directory.resolve("config.json"), "invalid");
        assertThrows(java.io.IOException.class, () -> RestockRoamConfig.load(directory));
        assertEquals("invalid", Files.readString(directory.resolve("config.json")));
    }
}
