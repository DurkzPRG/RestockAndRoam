package com.durkz.expeditionprep;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
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

    @Test void invalidNumbersAndRolesExplainTheAllowedInput() {
        assertEquals(12, ManagementPage.number(" 12 ", "Priority", -1000, 1000));
        var text = assertThrows(IllegalArgumentException.class, () -> ManagementPage.number("abc", "Priority", -1000, 1000));
        assertEquals("Priority must be a whole number from -1000 to 1000.", text.getMessage());
        assertThrows(IllegalArgumentException.class, () -> ManagementPage.number("0", "Desired quantity", 1, 100_000));
        assertEquals(Turnaround.Role.LOOT, ManagementPage.role(" loot "));
        assertEquals("Role must be SUPPLY, LOOT or BOTH.",
                assertThrows(IllegalArgumentException.class, () -> ManagementPage.role("chest")).getMessage());
    }

    @Test void kitsWithItemsRemovedByAnUpdateAreFlagged() {
        assertFalse(ManagementPage.hasRemovedItems(new ProfileStore.Profile(List.of(new ProfileStore.Target("Test_Food", null, 0, 1)))));
        assertTrue(ManagementPage.hasRemovedItems(new ProfileStore.Profile(List.of(new ProfileStore.Target("Old_Removed_Item", null, 0, 1)))));
    }
}
