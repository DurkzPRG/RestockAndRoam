package com.durkz.expeditionprep;

import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.SimpleItemContainer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class LedgerTest {
    @BeforeAll static void assets() { SuppliesTest.registerTestItems(); }

    @Test void claimingIsFreeAndRepeatedClicksDoNotFillInventory() {
        var inventory = new SimpleItemContainer((short)2);
        assertTrue(ExpeditionItems.claimLedger(inventory).contains("received"));
        assertTrue(ExpeditionItems.claimLedger(inventory).contains("already have"));
        int count = 0;
        for (short i = 0; i < inventory.getCapacity(); i++) {
            var stack = inventory.getItemStack(i);
            if (!ItemStack.isEmpty(stack)) count += stack.getQuantity();
        }
        assertEquals(1, count);
    }

    @Test void fullInventoryIsKeptAndClaimCanBeRetried() {
        var inventory = new SimpleItemContainer((short)1);
        var food = new ItemStack("Test_Food", 20);
        inventory.setItemStackForSlot((short)0, food);
        assertTrue(ExpeditionItems.claimLedger(inventory).contains("Inventory full"));
        assertEquals(food, inventory.getItemStack((short)0));
        inventory.removeItemStackFromSlot((short)0);
        assertTrue(ExpeditionItems.claimLedger(inventory).contains("received"));
    }

    @Test void ledgerClaimedDuringTripStaysWithPlayerInsteadOfBecomingLoot() {
        var inventory = new SimpleItemContainer((short)2);
        var sections = Map.<ProfileStore.Section, com.hypixel.hytale.server.core.inventory.container.ItemContainer>of(
                ProfileStore.Section.HOTBAR, inventory);
        inventory.setItemStackForSlot((short)1, new ItemStack("Test_Food", 1));
        var baseline = Turnaround.list(Turnaround.counts(sections.values()));
        ExpeditionItems.claimLedger(inventory);
        var kit = Turnaround.capture(sections);
        assertEquals(1, kit.slots().size());
        assertEquals("Test_Food", kit.slots().getFirst().target().itemId());
        var depot = new SimpleItemContainer((short)2);
        var result = Turnaround.execute(kit, sections,
                List.of(new Turnaround.Chest(depot, Turnaround.Rule.fallback(), 0)), baseline);
        assertEquals(0, result.deposited());
        assertTrue(result.pending().isEmpty());
        assertEquals(baseline, result.baseline());
        assertEquals(ExpeditionItems.LEDGER, inventory.getItemStack((short)0).getItemId());
    }
}
