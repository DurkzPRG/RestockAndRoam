package com.durkz.expeditionprep;

import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import org.bson.BsonDocument;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

final class Supplies {
    record Line(ProfileStore.Target target, int before, int moved, int missing) {}

    static ProfileStore.Profile capture(ItemContainer hotbar) {
        List<ProfileStore.Target> targets = new ArrayList<>();
        for (short slot = 0; slot < hotbar.getCapacity(); slot++) {
            ItemStack stack = hotbar.getItemStack(slot);
            if (ItemStack.isEmpty(stack)) continue;
            int found = -1;
            for (int i = 0; i < targets.size(); i++) {
                if (matches(targets.get(i), stack)) { found = i; break; }
            }
            var target = new ProfileStore.Target(stack.getItemId(), metadata(stack), stack.getQualityIndex(),
                    stack.getQuantity() + (found < 0 ? 0 : targets.get(found).quantity()));
            if (found < 0) targets.add(target); else targets.set(found, target);
        }
        if (targets.isEmpty()) throw new IllegalArgumentException("Put your desired supplies in the hotbar first.");
        return new ProfileStore.Profile(targets);
    }

    static boolean matches(ProfileStore.Target target, ItemStack stack) {
        if (ItemStack.isEmpty(stack) || !target.itemId().equals(stack.getItemId())
                || target.quality() != stack.getQualityIndex()) return false;
        BsonDocument expected = target.metadata() == null ? null : BsonDocument.parse(target.metadata());
        BsonDocument actual = stack.getMetadata();
        if (expected != null && expected.isEmpty()) expected = null;
        if (actual != null && actual.isEmpty()) actual = null;
        return Objects.equals(expected, actual);
    }

    private static String metadata(ItemStack stack) {
        var metadata = stack.getMetadata();
        return metadata == null || metadata.isEmpty() ? null : metadata.toJson();
    }

    static int count(ItemContainer container, ProfileStore.Target target) {
        int total = 0;
        for (short slot = 0; slot < container.getCapacity(); slot++) {
            ItemStack stack = container.getItemStack(slot);
            if (matches(target, stack)) total += stack.getQuantity();
        }
        return total;
    }

    static List<Line> prepare(ProfileStore.Profile profile, ItemContainer depot, ItemContainer inventory) {
        if (depot == inventory || depot.containsContainer(inventory) || inventory.containsContainer(depot)) {
            throw new IllegalArgumentException("The depot must be separate from your inventory.");
        }
        List<Line> lines = new ArrayList<>();
        for (var target : profile.targets()) {
            int before = count(inventory, target);
            int missing = Math.max(0, target.quantity() - before);
            for (short slot = 0; missing > 0 && slot < depot.getCapacity(); slot++) {
                ItemStack stack = depot.getItemStack(slot);
                if (!matches(target, stack)) continue;
                // Engine transfer preserves metadata and only removes items accepted by the destination.
                depot.moveItemStackFromSlot(slot, Math.min(missing, stack.getQuantity()), inventory);
                missing = Math.max(0, target.quantity() - count(inventory, target));
            }
            int after = count(inventory, target);
            lines.add(new Line(target, before, after - before, Math.max(0, target.quantity() - after)));
        }
        return List.copyOf(lines);
    }
}
