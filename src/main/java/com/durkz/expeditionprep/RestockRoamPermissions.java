package com.durkz.expeditionprep;

import com.hypixel.hytale.server.core.permissions.PermissionsModule;
import com.hypixel.hytale.server.core.universe.PlayerRef;

public final class RestockRoamPermissions {
    static final String ADMIN = "expeditionprep.admin";

    private RestockRoamPermissions() {}

    static void register() {
        PermissionsModule.registerPermission(ADMIN);
    }

    public static boolean canReceiveUpdateNotice(PlayerRef playerRef) {
        if (playerRef == null || playerRef.getUuid() == null) return false;
        if (playerRef.hasPermission(ADMIN)) return true;
        PermissionsModule permissions = PermissionsModule.get();
        return permissions != null && permissions.hasPermission(playerRef.getUuid(), ADMIN);
    }
}
