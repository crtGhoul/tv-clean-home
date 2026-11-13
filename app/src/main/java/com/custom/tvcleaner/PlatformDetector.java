package com.custom.tvcleaner;

import android.os.Build;

/**
 * Hardware & Operating System runtime detection.
 * Identifies whether the app is executing on Google TV / Android TV or Amazon Fire OS,
 * enabling platform-specific features while freezing Google TV behavior.
 */
public final class PlatformDetector {

    public static final boolean IS_FIRE_TV;
    public static final int FIRE_OS_MAJOR_VERSION;
    public static final String PLATFORM_NAME;

    static {
        String manufacturer = Build.MANUFACTURER != null ? Build.MANUFACTURER.toLowerCase() : "";
        String brand = Build.BRAND != null ? Build.BRAND.toLowerCase() : "";
        String model = Build.MODEL != null ? Build.MODEL.toLowerCase() : "";

        boolean isAmazon = manufacturer.contains("amazon") || brand.contains("amazon") || model.startsWith("aft");
        IS_FIRE_TV = isAmazon;

        if (isAmazon) {
            // Fire OS 8 = Android 11 (API 30+) e.g. Fire TV Stick 4K 2nd Gen, Stick 4K Max 2nd Gen
            // Fire OS 7 = Android 9 (API 28) e.g. Fire TV Stick 4K 1st Gen, Stick 3rd Gen, Stick Lite
            // Fire OS 6 = Android 7.1 (API 25)
            if (Build.VERSION.SDK_INT >= 30) {
                FIRE_OS_MAJOR_VERSION = 8;
                PLATFORM_NAME = "Fire OS 8 (Android " + Build.VERSION.RELEASE + ")";
            } else if (Build.VERSION.SDK_INT >= 28) {
                FIRE_OS_MAJOR_VERSION = 7;
                PLATFORM_NAME = "Fire OS 7 (Android " + Build.VERSION.RELEASE + ")";
            } else {
                FIRE_OS_MAJOR_VERSION = 6;
                PLATFORM_NAME = "Fire OS 6 (Android " + Build.VERSION.RELEASE + ")";
            }
        } else {
            FIRE_OS_MAJOR_VERSION = 0;
            PLATFORM_NAME = "Google TV / Android TV (Android " + Build.VERSION.RELEASE + ")";
        }
    }

    private PlatformDetector() {}
}
