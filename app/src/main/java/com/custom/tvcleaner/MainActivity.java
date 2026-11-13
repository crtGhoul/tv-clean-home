package com.custom.tvcleaner;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.net.TrafficStats;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Android TV Background App Killer, Debloater & Network Monitor
 * Tailored for Chromecast with Google TV (Android 11 / 12 / 14, API 26-34).
 * Pure Android framework implementation with zero external UI dependencies.
 */
public class MainActivity extends Activity {

    // UI Colors (Pure Dark Theme with High-Contrast TV D-Pad Focus Accents)
    private static final int COLOR_BG = 0xFF121212;
    private static final int COLOR_CARD = 0xFF1E1E1E;
    private static final int COLOR_CARD_FOCUSED = 0xFF2A233C;
    private static final int COLOR_BORDER_DEFAULT = 0xFF2F2F2F;
    private static final int COLOR_ACCENT_PURPLE = 0xFFBB86FC;
    private static final int COLOR_ACCENT_TEAL = 0xFF03DAC5;
    private static final int COLOR_TEXT_PRIMARY = 0xFFFFFFFF;
    private static final int COLOR_TEXT_SECONDARY = 0xFFB0B0B0;
    private static final int COLOR_TEXT_MUTED = 0xFF757575;
    private static final int COLOR_BADGE_USER = 0xFF1B5E20;
    private static final int COLOR_BADGE_SYSTEM = 0xFFE65100;
    private static final int COLOR_BADGE_DATA = 0xFF4A148C;

    // Data Models
    public static class AppItem {
        public final String packageName;
        public final String label;
        public final Drawable icon;
        public final int uid;
        public final boolean isSystem;
        public final long rxBytes;

        public AppItem(String packageName, String label, Drawable icon, int uid, boolean isSystem, long rxBytes) {
            this.packageName = packageName;
            this.label = label;
            this.icon = icon;
            this.uid = uid;
            this.isSystem = isSystem;
            this.rxBytes = rxBytes;
        }
    }

    // App state
    private final List<AppItem> rawUnstoppedApps = new ArrayList<>();
    private final List<AppItem> displayedApps = new ArrayList<>();
    private boolean showSystemApps = false;
    private boolean sortByDataUsed = false;

    // Batch Force-Stop Queue State
    private boolean batchClosing = false;
    private final Set<String> batchVisitedPackages = new HashSet<>();
    private int batchTotalTargetCount = 0;
    private long lastBackPressTime = 0;

    // Network speed polling
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService backgroundExecutor = Executors.newSingleThreadExecutor();
    private long lastRxTotalBytes = 0;
    private long lastTxTotalBytes = 0;
    private long lastTrafficSampleTime = 0;

    // UI Widgets
    private TextView tvRamValue;
    private TextView tvTrafficValue;
    private TextView tvStatusSummary;
    private TextView btnOpenLauncher;
    private TextView btnBoostRam;
    private TextView btnBatchQueue;
    private TextView btnToggleSystem;
    private TextView btnToggleSort;
    private TextView btnRefresh;
    private ListView listViewApps;
    private AppListAdapter listAdapter;

    private final Runnable trafficPollRunnable = new Runnable() {
        @Override
        public void run() {
            updateNetworkTraffic();
            mainHandler.postDelayed(this, 2000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Build entire TV interface programmatically using Android framework
        View rootView = buildUserInterface();
        setContentView(rootView);

        listAdapter = new AppListAdapter();
        listViewApps.setAdapter(listAdapter);

        setupButtonListeners();
    }

    @Override
    protected void onResume() {
        super.onResume();

        // Start 2-second real-time network traffic polling
        lastRxTotalBytes = TrafficStats.getTotalRxBytes();
        lastTxTotalBytes = TrafficStats.getTotalTxBytes();
        lastTrafficSampleTime = SystemClock.elapsedRealtime();
        mainHandler.removeCallbacks(trafficPollRunnable);
        mainHandler.post(trafficPollRunnable);

        // Refresh RAM readout
        updateMemoryInfo();

        // Load apps and advance batch force-stop queue if active
        loadApps(() -> {
            if (batchClosing) {
                advanceBatchQueue();
            }
        });
    }

    @Override
    protected void onPause() {
        super.onPause();
        mainHandler.removeCallbacks(trafficPollRunnable);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        backgroundExecutor.shutdown();
    }

    /**
     * TV Remote Back Key Handling:
     * Pressing BACK twice cancels the batch force-stop queue.
     */
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN && event.getKeyCode() == KeyEvent.KEYCODE_BACK) {
            if (batchClosing) {
                long now = SystemClock.elapsedRealtime();
                if (now - lastBackPressTime < 2000) {
                    batchClosing = false;
                    batchVisitedPackages.clear();
                    tvStatusSummary.setText("Batch Force-Stop Queue cancelled by user.");
                    tvStatusSummary.setTextColor(0xFFFF5252);
                    Toast.makeText(this, "Batch Force Stop cancelled", Toast.LENGTH_SHORT).show();
                    return true;
                } else {
                    lastBackPressTime = now;
                    Toast.makeText(this, "Press BACK again to exit Batch Queue", Toast.LENGTH_SHORT).show();
                    return true;
                }
            }
        }
        return super.dispatchKeyEvent(event);
    }

    // =========================================================================
    // Core Engine: Background App Detection & Batch Queue Management
    // =========================================================================

    /**
     * Inspects all installed apps using ApplicationInfo.FLAG_STOPPED.
     * Separates User vs System apps, queries TrafficStats per UID, and sorts.
     */
    private void loadApps(Runnable onComplete) {
        backgroundExecutor.execute(() -> {
            PackageManager pm = getPackageManager();
            List<ApplicationInfo> installedList = pm.getInstalledApplications(0);
            String selfPackage = getPackageName();

            List<AppItem> unstoppedList = new ArrayList<>();

            for (ApplicationInfo info : installedList) {
                // Exclude this app itself
                if (info.packageName.equals(selfPackage)) {
                    continue;
                }

                // Identify active/unstopped apps: ((flags & FLAG_STOPPED) == 0) && enabled
                boolean isStopped = (info.flags & ApplicationInfo.FLAG_STOPPED) != 0;
                if (!isStopped && info.enabled) {
                    boolean isSystem = (info.flags & (ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0;
                    String label = info.loadLabel(pm).toString();
                    Drawable icon = info.loadIcon(pm);

                    long uidRx = TrafficStats.getUidRxBytes(info.uid);
                    if (uidRx < 0) {
                        uidRx = 0;
                    }

                    unstoppedList.add(new AppItem(info.packageName, label, icon, info.uid, isSystem, uidRx));
                }
            }

            mainHandler.post(() -> {
                rawUnstoppedApps.clear();
                rawUnstoppedApps.addAll(unstoppedList);
                applyFilterAndSort();
                updateMemoryInfo();

                if (onComplete != null) {
                    onComplete.run();
                }
            });
        });
    }

    private void applyFilterAndSort() {
        displayedApps.clear();
        int userCount = 0;
        int systemCount = 0;

        for (AppItem item : rawUnstoppedApps) {
            if (item.isSystem) {
                systemCount++;
                if (showSystemApps) {
                    displayedApps.add(item);
                }
            } else {
                userCount++;
                displayedApps.add(item);
            }
        }

        if (sortByDataUsed) {
            Collections.sort(displayedApps, (a, b) -> Long.compare(b.rxBytes, a.rxBytes));
        } else {
            Collections.sort(displayedApps, (a, b) -> a.label.compareToIgnoreCase(b.label));
        }

        listAdapter.notifyDataSetChanged();

        if (!batchClosing) {
            String summary = String.format(Locale.US,
                    "Active Apps: %d (User: %d | System: %d) — Showing: %d | Click app to Force Stop",
                    rawUnstoppedApps.size(), userCount, systemCount, displayedApps.size());
            tvStatusSummary.setText(summary);
            tvStatusSummary.setTextColor(COLOR_TEXT_SECONDARY);
        }
    }

    /**
     * Soft RAM Boost: Calls ActivityManager.killBackgroundProcesses() for each unstopped package.
     */
    private void executeSoftRamBoost() {
        ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        if (am == null) return;

        int boostCount = 0;
        for (AppItem app : displayedApps) {
            am.killBackgroundProcesses(app.packageName);
            boostCount++;
        }

        Toast.makeText(this, "Soft Boost: Killed background processes for " + boostCount + " apps", Toast.LENGTH_SHORT).show();
        updateMemoryInfo();
        loadApps(null);
    }

    /**
     * Initializes the Automated Force-Stop Queue.
     */
    private void startBatchQueue() {
        if (displayedApps.isEmpty()) {
            Toast.makeText(this, "No active apps in current filter to close", Toast.LENGTH_SHORT).show();
            return;
        }

        batchClosing = true;
        batchVisitedPackages.clear();
        batchTotalTargetCount = displayedApps.size();
        advanceBatchQueue();
    }

    /**
     * Advances to the next unstopped app in the batch queue.
     */
    private void advanceBatchQueue() {
        AppItem nextTarget = null;
        for (AppItem app : displayedApps) {
            if (!batchVisitedPackages.contains(app.packageName)) {
                nextTarget = app;
                break;
            }
        }

        if (nextTarget != null) {
            batchVisitedPackages.add(nextTarget.packageName);
            String status = String.format(Locale.US,
                    "QUEUE ACTIVE (%d/%d): [%s] -> Click 'Force Stop' then press BACK | Press BACK twice to cancel",
                    batchVisitedPackages.size(), batchTotalTargetCount, nextTarget.label);
            tvStatusSummary.setText(status);
            tvStatusSummary.setTextColor(COLOR_ACCENT_TEAL);

            launchAppDetailsSettings(nextTarget.packageName);
        } else {
            // Queue completed
            batchClosing = false;
            batchVisitedPackages.clear();
            tvStatusSummary.setText("Batch Force-Stop Queue Complete! All active apps processed.");
            tvStatusSummary.setTextColor(0xFF81C784);
            Toast.makeText(this, "Batch Force Stop Queue Completed", Toast.LENGTH_LONG).show();
        }
    }

    /**
     * Opens Android System App Details Settings for the target package.
     */
    private void launchAppDetailsSettings(String packageName) {
        try {
            Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            intent.setData(Uri.fromParts("package", packageName, null));
            startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(this, "Could not open settings for: " + packageName, Toast.LENGTH_SHORT).show();
        }
    }

    // =========================================================================
    // System Monitoring: RAM & Live Network Traffic
    // =========================================================================

    private void updateMemoryInfo() {
        ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        if (am == null) return;

        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);

        long totalMb = mi.totalMem / (1024 * 1024);
        long availMb = mi.availMem / (1024 * 1024);
        long usedMb = totalMb - availMb;
        int usedPercent = totalMb > 0 ? (int) ((usedMb * 100) / totalMb) : 0;

        String ramText = String.format(Locale.US,
                "Free RAM: %d MB / Total: %d MB (%d%% Used)",
                availMb, totalMb, usedPercent);
        tvRamValue.setText(ramText);
    }

    private void updateNetworkTraffic() {
        long currentRx = TrafficStats.getTotalRxBytes();
        long currentTx = TrafficStats.getTotalTxBytes();
        long currentTime = SystemClock.elapsedRealtime();

        if (lastTrafficSampleTime > 0 && currentTime > lastTrafficSampleTime) {
            long dtMs = currentTime - lastTrafficSampleTime;
            long rxDiff = currentRx - lastRxTotalBytes;
            long txDiff = currentTx - lastTxTotalBytes;

            if (rxDiff < 0) rxDiff = 0;
            if (txDiff < 0) txDiff = 0;

            double rxKbps = (rxDiff / 1024.0) / (dtMs / 1000.0);
            double txKbps = (txDiff / 1024.0) / (dtMs / 1000.0);

            String speedText = String.format(Locale.US,
                    "Down: %s | Up: %s",
                    formatRate(rxKbps), formatRate(txKbps));
            tvTrafficValue.setText(speedText);
        }

        lastRxTotalBytes = currentRx;
        lastTxTotalBytes = currentTx;
        lastTrafficSampleTime = currentTime;
    }

    private String formatRate(double kbps) {
        if (kbps >= 1024.0) {
            return String.format(Locale.US, "%.2f MB/s", kbps / 1024.0);
        } else {
            return String.format(Locale.US, "%.1f KB/s", kbps);
        }
    }

    // =========================================================================
    // UI Construction (Pure Android Framework, Zero Dependencies)
    // =========================================================================

    private View buildUserInterface() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(COLOR_BG);
        root.setPadding(dp(20), dp(16), dp(20), dp(16));

        // 1. Header (Title & Subtitle)
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        TextView tvTitle = new TextView(this);
        tvTitle.setText("TV Cleaner & Network Monitor");
        tvTitle.setTextColor(COLOR_TEXT_PRIMARY);
        tvTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        tvTitle.setTypeface(Typeface.DEFAULT_BOLD);
        header.addView(tvTitle);

        TextView tvSub = new TextView(this);
        tvSub.setText("  —  Chromecast with Google TV Edition");
        tvSub.setTextColor(COLOR_ACCENT_TEAL);
        tvSub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        header.addView(tvSub);

        root.addView(header);

        // 2. Metrics Header Bar (Live RAM & Traffic Cards)
        LinearLayout metricsBar = new LinearLayout(this);
        metricsBar.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams metricsParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        metricsParams.setMargins(0, dp(12), 0, dp(10));
        metricsBar.setLayoutParams(metricsParams);

        // RAM Card
        LinearLayout ramCard = createMetricCard("SYSTEM MEMORY (RAM)", COLOR_ACCENT_TEAL);
        tvRamValue = new TextView(this);
        tvRamValue.setText("Free RAM: -- MB / Total: -- MB");
        tvRamValue.setTextColor(COLOR_TEXT_PRIMARY);
        tvRamValue.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        tvRamValue.setTypeface(Typeface.DEFAULT_BOLD);
        ramCard.addView(tvRamValue);
        LinearLayout.LayoutParams cardColParams1 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f);
        cardColParams1.setMarginEnd(dp(12));
        ramCard.setLayoutParams(cardColParams1);
        metricsBar.addView(ramCard);

        // Network Traffic Card
        LinearLayout trafficCard = createMetricCard("LIVE NETWORK TRAFFIC (2s Poll)", COLOR_ACCENT_PURPLE);
        tvTrafficValue = new TextView(this);
        tvTrafficValue.setText("Down: 0.0 KB/s | Up: 0.0 KB/s");
        tvTrafficValue.setTextColor(COLOR_TEXT_PRIMARY);
        tvTrafficValue.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        tvTrafficValue.setTypeface(Typeface.DEFAULT_BOLD);
        trafficCard.addView(tvTrafficValue);
        LinearLayout.LayoutParams cardColParams2 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f);
        trafficCard.setLayoutParams(cardColParams2);
        metricsBar.addView(trafficCard);

        root.addView(metricsBar);

        // 3. Control Action Bar (D-Pad Focusable Buttons)
        LinearLayout actionsBar = new LinearLayout(this);
        actionsBar.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams actionsParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        actionsParams.setMargins(0, 0, 0, dp(8));
        actionsBar.setLayoutParams(actionsParams);

        btnOpenLauncher = createTvButton("[Home Launcher]", COLOR_ACCENT_TEAL);
        btnBoostRam = createTvButton("⚡ Boost RAM", COLOR_ACCENT_TEAL);
        btnBatchQueue = createTvButton("⛔ Force Stop Queue", COLOR_ACCENT_PURPLE);
        btnToggleSystem = createTvButton("System: OFF", COLOR_TEXT_PRIMARY);
        btnToggleSort = createTvButton("Sort: Name", COLOR_TEXT_PRIMARY);
        btnRefresh = createTvButton("🔄 Refresh", COLOR_TEXT_PRIMARY);

        actionsBar.addView(btnOpenLauncher);
        actionsBar.addView(btnBoostRam);
        actionsBar.addView(btnBatchQueue);
        actionsBar.addView(btnToggleSystem);
        actionsBar.addView(btnToggleSort);
        actionsBar.addView(btnRefresh);

        if (PlatformDetector.IS_FIRE_TV) {
            TextView btnFireDebloat = createTvButton("🛡 Fire Debloater", Color.parseColor("#FFC107"));
            btnFireDebloat.setOnClickListener(v -> startActivity(new Intent(this, FireTvDebloatActivity.class)));
            actionsBar.addView(btnFireDebloat);
        }

        root.addView(actionsBar);

        // 4. Status Summary Line
        tvStatusSummary = new TextView(this);
        tvStatusSummary.setText("Querying active background applications...");
        tvStatusSummary.setTextColor(COLOR_TEXT_SECONDARY);
        tvStatusSummary.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        statusParams.setMargins(0, 0, 0, dp(8));
        tvStatusSummary.setLayoutParams(statusParams);
        root.addView(tvStatusSummary);

        // 5. Scrollable App List
        listViewApps = new ListView(this);
        listViewApps.setDivider(null);
        listViewApps.setDividerHeight(dp(6));
        listViewApps.setItemsCanFocus(true);
        listViewApps.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(listViewApps);

        return root;
    }

    private LinearLayout createMetricCard(String title, int accentColor) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(12), dp(10), dp(12), dp(10));

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(COLOR_CARD);
        bg.setCornerRadius(dp(8));
        bg.setStroke(dp(1), COLOR_BORDER_DEFAULT);
        card.setBackground(bg);

        TextView tvTitle = new TextView(this);
        tvTitle.setText(title);
        tvTitle.setTextColor(accentColor);
        tvTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        tvTitle.setTypeface(Typeface.DEFAULT_BOLD);
        tvTitle.setPadding(0, 0, 0, dp(4));
        card.addView(tvTitle);

        return card;
    }

    private TextView createTvButton(String text, int textColor) {
        TextView btn = new TextView(this);
        btn.setText(text);
        btn.setTextColor(textColor);
        btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        btn.setTypeface(Typeface.DEFAULT_BOLD);
        btn.setGravity(Gravity.CENTER);
        btn.setPadding(dp(12), dp(8), dp(12), dp(8));
        btn.setFocusable(true);
        btn.setFocusableInTouchMode(true);
        btn.setClickable(true);

        // StateListDrawable for TV Remote D-Pad focus
        btn.setBackground(createButtonSelector());

        // TV Remote scale feedback
        btn.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) {
                v.animate().scaleX(1.05f).scaleY(1.05f).setDuration(120).start();
            } else {
                v.animate().scaleX(1.0f).scaleY(1.0f).setDuration(120).start();
            }
        });

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f);
        lp.setMargins(dp(3), 0, dp(3), 0);
        btn.setLayoutParams(lp);

        return btn;
    }

    private StateListDrawable createButtonSelector() {
        StateListDrawable sld = new StateListDrawable();

        // Focused state (TV cursor on button)
        GradientDrawable focused = new GradientDrawable();
        focused.setColor(0xFF262626);
        focused.setCornerRadius(dp(6));
        focused.setStroke(dp(2), COLOR_ACCENT_TEAL);
        sld.addState(new int[]{android.R.attr.state_focused}, focused);

        // Pressed state
        GradientDrawable pressed = new GradientDrawable();
        pressed.setColor(0xFF333333);
        pressed.setCornerRadius(dp(6));
        pressed.setStroke(dp(2), COLOR_ACCENT_PURPLE);
        sld.addState(new int[]{android.R.attr.state_pressed}, pressed);

        // Normal state
        GradientDrawable normal = new GradientDrawable();
        normal.setColor(COLOR_CARD);
        normal.setCornerRadius(dp(6));
        normal.setStroke(dp(1), COLOR_BORDER_DEFAULT);
        sld.addState(new int[]{}, normal);

        return sld;
    }

    private StateListDrawable createItemSelector() {
        StateListDrawable sld = new StateListDrawable();

        // Focused state (D-Pad selected row)
        GradientDrawable focused = new GradientDrawable();
        focused.setColor(COLOR_CARD_FOCUSED);
        focused.setCornerRadius(dp(8));
        focused.setStroke(dp(3), COLOR_ACCENT_PURPLE);
        sld.addState(new int[]{android.R.attr.state_focused}, focused);

        // Pressed state
        GradientDrawable pressed = new GradientDrawable();
        pressed.setColor(0xFF352B4D);
        pressed.setCornerRadius(dp(8));
        pressed.setStroke(dp(3), COLOR_ACCENT_TEAL);
        sld.addState(new int[]{android.R.attr.state_pressed}, pressed);

        // Default state
        GradientDrawable normal = new GradientDrawable();
        normal.setColor(COLOR_CARD);
        normal.setCornerRadius(dp(8));
        normal.setStroke(dp(1), COLOR_BORDER_DEFAULT);
        sld.addState(new int[]{}, normal);

        return sld;
    }

    private void setupButtonListeners() {
        btnOpenLauncher.setOnClickListener(v -> {
            batchClosing = false;
            Intent intent = new Intent(this, LauncherActivity.class);
            startActivity(intent);
        });

        btnBoostRam.setOnClickListener(v -> {
            batchClosing = false;
            executeSoftRamBoost();
        });

        btnBatchQueue.setOnClickListener(v -> startBatchQueue());

        btnToggleSystem.setOnClickListener(v -> {
            batchClosing = false;
            showSystemApps = !showSystemApps;
            btnToggleSystem.setText(showSystemApps ? "System Apps: ON" : "System Apps: OFF");
            btnToggleSystem.setTextColor(showSystemApps ? COLOR_ACCENT_TEAL : COLOR_TEXT_PRIMARY);
            applyFilterAndSort();
        });

        btnToggleSort.setOnClickListener(v -> {
            batchClosing = false;
            sortByDataUsed = !sortByDataUsed;
            btnToggleSort.setText(sortByDataUsed ? "Sort: Data Used" : "Sort: Name");
            btnToggleSort.setTextColor(sortByDataUsed ? COLOR_ACCENT_PURPLE : COLOR_TEXT_PRIMARY);
            applyFilterAndSort();
        });

        btnRefresh.setOnClickListener(v -> {
            batchClosing = false;
            updateMemoryInfo();
            loadApps(null);
        });
    }

    private int dp(float dpVal) {
        return (int) (dpVal * getResources().getDisplayMetrics().density + 0.5f);
    }

    // =========================================================================
    // List Adapter for TV Remote Navigation
    // =========================================================================

    private class AppListAdapter extends BaseAdapter {

        @Override
        public int getCount() {
            return displayedApps.size();
        }

        @Override
        public AppItem getItem(int position) {
            return displayedApps.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            ItemViewHolder holder;
            if (convertView == null) {
                LinearLayout card = new LinearLayout(MainActivity.this);
                card.setOrientation(LinearLayout.HORIZONTAL);
                card.setGravity(Gravity.CENTER_VERTICAL);
                card.setPadding(dp(12), dp(10), dp(16), dp(10));
                card.setFocusable(true);
                card.setFocusableInTouchMode(true);
                card.setClickable(true);
                card.setBackground(createItemSelector());

                // Remote D-Pad scale effect
                card.setOnFocusChangeListener((v, hasFocus) -> {
                    if (hasFocus) {
                        v.animate().scaleX(1.02f).scaleY(1.02f).setDuration(100).start();
                    } else {
                        v.animate().scaleX(1.0f).scaleY(1.0f).setDuration(100).start();
                    }
                });

                // App Icon
                ImageView ivIcon = new ImageView(MainActivity.this);
                LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(dp(44), dp(44));
                iconParams.setMarginEnd(dp(14));
                ivIcon.setLayoutParams(iconParams);
                card.addView(ivIcon);

                // Middle Info Column
                LinearLayout infoCol = new LinearLayout(MainActivity.this);
                infoCol.setOrientation(LinearLayout.VERTICAL);
                LinearLayout.LayoutParams colParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f);
                infoCol.setLayoutParams(colParams);

                TextView tvLabel = new TextView(MainActivity.this);
                tvLabel.setTextColor(COLOR_TEXT_PRIMARY);
                tvLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
                tvLabel.setTypeface(Typeface.DEFAULT_BOLD);
                infoCol.addView(tvLabel);

                TextView tvPkg = new TextView(MainActivity.this);
                tvPkg.setTextColor(COLOR_TEXT_MUTED);
                tvPkg.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
                infoCol.addView(tvPkg);

                // Badges row
                LinearLayout badgeRow = new LinearLayout(MainActivity.this);
                badgeRow.setOrientation(LinearLayout.HORIZONTAL);
                badgeRow.setPadding(0, dp(4), 0, 0);

                TextView tvTypeBadge = createBadgeView();
                badgeRow.addView(tvTypeBadge);

                TextView tvDataBadge = createBadgeView();
                badgeRow.addView(tvDataBadge);

                infoCol.addView(badgeRow);
                card.addView(infoCol);

                // Right Action Button Indicator
                TextView tvAction = new TextView(MainActivity.this);
                tvAction.setText("Force Stop >");
                tvAction.setTextColor(COLOR_ACCENT_TEAL);
                tvAction.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
                tvAction.setTypeface(Typeface.DEFAULT_BOLD);
                tvAction.setPadding(dp(8), dp(4), dp(8), dp(4));
                card.addView(tvAction);

                holder = new ItemViewHolder();
                holder.card = card;
                holder.ivIcon = ivIcon;
                holder.tvLabel = tvLabel;
                holder.tvPkg = tvPkg;
                holder.tvTypeBadge = tvTypeBadge;
                holder.tvDataBadge = tvDataBadge;
                card.setTag(holder);

                convertView = card;
            } else {
                holder = (ItemViewHolder) convertView.getTag();
            }

            AppItem item = getItem(position);
            holder.ivIcon.setImageDrawable(item.icon);
            holder.tvLabel.setText(item.label);
            holder.tvPkg.setText(item.packageName);

            // User / System Badge
            if (item.isSystem) {
                holder.tvTypeBadge.setText("SYSTEM");
                holder.tvTypeBadge.setTextColor(0xFFFFB74D);
                setBadgeBackground(holder.tvTypeBadge, COLOR_BADGE_SYSTEM);
            } else {
                holder.tvTypeBadge.setText("USER");
                holder.tvTypeBadge.setTextColor(0xFF81C784);
                setBadgeBackground(holder.tvTypeBadge, COLOR_BADGE_USER);
            }

            // Cumulative Boot Download Badge (TrafficStats.getUidRxBytes)
            if (item.rxBytes > 0) {
                double rxMb = item.rxBytes / (1024.0 * 1024.0);
                holder.tvDataBadge.setVisibility(View.VISIBLE);
                holder.tvDataBadge.setText(String.format(Locale.US, "⬇ %.1f MB", rxMb));
                holder.tvDataBadge.setTextColor(COLOR_ACCENT_PURPLE);
                setBadgeBackground(holder.tvDataBadge, COLOR_BADGE_DATA);
            } else {
                holder.tvDataBadge.setVisibility(View.GONE);
            }

            // Clicking an item opens settings directly and aborts batch mode
            convertView.setOnClickListener(v -> {
                batchClosing = false;
                batchVisitedPackages.clear();
                launchAppDetailsSettings(item.packageName);
            });

            return convertView;
        }

        private TextView createBadgeView() {
            TextView badge = new TextView(MainActivity.this);
            badge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9);
            badge.setTypeface(Typeface.DEFAULT_BOLD);
            badge.setPadding(dp(6), dp(2), dp(6), dp(2));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMarginEnd(dp(6));
            badge.setLayoutParams(lp);
            return badge;
        }

        private void setBadgeBackground(TextView view, int solidColor) {
            GradientDrawable gd = new GradientDrawable();
            gd.setColor(solidColor);
            gd.setCornerRadius(dp(4));
            view.setBackground(gd);
        }
    }

    private static class ItemViewHolder {
        LinearLayout card;
        ImageView ivIcon;
        TextView tvLabel;
        TextView tvPkg;
        TextView tvTypeBadge;
        TextView tvDataBadge;
    }
}
