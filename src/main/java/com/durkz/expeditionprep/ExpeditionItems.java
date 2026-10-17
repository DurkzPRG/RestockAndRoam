package com.durkz.expeditionprep;

import com.hypixel.hytale.server.core.asset.type.item.config.Item;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;

final class ExpeditionItems {
    static final String LEDGER = "Durkz_Expedition_Ledger";
    static final String LEDGER_RECEIVED = "Expedition Ledger received. Hold it and use secondary action to open this menu.";
    private ExpeditionItems() {}
    static boolean isExcluded(String itemId) { return LEDGER.equals(itemId) || isEditorTool(itemId); }

    static String claimLedger(ItemContainer inventory) { return claimLedger(inventory, inventory); }

    /** Searches every slot the player owns, but only adds to regular inventory slots. */
    static String claimLedger(ItemContainer owned, ItemContainer target) {
        if (hasLedger(owned))
            return "You already have an Expedition Ledger. Hold it and use secondary action to open this menu.";
        var transaction = target.addItemStack(new ItemStack(LEDGER, 1));
        if (!transaction.succeeded() || !ItemStack.isEmpty(transaction.getRemainder()))
            return "Inventory full. Free one slot and claim your Expedition Ledger again.";
        return LEDGER_RECEIVED;
    }
    static boolean hasLedger(ItemContainer owned) {
        for (short slot = 0; slot < owned.getCapacity(); slot++) {
            var stack = owned.getItemStack(slot);
            if (!ItemStack.isEmpty(stack) && LEDGER.equals(stack.getItemId())) return true;
        }
        return false;
    }
    // Vanilla editor tools are a separate item family, not survival equipment.
    static boolean isEditorTool(String itemId) { return itemId != null && itemId.startsWith("EditorTool_"); }

    /** False for items a game update removed or renamed since the kit was saved. */
    static boolean isKnown(String itemId) {
        try { return Item.getAssetMap().getAsset(itemId) != null; }
        catch (RuntimeException error) { return true; }
    }
}
