package com.durkz.expeditionprep;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

class ManagementPageTest {
    @BeforeAll static void assets() { SuppliesTest.registerTestItems(); }

    @Test void itemRowsAlwaysUsePlainReadableText() {
        assertEquals("Test Food", ManagementPage.itemName("Test_Food"));
        assertEquals("Aqua Brick", ManagementPage.itemName("Rock_Aqua_Brick"));
        assertEquals("Lumberjack Chest Small", ManagementPage.itemName("Furniture_Lumberjack_Chest_Small"));
    }

    @Test void overviewPrefersExplicitSelectionThenActiveKitInsteadOfFirstCreated() {
        var profile = new ProfileStore.Profile(List.of(new ProfileStore.Target("Test_Food", null, 0, 1)));
        Map<String, ProfileStore.Profile> profiles = new LinkedHashMap<>();
        profiles.put("First", profile);
        profiles.put("Mining", profile);
        profiles.put("Building", profile);

        assertEquals("Mining", ManagementPage.chooseProfile(profiles, null, "Mining"));
        assertEquals("Building", ManagementPage.chooseProfile(profiles, "Building", "Mining"));
        assertEquals("First", ManagementPage.chooseProfile(profiles, "Deleted", "Missing"));
    }
}
