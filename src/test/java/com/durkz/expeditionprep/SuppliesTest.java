package com.durkz.expeditionprep;

import com.hypixel.hytale.assetstore.AssetRegistry;
import com.hypixel.hytale.assetstore.map.DefaultAssetMap;
import com.hypixel.hytale.assetstore.AssetStore;
import com.hypixel.hytale.assetstore.AssetUpdateQuery;
import com.hypixel.hytale.event.EventBus;
import com.hypixel.hytale.event.IEventBus;
import com.hypixel.hytale.server.core.asset.type.item.config.Item;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import org.bson.BsonDocument;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

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

    @Test void matchingKeepsQualityAndCustomDataApart() {
        var expected = new ItemStack("Test_Tool", 1, 7, 100, 2, BsonDocument.parse("{\"name\":\"Mining\"}"));
        var target = Turnaround.target(expected);
        assertTrue(Supplies.matches(target, expected));
        assertTrue(Supplies.matches(target, expected.withDurability(50)));
        assertFalse(Supplies.matches(target, new ItemStack("Test_Tool", 1, 7, 100, 2, BsonDocument.parse("{\"name\":\"Valuable\"}"))));
        assertFalse(Supplies.matches(target, expected.withQuality(3)));
        assertFalse(Supplies.matches(target, null));
    }

    @Test void emptyMetadataMatchesPlainStacks() {
        var plain = new ItemStack("Test_Food", 3);
        assertTrue(Supplies.matches(new ProfileStore.Target("Test_Food", "{}", 0, 1), plain));
        assertTrue(Supplies.matches(new ProfileStore.Target("Test_Food", null, 0, 1), plain));
        assertNull(Supplies.metadata(plain));
    }
}
