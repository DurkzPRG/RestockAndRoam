package com.durkz.expeditionprep;

import com.hypixel.hytale.assetstore.AssetRegistry;
import com.hypixel.hytale.assetstore.map.DefaultAssetMap;
import com.hypixel.hytale.assetstore.AssetStore;
import com.hypixel.hytale.assetstore.AssetUpdateQuery;
import com.hypixel.hytale.event.EventBus;
import com.hypixel.hytale.event.IEventBus;
import com.hypixel.hytale.server.core.asset.type.item.config.Item;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.CombinedItemContainer;
import com.hypixel.hytale.server.core.inventory.container.SimpleItemContainer;
import org.bson.BsonDocument;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SuppliesTest {
    private static boolean registered;
    @BeforeAll static void registerTestItems() {
        if (registered) return;
        registered = true;
        var map = new DefaultAssetMap<String, Item>(Map.of("Test_Food", new TestItem("Test_Food"),
                "Test_Tool", new TestItem("Test_Tool"), "Test_Other", new TestItem("Test_Other"),
                "EditorTool_Paint", new TestItem("EditorTool_Paint"),
                ExpeditionItems.LEDGER, new TestItem(ExpeditionItems.LEDGER)));
        AssetRegistry.register(new TestStore.TestStoreBuilder(map)
                .setPath("Item/Items").setCodec(Item.CODEC).setKeyFunction(Item::getId).build());
    }

    // Real engine containers and transfers, with only the asset registry isolated from server startup.
    private static final class TestStore extends AssetStore<String, Item, DefaultAssetMap<String, Item>> {
        private static final class TestStoreBuilder extends AssetStore.Builder<String, Item, DefaultAssetMap<String, Item>, TestStoreBuilder> {
            TestStoreBuilder(DefaultAssetMap<String, Item> map) { super(String.class, Item.class, map); }
            @Override public AssetStore<String, Item, DefaultAssetMap<String, Item>> build() { return new TestStore(this); }
        }
        private final EventBus bus = new EventBus(false);
        TestStore(TestStoreBuilder builder) { super(builder); }
        @Override protected IEventBus getEventBus() { return bus; }
        @Override public void addFileMonitor(String pack, java.nio.file.Path path) {}
        @Override public void removeFileMonitor(java.nio.file.Path path) {}
        @Override protected void handleRemoveOrUpdate(java.util.Set<String> removed, Map<String, Item> changed, AssetUpdateQuery query) {}
    }

    private static final class TestItem extends Item {
        TestItem(String id) { super(id); maxStack = 20; }
        @Override public int getQualityIndex() { return 0; }
        @Override public String[] getCategories() { return getId().equals("Test_Food") ? new String[]{"Food"} : null; }
        @Override public com.hypixel.hytale.protocol.ItemResourceType[] getResourceTypes() {
            return getId().equals("Test_Other") ? new com.hypixel.hytale.protocol.ItemResourceType[]{new com.hypixel.hytale.protocol.ItemResourceType("Ore", 1)} : null;
        }
    }

    private static ItemStack food(int quantity) { return new ItemStack("Test_Food", quantity); }
    private static ProfileStore.Profile goal(int quantity) {
        return new ProfileStore.Profile(List.of(new ProfileStore.Target("Test_Food", null, 0, quantity)));
    }
    private static SimpleItemContainer container(ItemStack... stacks) {
        var result = new SimpleItemContainer((short) stacks.length);
        for (short i = 0; i < stacks.length; i++) if (stacks[i] != null) result.setItemStackForSlot(i, stacks[i]);
        return result;
    }

    @Test void topsUpOnlyTheDeficitAndRepeatedPrepareDoesNotDuplicate() {
        var depot = container(food(20));
        var inventory = container(food(3), null);
        var line = Supplies.prepare(goal(12), depot, inventory).getFirst();
        assertEquals(9, line.moved());
        assertEquals(0, line.missing());
        assertEquals(11, depot.getItemStack((short) 0).getQuantity());
        assertEquals(0, Supplies.prepare(goal(12), depot, inventory).getFirst().moved());
        assertEquals(23, Supplies.count(depot, goal(12).targets().getFirst()) + Supplies.count(inventory, goal(12).targets().getFirst()));
    }

    @Test void fullInventoryLeavesChestUntouched() {
        var depot = container(food(20));
        var inventory = container(new ItemStack("Test_Other", 20));
        var line = Supplies.prepare(goal(12), depot, inventory).getFirst();
        assertEquals(0, line.moved());
        assertEquals(12, line.missing());
        assertEquals(20, depot.getItemStack((short) 0).getQuantity());
        assertEquals("Test_Other", inventory.getItemStack((short) 0).getItemId());
    }

    @Test void partiallyFullStackMovesOnlyWhatFits() {
        var depot = container(food(20));
        var inventory = container(food(18));
        var line = Supplies.prepare(goal(30), depot, inventory).getFirst();
        assertEquals(2, line.moved());
        assertEquals(10, line.missing());
        assertEquals(18, depot.getItemStack((short) 0).getQuantity());
    }

    @Test void insufficientStockReportsRemainingDeficit() {
        var depot = container(food(2), food(3));
        var inventory = container(food(1));
        var line = Supplies.prepare(goal(12), depot, inventory).getFirst();
        assertEquals(5, line.moved());
        assertEquals(6, line.missing());
        assertTrue(depot.isEmpty());
    }

    @Test void countsBackpackAndDoesNotRemoveExcess() {
        var depot = container(food(20));
        var inventory = new CombinedItemContainer(container(food(3)), container(food(15)));
        assertEquals(0, Supplies.prepare(goal(12), depot, inventory).getFirst().moved());
        assertEquals(18, Supplies.count(inventory, goal(12).targets().getFirst()));
        assertEquals(20, depot.getItemStack((short) 0).getQuantity());
    }

    @Test void captureCombinesDuplicateHotbarStacksWithoutMovingThem() {
        var hotbar = container(food(4), food(5), null);
        var profile = Supplies.capture(hotbar);
        assertEquals(1, profile.targets().size());
        assertEquals(9, profile.targets().getFirst().quantity());
        assertEquals(4, hotbar.getItemStack((short) 0).getQuantity());
    }

    @Test void preservesDurabilityQualityAndCustomDataAndIgnoresOtherVariants() {
        var expected = new ItemStack("Test_Tool", 1, 7, 100, 2, BsonDocument.parse("{\"name\":\"Mining\"}"));
        var profile = Supplies.capture(container(expected));
        var other = new ItemStack("Test_Tool", 1, 100, 100, 2, BsonDocument.parse("{\"name\":\"Valuable\"}"));
        var wrongQuality = expected.withQuality(3);
        var depot = container(other, wrongQuality, expected);
        var inventory = container((ItemStack) null);
        assertEquals(1, Supplies.prepare(profile, depot, inventory).getFirst().moved());
        assertEquals(expected, inventory.getItemStack((short) 0));
        assertEquals(other, depot.getItemStack((short) 0));
        assertEquals(wrongQuality, depot.getItemStack((short) 1));
    }
}
