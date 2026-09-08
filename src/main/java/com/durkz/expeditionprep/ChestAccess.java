package com.durkz.expeditionprep;

import com.hypixel.hytale.server.core.plugin.PluginManager;
import com.hypixel.hytale.server.core.modules.block.BlockModule;
import com.hypixel.hytale.server.core.modules.block.components.ItemContainerBlock;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.universe.world.World;
import java.util.UUID;
import java.util.function.Predicate;

final class ChestAccess {
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
        try {
            var loader = plugin.getClass().getClassLoader();
            Class<?> registryClass = Class.forName("com.durkz.territory.TerritoryRegistry", false, loader);
            Class<?> partyClass = Class.forName("com.durkz.party.Party", false, loader);
            Class<?> permissionClass = Class.forName("com.durkz.party.PartyPermission", false, loader);
            Object registry = registryClass.getMethod("getInstance").invoke(null);
            return query(registry, partyClass, permissionClass, player, world.getName(), position);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            return false;
        }
    }
    static boolean query(Object registry, Class<?> partyClass, Class<?> permissionClass,
                         UUID player, String world, ProfileStore.Depot position) {
        try {
            Object permission = permissionClass.getField("INTERACT_CHEST").get(null);
            var chestEnabled = partyClass.getMethod("isChestInteractEnabled");
            Predicate<Object> predicate = party -> {
                try { return Boolean.TRUE.equals(chestEnabled.invoke(party)); }
                catch (ReflectiveOperationException error) { return false; }
            };
            return Boolean.TRUE.equals(registry.getClass().getMethod("isAllowedToInteract", UUID.class, String.class,
                    int.class, int.class, Predicate.class, permissionClass)
                    .invoke(registry, player, world, position.x(), position.z(), predicate, permission));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            // An installed but incompatible protection integration must never grant access.
            return false;
        }
    }
}
