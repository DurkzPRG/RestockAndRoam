package com.durkz.expeditionprep;

import com.hypixel.hytale.server.core.plugin.PluginManager;
import com.hypixel.hytale.server.core.modules.block.BlockModule;
import com.hypixel.hytale.server.core.modules.block.components.ItemContainerBlock;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.universe.world.World;
import java.lang.reflect.Method;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.function.Predicate;

final class ChestAccess {
    /** Reflection handles for one loaded BetterClaim instance. The registry is read on every check. */
    record Check(Callable<Object> registry, Method interact, Method chestEnabled, Object permission) {
        static Check bind(Class<?> registryClass, Callable<Object> registry, Class<?> partyClass,
                          Class<?> permissionClass) throws ReflectiveOperationException {
            return new Check(registry,
                    registryClass.getMethod("isAllowedToInteract", UUID.class, String.class,
                            int.class, int.class, Predicate.class, permissionClass),
                    partyClass.getMethod("isChestInteractEnabled"),
                    permissionClass.getField("INTERACT_CHEST").get(null));
        }
        boolean allowed(UUID player, String world, ProfileStore.Depot position) {
            try {
                Predicate<Object> predicate = party -> {
                    try { return Boolean.TRUE.equals(chestEnabled.invoke(party)); }
                    catch (ReflectiveOperationException error) { return false; }
                };
                return Boolean.TRUE.equals(interact.invoke(registry.call(), player, world,
                        position.x(), position.z(), predicate, permission));
            } catch (Exception | LinkageError error) {
                // An installed but incompatible protection integration must never grant access.
                return false;
            }
        }
    }
    private record Bound(Object plugin, Check check) {}
    private static volatile Bound bound;

    static ItemContainer resolve(World world, ProfileStore.Depot position) {
        var chunks = world.getChunkStore();
        var section = chunks.getChunkSectionReferenceAtBlock(position.x(), position.y(), position.z());
        if (section == null || !section.isValid()) return null;
        var block = BlockModule.getBlockEntity(chunks.getStore(), section, position.x(), position.y(), position.z());
        if (block == null || !block.isValid()) return null;
        var component = chunks.getStore().getComponent(block, ItemContainerBlock.getComponentType());
        return component == null ? null : component.getItemContainer();
    }
    static boolean allowed(UUID player, World world, ProfileStore.Depot position) {
        var plugin = PluginManager.get().getPlugins().stream()
                .filter(p -> "BetterClaim".equals(p.getManifest().getName())).findFirst().orElse(null);
        if (plugin == null) return true;
        if (!plugin.isEnabled()) return false;
        var cached = bound;
        if (cached == null || cached.plugin() != plugin) {
            try {
                var loader = plugin.getClass().getClassLoader();
                Class<?> registryClass = Class.forName("com.durkz.territory.TerritoryRegistry", false, loader);
                Method getInstance = registryClass.getMethod("getInstance");
                cached = new Bound(plugin, Check.bind(registryClass, () -> getInstance.invoke(null),
                        Class.forName("com.durkz.party.Party", false, loader),
                        Class.forName("com.durkz.party.PartyPermission", false, loader)));
                bound = cached;
            } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
                return false;
            }
        }
        return cached.check().allowed(player, world.getName(), position);
    }
    static boolean query(Object registry, Class<?> partyClass, Class<?> permissionClass,
                         UUID player, String world, ProfileStore.Depot position) {
        try {
            return Check.bind(registry.getClass(), () -> registry, partyClass, permissionClass)
                    .allowed(player, world, position);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            return false;
        }
    }
}
