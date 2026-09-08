package com.durkz.expeditionprep;

final class ExpeditionItems {
    static final String LEDGER = "Durkz_Expedition_Ledger";
    private ExpeditionItems() {}
    static boolean isExcluded(String itemId) { return LEDGER.equals(itemId) || isEditorTool(itemId); }

    static String claimLedger(com.hypixel.hytale.server.core.inventory.container.ItemContainer inventory) {
        for (short slot = 0; slot < inventory.getCapacity(); slot++) {
            var stack = inventory.getItemStack(slot);
            if (!com.hypixel.hytale.server.core.inventory.ItemStack.isEmpty(stack) && LEDGER.equals(stack.getItemId()))
                return "You already have an Expedition Ledger. Hold it and use secondary action to open this menu.";
        }
        var transaction = inventory.addItemStack(new com.hypixel.hytale.server.core.inventory.ItemStack(LEDGER, 1));
        if (!transaction.succeeded() || !com.hypixel.hytale.server.core.inventory.ItemStack.isEmpty(transaction.getRemainder()))
            return "Inventory full. Free one slot and claim your Expedition Ledger again.";
        return "Expedition Ledger received. Hold it and use secondary action to open this menu.";
    }
    // Vanilla editor tools are a separate item family, not survival equipment.
    static boolean isEditorTool(String itemId) { return itemId != null && itemId.startsWith("EditorTool_"); }
}
