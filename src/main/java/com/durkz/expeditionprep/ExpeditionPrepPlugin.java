package com.durkz.expeditionprep;

import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.DelayedEntitySystem;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.entities.player.windows.ContainerBlockWindow;
import com.hypixel.hytale.server.core.entity.entities.player.windows.Window;
import com.hypixel.hytale.server.core.event.events.player.PlayerDisconnectEvent;
import com.hypixel.hytale.server.core.event.events.player.AddPlayerToWorldEvent;
import com.hypixel.hytale.server.core.inventory.InventoryComponent;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.Set;
import java.util.HashSet;
import java.util.function.Function;
import com.hypixel.hytale.component.system.EntityEventSystem;
import com.hypixel.hytale.server.core.event.events.ecs.UseBlockEvent;
import com.hypixel.hytale.server.core.entity.movement.MovementStatesComponent;

public final class ExpeditionPrepPlugin extends JavaPlugin {
    private ProfileStore profiles;
    private ExpeditionState stateStore;
    private RestockRoamConfig config = new RestockRoamConfig();
    private final Map<UUID, ExpeditionState.WorldData> states = new ConcurrentHashMap<>();
    private final OperationGate gate = new OperationGate();
    private final Map<UUID, Gesture> gestures = new ConcurrentHashMap<>();
    private record Gesture(ProfileStore.Key key, Ref<EntityStore> ref, ProfileStore.Depot position, long expires) {}
    private final Map<UUID, Pending> pending = new ConcurrentHashMap<>();
    private final Map<UUID, String> reports = new ConcurrentHashMap<>();

    private record Pending(ProfileStore.Key key, Ref<EntityStore> ref, long expires,
                           String name, ProfileStore.Profile profile, ProfileStore.Depot depot) {}

    public ExpeditionPrepPlugin(JavaPluginInit init) { super(init); }

    @Override
    protected void setup() {
        RestockRoamPermissions.register();
        // Keep existing player data when upgrading from the prototype identity.
        var legacyData = getDataDirectory().resolveSibling("durkz_ExpeditionPrep");
        var dataDirectory = java.nio.file.Files.isDirectory(legacyData) ? legacyData : getDataDirectory();
        try {
            config = RestockRoamConfig.load(dataDirectory);
        } catch (IOException error) {
            getLogger().atWarning().withCause(error).log("Could not load config. Using defaults; existing file was kept.");
        }
        profiles = new ProfileStore(dataDirectory.resolve("profiles"));
        stateStore = new ExpeditionState(dataDirectory.resolve("worlds"));
        com.hypixel.hytale.server.core.modules.interaction.interaction.config.server.OpenCustomUIInteraction
                .registerCustomPageSupplier(this, ManagementPage.class, "Durkz_ExpeditionLedger",
                        (ref, accessor, player, context) -> {
                            var world = accessor.getExternalData().getWorld();
                            try {
                                pending.remove(player.getUuid());
                                migrate(world, player);
                                return new ManagementPage(this, player, world, ManagementPage.Tab.HUB);
                            } catch (IOException error) { failure(player, error); return null; }
                        });
        getCommandRegistry().registerCommand(new ExpeditionCommand());
        getEntityStoreRegistry().registerSystem(new DepotSystem());
        getEntityStoreRegistry().registerSystem(new UseSystem());
        getEventRegistry().registerGlobal(AddPlayerToWorldEvent.class, event -> {
            PlayerRef player = event.getHolder().getComponent(PlayerRef.getComponentType());
            com.durkz.expeditionprep.update.ModUpdateChecker.getInstance().notifyPlayer(player);
        });
        getEventRegistry().registerGlobal(PlayerDisconnectEvent.class, event -> {
            pending.remove(event.getPlayerRef().getUuid());
            gestures.remove(event.getPlayerRef().getUuid());
            gate.disconnect(event.getPlayerRef().getUuid());
            reports.remove(event.getPlayerRef().getUuid());
            com.durkz.expeditionprep.update.ModUpdateChecker.getInstance()
                    .forgetPlayer(event.getPlayerRef().getUuid());
        });
        getLogger().at(Level.INFO).log("Restock & Roam loaded. Open /eprep to claim an Expedition Ledger.");
    }

    @Override
    protected void start() {
        super.start();
        com.durkz.expeditionprep.update.ModUpdateChecker.getInstance().start(this, config.checkForUpdates);
    }

    @Override
    protected void shutdown() {
        com.durkz.expeditionprep.update.ModUpdateChecker.getInstance().shutdown();
        pending.clear(); reports.clear(); gestures.clear(); states.clear(); gate.clear();
        super.shutdown();
    }

    ProfileStore profiles() { return profiles; }
    String report(PlayerRef player) { return reports.getOrDefault(player.getUuid(), "Save your hotbar, link a chest, then prepare."); }

    static ProfileStore.Key key(World world, PlayerRef player) {
        return new ProfileStore.Key(world.getWorldConfig().getUuid(), player.getUuid());
    }

    static ItemContainer inventory(Store<EntityStore> store, Ref<EntityStore> ref) {
        return InventoryComponent.getCombined(store, ref, InventoryComponent.HOTBAR_STORAGE_BACKPACK);
    }

    static Map<ProfileStore.Section, ItemContainer> sections(Store<EntityStore> store, Ref<EntityStore> ref) {
        Map<ProfileStore.Section, ItemContainer> result = new EnumMap<>(ProfileStore.Section.class);
        for (var section : ProfileStore.Section.values()) {
            InventoryComponent component = switch (section) {
                case HOTBAR -> store.getComponent(ref, InventoryComponent.Hotbar.getComponentType());
                case ARMOR -> store.getComponent(ref, InventoryComponent.Armor.getComponentType());
                case UTILITY -> store.getComponent(ref, InventoryComponent.Utility.getComponentType());
                case TOOLS -> store.getComponent(ref, InventoryComponent.Tool.getComponentType());
                case STORAGE -> store.getComponent(ref, InventoryComponent.Storage.getComponentType());
                case BACKPACK -> store.getComponent(ref, InventoryComponent.Backpack.getComponentType());
            };
            if (component != null) result.put(section, component.getInventory());
        }
        return result;
    }

    static ItemContainer linkedContainer(ExpeditionState.Link link, ProfileStore.Depot interactedPosition,
                                         ItemContainer interactedContainer,
                                         Function<ProfileStore.Depot, ItemContainer> resolver) {
        return link.position().equals(interactedPosition) ? interactedContainer : resolver.apply(link.position());
    }

    ExpeditionState.WorldData state(World world) throws IOException {
        UUID id = world.getWorldConfig().getUuid();
        var data = states.get(id);
        if (data == null) { data = stateStore.load(id); states.put(id, data); }
        return data;
    }

    void persist(World world, ExpeditionState.WorldData data) throws IOException {
        try { stateStore.save(world.getWorldConfig().getUuid(), data); }
        catch (IOException | RuntimeException error) { states.remove(world.getWorldConfig().getUuid()); throw error; }
    }

    ExpeditionState.StoredTrip trip(UUID player) throws IOException { return stateStore.findTrip(player); }
    ExpeditionState.WorldData savedState(UUID world) throws IOException { return stateStore.load(world); }

    void abandon(UUID target, PlayerRef requester) throws IOException {
        if (!target.equals(requester.getUuid()) && !requester.hasPermission("expeditionprep.admin"))
            throw new IllegalArgumentException("Requires expeditionprep.admin.");
        var stored = trip(target);
        if (stored == null) return;
        var origin = com.hypixel.hytale.server.core.universe.Universe.get().getWorld(stored.world());
        if (origin != null) {
            origin.execute(() -> {
                try {
                    var data = state(origin);
                    data.trips.remove(target); persist(origin, data);
                    tell(requester, "Trip abandoned. No items were moved. Refresh Trip status.");
                } catch (IOException error) { failure(requester, error); }
            });
        } else {
            var data = stateStore.load(stored.world());
            data.trips.remove(target); stateStore.save(stored.world(), data);
            states.remove(stored.world());
            tell(requester, "Trip abandoned. No items were moved.");
        }
    }

    void migrate(World world, PlayerRef player) throws IOException {
        var data = state(world);
        boolean namesChanged = false;
        String displayName = world.getWorldConfig().getDisplayName();
        if (displayName == null || displayName.isBlank()) displayName = world.getName();
        namesChanged = !java.util.Objects.equals(displayName, data.worldName);
        data.worldName = displayName;
        if (data.playerNames == null) data.playerNames = new java.util.LinkedHashMap<>();
        for (var online : com.hypixel.hytale.server.core.universe.Universe.get().getPlayers())
            namesChanged |= !online.getUsername().equals(data.playerNames.put(online.getUuid(), online.getUsername()));
        if (namesChanged) persist(world, data);
        if (data.migrated.contains(player.getUuid())) return;
        var old = profiles.load(key(world, player));
        if (old.depot() != null && data.owned(player.getUuid()) == null
                && data.networks.values().stream().flatMap(n -> n.chests().stream()).noneMatch(c -> c.position().equals(old.depot()))) {
            var network = new ExpeditionState.Network(UUID.randomUUID(), player.getUuid(),
                    List.of(new ExpeditionState.Link(old.depot(), Turnaround.Rule.fallback())), Set.of());
            data.networks.put(network.id(), network);
        }
        data.migrated.add(player.getUuid());
        persist(world, data);
    }

    void activate(World world, PlayerRef player, UUID networkId, String name) throws IOException {
        var data = state(world);
        var network = data.networks.get(networkId);
        if (network == null || !network.allows(player.getUuid())) throw new IllegalArgumentException("Network access denied.");
        if (!profiles.load(key(world, player)).profiles().containsKey(name)) throw new IllegalArgumentException("Select your own saved profile first.");
        data.active.computeIfAbsent(player.getUuid(), id -> new java.util.LinkedHashMap<>()).put(networkId, name);
        persist(world, data);
        tell(player, "Active kit: '" + name + "'. Crouch and use any linked chest to turn around.");
    }

    void saveProfile(World world, PlayerRef player, Store<EntityStore> store, Ref<EntityStore> ref,
                     String name, boolean replace) throws IOException {
        ProfileStore.validateName(name);
        var key = key(world, player);
        var data = profiles.load(key);
        if (!replace && data.profiles().containsKey(name)) {
            throw new IllegalArgumentException("That name already exists. Select it and use Replace, or choose a new name.");
        }
        var captured = Turnaround.capture(sections(store, ref));
        if (replace && data.profiles().containsKey(name))
            captured = new ProfileStore.Profile(data.profiles().get(name).targets(), captured.slots(), false);
        profiles.save(key, data.withProfile(name, captured));
        tell(player, "Saved full kit as '" + name + "'. Items were not moved.");
    }

    void arm(World world, PlayerRef player, Store<EntityStore> store, Ref<EntityStore> ref,
             String name) throws IOException {
        var key = key(world, player);
        migrate(world, player);
        var data = profiles.load(key);
        var profile = name == null ? null : data.profiles().get(name);
        if (name != null && profile == null) throw new IllegalArgumentException("Select a saved profile first.");
        if (name != null && !name.equals("@link")) {
            var network = state(world).owned(player.getUuid());
            if (network == null) throw new IllegalArgumentException("Link a primary chest first.");
            activate(world, player, network.id(), name);
            return;
        }
        Player entity = store.getComponent(ref, Player.getComponentType());
        if (entity == null) return;
        // A new vanilla chest opening must succeed after the request. Never reuse a stale window.
        entity.getWindowManager().closeAllWindows(ref, store);
        pending.put(player.getUuid(), new Pending(key, ref,
                System.nanoTime() + TimeUnit.SECONDS.toNanos(30), name, profile, data.depot()));
        tell(player, name == null ? "Open your depot chest within 30 seconds to link it."
                : "Open your linked chest at " + data.depot().coordinates() + " within 30 seconds to prepare '" + name + "'.");
    }

    void cancel(PlayerRef player) {
        pending.remove(player.getUuid());
        tell(player, "Pending depot action cancelled.");
    }

    void tell(PlayerRef player, String text) {
        reports.put(player.getUuid(), text);
        player.sendMessage(Message.raw(text).color("#a8d9c0"));
    }

    void failure(PlayerRef player, IOException error) {
        getLogger().at(Level.WARNING).withCause(error).log("Could not read or save ExpeditionPrep profiles for %s", player.getUuid());
        tell(player, "Could not read or save your profiles. Existing files were kept. Check the server log.");
    }

    private final class ExpeditionCommand extends AbstractPlayerCommand {
        ExpeditionCommand() {
            super("expedition", "Prepare supplies from your depot chest");
            addAliases("eprep", "restock", "rr");
            requireNoPermission();
            addSubCommand(new ActionCommand("bind", false));
            addSubCommand(new ActionCommand("save", true));
            addSubCommand(new ActionCommand("prepare", true));
            addSubCommand(new ActionCommand("cancel", false));
        }

        @Override
        protected void execute(CommandContext ctx, Store<EntityStore> store, Ref<EntityStore> ref,
                               PlayerRef playerRef, World world) {
            try {
                pending.remove(playerRef.getUuid());
                migrate(world, playerRef);
                var data = profiles.load(key(world, playerRef));
                Player player = store.getComponent(ref, Player.getComponentType());
                if (player != null) player.getPageManager().openCustomPage(ref, store,
                        new ManagementPage(ExpeditionPrepPlugin.this, playerRef, world, ManagementPage.Tab.HUB));
            } catch (IOException e) { failure(playerRef, e); }
        }
    }

    private final class ActionCommand extends AbstractPlayerCommand {
        private final com.hypixel.hytale.server.core.command.system.arguments.system.RequiredArg<String> name;
        ActionCommand(String action, boolean needsName) {
            super(action, "Expedition " + action);
            requireNoPermission();
            name = needsName ? withRequiredArg("name", "Saved profile name", ArgTypes.STRING) : null;
        }
        @Override
        protected void execute(CommandContext ctx, Store<EntityStore> store, Ref<EntityStore> ref,
                               PlayerRef player, World world) {
            try {
                switch (getName()) {
                    case "save" -> saveProfile(world, player, store, ref, name.get(ctx), false);
                    case "bind" -> arm(world, player, store, ref, null);
                    case "prepare" -> arm(world, player, store, ref, name.get(ctx));
                    case "cancel" -> cancel(player);
                    default -> throw new IllegalStateException("Unknown expedition command");
                }
            } catch (IOException e) { failure(player, e); }
            catch (IllegalArgumentException e) { tell(player, e.getMessage()); }
        }
    }

    private final class DepotSystem extends DelayedEntitySystem<EntityStore> {
        private final Query<EntityStore> query = Query.and(Player.getComponentType(), PlayerRef.getComponentType());
        DepotSystem() { super(0.2f); }
        @Override public Query<EntityStore> getQuery() { return query; }
        @Override public boolean isParallel(int size, int workers) { return false; }

        @Override
        public void tick(float dt, int index, ArchetypeChunk<EntityStore> chunk,
                         Store<EntityStore> store, CommandBuffer<EntityStore> buffer) {
            if (pending.isEmpty() && gestures.isEmpty()) return;
            PlayerRef playerRef = chunk.getComponent(index, PlayerRef.getComponentType());
            Gesture gesture = gestures.get(playerRef.getUuid());
            if (gesture != null) {
                var world = store.getExternalData().getWorld();
                var ref = chunk.getReferenceTo(index);
                if (System.nanoTime() > gesture.expires() || !ref.equals(gesture.ref()) || !key(world, playerRef).equals(gesture.key())) {
                    gestures.remove(playerRef.getUuid(), gesture);
                } else {
                    var player = chunk.getComponent(index, Player.getComponentType());
                    for (Window window : List.copyOf(player.getWindowManager().getWindows())) {
                        if (window instanceof ContainerBlockWindow chest && chest.validate(ref, buffer)
                                && gesture.position().equals(new ProfileStore.Depot(chest.getX(), chest.getY(), chest.getZ()))) {
                            gestures.remove(playerRef.getUuid(), gesture);
                            try { turnaround(world, playerRef, store, ref, player, chest); }
                            catch (IOException error) { failure(playerRef, error); }
                            catch (RuntimeException error) {
                                getLogger().at(Level.WARNING).withCause(error).log("Turnaround failed for %s", playerRef.getUuid());
                                tell(playerRef, "Turnaround stopped. Inspect Trip status before retrying.");
                            }
                            break;
                        }
                    }
                }
            }
            Pending action = pending.get(playerRef.getUuid());
            if (action == null) return;
            var ref = chunk.getReferenceTo(index);
            World world = store.getExternalData().getWorld();
            if (System.nanoTime() > action.expires() || !ref.equals(action.ref())
                    || !key(world, playerRef).equals(action.key())) {
                if (pending.remove(playerRef.getUuid(), action)) tell(playerRef, "Depot action expired or cancelled after changing world. Open /expedition to retry.");
                return;
            }
            Player player = chunk.getComponent(index, Player.getComponentType());
            for (Window window : player.getWindowManager().getWindows()) {
                if (!(window instanceof ContainerBlockWindow chest) || !chest.validate(ref, buffer)) continue;
                var depot = new ProfileStore.Depot(chest.getX(), chest.getY(), chest.getZ());
                if (action.profile() != null && !depot.equals(action.depot())) continue;
                if (!pending.remove(playerRef.getUuid(), action)) return;
                try {
                    if (action.profile() == null) {
                        link(world, playerRef, depot, "@link".equals(action.name()));
                    } else {
                        List<Supplies.Line> lines = Supplies.prepare(action.profile(), chest.getItemContainer(), inventory(store, ref));
                        int moved = lines.stream().mapToInt(Supplies.Line::moved).sum();
                        int missing = lines.stream().mapToInt(Supplies.Line::missing).sum();
                        tell(playerRef, "'" + action.name() + "': moved " + moved + " items. "
                                + (missing == 0 ? "Ready to go." : missing + " still missing (chest stock or inventory space)."));
                        for (var line : lines) {
                            if (line.missing() > 0) playerRef.sendMessage(Message.raw("Missing " + line.missing() + " x " + line.target().itemId()).color("#edc18b"));
                        }
                    }
                } catch (IOException e) { failure(playerRef, e); }
                catch (IllegalArgumentException e) { tell(playerRef, e.getMessage()); }
                return;
            }
        }
    }

    void armAdditional(World world, PlayerRef player, Store<EntityStore> store, Ref<EntityStore> ref) throws IOException {
        if (state(world).owned(player.getUuid()) == null) throw new IllegalArgumentException("Link a primary chest first.");
        arm(world, player, store, ref, null);
        var action = pending.get(player.getUuid());
        pending.put(player.getUuid(), new Pending(action.key(), ref, action.expires(), "@link", null, action.depot()));
        tell(player, "Open an additional chest within 30 seconds, within 16 blocks of your primary.");
    }

    private void link(World world, PlayerRef player, ProfileStore.Depot position, boolean additional) throws IOException {
        var data = state(world);
        for (var network : data.networks.values()) for (var chest : network.chests())
            if (chest.position().equals(position)) throw new IllegalArgumentException("This chest is already linked.");
        var network = data.owned(player.getUuid());
        if (additional) {
            if (network == null) throw new IllegalArgumentException("Link a primary chest first.");
            var links = new ArrayList<>(network.chests());
            links.add(new ExpeditionState.Link(position, Turnaround.Rule.fallback()));
            network = network.withChests(links);
        } else {
            if (network != null) throw new IllegalArgumentException("A primary is already linked. Remove the network explicitly before replacing it.");
            network = new ExpeditionState.Network(UUID.randomUUID(), player.getUuid(),
                    List.of(new ExpeditionState.Link(position, Turnaround.Rule.fallback())), Set.of());
        }
        data.networks.put(network.id(), network);
        persist(world, data);
        tell(player, "Chest linked at " + position.coordinates() + ". Configure it in /expedition.");
    }

    private void turnaround(World world, PlayerRef playerRef, Store<EntityStore> store, Ref<EntityStore> ref,
                            Player player, ContainerBlockWindow chest) throws IOException {
        var data = state(world);
        var interactedPosition = new ProfileStore.Depot(chest.getX(), chest.getY(), chest.getZ());
        var network = data.atLinkedChest(interactedPosition);
        if (network == null || !network.allows(playerRef.getUuid())) return;
        String name = data.active(playerRef.getUuid(), network.id());
        var profile = profiles.load(key(world, playerRef)).profiles().get(name);
        if (profile == null) return;
        var originTrip = trip(playerRef.getUuid());
        if (originTrip != null && !originTrip.world().equals(world.getWorldConfig().getUuid())) {
            tell(playerRef, "Trip belongs to another world. Return to its origin or abandon it in Trip status."); return;
        }
        var trip = data.trips.get(playerRef.getUuid());
        if (trip != null && (!trip.network().equals(network.id()) || trip.interrupted())) {
            tell(playerRef, "Trip belongs to another network or needs recovery. Open Trip status."); return;
        }
        if (!ChestAccess.allowed(playerRef.getUuid(), world, interactedPosition)) return;
        if (!gate.enter(network.id())) return;
        try {
            List<Turnaround.Chest> resolved = new ArrayList<>();
            List<String> skipped = new ArrayList<>();
            for (int i = 0; i < network.chests().size(); i++) {
                var link = network.chests().get(i);
                if (!ChestAccess.allowed(playerRef.getUuid(), world, link.position())) { skipped.add("Access denied: " + link.position().coordinates()); continue; }
                var container = linkedContainer(link, interactedPosition, chest.getItemContainer(),
                        position -> ChestAccess.resolve(world, position));
                if (container == null) { skipped.add("Unavailable chest: " + link.position().coordinates()); continue; }
                resolved.add(new Turnaround.Chest(container, link.rule(), i));
            }
            var baseline = trip == null ? null : trip.baseline();
            // Persist an interruption marker before moving anything. Recovery never replays transfers.
            data.trips.put(playerRef.getUuid(), new ExpeditionState.Trip(network.id(), name,
                    baseline == null ? List.of() : baseline, trip == null ? List.of() : trip.pending(),
                    "Operation interrupted. Inspect inventory, then abandon this trip to recover.", List.of(), true));
            persist(world, data);
            player.getWindowManager().closeWindow(ref, chest.getId(), store);
            var result = Turnaround.execute(profile, sections(store, ref), resolved, baseline);
            skipped.addAll(result.details());
            data.trips.put(playerRef.getUuid(), new ExpeditionState.Trip(network.id(), name, result.baseline(), result.pending(),
                    result.summary(), skipped, false));
            persist(world, data);
            tell(playerRef, result.summary());
            getLogger().at(Level.INFO).log("Turnaround completed for %s in %s at %s: %s Pending item types: %s",
                    playerRef.getUsername(), world.getName(), interactedPosition.coordinates(),
                    result.summary(), result.pending().size());
        } finally { gate.leave(network.id()); }
    }

    private final class UseSystem extends EntityEventSystem<EntityStore, UseBlockEvent.Post> {
        UseSystem() { super(UseBlockEvent.Post.class); }
        @Override public Query<EntityStore> getQuery() { return Query.and(Player.getComponentType(), PlayerRef.getComponentType(), MovementStatesComponent.getComponentType()); }
        @Override public void handle(int index, ArchetypeChunk<EntityStore> chunk, Store<EntityStore> store,
                                     CommandBuffer<EntityStore> buffer, UseBlockEvent.Post event) {
            var movement = chunk.getComponent(index, MovementStatesComponent.getComponentType());
            if (!movement.getMovementStates().crouching) return;
            var player = chunk.getComponent(index, PlayerRef.getComponentType());
            if (pending.containsKey(player.getUuid()) || gestures.containsKey(player.getUuid())) return;
            long now = System.nanoTime();
            var position = event.getTargetBlock();
            var depot = new ProfileStore.Depot(position.x, position.y, position.z);
            var world = store.getExternalData().getWorld();
            try {
                var data = state(world);
                var network = data.atLinkedChest(depot);
                if (network == null || !network.allows(player.getUuid()) || data.active(player.getUuid(), network.id()) == null) return;
                if (!gate.gesture(player.getUuid(), now)) return;
                // Post is required, and a valid opened window must also exist before any transfer.
                gestures.put(player.getUuid(), new Gesture(key(world, player), chunk.getReferenceTo(index), depot, now + 1_000_000_000L));
            } catch (IOException error) { failure(player, error); }
        }
    }
}
