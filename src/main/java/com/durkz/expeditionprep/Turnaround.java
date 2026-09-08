package com.durkz.expeditionprep;

import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import org.bson.BsonDocument;
import org.bson.BsonValue;

import java.util.*;

/** Pure planning and native transfers. Call only on the owning world thread. */
final class Turnaround {
    record Identity(String itemId, int quality, String metadata) {
        static Identity of(ItemStack stack) {
            var data = stack.getMetadata();
            return new Identity(stack.getItemId(), stack.getQualityIndex(),
                    data == null || data.isEmpty() ? null : canonical(data).asDocument().toJson());
        }
        static Identity of(ProfileStore.Target target) {
            var data = target.metadata() == null ? null : BsonDocument.parse(target.metadata());
            return new Identity(target.itemId(), target.quality(),
                    data == null || data.isEmpty() ? null : canonical(data).asDocument().toJson());
        }
        private static BsonValue canonical(BsonValue value) {
            if (value.isDocument()) {
                BsonDocument result = new BsonDocument();
                value.asDocument().keySet().stream().sorted().forEach(k -> result.put(k, canonical(value.asDocument().get(k))));
                return result;
            }
            if (value.isArray()) return new org.bson.BsonArray(value.asArray().stream().map(Identity::canonical).toList());
            return value;
        }
    }
    record Count(Identity item, int quantity) {
        Count { if (item == null || quantity < 0) throw new IllegalArgumentException("Invalid snapshot count"); }
    }
    enum Role { SUPPLY, LOOT, BOTH }
    record Rule(Role role, int priority, List<String> items, List<String> categories, List<String> resources) {
        Rule {
            Objects.requireNonNull(role);
            items = List.copyOf(items); categories = List.copyOf(categories); resources = List.copyOf(resources);
            if (items.size() + categories.size() + resources.size() > 128 || Math.abs((long) priority) > 1000)
                throw new IllegalArgumentException("Too many filters or invalid priority");
        }
        static Rule fallback() { return new Rule(Role.BOTH, 0, List.of(), List.of(), List.of()); }
        boolean fallbackOnly() { return items.isEmpty() && categories.isEmpty() && resources.isEmpty(); }
        boolean accepts(ItemStack stack) {
            if (fallbackOnly() || items.contains(stack.getItemId())) return true;
            var item = stack.getItem();
            if (item == null) return false;
            if (item.getCategories() != null) for (String category : item.getCategories())
                if (categories.contains(category)) return true;
            if (item.getResourceTypes() != null) for (var resource : item.getResourceTypes())
                if (resources.contains(resource.id)) return true;
            return false;
        }
    }
    record Chest(ItemContainer container, Rule rule, int order) {}
    record Result(List<Count> baseline, List<Count> pending, int deposited, int refilled,
                  int missing, int conflicts, List<String> details) {
        String summary() { return "Loot: " + deposited + " deposited. Kit: " + refilled
                + " supplied, " + missing + " missing, " + conflicts + " conflicts."; }
    }
    static ProfileStore.Target target(ItemStack stack) {
        Identity id = Identity.of(stack);
        return new ProfileStore.Target(id.itemId(), id.metadata(), id.quality(), stack.getQuantity());
    }
    static ProfileStore.Profile capture(Map<ProfileStore.Section, ItemContainer> inventory) {
        List<ProfileStore.Slot> slots = new ArrayList<>();
        inventory.forEach((section, container) -> {
            for (short i = 0; i < container.getCapacity(); i++) {
                ItemStack stack = container.getItemStack(i);
                if (!ItemStack.isEmpty(stack) && !ExpeditionItems.isExcluded(stack.getItemId())) slots.add(new ProfileStore.Slot(section, i, target(stack)));
            }
        });
        return new ProfileStore.Profile(List.of(), slots, false);
    }
    static Map<Identity, Integer> counts(Collection<ItemContainer> containers) {
        Map<Identity, Integer> result = new LinkedHashMap<>();
        for (ItemContainer container : containers) for (short i = 0; i < container.getCapacity(); i++) {
            ItemStack stack = container.getItemStack(i);
            if (!ItemStack.isEmpty(stack) && !ExpeditionItems.isExcluded(stack.getItemId())) result.merge(Identity.of(stack), stack.getQuantity(), Math::addExact);
        }
        return result;
    }
    static Map<Identity, Integer> map(List<Count> counts) {
        Map<Identity, Integer> result = new LinkedHashMap<>();
        for (Count count : counts) if (!ExpeditionItems.isExcluded(count.item().itemId())) result.merge(count.item(), count.quantity(), Math::addExact);
        return result;
    }
    static List<Count> list(Map<Identity, Integer> counts) {
        return counts.entrySet().stream().filter(e -> e.getValue() > 0).map(e -> new Count(e.getKey(), e.getValue())).toList();
    }
    static Map<Identity, Integer> gains(Map<Identity, Integer> current, List<Count> baseline) {
        var previous = map(baseline);
        Map<Identity, Integer> result = new LinkedHashMap<>();
        current.forEach((id, n) -> { int gain = Math.max(0, n - previous.getOrDefault(id, 0)); if (gain > 0) result.put(id, gain); });
        return result;
    }

    static Result execute(ProfileStore.Profile profile, Map<ProfileStore.Section, ItemContainer> inventory,
                          List<Chest> chests, List<Count> baseline) {
        // Validate and simulate the complete operation before touching real containers.
        var identities = Collections.newSetFromMap(new IdentityHashMap<ItemContainer, Boolean>());
        identities.addAll(inventory.values());
        List<Chest> unique = new ArrayList<>();
        for (Chest chest : chests) {
            if (inventory.values().stream().anyMatch(c -> c == chest.container()
                    || c.containsContainer(chest.container()) || chest.container().containsContainer(c)))
                throw new IllegalArgumentException("A chest overlaps player inventory");
            if (identities.add(chest.container())) unique.add(chest);
        }
        unique.sort(Comparator.comparing((Chest c) -> c.rule().fallbackOnly())
                .thenComparing(Comparator.comparingInt((Chest c) -> c.rule().priority()).reversed())
                .thenComparingInt(Chest::order));
        Map<ProfileStore.Section, ItemContainer> clones = new EnumMap<>(ProfileStore.Section.class);
        inventory.forEach((section, container) -> clones.put(section, container.clone()));
        run(profile, clones, unique.stream().map(c -> new Chest(c.container().clone(), c.rule(), c.order())).toList(), baseline);
        return run(profile, inventory, unique, baseline);
    }

    private static Result run(ProfileStore.Profile profile, Map<ProfileStore.Section, ItemContainer> inventory,
                              List<Chest> chests, List<Count> baseline) {
        List<String> details = new ArrayList<>();
        var pending = baseline == null ? new LinkedHashMap<Identity, Integer>() : gains(counts(inventory.values()), baseline);
        int deposited = 0, refilled = 0, missing = 0, conflicts = 0;
        for (var gain : pending.entrySet()) {
            int left = gain.getValue();
            List<ItemContainer> depositSources = new ArrayList<>();
            for (var section : List.of(ProfileStore.Section.STORAGE, ProfileStore.Section.BACKPACK,
                    ProfileStore.Section.HOTBAR, ProfileStore.Section.UTILITY, ProfileStore.Section.TOOLS, ProfileStore.Section.ARMOR))
                if (inventory.containsKey(section)) depositSources.add(inventory.get(section));
            for (ItemContainer source : depositSources) for (short slot = 0; left > 0 && slot < source.getCapacity(); slot++) {
                ItemStack stack = source.getItemStack(slot);
                if (ItemStack.isEmpty(stack) || !Identity.of(stack).equals(gain.getKey())) continue;
                for (Chest chest : chests) {
                    if (chest.rule().role() == Role.SUPPLY || !chest.rule().accepts(stack)) continue;
                    int moved = move(source, slot, Math.min(left, quantity(source, slot)), chest.container(), null);
                    left -= moved; deposited += moved;
                    if (left == 0 || quantity(source, slot) == 0) break;
                }
            }
            gain.setValue(left);
            if (left > 0) details.add("Pending loot: " + left + " x " + gain.getKey().itemId());
        }
        // Protect correctly equipped slots, but allow displaced items in other configured slots to move.
        Map<String, Integer> reserved = new HashMap<>();
        for (var desired : profile.slots()) {
            if (ExpeditionItems.isExcluded(desired.target().itemId())) continue;
            var container = inventory.get(desired.section());
            if (container != null && desired.index() < container.getCapacity()
                    && Supplies.matches(desired.target(), container.getItemStack(desired.index())))
                reserved.put(desired.section() + ":" + desired.index(), desired.target().quantity());
        }
        for (var desired : profile.slots()) {
            if (ExpeditionItems.isExcluded(desired.target().itemId())) continue;
            ItemContainer destination = inventory.get(desired.section());
            if (destination == null || desired.index() >= destination.getCapacity()) {
                conflicts++; details.add("Unavailable section/slot: " + desired.section() + ":" + desired.index()); continue;
            }
            var target = desired.target();
            var equipped = destination.getItemStack(desired.index());
            if (!ItemStack.isEmpty(equipped) && !Supplies.matches(target, equipped)) {
                if (ExpeditionItems.isExcluded(equipped.getItemId())) {
                    conflicts++; details.add("Protected item left untouched in " + desired.section() + " slot " + (desired.index() + 1)); continue;
                }
                boolean cleared = false;
                for (var section : List.of(ProfileStore.Section.STORAGE, ProfileStore.Section.BACKPACK)) {
                    var storage = inventory.get(section);
                    if (storage == null || storage == destination) continue;
                    // Require full room before moving a personal displaced stack.
                    var trial = storage.clone();
                    var src = destination.clone();
                    if (move(src, desired.index(), equipped.getQuantity(), trial, null) != equipped.getQuantity()) continue;
                    move(destination, desired.index(), equipped.getQuantity(), storage, null);
                    cleared = ItemStack.isEmpty(destination.getItemStack(desired.index()));
                    if (cleared) break;
                }
                if (!cleared) { conflicts++; details.add("Slot unchanged (no storage space): " + desired.section() + ":" + desired.index()); continue; }
            }
            int need = Math.max(0, target.quantity() - quantity(destination, desired.index()));
            for (var sourceEntry : inventory.entrySet()) {
                var source = sourceEntry.getValue();
                for (short slot = 0; need > 0 && slot < source.getCapacity(); slot++) {
                    if ((source == destination && slot == desired.index()) || !Supplies.matches(target, source.getItemStack(slot))) continue;
                    int available = Math.max(0, quantity(source, slot) - reserved.getOrDefault(sourceEntry.getKey() + ":" + slot, 0));
                    need -= move(source, slot, Math.min(need, available), destination, desired.index());
                }
            }
            for (Chest chest : chests) {
                if (chest.rule().role() == Role.LOOT) continue;
                for (short slot = 0; need > 0 && slot < chest.container().getCapacity(); slot++) {
                    var stack = chest.container().getItemStack(slot);
                    if (!Supplies.matches(target, stack) || !chest.rule().accepts(stack)) continue;
                    int moved = move(chest.container(), slot, Math.min(need, stack.getQuantity()), destination, desired.index());
                    need -= moved; refilled += moved;
                }
            }
            missing += need;
            if (need > 0) details.add("Missing " + need + " x " + target.itemId() + " at " + desired.section() + ":" + desired.index());
            equipped = destination.getItemStack(desired.index());
            if (Supplies.matches(target, equipped)) reserved.put(desired.section() + ":" + desired.index(), target.quantity());
            if (!ItemStack.isEmpty(equipped) && equipped.getMaxDurability() > 0 && equipped.getDurability() / equipped.getMaxDurability() < .2)
                details.add("Low durability: " + equipped.getItemId() + " at " + desired.section() + ":" + desired.index());
        }
        for (var target : profile.targets()) {
            if (ExpeditionItems.isExcluded(target.itemId())) continue;
            int need = Math.max(0, target.quantity() - counts(inventory.values()).getOrDefault(Identity.of(target), 0));
            for (Chest chest : chests) {
                if (chest.rule().role() == Role.LOOT) continue;
                for (short slot = 0; need > 0 && slot < chest.container().getCapacity(); slot++) {
                    var stack = chest.container().getItemStack(slot);
                    if (!Supplies.matches(target, stack) || !chest.rule().accepts(stack)) continue;
                    for (var section : List.of(ProfileStore.Section.STORAGE, ProfileStore.Section.BACKPACK)) {
                        var destination = inventory.get(section);
                        if (destination == null) continue;
                        int moved = move(chest.container(), slot, Math.min(need, quantity(chest.container(), slot)), destination, null);
                        need -= moved; refilled += moved;
                        if (need == 0) break;
                    }
                }
            }
            missing += need;
            if (need > 0) details.add("Missing reserve: " + need + " x " + target.itemId());
        }
        var finalCounts = counts(inventory.values());
        var nextBaseline = new LinkedHashMap<>(finalCounts);
        pending.replaceAll((id, n) -> Math.min(n, finalCounts.getOrDefault(id, 0)));
        pending.forEach((id, n) -> nextBaseline.computeIfPresent(id, (k, total) -> Math.max(0, total - n)));
        return new Result(list(nextBaseline), list(pending), deposited, refilled, missing, conflicts, List.copyOf(details));
    }
    private static int quantity(ItemContainer container, short slot) {
        var stack = container.getItemStack(slot); return ItemStack.isEmpty(stack) ? 0 : stack.getQuantity();
    }
    private static int move(ItemContainer source, short slot, int amount, ItemContainer destination, Short targetSlot) {
        if (amount <= 0) return 0;
        int before = quantity(source, slot);
        if (targetSlot == null) source.moveItemStackFromSlot(slot, amount, destination);
        else source.moveItemStackFromSlotToSlot(slot, amount, destination, targetSlot);
        return Math.max(0, before - quantity(source, slot));
    }
}
