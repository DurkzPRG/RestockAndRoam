package com.durkz.expeditionprep;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.entities.player.pages.InteractiveCustomUIPage;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.ui.builder.*;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.io.IOException;
import java.util.*;
import com.hypixel.hytale.server.core.universe.Universe;

final class ManagementPage extends InteractiveCustomUIPage<ManagementPage.Input> {
    enum Tab { HUB, PROFILES, NETWORK, RULES, TRIP }
    private final ExpeditionPrepPlugin plugin;
    private final World world;
    private final Tab tab;
    private UUID networkId;
    private String profileName;
    private int entry = -1;
    private int chestIndex;
    private String status = "";
    private String confirm;
    private boolean adminTools;
    private UUID selectedGuest;

    ManagementPage(ExpeditionPrepPlugin plugin, PlayerRef player, World world, Tab tab) {
        super(player, CustomPageLifetime.CanDismiss, Input.CODEC);
        this.plugin = plugin; this.world = world; this.tab = tab;
    }
    @Override public void build(Ref<EntityStore> ref, UICommandBuilder ui, UIEventBuilder events, Store<EntityStore> store) {
        ui.append("Pages/Durkz_ExpeditionPrep_" + tab.name() + ".ui");
        for (Tab destination : Tab.values()) events.addEventBinding(CustomUIEventBindingType.Activating,
                "#" + destination.name(), EventData.of("Action", "Tab:" + destination.name()));
        for (int i = 1; i <= 6; i++) events.addEventBinding(CustomUIEventBindingType.Activating, "#Button" + i,
                EventData.of("Action", "Button" + i).append("@First", "#First.Value")
                        .append("@Second", "#Second.Value").append("@Third", "#Third.Value"));
        events.addEventBinding(CustomUIEventBindingType.Activating, "#Close", EventData.of("Action", "Close"));
        render(ui, events);
        if (tab == Tab.PROFILES && profileName != null) ui.set("#First.Value", profileName);
    }
    private void row(UICommandBuilder ui, UIEventBuilder events, int index, String text, String action) {
        if (action.equals("None")) {
            ui.append("#Rows", "Pages/Durkz_ExpeditionPrep_InfoRow.ui");
            ui.set("#Rows[" + index + "] #Text.Text", text);
            return;
        }
        ui.append("#Rows", "Pages/Durkz_ExpeditionPrep_ProfileRow.ui");
        String selector = "#Rows[" + index + "] #Pick";
        ui.set(selector + ".Text", text);
        events.addEventBinding(CustomUIEventBindingType.Activating, selector, EventData.of("Action", action));
    }
    private void kitRow(UICommandBuilder ui, UIEventBuilder events, int row, int entryIndex, String prefix, ProfileStore.Target target) {
        ui.append("#Rows", "Pages/Durkz_ExpeditionPrep_KitRow.ui");
        String selector = "#Rows[" + row + "] ";
        ui.set(selector + "#Item.ItemId", target.itemId());
        ui.set(selector + "#Pick.Text", (entry == entryIndex ? "> " : "") + prefix
                + target.quantity() + " x " + itemName(target.itemId()));
        events.addEventBinding(CustomUIEventBindingType.Activating, selector + "#Pick", EventData.of("Action", "Entry:" + entryIndex));
    }
    private String playerName(UUID id) throws IOException {
        if (id.equals(playerRef.getUuid())) return playerRef.getUsername();
        var online = Universe.get().getPlayer(id);
        if (online != null) return online.getUsername();
        var names = plugin.state(world).playerNames;
        return names == null ? "Offline player" : names.getOrDefault(id, "Offline player");
    }
    private UUID playerId(String name) throws IOException {
        Set<UUID> matches = new HashSet<>();
        for (var online : Universe.get().getPlayers()) if (online.getUsername().equalsIgnoreCase(name.trim())) matches.add(online.getUuid());
        var names = plugin.state(world).playerNames;
        if (names != null) names.forEach((id, nick) -> { if (nick.equalsIgnoreCase(name.trim())) matches.add(id); });
        if (matches.size() != 1) throw new IllegalArgumentException("Player not found or name is ambiguous. Ask them to join, then reopen this menu.");
        return matches.iterator().next();
    }
    private String baseName(ExpeditionState.Network network) throws IOException {
        if (network == null) return "No base linked";
        return (network.owner().equals(playerRef.getUuid()) ? "Your base" : playerName(network.owner()) + "'s base")
                + " (" + network.chests().getFirst().position().coordinates() + ")";
    }
    private static String sectionName(ProfileStore.Section section) {
        return switch (section) { case HOTBAR -> "Hotbar"; case ARMOR -> "Armor"; case UTILITY -> "Utility";
            case TOOLS -> "Tools"; case STORAGE -> "Inventory"; case BACKPACK -> "Backpack"; };
    }
    private void render(UICommandBuilder ui, UIEventBuilder events) {
        ui.clear("#Rows");
        ui.clear("#ProfileChoices");
        ui.set("#ProfileChoicesGroup.Visible", tab == Tab.HUB || tab == Tab.PROFILES);
        try {
            var state = plugin.state(world);
            var profiles = plugin.profiles().load(ExpeditionPrepPlugin.key(world, playerRef)).profiles();
            if (networkId == null || !state.networks.containsKey(networkId)) networkId = state.networks.values().stream()
                    .filter(n -> n.allows(playerRef.getUuid())).map(ExpeditionState.Network::id).findFirst().orElse(null);
            var network = state.networks.get(networkId);
            profileName = chooseProfile(profiles, profileName,
                    network == null ? null : state.active(playerRef.getUuid(), network.id()));
            renderProfileChoices(ui, events, profiles);
            ui.set("#Heading.Text", switch (tab) { case HUB -> "Prepare your next expedition"; case PROFILES -> "Your equipment kits";
                case NETWORK -> "Your base and guests"; case RULES -> "Where items go"; case TRIP -> "Your current expedition"; });
            ui.set("#Context.Text", baseName(network) + " | Selected kit: " + Objects.toString(profileName, "not saved yet")
                    + " | Active kit: " + (network == null ? "not set" : Objects.toString(state.active(playerRef.getUuid(), network.id()), "not set")));
            String help;
            String[] buttons;
            String first = "", second = "", third = "";
            int i = 0;
            switch (tab) {
                case HUB -> {
                    help = profileName == null ? "Step 1: open Kits and save the equipment you want to take. Creative editor tools are ignored."
                            : network == null ? "Step 2: open Base and link your main chest."
                            : !profileName.equals(state.active(playerRef.getUuid(), network.id())) ? "Choose a saved kit below, then click Use this kit."
                            : "Ready with '" + profileName + "': crouch and interact with any linked chest. First use prepares your kit; later returns store new loot and refill supplies.";
                    buttons = new String[]{"Use this kit", "Refresh", "Claim Expedition Ledger", "", "", ""};
                    for (var n : state.networks.values()) if (n.allows(playerRef.getUuid()) || playerRef.hasPermission("expeditionprep.admin"))
                        row(ui, events, i++, (n.id().equals(networkId) ? "> " : "") + baseName(n)
                                + " | " + n.chests().size() + " chests | Owner: " + playerName(n.owner()), "Network:" + n.id());
                }
                case PROFILES -> {
                    help = "Save your equipment, inventory and backpack as a kit. Select an item to edit its quantity or remove it. The Ledger and editor tools are ignored.";
                    first = "Kit name"; second = "Desired quantity";
                    buttons = new String[]{"Save full kit", "Set quantity", "Remove item", "Add held supplies", "Delete kit", "Use this kit"};
                    var profile = profiles.get(profileName);
                    if (profile != null) {
                        int n = 0;
                        for (var slot : profile.slots()) kitRow(ui, events, i++, n++, sectionName(slot.section()) + " " + (slot.index()+1) + " | ", slot.target());
                        for (var target : profile.targets()) kitRow(ui, events, i++, n++, "Supplies total | ", target);
                        if (entry >= 0 && entry < profile.slots().size() + profile.targets().size()) {
                            var selected = entry < profile.slots().size() ? profile.slots().get(entry).target() : profile.targets().get(entry - profile.slots().size());
                            ui.set("#Second.Value", Integer.toString(selected.quantity()));
                        }
                    }
                }
                case NETWORK -> {
                    help = "Link your main chest first, then optional nearby chests (up to 16 blocks away). Guests use their own kits. Enter their player name to invite them, or select a guest to remove.";
                    first = "Guest player name";
                    buttons = new String[]{"Link primary", "Link additional", "Unlink selected", "Add guest", "Remove guest", "Delete network"};
                    if (network != null) {
                        for (var link : network.chests()) {
                            int index = i;
                            row(ui, events, i++, (index == chestIndex ? "> " : "") + (index == 0 ? "Primary " : "Chest ")
                                    + link.position().coordinates() + " | " + link.rule().role(), "Chest:" + index);
                        }
                        for (var guest : network.guests()) row(ui, events, i++, "Guest: " + playerName(guest), "Guest:" + guest);
                    }
                }
                case RULES -> {
                    help = "Select a chest. Filters are OR: item:ID, category:ID, resource:ID separated by commas. Empty means fallback. Filtered chests precede fallback; higher priority first, then link order.";
                    first = "Role: SUPPLY, LOOT or BOTH"; second = "Priority: -1000 to 1000"; third = "Filters (empty = fallback)";
                    buttons = new String[]{"Save chest rules", "Move earlier", "Move later", "Refresh", "", ""};
                    if (network != null) for (var link : network.chests()) {
                        int index = i;
                        row(ui, events, i++, (index == chestIndex ? "> " : "") + link.position().coordinates() + " | "
                                + link.rule().role() + " | priority " + link.rule().priority() + " | " + filters(link.rule()), "Chest:" + index);
                    }
                    if (network != null && chestIndex >= 0 && chestIndex < network.chests().size()) {
                        var rule = network.chests().get(chestIndex).rule();
                        ui.set("#First.Value", rule.role().name()); ui.set("#Second.Value", Integer.toString(rule.priority()));
                        ui.set("#Third.Value", rule.fallbackOnly() ? "" : filters(rule));
                    }
                }
                case TRIP -> {
                    help = "A trip belongs to its origin network. Changing the active kit does not change the previous departure snapshot. Abandon only clears tracking and never moves items. Interrupted operations require inspection before abandoning.";
                    first = adminTools ? "Player name (empty = you)" : "";
                    buttons = new String[]{"Abandon trip", adminTools ? "Inspect player" : "", adminTools ? "Reset tracking" : "", "Refresh",
                            playerRef.hasPermission("expeditionprep.admin") ? (adminTools ? "Hide admin tools" : "Admin tools") : "", ""};
                    var stored = plugin.trip(inspectPlayer == null ? playerRef.getUuid() : inspectPlayer);
                    var trip = stored == null ? null : stored.trip();
                    if (trip == null) row(ui, events, i++, "No active trip.", "None");
                    else {
                        var originWorld = Universe.get().getWorld(stored.world());
                        var originState = plugin.savedState(stored.world());
                        String worldName = originWorld == null ? originState.worldName : originWorld.getWorldConfig().getDisplayName();
                        if (worldName == null || worldName.isBlank()) worldName = originWorld == null ? "Unavailable world" : originWorld.getName();
                        row(ui, events, i++, "World: " + worldName, "None");
                        var originNetwork = originState.networks.get(trip.network());
                        row(ui, events, i++, "Return to: " + (originNetwork == null ? "Base no longer linked" : baseName(originNetwork)), "None");
                        row(ui, events, i++, "Kit: " + trip.profile() + (trip.interrupted() ? " | Needs recovery" : " | Tracking active"), "None");
                        row(ui, events, i++, trip.report(), "None");
                        for (String detail : trip.details()) row(ui, events, i++, detail, "None");
                        for (var pending : trip.pending()) if (!ExpeditionItems.isExcluded(pending.item().itemId())) {
                            row(ui, events, i++, "Waiting for chest space: " + pending.quantity() + " x "
                                    + itemName(pending.item().itemId()), "None");
                        }
                    }
                }
                default -> throw new IllegalStateException();
            }
            if (i == 0) row(ui, events, 0, switch (tab) {
                case HUB -> "No base yet. Open Base, choose Link primary, then open your chest.";
                case PROFILES -> "Arrange your travel items, enter a kit name, then choose Save full kit.";
                case NETWORK -> "Your linked chests and invited players will appear here.";
                case RULES -> "Link a chest in Base to configure where your items go.";
                case TRIP -> "Your next departure will start trip tracking.";
            }, "None");
            ui.set("#Help.Text", help);
            ui.set("#FirstLabel.Text", first); ui.set("#SecondLabel.Text", second); ui.set("#ThirdLabel.Text", third);
            ui.set("#FirstGroup.Visible", !first.isEmpty()); ui.set("#SecondGroup.Visible", !second.isEmpty()); ui.set("#ThirdGroup.Visible", !third.isEmpty());
            for (i = 0; i < buttons.length; i++) { ui.set("#Button" + (i+1) + ".Text", buttons[i]); ui.set("#Button" + (i+1) + ".Visible", !buttons[i].isEmpty()); }
        } catch (IOException error) { plugin.failure(playerRef, error); status = "Cannot load expedition data. Check the server log."; }
        ui.set("#Status.Text", status);
    }
    private UUID inspectPlayer;
    private void renderProfileChoices(UICommandBuilder ui, UIEventBuilder events, Map<String, ProfileStore.Profile> profiles) {
        int row = 0;
        for (var profile : profiles.entrySet()) {
            ui.append("#ProfileChoices", "Pages/Durkz_ExpeditionPrep_ProfileRow.ui");
            String selector = "#ProfileChoices[" + row++ + "] #Pick";
            ui.set(selector + ".Text", (profile.getKey().equals(profileName) ? "> " : "") + profile.getKey()
                    + (profile.getValue().needsRecapture() ? " (save equipment again)" : ""));
            events.addEventBinding(CustomUIEventBindingType.Activating, selector,
                    EventData.of("Action", "Profile:" + profile.getKey()));
        }
    }
    static String chooseProfile(Map<String, ProfileStore.Profile> profiles, String requested, String active) {
        if (requested != null && profiles.containsKey(requested)) return requested;
        if (active != null && profiles.containsKey(active)) return active;
        return profiles.keySet().stream().findFirst().orElse(null);
    }
    private static String filters(Turnaround.Rule rule) {
        List<String> result = new ArrayList<>();
        rule.items().forEach(s -> result.add("item:" + s)); rule.categories().forEach(s -> result.add("category:" + s)); rule.resources().forEach(s -> result.add("resource:" + s));
        return result.isEmpty() ? "fallback" : String.join(",", result);
    }
    @Override public void handleDataEvent(Ref<EntityStore> ref, Store<EntityStore> store, Input input) {
        super.handleDataEvent(ref, store, input);
        if (input.action == null) return;
        try {
            if (input.action.equals("Close")) { close(); return; }
            if (input.action.startsWith("Tab:")) {
                var page = new ManagementPage(plugin, playerRef, world, Tab.valueOf(input.action.substring(4)));
                page.networkId = networkId; page.profileName = profileName;
                store.getComponent(ref, Player.getComponentType()).getPageManager().openCustomPage(ref, store, page); return;
            }
            if (input.action.startsWith("Network:")) { networkId = UUID.fromString(input.action.substring(8)); chestIndex = 0; selectedGuest = null; confirm = null; }
            else if (input.action.startsWith("Guest:")) { selectedGuest = UUID.fromString(input.action.substring(6)); confirm = null; status = "Selected guest: " + playerName(selectedGuest); }
            else if (input.action.startsWith("Profile:")) { profileName = input.action.substring(8); entry = -1; confirm = null; }
            else if (input.action.startsWith("Entry:")) { entry = Integer.parseInt(input.action.substring(6)); confirm = null; }
            else if (input.action.startsWith("Chest:")) { chestIndex = Integer.parseInt(input.action.substring(6)); confirm = null; }
            else if (input.action.startsWith("Button")) {
                int button = Integer.parseInt(input.action.substring(6));
                if (act(button, input, ref, store)) return;
            }
        } catch (IOException error) { plugin.failure(playerRef, error); status = "Save failed. Existing data was kept; check log."; }
        catch (IllegalArgumentException error) { status = Objects.toString(error.getMessage(), "Invalid input."); }
        UICommandBuilder ui = new UICommandBuilder(); UIEventBuilder events = new UIEventBuilder();
        render(ui, events);
        if (input.action.startsWith("Profile:")) ui.set("#First.Value", profileName);
        sendUpdate(ui, events, false);
    }
    private boolean confirmed(String token) {
        if (token.equals(confirm)) { confirm = null; return true; }
        confirm = token; status = "Click the same action again to confirm. No items will be moved."; return false;
    }
    private ExpeditionState.Network owner(ExpeditionState.WorldData data) {
        var network = data.networks.get(networkId);
        if (network == null || !network.owner().equals(playerRef.getUuid())) throw new IllegalArgumentException("Only this network's owner can edit it.");
        return network;
    }
    private boolean act(int button, Input input, Ref<EntityStore> ref, Store<EntityStore> store) throws IOException {
        var state = plugin.state(world);
        var key = ExpeditionPrepPlugin.key(world, playerRef);
        var data = plugin.profiles().load(key);
        switch (tab) {
            case HUB -> {
                if (button == 1) plugin.activate(world, playerRef, networkId, profileName);
                if (button == 3) {
                    status = ExpeditionItems.claimLedger(ExpeditionPrepPlugin.inventory(store, ref));
                    return false;
                }
            }
            case PROFILES -> {
                if (button == 1) {
                    String name = input.first.trim();
                    if (data.profiles().containsKey(name) && !confirmed("Replace:" + name)) return false;
                    plugin.saveProfile(world, playerRef, store, ref, name, data.profiles().containsKey(name));
                    profileName = name; entry = -1;
                } else if (button == 6) plugin.activate(world, playerRef, networkId, profileName);
                else {
                    var profile = data.profiles().get(profileName);
                    if (profile == null) throw new IllegalArgumentException("Select a profile first.");
                    if (button == 5) {
                        if (!confirmed("Delete:" + profileName)) return false;
                        plugin.profiles().save(key, data.withoutProfile(profileName));
                    } else {
                        var slots = new ArrayList<>(profile.slots()); var reserves = new ArrayList<>(profile.targets());
                        if (button == 4) {
                            var hotbar = store.getComponent(ref, com.hypixel.hytale.server.core.inventory.InventoryComponent.Hotbar.getComponentType());
                            short active = hotbar.getActiveSlot();
                            if (active < 0 || active >= hotbar.getInventory().getCapacity()) throw new IllegalArgumentException("Hold an item in your hotbar.");
                            ItemStack held = hotbar.getInventory().getItemStack(active);
                            if (ItemStack.isEmpty(held)) throw new IllegalArgumentException("Hold an item in your hotbar.");
                            if (ExpeditionItems.isExcluded(held.getItemId())) throw new IllegalArgumentException("Editor tools and the Expedition Ledger cannot be added to an expedition kit.");
                            var target = quantity(Turnaround.target(held), Integer.parseInt(input.second.trim()));
                            reserves.removeIf(t -> Turnaround.Identity.of(t).equals(Turnaround.Identity.of(target)));
                            reserves.add(target);
                        } else {
                            if (entry < 0 || entry >= slots.size() + reserves.size()) throw new IllegalArgumentException("Select a kit entry first.");
                            if (entry < slots.size()) {
                                var slot = slots.get(entry);
                                if (button == 3) slots.remove(entry);
                                else slots.set(entry, new ProfileStore.Slot(slot.section(), slot.index(), quantity(slot.target(), Integer.parseInt(input.second.trim()))));
                            } else {
                                int n = entry - slots.size();
                                if (button == 3) reserves.remove(n); else reserves.set(n, quantity(reserves.get(n), Integer.parseInt(input.second.trim())));
                            }
                        }
                        plugin.profiles().save(key, data.withProfile(profileName, new ProfileStore.Profile(reserves, slots, profile.needsRecapture())));
                    }
                }
            }
            case NETWORK -> {
                if (button == 1) { plugin.arm(world, playerRef, store, ref, null); close(); return true; }
                if (button == 2) { plugin.armAdditional(world, playerRef, store, ref); close(); return true; }
                var network = owner(state);
                if (button == 3) {
                    if (chestIndex <= 0 || chestIndex >= network.chests().size()) throw new IllegalArgumentException("Select an additional chest. Primary removal requires Delete network.");
                    var links = new ArrayList<>(network.chests()); links.remove(chestIndex); chestIndex = 0;
                    state.networks.put(network.id(), network.withChests(links));
                } else if (button == 4 || button == 5) {
                    UUID guest = button == 5 && selectedGuest != null ? selectedGuest : playerId(input.first);
                    var guests = new HashSet<>(network.guests());
                    if (button == 4) guests.add(guest); else guests.remove(guest);
                    state.networks.put(network.id(), new ExpeditionState.Network(network.id(), network.owner(), network.chests(), guests));
                } else if (button == 6) {
                    if (!confirmed("RemoveNetwork:" + network.id())) return false;
                    state.networks.remove(network.id());
                    state.active.values().forEach(m -> m.remove(network.id()));
                }
                plugin.persist(world, state);
            }
            case RULES -> {
                if (button == 4) break;
                var network = owner(state);
                if (chestIndex < 0 || chestIndex >= network.chests().size()) throw new IllegalArgumentException("Select a chest first.");
                var links = new ArrayList<>(network.chests());
                if (button == 1) {
                    var role = Turnaround.Role.valueOf(input.first.trim().toUpperCase(Locale.ROOT));
                    int priority = Integer.parseInt(input.second.trim());
                    List<String> items = new ArrayList<>(), categories = new ArrayList<>(), resources = new ArrayList<>();
                    if (!input.third.isBlank()) for (String value : input.third.split(",")) {
                        String[] pair = value.trim().split(":", 2);
                        if (pair.length != 2 || pair[1].isBlank()) throw new IllegalArgumentException("Use item:ID, category:ID or resource:ID.");
                        switch (pair[0]) { case "item" -> items.add(pair[1]); case "category" -> categories.add(pair[1]); case "resource" -> resources.add(pair[1]); default -> throw new IllegalArgumentException("Unknown filter: " + pair[0]); }
                    }
                    links.set(chestIndex, new ExpeditionState.Link(links.get(chestIndex).position(), new Turnaround.Rule(role, priority, items, categories, resources)));
                } else {
                    int to = chestIndex + (button == 2 ? -1 : 1);
                    if (chestIndex == 0 || to < 1 || to >= links.size()) throw new IllegalArgumentException("The primary stays first; select another position.");
                    Collections.swap(links, chestIndex, to); chestIndex = to;
                }
                state.networks.put(network.id(), network.withChests(links)); plugin.persist(world, state);
            }
            case TRIP -> {
                if (button == 5) {
                    if (!playerRef.hasPermission("expeditionprep.admin")) throw new IllegalArgumentException("Requires expeditionprep.admin.");
                    adminTools = !adminTools;
                    if (!adminTools) inspectPlayer = null;
                    break;
                }
                if (button == 2 || button == 3) {
                    if (!playerRef.hasPermission("expeditionprep.admin")) throw new IllegalArgumentException("Requires expeditionprep.admin.");
                    UUID target = input.first.isBlank() ? playerRef.getUuid() : playerId(input.first);
                    if (button == 2) inspectPlayer = target;
                    else { if (!confirmed("Recover:" + target)) return false; plugin.abandon(target, playerRef); }
                } else if (button == 1) {
                    if (!confirmed("Abandon:" + playerRef.getUuid())) return false;
                    plugin.abandon(playerRef.getUuid(), playerRef);
                }
            }
        }
        status = switch (tab) {
            case HUB -> button == 1 ? "Kit activated. Crouch and use any linked chest to prepare." : "Base list refreshed.";
            case PROFILES -> button == 6 ? "Kit activated for this base." : button == 5 ? "Kit deleted. Your items were not changed." : "Kit saved. Your items were not moved.";
            case NETWORK -> "Base settings saved.";
            case RULES -> "Chest rules saved / refreshed.";
            case TRIP -> button == 1 || button == 3 ? "Tracking reset requested. Your items are unchanged. Refresh to check." : "Trip status refreshed.";
        };
        return false;
    }
    private static ProfileStore.Target quantity(ProfileStore.Target target, int quantity) {
        return new ProfileStore.Target(target.itemId(), target.metadata(), target.quality(), quantity);
    }
    static String itemName(String itemId) {
        var stack = new ItemStack(itemId);
        String raw = stack.getDisplayName().getRawText();
        if (raw != null && !raw.isBlank()) return raw;
        String readable = itemId;
        int separator = readable.indexOf('_');
        if (separator > 0 && Set.of("Armor", "Consumable", "Food", "Furniture", "Ingredient", "Ore", "Plant",
                "Rock", "Tool", "Utility", "Weapon").contains(readable.substring(0, separator))) {
            readable = readable.substring(separator + 1);
        }
        return readable.replace('_', ' ');
    }
    public static final class Input {
        public String action, first = "", second = "", third = "";
        public static final BuilderCodec<Input> CODEC = BuilderCodec.builder(Input.class, Input::new)
                .append(new KeyedCodec<>("Action", Codec.STRING), (d,v) -> d.action=v, d -> d.action).add()
                .append(new KeyedCodec<>("@First", Codec.STRING), (d,v) -> d.first=v, d -> d.first).add()
                .append(new KeyedCodec<>("@Second", Codec.STRING), (d,v) -> d.second=v, d -> d.second).add()
                .append(new KeyedCodec<>("@Third", Codec.STRING), (d,v) -> d.third=v, d -> d.third).add().build();
        public Input() {}
    }
}
