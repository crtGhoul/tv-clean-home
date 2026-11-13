package com.custom.tvcleaner;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * TV Remote-optimized Fire TV Debloater dashboard.
 * High-contrast D-pad navigation, preset profiles, safe toggle controls, and 1-click restore.
 */
public class FireTvDebloatActivity extends Activity {

    private static final int COLOR_BG = Color.parseColor("#0F0F12");
    private static final int COLOR_CARD_BG = Color.parseColor("#1B1B22");
    private static final int COLOR_CARD_FOCUS = Color.parseColor("#282836");
    private static final int COLOR_ACCENT_TEAL = Color.parseColor("#03DAC5");
    private static final int COLOR_ACCENT_PURPLE = Color.parseColor("#BB86FC");
    private static final int COLOR_ACCENT_GREEN = Color.parseColor("#4CAF50");
    private static final int COLOR_ACCENT_YELLOW = Color.parseColor("#FFC107");
    private static final int COLOR_ACCENT_RED = Color.parseColor("#CF6679");
    private static final int COLOR_TEXT_PRIMARY = Color.parseColor("#FFFFFF");
    private static final int COLOR_TEXT_SECONDARY = Color.parseColor("#9E9E9E");

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private List<FireTvDebloatManager.DebloatItem> debloatItems = new ArrayList<>();
    private DebloatListAdapter listAdapter;
    private ListView listView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildView());
        loadDebloatCatalog();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdown();
    }

    private View buildView() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(COLOR_BG);
        int pad = dp(24);
        root.setPadding(pad, pad, pad, pad);

        // Header Title & Platform Indicator
        TextView tvTitle = new TextView(this);
        tvTitle.setText("🛡 Fire TV System Debloater");
        tvTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26);
        tvTitle.setTextColor(COLOR_TEXT_PRIMARY);
        tvTitle.setTypeface(null, Typeface.BOLD);
        root.addView(tvTitle);

        TextView tvSubtitle = new TextView(this);
        tvSubtitle.setText("Disable background telemetry, ad banners & forced updates. 100% reversible via Restore All.");
        tvSubtitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        tvSubtitle.setTextColor(COLOR_TEXT_SECONDARY);
        tvSubtitle.setPadding(0, dp(4), 0, dp(16));
        root.addView(tvSubtitle);

        // Preset Pills Bar
        LinearLayout presetsBar = new LinearLayout(this);
        presetsBar.setOrientation(LinearLayout.HORIZONTAL);
        presetsBar.setGravity(Gravity.CENTER_VERTICAL);
        presetsBar.setPadding(0, 0, 0, dp(16));

        TextView btnSafe = createPillButton("🟢 Safe Preset", COLOR_ACCENT_GREEN);
        btnSafe.setOnClickListener(v -> applyPresetMode(0, "Safe Preset: Ads, Telemetry & Shipping"));
        presetsBar.addView(btnSafe);

        TextView btnRec = createPillButton("✨ Recommended", COLOR_ACCENT_TEAL);
        btnRec.setOnClickListener(v -> applyPresetMode(1, "Recommended Preset: Safe + Live TV & News"));
        presetsBar.addView(btnRec);

        TextView btnMax = createPillButton("⚡ Max Debloat", COLOR_ACCENT_YELLOW);
        btnMax.setOnClickListener(v -> applyPresetMode(2, "Max Debloat: Disables all non-critical Amazon overlays"));
        presetsBar.addView(btnMax);

        TextView btnRestore = createPillButton("↺ Restore All", COLOR_ACCENT_PURPLE);
        btnRestore.setOnClickListener(v -> confirmRestoreAll());
        presetsBar.addView(btnRestore);

        TextView btnApply = createPillButton("✔ Apply Changes", COLOR_TEXT_PRIMARY);
        btnApply.setOnClickListener(v -> confirmApplyChanges());
        presetsBar.addView(btnApply);

        root.addView(presetsBar);

        // Package List View
        listView = new ListView(this);
        listView.setDivider(null);
        listView.setDividerHeight(dp(6));
        listView.setChoiceMode(ListView.CHOICE_MODE_NONE);
        listAdapter = new DebloatListAdapter();
        listView.setAdapter(listAdapter);

        LinearLayout.LayoutParams listParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.0f);
        root.addView(listView, listParams);

        return root;
    }

    private void loadDebloatCatalog() {
        executor.execute(() -> {
            List<FireTvDebloatManager.DebloatItem> items = FireTvDebloatManager.getCatalog(this);
            // Default to Safe preset
            FireTvDebloatManager.applyPreset(items, 0);

            mainHandler.post(() -> {
                debloatItems.clear();
                debloatItems.addAll(items);
                listAdapter.notifyDataSetChanged();
            });
        });
    }

    private void applyPresetMode(int mode, String msg) {
        FireTvDebloatManager.applyPreset(debloatItems, mode);
        listAdapter.notifyDataSetChanged();
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    private void confirmApplyChanges() {
        int selectedCount = 0;
        StringBuilder sb = new StringBuilder();
        for (FireTvDebloatManager.DebloatItem item : debloatItems) {
            if (item.isSelected && item.isInstalled && !item.isCurrentlyDisabled) {
                selectedCount++;
                sb.append("• ").append(item.friendlyName).append(" (").append(item.packageName).append(")\n");
            }
        }

        if (selectedCount == 0) {
            Toast.makeText(this, "No new packages selected to disable.", Toast.LENGTH_SHORT).show();
            return;
        }

        new AlertDialog.Builder(this)
                .setTitle("Confirm Debloat (" + selectedCount + " packages)")
                .setMessage("TV Clean Home will run 'pm disable-user' on:\n\n" + sb.toString() +
                        "\nSystem packages will only be dormant. You can restore them anytime with 'Restore All'.")
                .setPositiveButton("Execute", (dialog, which) -> executeApply())
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void executeApply() {
        Toast.makeText(this, "Applying package modifications...", Toast.LENGTH_SHORT).show();
        executor.execute(() -> {
            StringBuilder log = new StringBuilder();
            boolean success = FireTvDebloatManager.disableSelectedPackages(this, debloatItems, log);

            mainHandler.post(() -> {
                if (success) {
                    Toast.makeText(this, "Debloat applied successfully!", Toast.LENGTH_LONG).show();
                } else {
                    showAdbRequiredDialog(log.toString());
                }
                loadDebloatCatalog();
            });
        });
    }

    private void confirmRestoreAll() {
        new AlertDialog.Builder(this)
                .setTitle("Restore All Packages?")
                .setMessage("This will re-enable all Fire OS packages back to their default factory state.")
                .setPositiveButton("Restore All", (dialog, which) -> {
                    Toast.makeText(this, "Restoring all packages...", Toast.LENGTH_SHORT).show();
                    executor.execute(() -> {
                        StringBuilder log = new StringBuilder();
                        boolean ok = FireTvDebloatManager.restoreAll(this, log);
                        mainHandler.post(() -> {
                            if (ok) {
                                Toast.makeText(this, "All packages restored!", Toast.LENGTH_LONG).show();
                            } else {
                                showAdbRequiredDialog(log.toString());
                            }
                            loadDebloatCatalog();
                        });
                    });
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showAdbRequiredDialog(String errorLog) {
        new AlertDialog.Builder(this)
                .setTitle("ADB Permission Required")
                .setMessage("Fire OS blocks normal apps from modifying system packages without ADB authorization.\n\n" +
                        "To grant permission:\n" +
                        "1. Turn ON ADB Debugging in Fire TV Settings ➔ My Fire TV ➔ Developer Options.\n" +
                        "2. Connect your PC over Wi-Fi and run the companion script: .\\debloat_firetv.ps1\n\n" +
                        "Log detail:\n" + (errorLog.isEmpty() ? "Permission denied by OS" : errorLog))
                .setPositiveButton("Copy ADB Commands", (dialog, which) -> {
                    ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                    if (cm != null) {
                        StringBuilder cmds = new StringBuilder();
                        for (FireTvDebloatManager.DebloatItem item : debloatItems) {
                            if (item.isSelected && item.tier != FireTvDebloatManager.Tier.NEVER_TOUCH) {
                                cmds.append("adb shell pm disable-user --user 0 ").append(item.packageName).append("\n");
                            }
                        }
                        cm.setPrimaryClip(ClipData.newPlainText("ADB Debloat Commands", cmds.toString()));
                        Toast.makeText(this, "Commands copied to clipboard!", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("OK", null)
                .show();
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
        defaultBg.setColor(Color.parseColor("#22222E"));
        defaultBg.setCornerRadius(dp(18));
        defaultBg.setStroke(dp(1), Color.parseColor("#38384A"));

        GradientDrawable focusedBg = new GradientDrawable();
        focusedBg.setColor(Color.parseColor("#2E2E40"));
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

        return tv;
    }

    private int dp(int val) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, val, getResources().getDisplayMetrics());
    }

    // =========================================================================
    // List Adapter with High-Contrast Remote D-Pad Navigation
    // =========================================================================

    private class DebloatListAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return debloatItems.size();
        }

        @Override
        public FireTvDebloatManager.DebloatItem getItem(int position) {
            return debloatItems.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            DebloatRowView row = (convertView instanceof DebloatRowView) ?
                    (DebloatRowView) convertView : new DebloatRowView(FireTvDebloatActivity.this);
            row.bind(getItem(position));
            return row;
        }
    }

    private class DebloatRowView extends LinearLayout {
        private final TextView tvCheck;
        private final TextView tvName;
        private final TextView tvDesc;
        private final TextView tvTier;
        private final TextView tvStatus;
        private FireTvDebloatManager.DebloatItem boundItem;

        public DebloatRowView(Context context) {
            super(context);
            setOrientation(HORIZONTAL);
            setGravity(Gravity.CENTER_VERTICAL);
            setFocusable(true);
            setFocusableInTouchMode(true);
            int pad = dp(14);
            setPadding(pad, pad, pad, pad);

            GradientDrawable defaultBg = new GradientDrawable();
            defaultBg.setColor(COLOR_CARD_BG);
            defaultBg.setCornerRadius(dp(10));
            defaultBg.setStroke(dp(1), Color.parseColor("#282836"));

            GradientDrawable focusedBg = new GradientDrawable();
            focusedBg.setColor(COLOR_CARD_FOCUS);
            focusedBg.setCornerRadius(dp(10));
            focusedBg.setStroke(dp(2), COLOR_ACCENT_TEAL);

            StateListDrawable states = new StateListDrawable();
            states.addState(new int[]{android.R.attr.state_focused}, focusedBg);
            states.addState(new int[]{}, defaultBg);
            setBackground(states);

            setOnFocusChangeListener((v, hasFocus) -> {
                if (hasFocus) {
                    v.animate().scaleX(1.02f).scaleY(1.02f).setDuration(100).start();
                } else {
                    v.animate().scaleX(1.0f).scaleY(1.0f).setDuration(100).start();
                }
            });

            setOnClickListener(v -> {
                if (boundItem == null || boundItem.tier == FireTvDebloatManager.Tier.NEVER_TOUCH) {
                    Toast.makeText(getContext(), "Protected system service. Cannot be toggled.", Toast.LENGTH_SHORT).show();
                    return;
                }
                boundItem.isSelected = !boundItem.isSelected;
                updateCheckState();
            });

            // Checkbox indicator
            tvCheck = new TextView(context);
            tvCheck.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
            tvCheck.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
            tvCheck.setGravity(Gravity.CENTER);
            tvCheck.setWidth(dp(36));
            addView(tvCheck);

            // Center details (Name + Package + Description)
            LinearLayout details = new LinearLayout(context);
            details.setOrientation(VERTICAL);
            LayoutParams dlp = new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f);
            dlp.setMargins(dp(12), 0, dp(12), 0);
            details.setLayoutParams(dlp);

            tvName = new TextView(context);
            tvName.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
            tvName.setTextColor(COLOR_TEXT_PRIMARY);
            tvName.setTypeface(null, Typeface.BOLD);
            details.addView(tvName);

            tvDesc = new TextView(context);
            tvDesc.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            tvDesc.setTextColor(COLOR_TEXT_SECONDARY);
            details.addView(tvDesc);

            addView(details);

            // Right badges (Tier + Current Status)
            LinearLayout badges = new LinearLayout(context);
            badges.setOrientation(VERTICAL);
            badges.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);

            tvTier = new TextView(context);
            tvTier.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
            tvTier.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
            badges.addView(tvTier);

            tvStatus = new TextView(context);
            tvStatus.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
            tvStatus.setTypeface(Typeface.MONOSPACE);
            badges.addView(tvStatus);

            addView(badges);
        }

        public void bind(FireTvDebloatManager.DebloatItem item) {
            this.boundItem = item;
            tvName.setText(item.friendlyName + " (" + item.packageName + ")");
            tvDesc.setText(item.description);

            if (item.tier == FireTvDebloatManager.Tier.SAFE) {
                tvTier.setText("SAFE");
                tvTier.setTextColor(COLOR_ACCENT_GREEN);
            } else if (item.tier == FireTvDebloatManager.Tier.CAUTION) {
                tvTier.setText("CAUTION");
                tvTier.setTextColor(COLOR_ACCENT_YELLOW);
            } else {
                tvTier.setText("NEVER TOUCH");
                tvTier.setTextColor(COLOR_ACCENT_RED);
            }

            if (!item.isInstalled) {
                tvStatus.setText("Not Installed");
                tvStatus.setTextColor(COLOR_TEXT_SECONDARY);
            } else if (item.isCurrentlyDisabled) {
                tvStatus.setText("● Disabled");
                tvStatus.setTextColor(COLOR_ACCENT_YELLOW);
            } else {
                tvStatus.setText("● Active");
                tvStatus.setTextColor(COLOR_ACCENT_GREEN);
            }

            updateCheckState();
        }

        private void updateCheckState() {
            if (boundItem == null) return;
            if (boundItem.tier == FireTvDebloatManager.Tier.NEVER_TOUCH) {
                tvCheck.setText("🔒");
                tvCheck.setTextColor(COLOR_ACCENT_RED);
            } else if (boundItem.isSelected) {
                tvCheck.setText("[✔]");
                tvCheck.setTextColor(COLOR_ACCENT_TEAL);
            } else {
                tvCheck.setText("[ ]");
                tvCheck.setTextColor(COLOR_TEXT_SECONDARY);
            }
        }
    }
}
