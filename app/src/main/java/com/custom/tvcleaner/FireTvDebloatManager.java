package com.custom.tvcleaner;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Manages package debloating, categorization, safety rules, and rollback states for Fire TV Stick.
 * strictly uses disable-only (pm disable-user --user 0) - NEVER uninstalls or deletes data.
 */
public class FireTvDebloatManager {

    private static final String PREFS_NAME = "firetv_debloat_prefs";
    private static final String KEY_BACKUP_DISABLED = "backup_disabled_packages";

    public enum Tier {
        SAFE,           // Telemetry, ads, forced OTA, shopping overlays
        CAUTION,        // Screensaver, Live TV guide, casting/mirroring, Alexa news
        NEVER_TOUCH     // Bluetooth remote, Wi-Fi, Settings, Installer, Downloader, TV Clean Home
    }

    public static class DebloatItem {
        public final String packageName;
        public final String friendlyName;
        public final String description;
        public final Tier tier;
        public boolean isInstalled;
        public boolean isCurrentlyDisabled;
        public boolean isSelected;

        public DebloatItem(String packageName, String friendlyName, String description, Tier tier) {
            this.packageName = packageName;
            this.friendlyName = friendlyName;
            this.description = description;
            this.tier = tier;
        }
    }

    // Master Catalog of Fire OS packages
    private static final List<DebloatItem> CATALOG = Arrays.asList(
            // SAFE TIER
            new DebloatItem("com.amazon.advertising.firetv", "Fire TV Ad Service", "Home screen promo banners and sponsored video ad delivery", Tier.SAFE),
            new DebloatItem("com.amazon.device.sale.service", "Amazon Sale Service", "In-app purchasing promos and shopping recommendations", Tier.SAFE),
            new DebloatItem("com.amazon.visualbuys", "Visual Buys Overlay", "\"Buy on Amazon\" shopping cards and promo popups", Tier.SAFE),
            new DebloatItem("com.amazon.tv.forcedotaupdates.v2", "Forced Silent OTA Updater", "Silently pushes system updates that can break custom launchers", Tier.SAFE),
            new DebloatItem("com.amazon.tv.forcedotaupdater.v2", "Forced Silent OTA Updater (Fire OS 8)", "Silently pushes system updates that can break custom launchers", Tier.SAFE),
            new DebloatItem("com.amazon.shoptv.client", "ShopTV Shopping Client", "Shopping recommendations and promo overlays", Tier.SAFE),
            new DebloatItem("com.amazon.shoptv.firetv.client", "ShopTV Fire TV Client", "Sponsored shopping feeds and product ads", Tier.SAFE),
            new DebloatItem("com.amazon.device.telemetry.emitter", "Device Telemetry Emitter", "Telemetry and usage data reporting daemon", Tier.SAFE),
            new DebloatItem("com.amazon.minitv.android.app", "Amazon miniTV Ads & Shows", "Promotional video stream service and banner delivery", Tier.SAFE),
            new DebloatItem("com.amazon.gamehub", "Amazon GameHub", "Sponsored gaming promos and game launcher overlay", Tier.SAFE),
            new DebloatItem("com.amazon.device.software.ota", "OTA Update Downloader", "Background system firmware download daemon", Tier.SAFE),
            new DebloatItem("com.amazon.device.software.ota.override", "OTA Override Service", "System update override controller", Tier.SAFE),
            new DebloatItem("com.amazon.kor.demo", "Retail Demo Loop", "Store kiosk demo video player", Tier.SAFE),
            new DebloatItem("com.amazon.shpm", "Amazon Shipping Status", "Amazon delivery and package tracking notifications on TV", Tier.SAFE),
            new DebloatItem("com.amazon.kato", "Voice & Search Telemetry", "User metrics and search usage feedback collector", Tier.SAFE),
            new DebloatItem("com.amazon.device.metrics", "Device Analytics Metrics", "Crash reporting and background analytics uploader", Tier.SAFE),
            new DebloatItem("com.amazon.bueller.music", "Amazon Music Preloader", "Background service pre-caching Amazon Music tracks", Tier.SAFE),
            new DebloatItem("com.amazon.firehomedashboardservice", "Alexa Smart Home Dashboard", "Smart home devices overlay dashboard", Tier.SAFE),

            // CAUTION TIER
            new DebloatItem("com.amazon.vizzini", "Amazon Screensaver Engine", "Ambient photo screensaver backdrop (disables sleep wallpaper)", Tier.CAUTION),
            new DebloatItem("com.amazon.ftv.screensaver", "Screensaver Cache", "Screensaver high-res image cache daemon", Tier.CAUTION),
            new DebloatItem("com.amazon.cardinal", "Amazon News App", "Amazon News live feed integration", Tier.CAUTION),
            new DebloatItem("com.amazon.tv.livetv", "Live TV Guide Channel", "Fire OS Live TV channel guide aggregator", Tier.CAUTION),
            new DebloatItem("com.amazon.whisperplay.contracts", "Screen Mirroring Service", "Miracast screen casting receiver from phones/tablets", Tier.CAUTION),
            new DebloatItem("com.amazon.speech.simplesearch", "Alexa Voice Search Results", "Alexa remote voice search results card overlay", Tier.CAUTION),

            // NEVER TOUCH TIER (LOCKED)
            new DebloatItem("com.amazon.tv.settings", "Fire TV Settings", "Core System Settings, Resolution, Network, Audio", Tier.NEVER_TOUCH),
            new DebloatItem("com.amazon.tv.settings.v2", "Fire TV Settings v2", "Settings provider interface", Tier.NEVER_TOUCH),
            new DebloatItem("com.amazon.device.bluetooth", "Bluetooth Remote Service", "CRITICAL: Pairing for Fire TV voice remote", Tier.NEVER_TOUCH),
            new DebloatItem("com.amazon.inputmethod.latin", "TV On-Screen Keyboard", "CRITICAL: Required for typing passwords and search", Tier.NEVER_TOUCH),
            new DebloatItem("com.android.packageinstaller", "Package Installer", "CRITICAL: Sideloading and app installation engine", Tier.NEVER_TOUCH),
            new DebloatItem("com.amazon.tv.auth", "Amazon Auth & DRM", "CRITICAL: Required for Prime Video, Netflix, Disney+ playback", Tier.NEVER_TOUCH),
            new DebloatItem("com.amazon.tv.support", "HDMI-CEC & Power Controller", "CRITICAL: Remote volume and TV power button support", Tier.NEVER_TOUCH),
            new DebloatItem("com.esaba.downloader", "Downloader by AFTVnews", "User sideloading utility", Tier.NEVER_TOUCH),
            new DebloatItem("com.custom.tvcleaner", "TV Clean Home", "This launcher application", Tier.NEVER_TOUCH)
    );

    public static List<DebloatItem> getCatalog(Context context) {
        PackageManager pm = context.getPackageManager();
        List<DebloatItem> list = new ArrayList<>();

        for (DebloatItem item : CATALOG) {
            DebloatItem copy = new DebloatItem(item.packageName, item.friendlyName, item.description, item.tier);
            try {
                ApplicationInfo ai = pm.getApplicationInfo(copy.packageName, 0);
                copy.isInstalled = true;
                copy.isCurrentlyDisabled = !ai.enabled;
            } catch (PackageManager.NameNotFoundException e) {
                copy.isInstalled = false;
                copy.isCurrentlyDisabled = false;
            }
            list.add(copy);
        }
        return list;
    }

    public static void applyPreset(List<DebloatItem> items, int presetMode) {
        // 0: Safe only, 1: Recommended (Safe + Ambient/News), 2: Max (Safe + All Caution)
        for (DebloatItem item : items) {
            if (item.tier == Tier.NEVER_TOUCH) {
                item.isSelected = false;
                continue;
            }

            if (presetMode == 0) { // SAFE
                item.isSelected = (item.tier == Tier.SAFE && item.isInstalled);
            } else if (presetMode == 1) { // RECOMMENDED
                if (item.tier == Tier.SAFE) {
                    item.isSelected = item.isInstalled;
                } else if (item.tier == Tier.CAUTION) {
                    // Safe caution items (ads news and demo guide)
                    item.isSelected = item.packageName.contains("cardinal") ||
                                      item.packageName.contains("livetv");
                } else {
                    item.isSelected = false;
                }
            } else if (presetMode == 2) { // MAX
                item.isSelected = (item.tier != Tier.NEVER_TOUCH && item.isInstalled);
            }
        }
    }

    /**
     * Executes disable on selected packages. Saves state to backup preferences before execution.
     */
    public static boolean disableSelectedPackages(Context context, List<DebloatItem> items, StringBuilder logOut) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        Set<String> newlyDisabled = new HashSet<>(prefs.getStringSet(KEY_BACKUP_DISABLED, new HashSet<>()));

        boolean anyFailed = false;

        for (DebloatItem item : items) {
            if (item.tier == Tier.NEVER_TOUCH) continue; // Safety guard
            if (!item.isInstalled || !item.isSelected) continue;

            String cmd = "pm disable-user --user 0 " + item.packageName;
            boolean ok = runShellCommand(cmd, logOut);
            if (ok) {
                newlyDisabled.add(item.packageName);
                item.isCurrentlyDisabled = true;
            } else {
                anyFailed = true;
            }
        }

        prefs.edit().putStringSet(KEY_BACKUP_DISABLED, newlyDisabled).apply();
        return !anyFailed;
    }

    /**
     * Re-enables all packages disabled through this manager or catalog.
     */
    public static boolean restoreAll(Context context, StringBuilder logOut) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        Set<String> backedUp = prefs.getStringSet(KEY_BACKUP_DISABLED, new HashSet<>());

        // Also include all items in catalog to ensure full recovery
        Set<String> toRestore = new HashSet<>(backedUp);
        for (DebloatItem item : CATALOG) {
            if (item.tier != Tier.NEVER_TOUCH) {
                toRestore.add(item.packageName);
            }
        }

        boolean anyFailed = false;
        for (String pkg : toRestore) {
            String cmd = "pm enable " + pkg;
            boolean ok = runShellCommand(cmd, logOut);
            if (!ok) anyFailed = true;
        }

        prefs.edit().remove(KEY_BACKUP_DISABLED).apply();
        return !anyFailed;
    }

    private static boolean runShellCommand(String cmd, StringBuilder logOut) {
        try {
            Process p = Runtime.getRuntime().exec(cmd);
            BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line;
            while ((line = reader.readLine()) != null) {
                if (logOut != null) logOut.append(line).append("\n");
            }
            int exitCode = p.waitFor();
            return exitCode == 0;
        } catch (Exception e) {
            if (logOut != null) logOut.append("Error executing '").append(cmd).append("': ").append(e.getMessage()).append("\n");
            return false;
        }
    }
}
