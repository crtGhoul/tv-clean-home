@echo off
title Google TV (Chromecast & onn.) - 1-Click Setup
color 0b
echo ======================================================================
echo    GOOGLE TV (CHROMECAST & ONN.) - 1-CLICK LAUNCHER SETUP
echo ======================================================================
echo.
echo Make sure your TV device and PC are on the same Wi-Fi network.
echo On TV: Settings - System - Developer Options:
echo   - Turn USB debugging: ON
echo   - Turn Wireless debugging: ON
echo.

set /p TV_IP="Enter TV IP address (e.g. 192.168.1.150): "

echo.
echo [*] Testing direct connection to %TV_IP%:5555...
platform-tools\adb.exe connect %TV_IP%:5555

echo.
echo [!] LOOK AT YOUR TV SCREEN NOW:
echo     If a popup says "Allow USB debugging?", check "Always allow" and click OK!
echo.
pause

set TARGET=%TV_IP%:5555
platform-tools\adb.exe -s %TARGET% get-state 2>nul | findstr "device" >nul
if %errorlevel% equ 0 (
    echo [+] Connected successfully on port 5555!
    goto :INSTALL
)

echo.
echo [*] Direct connection closed (Chromecast wireless mode detected).
set /p TV_PORT="Enter 5-digit Port shown under Wireless Debugging (e.g. 38407): "
set TARGET=%TV_IP%:%TV_PORT%

echo [*] Connecting to %TARGET%...
platform-tools\adb.exe connect %TARGET%

platform-tools\adb.exe devices | findstr /R "%TV_PORT%.*device" >nul
if %errorlevel% neq 0 (
    echo.
    echo [!] First-time connection requires pairing!
    echo On your TV: Click "Pair device with pairing code"
    echo.
    set /p PAIR_PORT="Enter 5-digit Pairing Port from TV popup: "
    set /p PAIR_CODE="Enter 6-digit Pairing Code from TV: "
    echo.
    echo [*] Pairing device...
    platform-tools\adb.exe pair %TV_IP%:%PAIR_PORT% %PAIR_CODE%
    echo [*] Re-connecting...
    platform-tools\adb.exe connect %TARGET%
)

:INSTALL
echo.
echo [*] Installing TV Clean Home (Universal APK)...
set APK_FILE=TVCleaner.apk
if not exist "%APK_FILE%" set APK_FILE=TVCleaner-debug.apk
if not exist "%APK_FILE%" (
    echo [-] Could not find TVCleaner.apk or TVCleaner-debug.apk in this folder!
    pause
    exit /b 1
)

platform-tools\adb.exe -s %TARGET% install -r %APK_FILE%

echo [*] Activating TV Clean Home as Primary Launcher...
platform-tools\adb.exe -s %TARGET% shell appops set com.custom.tvcleaner ACCESS_RESTRICTED_SETTINGS allow
platform-tools\adb.exe -s %TARGET% shell settings put secure enabled_accessibility_services com.custom.tvcleaner/com.custom.tvcleaner.TvHomeAccessibilityService
platform-tools\adb.exe -s %TARGET% shell settings put secure accessibility_enabled 1

echo [*] Launching TV Clean Home on screen...
platform-tools\adb.exe -s %TARGET% shell am start -n com.custom.tvcleaner/.LauncherActivity
platform-tools\adb.exe -s %TARGET% shell input keyevent KEYCODE_HOME

echo.
echo ======================================================================
echo [SUCCESS] Your Google TV is now running TV Clean Home!
echo Press the Home button on your TV remote to enjoy the clean launcher.
echo ======================================================================
echo.
pause
