package com.durkz.expeditionprep;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.server.core.entity.entities.player.pages.InteractiveCustomUIPage;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import java.io.IOException;

final class ExpeditionPage extends InteractiveCustomUIPage<ExpeditionPage.Input> {
    private final ExpeditionPrepPlugin plugin;
    private final World world;
    private ProfileStore.Data data;
    private String selected;
    private String confirmation;
    private String status;

    ExpeditionPage(ExpeditionPrepPlugin plugin, PlayerRef player, World world, ProfileStore.Data data) {
        super(player, CustomPageLifetime.CanDismiss, Input.CODEC);
        this.plugin = plugin;
        this.world = world;
        this.data = data;
        selected = data.profiles().keySet().stream().findFirst().orElse(null);
        status = plugin.report(player);
    }

    @Override
    public void build(Ref<EntityStore> ref, UICommandBuilder ui, UIEventBuilder events, Store<EntityStore> store) {
        ui.append("Pages/Durkz_ExpeditionPrep.ui");
        for (String action : new String[]{"Save", "Replace", "Delete", "Link", "Prepare", "Refresh", "Close"}) {
            EventData event = EventData.of("Action", action);
            if (action.equals("Save")) event.append("@Name", "#Name.Value");
            events.addEventBinding(CustomUIEventBindingType.Activating, "#" + action, event);
        }
        render(ref, store, ui, events);
    }

    private void render(Ref<EntityStore> ref, Store<EntityStore> store, UICommandBuilder ui, UIEventBuilder events) {
        ui.set("#Depot.Text", data.depot() == null ? "Depot: no chest linked" : "Depot: " + data.depot().coordinates());
        ui.set("#Status.Text", status);
        ui.set("#Selected.Text", selected == null ? "No profile selected" : selected);
        ui.clear("#Profiles");
        int index = 0;
        for (String name : data.profiles().keySet()) {
            ui.append("#Profiles", "Pages/Durkz_ExpeditionPrep_ProfileRow.ui");
            String selector = "#Profiles[" + index++ + "] #Pick";
            ui.set(selector + ".Text", (name.equals(selected) ? "> " : "") + name);
            events.addEventBinding(CustomUIEventBindingType.Activating, selector, EventData.of("Action", "Select:" + name));
        }
        ui.clear("#Items");
        var profile = data.profiles().get(selected);
        if (profile == null) return;
        var inventory = ExpeditionPrepPlugin.inventory(store, ref);
        index = 0;
        for (var target : profile.targets()) {
            ui.append("#Items", "Pages/Durkz_ExpeditionPrep_ItemRow.ui");
            String row = "#Items[" + index++ + "] ";
            int have = Supplies.count(inventory, target);
            ui.set(row + "#Item.ItemId", target.itemId());
            ui.set(row + "#Label.Text", new ItemStack(target.itemId()).getDisplayName());
            ui.set(row + "#Count.Text", "Have " + have + " / " + target.quantity() + "   |   Missing " + Math.max(0, target.quantity() - have));
        }
    }

    @Override
    public void handleDataEvent(Ref<EntityStore> ref, Store<EntityStore> store, Input input) {
        super.handleDataEvent(ref, store, input);
        if (input.action == null) return;
        String action = input.action;
        if (action.equals("Close")) { close(); return; }
        if (!action.equals("Replace") && !action.equals("Delete")) confirmation = null;
        try {
            switch (action) {
                case "Save" -> {
                    String name = input.name == null ? "" : input.name.trim();
                    plugin.saveProfile(world, playerRef, store, ref, name, false);
                    selected = name;
                    status = "Profile saved from your hotbar. Counts include your inventory and backpack when preparing.";
                }
                case "Replace", "Delete" -> {
                    if (selected == null) throw new IllegalArgumentException("Select a profile first.");
                    String token = action + ":" + selected;
                    if (!token.equals(confirmation)) {
                        confirmation = token;
                        status = "Click " + action + " again to " + (action.equals("Replace") ? "replace '" + selected + "' with your current hotbar." : "delete '" + selected + "'.");
                        break;
                    }
                    confirmation = null;
                    if (action.equals("Replace")) {
                        plugin.saveProfile(world, playerRef, store, ref, selected, true);
                        status = "Profile replaced from your current hotbar.";
                    } else {
                        var key = ExpeditionPrepPlugin.key(world, playerRef);
                        plugin.profiles().save(key, plugin.profiles().load(key).withoutProfile(selected));
                        selected = null;
                        status = "Profile deleted. Your items were not changed.";
                    }
                }
                case "Link" -> { plugin.arm(world, playerRef, store, ref, null); close(); return; }
                case "Prepare" -> {
                    if (selected == null) throw new IllegalArgumentException("Select a profile first.");
                    plugin.arm(world, playerRef, store, ref, selected);
                    close();
                    return;
                }
                case "Refresh" -> status = "Inventory counts refreshed.";
                default -> {
                    if (!action.startsWith("Select:")) return;
                    String name = action.substring(7);
                    if (!data.profiles().containsKey(name)) return;
                    selected = name;
                    status = "Selected '" + selected + "'. Prepare will top up from your linked chest.";
                }
            }
            data = plugin.profiles().load(ExpeditionPrepPlugin.key(world, playerRef));
            if (!data.profiles().containsKey(selected)) selected = data.profiles().keySet().stream().findFirst().orElse(null);
        } catch (IOException e) {
            plugin.failure(playerRef, e);
            status = "Could not read or save profiles. Check the server log.";
        } catch (IllegalArgumentException e) { status = e.getMessage(); }
        UICommandBuilder ui = new UICommandBuilder();
        UIEventBuilder events = new UIEventBuilder();
        render(ref, store, ui, events);
        sendUpdate(ui, events, false);
    }

    public static final class Input {
        public static final BuilderCodec<Input> CODEC = BuilderCodec.builder(Input.class, Input::new)
                .append(new KeyedCodec<>("Action", Codec.STRING), (data, value) -> data.action = value, data -> data.action).add()
                .append(new KeyedCodec<>("@Name", Codec.STRING), (data, value) -> data.name = value, data -> data.name).add()
                .build();
        public String action;
        public String name;
        public Input() {}
    }
}
