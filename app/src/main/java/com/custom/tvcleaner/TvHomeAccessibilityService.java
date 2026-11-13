package com.custom.tvcleaner;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

/**
 * TV Home Accessibility Service.
 * Intercepts the remote's HOME button and redirects stock launchers to TV Clean Home.
 * Whitelists Google TV Freeplay / Live TV channels so the dedicated remote button works seamlessly.
 */
public class TvHomeAccessibilityService extends AccessibilityService {

    private static final String GOOGLE_TV_LAUNCHER_PKG = "com.google.android.apps.tv.launcherx";
    private static final String AMAZON_TV_LAUNCHER_PKG = "com.amazon.tv.launcher";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private long lastLaunchTime = 0;
    private Runnable pendingRedirect = null;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        AccessibilityServiceInfo info = new AccessibilityServiceInfo();
        info.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED;
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC;
        info.flags = AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
                | AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
        info.notificationTimeout = 50;
        setServiceInfo(info);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            CharSequence pkgName = event.getPackageName();
            CharSequence className = event.getClassName();

            // Check if this window is part of Google TV Freeplay / Live TV / Guide
            if (isFreeplayOrAllowedActivity(className)) {
                // Cancel any pending redirect to clean home so Free TV stays active
                cancelPendingRedirect();
                return;
            }

            // Check if this window is Fire TV Settings or HUD (even if hosted inside com.amazon.tv.launcher)
            if (isFireTvSettingsActivity(className)) {
                // Cancel any pending redirect so Fire TV Settings / HUD opens cleanly
                cancelPendingRedirect();
                return;
            }

            if (pkgName != null && AMAZON_TV_LAUNCHER_PKG.contentEquals(pkgName)) {
                // Fire TV stock launcher: immediate redirect
                scheduleCleanHome(50);
            } else if (pkgName != null && GOOGLE_TV_LAUNCHER_PKG.contentEquals(pkgName)) {
                // Google TV: Could be Freeplay starting up via HomeActivity deep link!
                // Wait 400ms. If Freeplay (PageActivity/ModalDialog) opens, it will cancel this redirect.
                // If it is genuinely the stock Google TV home screen, redirect to TV Clean Home.
                scheduleCleanHome(400);
            } else {
                // Any other package (Netflix, YouTube, Stremio, etc.)
                cancelPendingRedirect();
            }
        }
    }

    private boolean isFreeplayOrAllowedActivity(CharSequence className) {
        if (className == null) return false;
        String cls = className.toString();
        return cls.contains("PageActivity")
                || cls.contains("ModalDialogActivity")
                || cls.contains("SearchTextInputActivity")
                || cls.contains(".live.")
                || cls.contains("fullscreeneducation")
                || cls.contains("freeplay");
    }

    private boolean isFireTvSettingsActivity(CharSequence className) {
        if (className == null) return false;
        String cls = className.toString();
        return cls.contains("MainSettingsActivity")
                || cls.contains("SettingsActivity")
                || cls.contains("QuickSettingsProxyActivity")
                || cls.contains(".settings.")
                || cls.contains("HudActivity")
                || cls.contains(".hud.");
    }

    private void scheduleCleanHome(long delayMs) {
        cancelPendingRedirect();
        pendingRedirect = new Runnable() {
            @Override
            public void run() {
                long now = SystemClock.elapsedRealtime();
                if (now - lastLaunchTime > 300) {
                    lastLaunchTime = now;
                    launchCleanHome();
                }
            }
        };
        handler.postDelayed(pendingRedirect, delayMs);
    }

    private void cancelPendingRedirect() {
        if (pendingRedirect != null) {
            handler.removeCallbacks(pendingRedirect);
            pendingRedirect = null;
        }
    }

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        int keyCode = event.getKeyCode();
        if (keyCode == KeyEvent.KEYCODE_HOME) {
            if (event.getAction() == KeyEvent.ACTION_UP) {
                cancelPendingRedirect();
                launchCleanHome();
            }
            // Consume the event so Google TV / system never receives the Home key
            return true;
        }
        return super.onKeyEvent(event);
    }

    private void launchCleanHome() {
        Intent intent = new Intent(this, LauncherActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_CLEAR_TOP
                | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(intent);
    }

    @Override
    public void onInterrupt() {
        cancelPendingRedirect();
    }
}
