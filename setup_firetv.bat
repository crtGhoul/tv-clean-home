@echo off
title Fire TV Stick - TV Clean Home & Debloater 1-Click Setup
color 0e
echo ======================================================================
echo    AMAZON FIRE TV STICK - 1-CLICK CLEAN LAUNCHER SETUP
echo ======================================================================
echo.
echo Make sure your Fire TV Stick and PC are on the same Wi-Fi network.
echo.
echo On your Fire TV Stick:
echo   1. Settings - My Fire TV - About
echo      (If Developer Options is hidden, click Fire TV Stick 7 times)
echo   2. Settings - My Fire TV - Developer Options:
echo      - Turn ADB Debugging: ON
echo      - Turn Install unknown apps: ON (for TV Clean Home / Downloader)
echo   3. Settings - My Fire TV - About - Network: Note your IP address
echo.

set /p TV_IP="Enter Fire TV IP (e.g. 192.168.1.120): "
set TV_PORT=5555

echo.
echo [*] Connecting to Fire TV at %TV_IP%:%TV_PORT%...
platform-tools\adb.exe connect %TV_IP%:%TV_PORT%

echo.
echo [*] Checking connection...
echo [!] LOOK AT YOUR TV SCREEN NOW: If a popup appears saying
echo     "Allow USB debugging?", check "Always allow" and click OK!
echo.
pause

platform-tools\adb.exe -s %TV_IP%:%TV_PORT% get-state >nul 2>&1
if %errorlevel% neq 0 (
    echo [!] Device not authorized or not reachable. Re-connecting...
    platform-tools\adb.exe connect %TV_IP%:%TV_PORT%
)

echo.
echo [*] Installing TV Clean Home (Universal APK)...
set APK_FILE=TVCleaner.apk
if not exist "%APK_FILE%" set APK_FILE=TVCleaner-debug.apk
if not exist "%APK_FILE%" (
    echo [-] Could not find TVCleaner.apk or TVCleaner-debug.apk in this folder!
    pause
    exit /b 1
)

platform-tools\adb.exe -s %TV_IP%:%TV_PORT% install -r %APK_FILE%
if %errorlevel% neq 0 (
    echo [-] Installation failed. Check if ADB debugging is ON in Fire TV Settings.
    pause
    exit /b %errorlevel%
)

echo [*] Granting TV Clean Home system permissions...
platform-tools\adb.exe -s %TV_IP%:%TV_PORT% shell settings put secure enabled_accessibility_services com.custom.tvcleaner/com.custom.tvcleaner.TvHomeAccessibilityService
platform-tools\adb.exe -s %TV_IP%:%TV_PORT% shell settings put secure accessibility_enabled 1

echo [*] Launching TV Clean Home on Fire TV...
platform-tools\adb.exe -s %TV_IP%:%TV_PORT% shell am start -n com.custom.tvcleaner/.LauncherActivity

echo.
echo ======================================================================
echo [SUCCESS] TV Clean Home installed and running on your Fire TV!
echo.
echo Tips for Fire TV:
echo  - Press HOME on your remote anytime to return to TV Clean Home.
echo  - To remove Amazon ads & telemetry, run: debloat_firetv.bat
echo ======================================================================
echo.
pause
