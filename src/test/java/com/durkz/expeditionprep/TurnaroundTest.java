package com.durkz.expeditionprep;

import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.inventory.container.SimpleItemContainer;
import org.bson.BsonDocument;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class TurnaroundTest {
    @Test void inventoryOnlyKitSurvivesSaveAndRestoresWithoutHotbarItems(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path directory) throws Exception {
        var inv = inventory(container((ItemStack)null), container(null, food(7)));
        inv.put(ProfileStore.Section.BACKPACK, container(ore(4)));
        var profile = Turnaround.capture(inv);
        assertEquals(2, profile.slots().size());
        var store = new ProfileStore(directory);
        var key = new ProfileStore.Key(UUID.randomUUID(), UUID.randomUUID());
        store.save(key, new ProfileStore.Data(Map.of("Travel", profile), null));
        var loaded = new ProfileStore(directory).load(key).profiles().get("Travel");
        assertEquals(profile, loaded);
        inv.get(ProfileStore.Section.STORAGE).clear();
        inv.get(ProfileStore.Section.BACKPACK).clear();
        var result = Turnaround.execute(loaded, inv, List.of(chest(container(food(7), ore(4)))), null);
        assertEquals(11, result.refilled());
        assertEquals(0, result.missing());
        assertTrue(inv.get(ProfileStore.Section.HOTBAR).isEmpty());
        assertEquals(food(7), inv.get(ProfileStore.Section.STORAGE).getItemStack((short)1));
        assertEquals(ore(4), inv.get(ProfileStore.Section.BACKPACK).getItemStack((short)0));
    }

    @Test void sameItemInHotbarAndInventoryKeepsSeparateQuantities() {
        var inv = inventory(container(food(3)), container(food(7)));
        var profile = Turnaround.capture(inv);
        inv.get(ProfileStore.Section.HOTBAR).clear();
        inv.get(ProfileStore.Section.STORAGE).clear();
        var result = Turnaround.execute(profile, inv, List.of(chest(container(food(10)))), null);
        assertEquals(10, result.refilled());
        assertEquals(0, result.missing());
        assertEquals(food(3), inv.get(ProfileStore.Section.HOTBAR).getItemStack((short)0));
        assertEquals(food(7), inv.get(ProfileStore.Section.STORAGE).getItemStack((short)0));
    }

    @Test void editorToolsAreNotCapturedCountedDepositedOrReplenished() {
        var editor = new ItemStack("EditorTool_Paint", 1);
        var inv = inventory(container(food(2), editor), container(editor));
        inv.put(ProfileStore.Section.TOOLS, container(editor, new ItemStack("Test_Tool", 1)));
        var kit = Turnaround.capture(inv);
        assertEquals(2, kit.slots().size());
        assertFalse(kit.slots().stream().anyMatch(s -> ExpeditionItems.isEditorTool(s.target().itemId())));
        var depot = container((ItemStack)null, null);
        var legacy = new ProfileStore.Profile(List.of(Turnaround.target(editor)), List.of(
                new ProfileStore.Slot(ProfileStore.Section.HOTBAR, (short)0, Turnaround.target(food(2))),
                new ProfileStore.Slot(ProfileStore.Section.TOOLS, (short)0, Turnaround.target(editor))), false);
        var result = Turnaround.execute(legacy, inv, List.of(chest(depot)), Turnaround.list(Turnaround.counts(inv.values())));
        assertEquals(0, result.deposited()); assertEquals(0, result.refilled()); assertEquals(0, result.missing());
        assertEquals(editor, inv.get(ProfileStore.Section.TOOLS).getItemStack((short)0));
        assertFalse(result.baseline().stream().anyMatch(c -> ExpeditionItems.isEditorTool(c.item().itemId())));
    }
    @BeforeAll static void assets() { SuppliesTest.registerTestItems(); }
    private static SimpleItemContainer container(ItemStack... stacks) {
        var result = new SimpleItemContainer((short)stacks.length);
        for (short i=0; i<stacks.length; i++) if (stacks[i] != null) result.setItemStackForSlot(i, stacks[i]);
        return result;
    }
    private static ItemStack food(int n) { return new ItemStack("Test_Food", n); }
    private static ItemStack ore(int n) { return new ItemStack("Test_Other", n); }
    private static ProfileStore.Profile reserve(int n) { return new ProfileStore.Profile(List.of(Turnaround.target(food(n)))); }
    private static Map<ProfileStore.Section, ItemContainer> inventory(ItemContainer hotbar, ItemContainer storage) {
        var result = new EnumMap<ProfileStore.Section, ItemContainer>(ProfileStore.Section.class);
        result.put(ProfileStore.Section.HOTBAR, hotbar); result.put(ProfileStore.Section.STORAGE, storage); return result;
    }
    private static Turnaround.Chest chest(ItemContainer c) { return new Turnaround.Chest(c, Turnaround.Rule.fallback(), 0); }
    private static int count(Map<ProfileStore.Section, ItemContainer> inv, ItemStack item) {
        return Turnaround.counts(inv.values()).getOrDefault(Turnaround.Identity.of(item), 0);
    }
    @Test void firstRunOnlyPreparesAndNextRunDepositsNetGain() {
        var inv = inventory(container(food(1)), container(ore(4), null));
        var depot = container(food(20), null, null);
        var first = Turnaround.execute(reserve(5), inv, List.of(chest(depot)), null);
        assertEquals(0, first.deposited()); assertEquals(4, first.refilled()); assertEquals(4, count(inv, ore(1)));
        inv.get(ProfileStore.Section.STORAGE).setItemStackForSlot((short)0, ore(9));
        var next = Turnaround.execute(reserve(5), inv, List.of(chest(depot)), first.baseline());
        assertEquals(5, next.deposited()); assertEquals(4, count(inv, ore(1)));
        assertEquals(0, Turnaround.execute(reserve(5), inv, List.of(chest(depot)), next.baseline()).deposited());
    }
    @Test void pendingLootIsRetriedAndNeverBecomesPersonalBaseline() {
        var inv = inventory(container(food(1)), container(ore(9)));
        var full = container(food(20));
        var baseline = List.of(new Turnaround.Count(Turnaround.Identity.of(food(1)), 1), new Turnaround.Count(Turnaround.Identity.of(ore(1)), 4));
        var result = Turnaround.execute(reserve(1), inv, List.of(chest(full)), baseline);
        assertEquals(5, Turnaround.map(result.pending()).get(Turnaround.Identity.of(ore(1))));
        assertEquals(4, Turnaround.map(result.baseline()).get(Turnaround.Identity.of(ore(1))));
        var empty = container((ItemStack)null);
        var retry = Turnaround.execute(reserve(1), inv, List.of(chest(empty)), result.baseline());
        assertEquals(5, retry.deposited()); assertTrue(retry.pending().isEmpty()); assertEquals(4, count(inv, ore(1)));
    }
    @Test void partialDepositLeavesRemainderPending() {
        var inv = inventory(container(food(1)), container(ore(10)));
        var depot = container(ore(18));
        var result = Turnaround.execute(reserve(1), inv, List.of(chest(depot)), List.of(new Turnaround.Count(Turnaround.Identity.of(food(1)), 1)));
        assertEquals(2, result.deposited()); assertEquals(8, result.pending().getFirst().quantity()); assertEquals(8, count(inv, ore(1)));
    }
    @Test void deathDoesNotGenerateNegativeLootOrFreeItems() {
        var inv = inventory(container((ItemStack)null), container((ItemStack)null));
        var result = Turnaround.execute(reserve(5), inv, List.of(chest(container(food(2)))),
                List.of(new Turnaround.Count(Turnaround.Identity.of(food(1)), 5), new Turnaround.Count(Turnaround.Identity.of(ore(1)), 40)));
        assertEquals(0, result.deposited()); assertEquals(2, result.refilled()); assertEquals(3, result.missing()); assertEquals(2, count(inv, food(1)));
    }
    @Test void profileChangeUsesOldSnapshotThenNewKit() {
        var inv = inventory(container(food(2)), container(ore(6), null));
        var result = Turnaround.execute(reserve(8), inv, List.of(chest(container(food(20), null))),
                List.of(new Turnaround.Count(Turnaround.Identity.of(food(1)), 5), new Turnaround.Count(Turnaround.Identity.of(ore(1)), 2)));
        assertEquals(4, result.deposited()); assertEquals(6, result.refilled()); assertEquals(8, count(inv, food(1))); assertEquals(2, count(inv, ore(1)));
    }
    @Test void metadataOrderIsNormalizedButQualityAndCustomValuesAreDistinct() {
        var a = new ItemStack("Test_Tool", 1, 90, 100, 2, BsonDocument.parse("{\"b\":2,\"a\":{\"y\":1,\"x\":0}}"));
        var b = new ItemStack("Test_Tool", 1, 10, 100, 2, BsonDocument.parse("{\"a\":{\"x\":0,\"y\":1},\"b\":2}"));
        assertEquals(Turnaround.Identity.of(a), Turnaround.Identity.of(b));
        assertNotEquals(Turnaround.Identity.of(a), Turnaround.Identity.of(b.withQuality(3)));
        assertNotEquals(Turnaround.Identity.of(a), Turnaround.Identity.of(new ItemStack("Test_Tool", 1)));
    }
    @Test void filteredDestinationsBeatFallbackAndRespectPriority() {
        var inv = inventory(container(food(1)), container(ore(5)));
        var fallback = container((ItemStack)null); var low = container((ItemStack)null); var high = container((ItemStack)null);
        var lowRule = new Turnaround.Rule(Turnaround.Role.LOOT, 1, List.of("Test_Other"), List.of(), List.of());
        var highRule = new Turnaround.Rule(Turnaround.Role.LOOT, 9, List.of(), List.of(), List.of("Ore"));
        Turnaround.execute(reserve(1), inv, List.of(chest(fallback), new Turnaround.Chest(low, lowRule, 1), new Turnaround.Chest(high, highRule, 2)),
                List.of(new Turnaround.Count(Turnaround.Identity.of(food(1)), 1)));
        assertEquals(5, high.getItemStack((short)0).getQuantity()); assertTrue(low.isEmpty()); assertTrue(fallback.isEmpty());
    }
    @Test void categoriesAndResourcesAreOptionalForModdedItems() {
        var rule = new Turnaround.Rule(Turnaround.Role.BOTH, 0, List.of("Test_Tool"), List.of("Food"), List.of("Ore"));
        assertTrue(rule.accepts(food(1))); assertTrue(rule.accepts(ore(1))); assertTrue(rule.accepts(new ItemStack("Test_Tool", 1)));
        assertFalse(new Turnaround.Rule(Turnaround.Role.LOOT, 0, List.of(), List.of("Food"), List.of()).accepts(new ItemStack("Test_Tool", 1)));
    }
    @Test void supplyOnlyNeverAcceptsLootAndLootOnlyNeverSupplies() {
        var inv = inventory(container(food(1)), container(ore(5), null));
        var supply = container((ItemStack)null); var loot = container(food(20));
        var result = Turnaround.execute(reserve(5), inv, List.of(
                new Turnaround.Chest(supply, new Turnaround.Rule(Turnaround.Role.SUPPLY, 0, List.of(), List.of(), List.of()), 0),
                new Turnaround.Chest(loot, new Turnaround.Rule(Turnaround.Role.LOOT, 0, List.of(), List.of(), List.of()), 1)),
                List.of(new Turnaround.Count(Turnaround.Identity.of(food(1)), 1)));
        assertEquals(0, result.refilled()); assertEquals(0, result.deposited()); assertEquals(4, result.missing()); assertTrue(supply.isEmpty());
    }
    @Test void restoresScrambledHotbarFromInventoryWithoutChestStock() {
        var inv = inventory(container(food(3), new ItemStack("Test_Tool", 1)), container((ItemStack)null, null));
        var profile = Turnaround.capture(inv);
        inv.get(ProfileStore.Section.HOTBAR).setItemStackForSlot((short)0, new ItemStack("Test_Tool", 1));
        inv.get(ProfileStore.Section.HOTBAR).setItemStackForSlot((short)1, food(3));
        var result = Turnaround.execute(profile, inv, List.of(), null);
        assertEquals(0, result.missing()); assertEquals(0, result.conflicts());
        assertEquals("Test_Food", inv.get(ProfileStore.Section.HOTBAR).getItemStack((short)0).getItemId());
        assertEquals("Test_Tool", inv.get(ProfileStore.Section.HOTBAR).getItemStack((short)1).getItemId());
        assertEquals(3, count(inv, food(1)));
    }
    @Test void wornEquippedToolStaysAndFreshMatchingLootIsDeposited() {
        var worn = new ItemStack("Test_Tool", 1, 10, 100, 0, null);
        var inv = inventory(container(worn), container((ItemStack)null));
        var profile = Turnaround.capture(inv);
        inv.get(ProfileStore.Section.STORAGE).setItemStackForSlot((short)0, new ItemStack("Test_Tool", 1, 100, 100, 0, null));
        var depot = container((ItemStack)null);
        var result = Turnaround.execute(profile, inv, List.of(chest(depot)), List.of(new Turnaround.Count(Turnaround.Identity.of(worn), 1)));
        assertEquals(worn, inv.get(ProfileStore.Section.HOTBAR).getItemStack((short)0));
        assertEquals(1, result.deposited()); assertTrue(result.details().stream().anyMatch(s -> s.startsWith("Low durability")));
    }
    @Test void fullInventoryConflictKeepsPersonalItemInPlace() {
        var inv = inventory(container(food(3)), container(ore(20)));
        var profile = Turnaround.capture(inv);
        var personal = new ItemStack("Test_Tool", 1);
        inv.get(ProfileStore.Section.HOTBAR).setItemStackForSlot((short)0, personal);
        var depot = container(food(20));
        var result = Turnaround.execute(profile, inv, List.of(chest(depot)), null);
        assertEquals(1, result.conflicts()); assertEquals(personal, inv.get(ProfileStore.Section.HOTBAR).getItemStack((short)0)); assertEquals(20, depot.getItemStack((short)0).getQuantity());
    }
    @Test void allEquipmentSectionsAreCapturedAndRestoredAndBackpackIsUsed() {
        var inv = inventory(container(food(2)), container(ore(20)));
        for (var section : List.of(ProfileStore.Section.ARMOR, ProfileStore.Section.UTILITY, ProfileStore.Section.TOOLS)) inv.put(section, container(new ItemStack("Test_Tool", 1)));
        inv.put(ProfileStore.Section.BACKPACK, container((ItemStack)null, null));
        var profile = Turnaround.capture(inv);
        assertEquals(5, profile.slots().size());
        for (var section : List.of(ProfileStore.Section.ARMOR, ProfileStore.Section.UTILITY, ProfileStore.Section.TOOLS)) inv.get(section).clear();
        var result = Turnaround.execute(profile, inv, List.of(chest(container(new ItemStack("Test_Tool", 3)))), null);
        assertEquals(3, result.refilled()); assertEquals(0, result.missing());
        assertEquals(3, count(inv, new ItemStack("Test_Tool", 1)));
    }
    @Test void duplicateContainerDoesNotDuplicateTransfersAndInventoryOverlapIsRejected() {
        var inv = inventory(container(food(1)), container((ItemStack)null)); var depot = container(food(3));
        var result = Turnaround.execute(reserve(8), inv, List.of(chest(depot), chest(depot)), null);
        assertEquals(3, result.refilled()); assertEquals(4, result.missing()); assertEquals(4, count(inv, food(1)));
        assertThrows(IllegalArgumentException.class, () -> Turnaround.execute(reserve(1), inv, List.of(chest(inv.get(ProfileStore.Section.HOTBAR))), null));
    }
    @Test void removedChestsLeaveLootAndKitDeficitWithPlayer() {
        var inv = inventory(container(food(1)), container(ore(4)));
        var result = Turnaround.execute(reserve(8), inv, List.of(), List.of(new Turnaround.Count(Turnaround.Identity.of(food(1)), 1)));
        assertEquals(7, result.missing()); assertEquals(4, result.pending().getFirst().quantity()); assertEquals(4, count(inv, ore(1)));
    }
    @Test void excessInCorrectSlotCanRestoreAnotherSlotWithoutTakingItsRequiredKit() {
        var inv = inventory(container(food(5),food(5)),container((ItemStack)null));
        var profile = Turnaround.capture(inv);
        inv.get(ProfileStore.Section.HOTBAR).setItemStackForSlot((short)0,food(10));
        inv.get(ProfileStore.Section.HOTBAR).removeItemStackFromSlot((short)1);
        var result = Turnaround.execute(profile,inv,List.of(),null);
        assertEquals(0,result.missing()); assertEquals(5,inv.get(ProfileStore.Section.HOTBAR).getItemStack((short)0).getQuantity());
        assertEquals(5,inv.get(ProfileStore.Section.HOTBAR).getItemStack((short)1).getQuantity());
    }
}
