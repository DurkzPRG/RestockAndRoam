package com.durkz.expeditionprep;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

final class RestockRoamConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    boolean checkForUpdates = true;

    static RestockRoamConfig load(Path dataDirectory) throws IOException {
        Files.createDirectories(dataDirectory);
        Path file = dataDirectory.resolve("config.json");
        if (!Files.isRegularFile(file)) {
            var config = new RestockRoamConfig();
            config.save(dataDirectory);
            return config;
        }
        try {
            JsonObject json = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), JsonObject.class);
            if (json == null) throw new JsonParseException("Empty config");
            var config = new RestockRoamConfig();
            boolean migrated = !json.has("checkForUpdates");
            if (!migrated) config.checkForUpdates = json.get("checkForUpdates").getAsBoolean();
            if (migrated) config.save(dataDirectory);
            return config;
        } catch (JsonParseException | IllegalStateException | UnsupportedOperationException error) {
            throw new IOException("Invalid Restock & Roam config: " + file, error);
        }
    }

    void save(Path dataDirectory) throws IOException {
        Files.createDirectories(dataDirectory);
        Files.writeString(dataDirectory.resolve("config.json"), GSON.toJson(this), StandardCharsets.UTF_8);
    }
}
