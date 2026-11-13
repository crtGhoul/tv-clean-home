package com.custom.tvcleaner;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.net.TrafficStats;
import android.net.Uri;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextClock;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Ultra-Lightweight, Zero-Bloat Universal TV Home Launcher.
 * Designed for Chromecast with Google TV & Amazon Fire TV Stick.
 * Uses ~18 MB of RAM. High-contrast remote D-pad navigation.
 */
public class LauncherActivity extends Activity {

    private static final int COLOR_BG = 0xFF0D0E12;
    private static final int COLOR_CARD = 0xFF181920;
    private static final int COLOR_CARD_FOCUSED = 0xFF242038;
    private static final int COLOR_BORDER = 0xFF282A36;
    private static final int COLOR_ACCENT_TEAL = 0xFF03DAC5;
    private static final int COLOR_ACCENT_PURPLE = 0xFFBB86FC;
    private static final int COLOR_TEXT_PRIMARY = 0xFFFFFFFF;
    private static final int COLOR_TEXT_MUTED = 0xFF8A8F9E;

    private static final String PREFS_LAUNCHER = "tv_clean_launcher_prefs";
    private static final String KEY_HIDDEN_PACKAGES = "hidden_packages";
    private static final String KEY_CUSTOM_ORDER = "custom_order";

    public static class TvApp {
        public final String packageName;
        public final String label;
        public final Drawable icon;
        public final Intent launchIntent;
        public final boolean isAddTile;

        public TvApp(String packageName, String label, Drawable icon, Intent launchIntent) {
            this(packageName, label, icon, launchIntent, false);
        }

        public TvApp(String packageName, String label, Drawable icon, Intent launchIntent, boolean isAddTile) {
            this.packageName = packageName;
            this.label = label;
            this.icon = icon;
            this.launchIntent = launchIntent;
            this.isAddTile = isAddTile;
        }
    }

    private final List<TvApp> appList = new ArrayList<>();
    private final List<TvApp> allInstalledCatalog = new ArrayList<>();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private TextView tvRamPill;
    private TextView tvWifiPill;
    private TextView btnDefaultPill;
    private GridView gridViewApps;
    private AppGridAdapter gridAdapter;

    private long lastRxBytes = 0;
    private long lastTxBytes = 0;
    private long lastNetSampleTime = 0;
    private int wifiDisplayMode = 0; // 0: Link + Down, 1: Down + Up, 2: SSID + Link

    private final Runnable netPollRunnable = new Runnable() {
        @Override
        public void run() {
            updateNetworkAndWifi();
            mainHandler.postDelayed(this, 2000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        View rootView = buildLauncherInterface();
        setContentView(rootView);

        gridAdapter = new AppGridAdapter();
        gridViewApps.setAdapter(gridAdapter);

        updateRamStatus();
        updateDefaultHomePill();
        loadInstalledApps();

        // Start 2-second real-time network traffic & Wi-Fi polling
        lastRxBytes = TrafficStats.getTotalRxBytes();
        lastTxBytes = TrafficStats.getTotalTxBytes();
        lastNetSampleTime = SystemClock.elapsedRealtime();
        mainHandler.removeCallbacks(netPollRunnable);
        mainHandler.post(netPollRunnable);
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateRamStatus();
        updateDefaultHomePill();
        loadInstalledApps();

        lastRxBytes = TrafficStats.getTotalRxBytes();
        lastTxBytes = TrafficStats.getTotalTxBytes();
        lastNetSampleTime = SystemClock.elapsedRealtime();
        mainHandler.removeCallbacks(netPollRunnable);
        mainHandler.post(netPollRunnable);
    }

    @Override
    protected void onPause() {
        super.onPause();
        mainHandler.removeCallbacks(netPollRunnable);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdown();
    }

    /**
     * Prevents Back button from exiting the Home launcher to stock launcher,
     * while allowing standard Android dialogs, menus, and popups to dismiss cleanly on Back.
     */
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (getCurrentFocus() == null && event.getAction() == KeyEvent.ACTION_DOWN) {
            if (gridViewApps != null && gridViewApps.getChildCount() > 0) {
                View firstChild = gridViewApps.getChildAt(0);
                if (firstChild != null) {
                    firstChild.requestFocus();
                    return true;
                }
            } else if (tvRamPill != null) {
                tvRamPill.requestFocus();
                return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    public void onBackPressed() {
        // Keep user on the home screen; do not finish Activity
    }

    // =========================================================================
    // Core Engine: Universal Installed TV Apps & Sideload Catalog
    // =========================================================================

    private void loadInstalledApps() {
        executor.execute(() -> {
            PackageManager pm = getPackageManager();
            List<TvApp> allCatalog = new ArrayList<>();
            Set<String> addedPackages = new HashSet<>();
            String selfPkg = getPackageName();

            // 1. Query Android TV Leanback Apps
            Intent leanbackIntent = new Intent(Intent.ACTION_MAIN, null);
            leanbackIntent.addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
            List<ResolveInfo> leanbackList = pm.queryIntentActivities(leanbackIntent, 0);

            // 2. Query Standard Android Launcher Apps
            Intent standardIntent = new Intent(Intent.ACTION_MAIN, null);
            standardIntent.addCategory(Intent.CATEGORY_LAUNCHER);
            List<ResolveInfo> standardList = pm.queryIntentActivities(standardIntent, 0);

            List<ResolveInfo> combined = new ArrayList<>();
            if (leanbackList != null) combined.addAll(leanbackList);
            if (standardList != null) combined.addAll(standardList);

            for (ResolveInfo ri : combined) {
                if (ri.activityInfo == null || ri.activityInfo.packageName == null) continue;
                String pkg = ri.activityInfo.packageName;

                if (addedPackages.contains(pkg) || pkg.equals(selfPkg)) continue;
                addedPackages.add(pkg);

                Intent launchIntent = pm.getLeanbackLaunchIntentForPackage(pkg);
                if (launchIntent == null) {
                    launchIntent = pm.getLaunchIntentForPackage(pkg);
                }

                if (launchIntent != null) {
                    String label = ri.loadLabel(pm).toString();
                    Drawable icon = ri.loadIcon(pm);
                    allCatalog.add(new TvApp(pkg, label, icon, launchIntent));
                }
            }

            // Fallback for sideloaded mobile apps with no TV category
            List<ApplicationInfo> allApps = pm.getInstalledApplications(0);
            for (ApplicationInfo ai : allApps) {
                if (addedPackages.contains(ai.packageName) || ai.packageName.equals(selfPkg)) continue;
                Intent launchIntent = pm.getLaunchIntentForPackage(ai.packageName);
                if (launchIntent != null && ai.enabled) {
                    addedPackages.add(ai.packageName);
                    String label = ai.loadLabel(pm).toString();
                    Drawable icon = ai.loadIcon(pm);
                    allCatalog.add(new TvApp(ai.packageName, label, icon, launchIntent));
                }
            }

            // Sort entire catalog alphabetically
            Collections.sort(allCatalog, (a, b) -> a.label.compareToIgnoreCase(b.label));

            // Load user preferences: Hidden Packages and Custom Order
            SharedPreferences prefs = getSharedPreferences(PREFS_LAUNCHER, MODE_PRIVATE);
            Set<String> hiddenSet = prefs.getStringSet(KEY_HIDDEN_PACKAGES, new HashSet<>());
            String customOrderStr = prefs.getString(KEY_CUSTOM_ORDER, "");
            List<String> customOrder = customOrderStr.isEmpty() ?
                    new ArrayList<>() : new ArrayList<>(Arrays.asList(customOrderStr.split(",")));

            // Filter out hidden packages for the home screen
            List<TvApp> visibleApps = new ArrayList<>();
            for (TvApp app : allCatalog) {
                if (!hiddenSet.contains(app.packageName)) {
                    visibleApps.add(app);
                }
            }

            // Apply custom ordering if saved
            if (!customOrder.isEmpty()) {
                Collections.sort(visibleApps, (a, b) -> {
                    int idxA = customOrder.indexOf(a.packageName);
                    int idxB = customOrder.indexOf(b.packageName);
                    if (idxA != -1 && idxB != -1) return Integer.compare(idxA, idxB);
                    if (idxA != -1) return -1;
                    if (idxB != -1) return 1;
                    return a.label.compareToIgnoreCase(b.label);
                });
            }

            // Append "+ Add App" Tile at the end of the home screen grid
            visibleApps.add(new TvApp("com.custom.tvcleaner.add_tile", "+ Add App", createAddIconDrawable(), null, true));

            mainHandler.post(() -> {
                allInstalledCatalog.clear();
                allInstalledCatalog.addAll(allCatalog);

                appList.clear();
                appList.addAll(visibleApps);
                gridAdapter.notifyDataSetChanged();
                gridViewApps.postDelayed(() -> {
                    if (gridViewApps.getChildCount() > 0) {
                        View first = gridViewApps.getChildAt(0);
                        if (first != null) first.requestFocus();
                    }
                }, 100);
            });
        });
    }

    private Drawable createAddIconDrawable() {
        return new Drawable() {
            @Override
            public void draw(Canvas canvas) {
                Rect bounds = getBounds();
                Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
                paint.setColor(COLOR_ACCENT_TEAL);
                paint.setStrokeWidth(dp(4));
                paint.setStrokeCap(Paint.Cap.ROUND);
                float cx = bounds.exactCenterX();
                float cy = bounds.exactCenterY();
                float len = dp(16);
                canvas.drawLine(cx - len, cy, cx + len, cy, paint);
                canvas.drawLine(cx, cy - len, cx, cy + len, paint);
            }
            @Override public void setAlpha(int alpha) {}
            @Override public void setColorFilter(ColorFilter cf) {}
            @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
        };
    }

    private void updateRamStatus() {
        ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        if (am == null) return;
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);

        long totalMb = mi.totalMem / (1024 * 1024);
        long availMb = mi.availMem / (1024 * 1024);
        long usedMb = totalMb - availMb;
        int usedPct = totalMb > 0 ? (int) ((usedMb * 100) / totalMb) : 0;

        if (tvRamPill != null) {
            tvRamPill.setText(String.format(Locale.US, "⚡ RAM: %d MB Free (%d%% Used)", availMb, usedPct));
        }
    }

    private void updateNetworkAndWifi() {
        long currentRx = TrafficStats.getTotalRxBytes();
        long currentTx = TrafficStats.getTotalTxBytes();
        long now = SystemClock.elapsedRealtime();

        int linkSpeed = -1;
        String ssid = "";
        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm != null) {
                WifiInfo wi = wm.getConnectionInfo();
                if (wi != null) {
                    linkSpeed = wi.getLinkSpeed();
                    String rawSsid = wi.getSSID();
                    if (rawSsid != null && !rawSsid.equals("<unknown ssid>")) {
                        ssid = rawSsid.replace("\"", "");
                    }
                }
            }
        } catch (Exception ignored) {}

        if (lastNetSampleTime > 0 && now > lastNetSampleTime) {
            long dt = now - lastNetSampleTime;
            long rxDelta = currentRx - lastRxBytes;
            long txDelta = currentTx - lastTxBytes;
            if (rxDelta < 0) rxDelta = 0;
            if (txDelta < 0) txDelta = 0;

            double rxKbps = (rxDelta / 1024.0) / (dt / 1000.0);
            double txKbps = (txDelta / 1024.0) / (dt / 1000.0);

            if (tvWifiPill != null) {
                String text;
                if (wifiDisplayMode == 0) {
                    if (linkSpeed > 0) {
                        text = String.format(Locale.US, "📶 %d Mbps · ⬇ %s", linkSpeed, formatNetRate(rxKbps));
                    } else {
                        text = String.format(Locale.US, "📶 ⬇ %s · ⬆ %s", formatNetRate(rxKbps), formatNetRate(txKbps));
                    }
                } else if (wifiDisplayMode == 1) {
                    text = String.format(Locale.US, "📶 ⬇ %s | ⬆ %s", formatNetRate(rxKbps), formatNetRate(txKbps));
                } else {
                    if (!ssid.isEmpty() && linkSpeed > 0) {
                        text = String.format(Locale.US, "📶 %s · %d Mbps", ssid, linkSpeed);
                    } else if (linkSpeed > 0) {
                        text = String.format(Locale.US, "📶 Wi-Fi: %d Mbps", linkSpeed);
                    } else {
                        text = "📶 Network Active";
                    }
                }
                tvWifiPill.setText(text);
            }
        }

        lastRxBytes = currentRx;
        lastTxBytes = currentTx;
        lastNetSampleTime = now;
    }

    private void toggleWifiDisplayMode() {
        wifiDisplayMode = (wifiDisplayMode + 1) % 3;
        String modeMsg;
        if (wifiDisplayMode == 0) {
            modeMsg = "Wi-Fi Mode: Link Speed + Live Download";
        } else if (wifiDisplayMode == 1) {
            modeMsg = "Wi-Fi Mode: Real-Time Download & Upload Bandwidth";
        } else {
            modeMsg = "Wi-Fi Mode: Network Name & Link Speed (Hold for Wi-Fi Settings)";
        }
        Toast.makeText(this, modeMsg, Toast.LENGTH_SHORT).show();
        updateNetworkAndWifi();
    }

    private String formatNetRate(double kbps) {
        if (kbps >= 1024.0) {
            return String.format(Locale.US, "%.1f MB/s", kbps / 1024.0);
        } else {
            return String.format(Locale.US, "%.0f KB/s", kbps);
        }
    }

    private void quickCleanRam() {
        ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        if (am == null) return;

        PackageManager pm = getPackageManager();
        List<ApplicationInfo> installed = pm.getInstalledApplications(0);
        String selfPkg = getPackageName();
        int count = 0;

        for (ApplicationInfo ai : installed) {
            if (ai.packageName.equals(selfPkg)) continue;
            boolean isStopped = (ai.flags & ApplicationInfo.FLAG_STOPPED) != 0;
            if (!isStopped && ai.enabled) {
                am.killBackgroundProcesses(ai.packageName);
                count++;
            }
        }

        updateRamStatus();
        Toast.makeText(this, "Quick Clean: Freed background processes for " + count + " apps", Toast.LENGTH_SHORT).show();
    }

    private boolean isAccessibilityServiceEnabled() {
        String pref = Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (pref != null) {
            return pref.contains(getPackageName() + "/" + TvHomeAccessibilityService.class.getName())
                    || pref.contains(TvHomeAccessibilityService.class.getSimpleName());
        }
        return false;
    }

    private void updateDefaultHomePill() {
        if (btnDefaultPill == null) return;
        boolean enabled = isAccessibilityServiceEnabled();
        if (enabled) {
            btnDefaultPill.setText("✔ Default Home");
            btnDefaultPill.setTextColor(0xFF81C784);
        } else {
            btnDefaultPill.setText("⚠ Set Default");
            btnDefaultPill.setTextColor(0xFFFFA726);
        }
    }

    private void handleDefaultHomeClick() {
        if (PlatformDetector.IS_FIRE_TV) {
            showFireTvHomeSetupDialog();
            return;
        }

        boolean enabled = isAccessibilityServiceEnabled();
        if (enabled) {
            Toast.makeText(this, "TV Clean Home is active as your primary remote launcher!", Toast.LENGTH_SHORT).show();
        } else {
            try {
                Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
                startActivity(intent);
                Toast.makeText(this, "Click 'TV Clean Home' and turn it ON to make it your permanent home launcher", Toast.LENGTH_LONG).show();
            } catch (Exception e) {
                Toast.makeText(this, "Go to Settings -> System -> Accessibility -> Turn ON TV Clean Home", Toast.LENGTH_LONG).show();
            }
        }
    }

    private void showFireTvHomeSetupDialog() {
        new AlertDialog.Builder(this)
                .setTitle("Fire TV Home Button Setup (" + PlatformDetector.PLATFORM_NAME + ")")
                .setMessage("Amazon Fire OS restricts setting third-party default launchers directly.\n\n" +
                        "Workarounds:\n" +
                        "1. Fire OS 7: Turn ON TV Clean Home under Settings ➔ Accessibility.\n" +
                        "2. Fire OS 8+: If Amazon patches the remote Home button, keep TV Clean Home pinned in your Recent Apps row or use Launcher Manager.\n\n" +
                        "Would you like to open Accessibility Settings now?")
                .setPositiveButton("Open Accessibility", (dialog, which) -> {
                    try {
                        startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
                    } catch (Exception e) {
                        Toast.makeText(this, "Open Fire TV Settings ➔ Accessibility ➔ TV Clean Home", Toast.LENGTH_LONG).show();
                    }
                })
                .setNegativeButton("Close", null)
                .show();
    }

    private void showAboutDialog() {
        new AlertDialog.Builder(this)
                .setTitle("TV Clean Home v1.2")
                .setMessage("Platform: " + PlatformDetector.PLATFORM_NAME + "\n" +
                        "Device: " + Build.MANUFACTURER + " " + Build.MODEL + " (" + Build.PRODUCT + ")\n" +
                        "RAM Overhead: ~18 MB (Zero 3P Dependencies)\n\n" +
                        "Remote Tips:\n" +
                        "• Tap [ + Add App ] to show/hide apps or launch sideloaded APKs.\n" +
                        "• Long-press OK or press MENU on any app tile to Move Left/Right or Hide it.")
                .setPositiveButton("OK", null)
                .show();
    }

    private void openSystemSettings() {
        if (PlatformDetector.IS_FIRE_TV) {
            ComponentName[] fireTvComponents = new ComponentName[] {
                    new ComponentName("com.amazon.tv.launcher", "com.amazon.tv.launcher.ui.MainSettingsActivity"),
                    new ComponentName("com.amazon.tv.settings.v2", "com.amazon.tv.settings.v2.hud.HudActivity"),
                    new ComponentName("com.amazon.tv.settings.v2", "com.amazon.tv.settings.v2.tv.device.DeviceActivity"),
                    new ComponentName("com.amazon.tv.launcher", "com.amazon.tv.launcher.ui.SettingsActivity"),
                    new ComponentName("com.amazon.tv.settings", "com.amazon.tv.settings.LandingPage")
            };

            for (ComponentName cn : fireTvComponents) {
                try {
                    Intent intent = new Intent();
                    intent.setComponent(cn);
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(intent);
                    return;
                } catch (Exception ignored) {}
            }

            try {
                Intent intent = new Intent("amazon.intent.action.MAINSETTINGS");
                intent.addCategory("android.intent.category.MAINSETTINGS");
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);
                return;
            } catch (Exception ignored) {}
        }

        // Standard Android / Google TV Settings
        try {
            Intent intent = new Intent(Settings.ACTION_SETTINGS);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
            return;
        } catch (Exception ignored) {}

        // Fallback for Android TV settings package
        try {
            Intent intent = new Intent();
            intent.setComponent(new ComponentName("com.android.tv.settings", "com.android.tv.settings.MainSettings"));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
            return;
        } catch (Exception ignored) {}

        Toast.makeText(this, "Could not open Settings", Toast.LENGTH_SHORT).show();
    }

    private void openWifiSettings() {
        if (PlatformDetector.IS_FIRE_TV) {
            ComponentName[] fireTvWifi = new ComponentName[] {
                    new ComponentName("com.amazon.tv.settings.v2", "com.amazon.tv.settings.v2.tv.network.NetworkActivity"),
                    new ComponentName("com.amazon.tv.settings", "com.amazon.tv.settings.tv.network.NetworkActivity")
            };
            for (ComponentName cn : fireTvWifi) {
                try {
                    Intent intent = new Intent();
                    intent.setComponent(cn);
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(intent);
                    return;
                } catch (Exception ignored) {}
            }
        }

        try {
            Intent intent = new Intent(Settings.ACTION_WIFI_SETTINGS);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
            return;
        } catch (Exception ignored) {}

        Toast.makeText(this, "Could not open Wi-Fi settings", Toast.LENGTH_SHORT).show();
    }

    // =========================================================================
    // Manage Apps & Long-Press Tile Reordering
    // =========================================================================

    private void showManageAppsDialog() {
        if (allInstalledCatalog.isEmpty()) {
            Toast.makeText(this, "Loading installed apps catalog...", Toast.LENGTH_SHORT).show();
            return;
        }

        SharedPreferences prefs = getSharedPreferences(PREFS_LAUNCHER, MODE_PRIVATE);
        Set<String> hiddenSet = new HashSet<>(prefs.getStringSet(KEY_HIDDEN_PACKAGES, new HashSet<>()));

        String[] labels = new String[allInstalledCatalog.size()];
        boolean[] checked = new boolean[allInstalledCatalog.size()];

        for (int i = 0; i < allInstalledCatalog.size(); i++) {
            TvApp app = allInstalledCatalog.get(i);
            labels[i] = app.label + (hiddenSet.contains(app.packageName) ? " (Hidden)" : "");
            checked[i] = !hiddenSet.contains(app.packageName);
        }

        new AlertDialog.Builder(this)
                .setTitle("Manage Home Apps (" + allInstalledCatalog.size() + " Installed)")
                .setMultiChoiceItems(labels, checked, (dialog, which, isChecked) -> {
                    TvApp app = allInstalledCatalog.get(which);
                    if (isChecked) {
                        hiddenSet.remove(app.packageName);
                    } else {
                        hiddenSet.add(app.packageName);
                    }
                })
                .setPositiveButton("Save Changes", (dialog, which) -> {
                    prefs.edit().putStringSet(KEY_HIDDEN_PACKAGES, hiddenSet).apply();
                    Toast.makeText(this, "Home screen updated!", Toast.LENGTH_SHORT).show();
                    loadInstalledApps();
                })
                .setNeutralButton("Launch App Directly", (dialog, which) -> showDirectLaunchPicker())
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showDirectLaunchPicker() {
        String[] labels = new String[allInstalledCatalog.size()];
        for (int i = 0; i < allInstalledCatalog.size(); i++) {
            labels[i] = allInstalledCatalog.get(i).label;
        }

        new AlertDialog.Builder(this)
                .setTitle("Direct Launch Sideloaded App")
                .setItems(labels, (dialog, which) -> {
                    TvApp app = allInstalledCatalog.get(which);
                    try {
                        startActivity(app.launchIntent);
                    } catch (Exception e) {
                        Toast.makeText(this, "Failed to launch " + app.label, Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("Back", null)
                .show();
    }

    private void showAppActionMenu(TvApp app, int position) {
        String[] actions = new String[]{"⬅ Move Left", "➡ Move Right", "👁 Hide from Home", "⚙ App Info / Clear Cache", "🚀 Launch App"};

        new AlertDialog.Builder(this)
                .setTitle(app.label)
                .setItems(actions, (dialog, which) -> {
                    if (which == 0) {
                        moveAppPosition(position, -1);
                    } else if (which == 1) {
                        moveAppPosition(position, 1);
                    } else if (which == 2) {
                        hideApp(app.packageName, app.label);
                    } else if (which == 3) {
                        openAppDetails(app.packageName);
                    } else if (which == 4) {
                        try {
                            startActivity(app.launchIntent);
                        } catch (Exception e) {
                            Toast.makeText(this, "Failed to launch " + app.label, Toast.LENGTH_SHORT).show();
                        }
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void moveAppPosition(int currentIndex, int delta) {
        int targetIndex = currentIndex + delta;
        // Don't move past boundaries or swap with the "+ Add App" tile at the end
        int maxIndex = appList.size() - 2;
        if (targetIndex < 0 || targetIndex > maxIndex) {
            Toast.makeText(this, "Cannot move further in that direction", Toast.LENGTH_SHORT).show();
            return;
        }

        Collections.swap(appList, currentIndex, targetIndex);
        gridAdapter.notifyDataSetChanged();

        // Save order to SharedPreferences
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < appList.size(); i++) {
            TvApp a = appList.get(i);
            if (!a.isAddTile) {
                if (sb.length() > 0) sb.append(",");
                sb.append(a.packageName);
            }
        }
        getSharedPreferences(PREFS_LAUNCHER, MODE_PRIVATE).edit()
                .putString(KEY_CUSTOM_ORDER, sb.toString()).apply();
    }

    private void hideApp(String packageName, String label) {
        SharedPreferences prefs = getSharedPreferences(PREFS_LAUNCHER, MODE_PRIVATE);
        Set<String> hiddenSet = new HashSet<>(prefs.getStringSet(KEY_HIDDEN_PACKAGES, new HashSet<>()));
        hiddenSet.add(packageName);
        prefs.edit().putStringSet(KEY_HIDDEN_PACKAGES, hiddenSet).apply();

        Toast.makeText(this, label + " hidden from Home. Restore anytime via [ + Add App ]", Toast.LENGTH_LONG).show();
        loadInstalledApps();
    }

    private void openAppDetails(String packageName) {
        try {
            Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            intent.setData(Uri.parse("package:" + packageName));
            startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(this, "Could not open app details", Toast.LENGTH_SHORT).show();
        }
    }

    // =========================================================================
    // UI Construction (Pure Android Framework)
    // =========================================================================

    private View buildLauncherInterface() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(COLOR_BG);
        root.setPadding(dp(28), dp(20), dp(28), dp(16));

        // 1. Top Bar: Digital Clock, Date, RAM Pill, and Quick Tool Buttons
        LinearLayout topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // Clock & Date column
        LinearLayout clockCol = new LinearLayout(this);
        clockCol.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams clockLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f);
        clockCol.setLayoutParams(clockLp);
        clockCol.setFocusable(false);
        clockCol.setClickable(false);

        TextClock clock = new TextClock(this);
        clock.setFormat12Hour("h:mm a");
        clock.setFormat24Hour("HH:mm");
        clock.setTextColor(COLOR_TEXT_PRIMARY);
        clock.setTextSize(TypedValue.COMPLEX_UNIT_SP, 30);
        clock.setTypeface(Typeface.DEFAULT_BOLD);
        clockCol.addView(clock);

        TextView tvDate = new TextView(this);
        SimpleDateFormat sdf = new SimpleDateFormat("EEEE, MMMM d", Locale.getDefault());
        tvDate.setText(sdf.format(new Date()));
        tvDate.setTextColor(COLOR_ACCENT_PURPLE);
        tvDate.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        clockCol.addView(tvDate);

        topBar.addView(clockCol);

        // Action Pill Buttons Column
        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER_VERTICAL);

        tvRamPill = createPillButton("⚡ RAM: -- MB Free", COLOR_ACCENT_TEAL);
        tvRamPill.setOnClickListener(v -> quickCleanRam());
        actions.addView(tvRamPill);

        tvWifiPill = createPillButton("📶 Wi-Fi: -- Mbps", COLOR_ACCENT_PURPLE);
        tvWifiPill.setOnClickListener(v -> toggleWifiDisplayMode());
        tvWifiPill.setOnLongClickListener(v -> {
            openWifiSettings();
            return true;
        });
        actions.addView(tvWifiPill);

        btnDefaultPill = createPillButton("Default Home", COLOR_TEXT_PRIMARY);
        btnDefaultPill.setOnClickListener(v -> handleDefaultHomeClick());
        actions.addView(btnDefaultPill);

        TextView btnDeepClean = createPillButton("⛔ Deep Clean", COLOR_ACCENT_PURPLE);
        btnDeepClean.setOnClickListener(v -> {
            Intent intent = new Intent(this, MainActivity.class);
            startActivity(intent);
        });
        actions.addView(btnDeepClean);

        TextView btnSettings = createPillButton("⚙ Settings", COLOR_TEXT_PRIMARY);
        btnSettings.setOnClickListener(v -> openSystemSettings());
        btnSettings.setOnLongClickListener(v -> {
            showAboutDialog();
            return true;
        });
        actions.addView(btnSettings);

        topBar.addView(actions);
        root.addView(topBar);

        // 2. Section Header
        TextView tvSection = new TextView(this);
        tvSection.setText("APPS & STREAMING");
        tvSection.setTextColor(COLOR_TEXT_MUTED);
        tvSection.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        tvSection.setTypeface(null, Typeface.BOLD);
        tvSection.setPadding(0, dp(18), 0, dp(12));
        root.addView(tvSection);

        // 3. App Grid View (Optimized for 1080p / 4K TV screens)
        gridViewApps = new GridView(this);
        gridViewApps.setNumColumns(5);
        gridViewApps.setHorizontalSpacing(dp(16));
        gridViewApps.setVerticalSpacing(dp(16));
        gridViewApps.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        gridViewApps.setGravity(Gravity.CENTER);
        gridViewApps.setFocusable(false);
        gridViewApps.setFocusableInTouchMode(false);
        gridViewApps.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        gridViewApps.setClipChildren(false);
        gridViewApps.setClipToPadding(false);

        LinearLayout.LayoutParams gridParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.0f);
        root.addView(gridViewApps, gridParams);

        return root;
    }

    private void focusFirstAppTile() {
        if (gridViewApps == null || appList.isEmpty()) return;
        gridViewApps.smoothScrollToPosition(0);
        gridViewApps.post(() -> {
            View child = gridViewApps.getChildAt(0);
            if (child != null) {
                child.requestFocus();
            } else {
                gridViewApps.setSelection(0);
            }
        });
    }

    private TextView createPillButton(String text, int accentColor) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        tv.setTypeface(null, Typeface.BOLD);
        tv.setTextColor(COLOR_TEXT_PRIMARY);
        tv.setFocusable(true);
        tv.setFocusableInTouchMode(true);
        tv.setGravity(Gravity.CENTER);

        int pxH = dp(14);
        int pxV = dp(8);
        tv.setPadding(pxH, pxV, pxH, pxV);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, dp(10), 0);
        tv.setLayoutParams(lp);

        GradientDrawable defaultBg = new GradientDrawable();
        defaultBg.setColor(Color.parseColor("#1B1B22"));
        defaultBg.setCornerRadius(dp(18));
        defaultBg.setStroke(dp(1), Color.parseColor("#2E2E3E"));

        GradientDrawable focusedBg = new GradientDrawable();
        focusedBg.setColor(Color.parseColor("#282836"));
        focusedBg.setCornerRadius(dp(18));
        focusedBg.setStroke(dp(2), accentColor);

        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{android.R.attr.state_focused}, focusedBg);
        states.addState(new int[]{}, defaultBg);
        tv.setBackground(states);

        tv.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) {
                v.animate().scaleX(1.05f).scaleY(1.05f).setDuration(120).start();
            } else {
                v.animate().scaleX(1.0f).scaleY(1.0f).setDuration(120).start();
            }
        });

        tv.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                focusFirstAppTile();
                return true;
            }
            return false;
        });

        return tv;
    }

    private int dp(int val) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, val, getResources().getDisplayMetrics());
    }

    // =========================================================================
    // Grid Adapter
    // =========================================================================

    private class AppGridAdapter extends BaseAdapter {

        @Override
        public int getCount() {
            return appList.size();
        }

        @Override
        public TvApp getItem(int position) {
            return appList.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            AppViewHolder holder;
            if (convertView == null) {
                LinearLayout card = new LinearLayout(LauncherActivity.this);
                card.setOrientation(LinearLayout.VERTICAL);
                card.setGravity(Gravity.CENTER);
                card.setPadding(dp(12), dp(16), dp(12), dp(14));
                card.setFocusable(true);
                card.setFocusableInTouchMode(true);
                card.setClickable(true);

                StateListDrawable sld = new StateListDrawable();
                GradientDrawable focused = new GradientDrawable();
                focused.setColor(COLOR_CARD_FOCUSED);
                focused.setCornerRadius(dp(12));
                focused.setStroke(dp(3), COLOR_ACCENT_PURPLE);
                sld.addState(new int[]{android.R.attr.state_focused}, focused);

                GradientDrawable normal = new GradientDrawable();
                normal.setColor(COLOR_CARD);
                normal.setCornerRadius(dp(12));
                normal.setStroke(dp(1), COLOR_BORDER);
                sld.addState(new int[]{}, normal);

                card.setBackground(sld);

                card.setOnFocusChangeListener((v, hasFocus) -> {
                    if (hasFocus) {
                        v.animate().scaleX(1.08f).scaleY(1.08f).setDuration(100).start();
                    } else {
                        v.animate().scaleX(1.0f).scaleY(1.0f).setDuration(100).start();
                    }
                });

                ImageView iv = new ImageView(LauncherActivity.this);
                LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(dp(54), dp(54));
                iconLp.setMargins(0, 0, 0, dp(10));
                iv.setLayoutParams(iconLp);
                card.addView(iv);

                TextView tv = new TextView(LauncherActivity.this);
                tv.setTextColor(COLOR_TEXT_PRIMARY);
                tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
                tv.setTypeface(Typeface.DEFAULT_BOLD);
                tv.setGravity(Gravity.CENTER);
                tv.setSingleLine(true);
                tv.setEllipsize(TextUtils.TruncateAt.END);
                card.addView(tv);

                holder = new AppViewHolder();
                holder.card = card;
                holder.ivIcon = iv;
                holder.tvLabel = tv;
                card.setTag(holder);

                convertView = card;
            } else {
                holder = (AppViewHolder) convertView.getTag();
            }

            TvApp app = getItem(position);
            holder.ivIcon.setImageDrawable(app.icon);
            holder.tvLabel.setText(app.label);

            convertView.setOnClickListener(v -> {
                if (app.isAddTile) {
                    showManageAppsDialog();
                    return;
                }
                try {
                    startActivity(app.launchIntent);
                } catch (Exception e) {
                    Toast.makeText(LauncherActivity.this, "Failed to launch " + app.label, Toast.LENGTH_SHORT).show();
                }
            });

            convertView.setOnLongClickListener(v -> {
                if (!app.isAddTile) {
                    showAppActionMenu(app, position);
                    return true;
                }
                return false;
            });

            convertView.setOnKeyListener((v, keyCode, event) -> {
                if (event.getAction() != KeyEvent.ACTION_DOWN) return false;

                if (keyCode == KeyEvent.KEYCODE_MENU) {
                    if (!app.isAddTile) {
                        showAppActionMenu(app, position);
                        return true;
                    }
                } else if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                    if (position < 5) {
                        if (tvRamPill != null) {
                            tvRamPill.requestFocus();
                            return true;
                        }
                    }
                } else if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                    int targetPos = position + 5;
                    if (targetPos >= appList.size() && position < appList.size() - 1) {
                        // Dead-end column on second-to-last row: jump directly to the last item (+ Add App)
                        int lastIdx = appList.size() - 1;
                        gridViewApps.smoothScrollToPosition(lastIdx);
                        gridViewApps.post(() -> {
                            int visibleIdx = lastIdx - gridViewApps.getFirstVisiblePosition();
                            View lastView = gridViewApps.getChildAt(visibleIdx);
                            if (lastView != null) {
                                lastView.requestFocus();
                            } else {
                                gridViewApps.setSelection(lastIdx);
                            }
                        });
                        return true;
                    }
                } else if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                    if (position % 5 == 4 && position + 1 < appList.size()) {
                        int nextIdx = position + 1;
                        gridViewApps.smoothScrollToPosition(nextIdx);
                        gridViewApps.post(() -> {
                            int visibleIdx = nextIdx - gridViewApps.getFirstVisiblePosition();
                            View nextView = gridViewApps.getChildAt(visibleIdx);
                            if (nextView != null) {
                                nextView.requestFocus();
                            } else {
                                gridViewApps.setSelection(nextIdx);
                            }
                        });
                        return true;
                    }
                } else if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                    if (position % 5 == 0 && position > 0) {
                        int prevIdx = position - 1;
                        gridViewApps.smoothScrollToPosition(prevIdx);
                        gridViewApps.post(() -> {
                            int visibleIdx = prevIdx - gridViewApps.getFirstVisiblePosition();
                            View prevView = gridViewApps.getChildAt(visibleIdx);
                            if (prevView != null) {
                                prevView.requestFocus();
                            } else {
                                gridViewApps.setSelection(prevIdx);
                            }
                        });
                        return true;
                    }
                }
                return false;
            });

            return convertView;
        }
    }

    private static class AppViewHolder {
        LinearLayout card;
        ImageView ivIcon;
        TextView tvLabel;
    }
}
