package com.durkz.expeditionprep.update;

import com.durkz.expeditionprep.RestockRoamPermissions;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

/** Checks the Restock & Roam page for a newer JAR and notifies only operators or explicit admins. */
public final class ModUpdateChecker {
    private static final ModUpdateChecker INSTANCE = new ModUpdateChecker();
    static final String PAGE_URL = "https://durkzprgmods.pages.dev/mods/restock-and-roam/";
    static final String DOWNLOAD_URL = "https://www.curseforge.com/hytale/mods/restock-and-roam";
    private static final Pattern JAR_VERSION = Pattern.compile("RestockAndRoam-(\\d+(?:\\.\\d+){1,3})\\.jar");

    private final AtomicReference<String> latestVersion = new AtomicReference<>();
    private final Set<UUID> notifiedThisSession = ConcurrentHashMap.newKeySet();
    private volatile boolean stopped;
    private String currentVersion = "0";

    private ModUpdateChecker() {}

    public static ModUpdateChecker getInstance() {
        return INSTANCE;
    }

    public void start(JavaPlugin plugin, boolean enabled) {
        stopped = false;
        latestVersion.set(null);
        notifiedThisSession.clear();
        if (!enabled) {
            plugin.getLogger().atInfo().log("Update check disabled.");
            return;
        }
        currentVersion = plugin.getManifest().getVersion().toString();
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(8))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        HttpRequest request = HttpRequest.newBuilder(URI.create(PAGE_URL))
                .timeout(Duration.ofSeconds(12))
                .header("User-Agent", "RestockAndRoam/" + currentVersion + " (Hytale plugin update check)")
                .GET()
                .build();
        client.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenAccept(response -> handleResponse(plugin, response))
                .exceptionally(error -> {
                    plugin.getLogger().atWarning().withCause(error).log("Update check request failed.");
                    return null;
                });
    }

    public void shutdown() {
        stopped = true;
        latestVersion.set(null);
        notifiedThisSession.clear();
    }

    public void forgetPlayer(UUID playerId) {
        if (playerId != null) notifiedThisSession.remove(playerId);
    }

    public void notifyPlayer(PlayerRef playerRef) {
        if (stopped || playerRef == null || !RestockRoamPermissions.canReceiveUpdateNotice(playerRef)) return;
        String latest = latestVersion.get();
        if (latest == null || !isNewer(latest, currentVersion) || !notifiedThisSession.add(playerRef.getUuid())) return;
        playerRef.sendMessage(Message.raw("[Restock & Roam] Update available: " + latest
                + " (running " + currentVersion + ")").color("#FFAA00"));
        playerRef.sendMessage(rainbowLink("Click here to download on CurseForge", DOWNLOAD_URL));
    }

    static String parseLatestJarVersion(String body) {
        if (body == null || body.isBlank()) return null;
        var matcher = JAR_VERSION.matcher(body);
        return matcher.find() ? matcher.group(1) : null;
    }

    private void handleResponse(JavaPlugin plugin, HttpResponse<String> response) {
        if (stopped) return;
        if (response.statusCode() != 200) {
            plugin.getLogger().atWarning().log("Update check HTTP " + response.statusCode());
            return;
        }
        String latest = parseLatestJarVersion(response.body());
        if (latest == null) {
            plugin.getLogger().atWarning().log("Update check could not find RestockAndRoam-*.jar on the mod page.");
            return;
        }
        latestVersion.set(latest);
        if (isNewer(latest, currentVersion)) {
            plugin.getLogger().atInfo().log("Newer Restock & Roam available: " + latest
                    + " (running " + currentVersion + ")");
            Universe universe = Universe.get();
            if (universe != null) for (PlayerRef online : universe.getPlayers()) notifyPlayer(online);
        } else {
            plugin.getLogger().atInfo().log("Restock & Roam is up to date (" + currentVersion + ").");
        }
    }

    public static boolean isNewer(String latest, String current) {
        return compareVersions(current, latest) < 0;
    }

    static int compareVersions(String left, String right) {
        int[] a = parseVersion(left);
        int[] b = parseVersion(right);
        for (int i = 0; i < 3; i++) if (a[i] != b[i]) return Integer.compare(a[i], b[i]);
        return 0;
    }

    private static int[] parseVersion(String version) {
        int[] parts = new int[3];
        if (version == null || version.isBlank()) return parts;
        int cut = version.indexOf('-');
        String numeric = cut >= 0 ? version.substring(0, cut) : version;
        if (numeric.startsWith("v") || numeric.startsWith("V")) numeric = numeric.substring(1);
        String[] split = numeric.split("\\.");
        for (int i = 0; i < 3 && i < split.length; i++) {
            try { parts[i] = Integer.parseInt(split[i]); }
            catch (NumberFormatException ignored) { parts[i] = 0; }
        }
        return parts;
    }

    public static Message rainbowLink(String text, String url) {
        Message root = Message.empty().link(url);
        int length = text.length();
        for (int i = 0; i < length; i++) {
            float hue = length == 1 ? 0F : (float) i / (length - 1);
            root.insert(Message.raw(String.valueOf(text.charAt(i))).color(hueToHex(hue)).link(url));
        }
        return root;
    }

    private static String hueToHex(float hue) {
        int sector = (int) (hue * 6);
        float fraction = hue * 6 - sector;
        int rising = Math.round(255 * fraction);
        int falling = 255 - rising;
        int red;
        int green;
        int blue;
        switch (sector % 6) {
            case 0 -> { red = 255; green = rising; blue = 0; }
            case 1 -> { red = falling; green = 255; blue = 0; }
            case 2 -> { red = 0; green = 255; blue = rising; }
            case 3 -> { red = 0; green = falling; blue = 255; }
            case 4 -> { red = rising; green = 0; blue = 255; }
            default -> { red = 255; green = 0; blue = falling; }
        }
        return String.format("#%02X%02X%02X", red, green, blue);
    }
}
