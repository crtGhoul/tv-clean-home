<#
.SYNOPSIS
    Chromecast with Google TV Wireless ADB Debloater, RAM Inspector & Manager.
.DESCRIPTION
    Automates wireless ADB connection, safe bloatware removal, custom debloating,
    package restoration, and memory diagnostics for Chromecast with Google TV
    (Android 11 / 12 / 14, 4K and HD models).
#>

[CmdletBinding()]
param(
    [Parameter(Mandatory = $false)]
    [string]$DeviceIp = "",

    [Parameter(Mandatory = $false)]
    [int]$Port = 5555
)

$ErrorActionPreference = "Continue"

# -----------------------------------------------------------------------------
# Color Formatting Helpers
# -----------------------------------------------------------------------------
function Write-Header {
    param([string]$Text)
    Write-Host ""
    Write-Host ("=" * 70) -ForegroundColor Cyan
    Write-Host "  $Text" -ForegroundColor White
    Write-Host ("=" * 70) -ForegroundColor Cyan
    Write-Host ""
}

function Write-Success {
    param([string]$Text)
    Write-Host "[+] $Text" -ForegroundColor Green
}

function Write-Info {
    param([string]$Text)
    Write-Host "[*] $Text" -ForegroundColor Cyan
}

function Write-Warn {
    param([string]$Text)
    Write-Host "[!] $Text" -ForegroundColor Yellow
}

function Write-Danger {
    param([string]$Text)
    Write-Host "[-] $Text" -ForegroundColor Red
}

# -----------------------------------------------------------------------------
# Curated Package Definitions for Chromecast with Google TV
# -----------------------------------------------------------------------------
$SafeBloatPackages = @(
    @{ Name = "com.google.android.videos"; Desc = "Google TV / Google Play Movies & TV" },
    @{ Name = "com.google.android.youtube.tvkids"; Desc = "YouTube Kids TV" },
    @{ Name = "com.google.android.youtube.tvmusic"; Desc = "YouTube Music TV" },
    @{ Name = "com.google.android.youtube.tvunplugged"; Desc = "YouTube TV (Live TV service)" },
    @{ Name = "com.google.android.feedback"; Desc = "Google TV Feedback Provider" },
    @{ Name = "com.google.android.tv.bugreportsender"; Desc = "Google TV Bug Report Sender" },
    @{ Name = "com.google.android.tungsten.setupwraith"; Desc = "Setup Wraith (Setup wizard post-onboarding)" },
    @{ Name = "com.android.printspooler"; Desc = "Android Print Spooler (Unneeded on TV)" },
    @{ Name = "com.android.providers.telephony"; Desc = "Telephony & SMS Provider (Unneeded on TV)" },
    @{ Name = "com.android.dreams.basic"; Desc = "Basic Screensavers (Backdrop handles ambient mode)" }
)

$AggressiveBloatPackages = @(
    @{ Name = "com.google.android.katniss"; Desc = "Google Assistant / Voice Search (Disables voice remote button)" }
)

$ProtectedPackages = @(
    "com.google.android.apps.mediashell", # Chromecast built-in - NEVER REMOVE
    "com.google.android.apps.tv.launcherx", # Google TV core display provider - DO NOT FREEZE (Use Accessibility service instead)
    "com.google.android.tv.remote.service",
    "com.google.android.gms",
    "com.android.vending",
    "com.android.systemui",
    "com.android.settings"
)

# -----------------------------------------------------------------------------
# ADB Discovery & Verification
# -----------------------------------------------------------------------------
function Find-Adb {
    # Check project-local platform-tools first
    $localAdb = "$PSScriptRoot\platform-tools\adb.exe"
    if (Test-Path $localAdb) {
        return $localAdb
    }

    $fixedAdb = "C:\Users\velaz\Documents\antigravity\happy-hypatia\platform-tools\adb.exe"
    if (Test-Path $fixedAdb) {
        return $fixedAdb
    }

    # Check if adb is on PATH
    $adbCmd = Get-Command adb -ErrorAction SilentlyContinue
    if ($adbCmd) {
        return $adbCmd.Source
    }

    # Common Android SDK paths
    $candidatePaths = @(
        "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe",
        "$env:ProgramFiles\Android\platform-tools\adb.exe",
        "$env:ProgramFiles(x86)\Android\platform-tools\adb.exe",
        "C:\platform-tools\adb.exe"
    )

    foreach ($p in $candidatePaths) {
        if (Test-Path $p) {
            return $p
        }
    }

    return $null
}

$AdbExe = Find-Adb

if (-not $AdbExe) {
    Write-Danger "ADB (Android Debug Bridge) executable could not be found."
    Write-Host ""
    Write-Host "Please ensure platform-tools is installed or added to your PATH." -ForegroundColor Yellow
    Write-Host "You can download platform-tools from: https://developer.android.com/tools/releases/platform-tools" -ForegroundColor Cyan
    Write-Host ""
    $manualPath = Read-Host "Or enter the full path to adb.exe manually"
    if (Test-Path $manualPath) {
        $AdbExe = $manualPath
    } else {
        Write-Danger "Invalid path. Exiting."
        exit 1
    }
}

Write-Success "Using ADB: $AdbExe"

# -----------------------------------------------------------------------------
# Wireless Connection Management
# -----------------------------------------------------------------------------
function Ensure-DeviceConnection {
    Write-Header "Chromecast Wireless ADB Connection"

    $connectedDevices = & $AdbExe devices | Select-String -Pattern "\bdevice\b"
    if ($connectedDevices) {
        Write-Success "Active ADB device detected:"
        foreach ($d in $connectedDevices) {
            Write-Host "  -> $d" -ForegroundColor Green
        }
        $reuse = Read-Host "Use currently connected device? (Y/n)"
        if ($reuse -eq "" -or $reuse -match "^[yY]") {
            return $true
        }
    }

    Write-Info "To enable Wireless Debugging on your Chromecast with Google TV:"
    Write-Host "  1. On TV: Settings -> System -> About -> Click 'Android TV OS build' 7 times." -ForegroundColor Gray
    Write-Host "  2. On TV: Settings -> System -> Developer Options -> Enable 'USB Debugging'." -ForegroundColor Gray
    Write-Host "  3. On TV: Settings -> Network & Internet -> Select your Wi-Fi -> Note IP address." -ForegroundColor Gray
    Write-Host ""

    $ip = $script:DeviceIp
    while ([string]::IsNullOrWhiteSpace($ip)) {
        $ip = Read-Host "Enter Chromecast IP address (e.g. 192.168.1.150)"
    }
    $script:DeviceIp = $ip.Trim()

    Write-Info "Connecting to $($script:DeviceIp):$($script:Port)..."
    & $AdbExe connect "$($script:DeviceIp):$($script:Port)"

    Start-Sleep -Seconds 1
    $check = & $AdbExe devices | Select-String -Pattern "$($script:DeviceIp)"
    if ($check -match "device$") {
        Write-Success "Successfully connected to Chromecast!"
        Show-DeviceInfo
        return $true
    } elseif ($check -match "unauthorized") {
        Write-Warn "Device is UNAUTHORIZED! Check your TV screen and select 'Always allow from this computer'."
        Read-Host "Press ENTER after accepting the prompt on your TV"
        & $AdbExe connect "$($script:DeviceIp):$($script:Port)"
        return $true
    } else {
        Write-Danger "Failed to connect to $($script:DeviceIp):$($script:Port)."
        Write-Warn "Make sure the Chromecast and your PC are on the same Wi-Fi network and USB Debugging is ON."
        return $false
    }
}

function Show-DeviceInfo {
    $model = (& $AdbExe shell getprop ro.product.model).Trim()
    $version = (& $AdbExe shell getprop ro.build.version.release).Trim()
    $sdk = (& $AdbExe shell getprop ro.build.version.sdk).Trim()
    $build = (& $AdbExe shell getprop ro.build.display.id).Trim()

    Write-Host ""
    Write-Host "  Device Model : $model" -ForegroundColor White
    Write-Host "  Android Ver  : Android $version (SDK API $sdk)" -ForegroundColor White
    Write-Host "  Build ID     : $build" -ForegroundColor White
    Write-Host ""
}

# -----------------------------------------------------------------------------
# Debloat & Restore Operations
# -----------------------------------------------------------------------------
function Safe-DisablePackage {
    param([string]$PkgName, [string]$Description)

    if ($ProtectedPackages -contains $PkgName) {
        Write-Danger "REFUSING TO DISABLE: $PkgName is a critical core package (Cast/System UI)!"
        return
    }

    Write-Host "  [Disabling] $PkgName ($Description)... " -NoNewline
    # Use pm disable-user --user 0 to safely freeze package without breaking factory restore
    $res = (& $AdbExe shell pm disable-user --user 0 $PkgName 2>&1)
    if ($res -match "new state: disabled" -or $res -match "disabled-user") {
        Write-Host "DISABLED" -ForegroundColor Green
    } else {
        # Fallback to pm uninstall -k --user 0
        $res2 = (& $AdbExe shell pm uninstall -k --user 0 $PkgName 2>&1)
        if ($res2 -match "Success") {
            Write-Host "UNINSTALLED (User 0)" -ForegroundColor Green
        } else {
            Write-Host "SKIPPED / NOT FOUND ($res2)" -ForegroundColor Gray
        }
    }
}

function Restore-Package {
    param([string]$PkgName)
    Write-Host "  [Restoring] $PkgName... " -NoNewline
    # Attempt to reinstall existing package for user 0
    $res1 = (& $AdbExe shell cmd package install-existing $PkgName 2>&1)
    $res2 = (& $AdbExe shell pm enable $PkgName 2>&1)

    if ($res1 -match "Installed" -or $res2 -match "enabled") {
        Write-Host "RESTORED / ENABLED" -ForegroundColor Green
    } else {
        Write-Host "RESULT: $res1 $res2" -ForegroundColor Gray
    }
}

function Run-SafeDebloat {
    Write-Header "Running Pre-Vetted Safe Debloat"
    Write-Info "This safely disables telemetry, background bloat, and unused TV apps."
    Write-Success "Chromecast Built-In (MediaShell) and TV Remote services will be PRESERVED."
    Write-Host ""

    $confirm = Read-Host "Proceed with safe debloat? (Y/n)"
    if ($confirm -ne "" -and $confirm -notmatch "^[yY]") {
        Write-Warn "Cancelled."
        return
    }

    foreach ($pkg in $SafeBloatPackages) {
        Safe-DisablePackage -PkgName $pkg.Name -Description $pkg.Desc
    }

    Write-Success "Safe Debloat process completed!"
}

function Run-AggressiveDebloat {
    Write-Header "Aggressive Debloat (Launcher Ads & Voice Assistant)"
    Write-Warn "CAUTION: Disabling LauncherX requires an alternative TV launcher (like Projectivy or FLauncher) already installed."
    Write-Warn "Disabling Katniss disables Google Assistant / Voice Search on the remote."
    Write-Host ""

    foreach ($pkg in $AggressiveBloatPackages) {
        Write-Host "Package: $($pkg.Name)" -ForegroundColor Yellow
        Write-Host "  Desc : $($pkg.Desc)" -ForegroundColor Gray
        $ans = Read-Host "  Disable this package? (y/N)"
        if ($ans -match "^[yY]") {
            Safe-DisablePackage -PkgName $pkg.Name -Description $pkg.Desc
        } else {
            Write-Host "  Skipped." -ForegroundColor Gray
        }
    }
}

function Run-CustomDebloat {
    Write-Header "Custom Package Debloat"
    $pkg = Read-Host "Enter exact package name to disable (e.g. com.netflix.ninja)"
    if ([string]::IsNullOrWhiteSpace($pkg)) {
        Write-Warn "No package entered."
        return
    }
    $pkg = $pkg.Trim()
    Safe-DisablePackage -PkgName $pkg -Description "Custom User Selection"
}

function Run-RestoreAll {
    Write-Header "Restoring All Debloated Packages"
    Write-Info "Re-enabling all safe & aggressive bloatware packages for User 0..."

    $all = $SafeBloatPackages + $AggressiveBloatPackages
    foreach ($pkg in $all) {
        Restore-Package -PkgName $pkg.Name
    }

    Write-Success "All default packages re-enabled."
}

function Show-MemoryDiagnostics {
    Write-Header "Chromecast Live Memory & Process Diagnostics"
    Write-Info "Querying dumpsys meminfo..."
    Write-Host ""
    $meminfo = & $AdbExe shell dumpsys meminfo
    $summary = $meminfo | Select-String -Pattern "Total RAM:|Free RAM:|Used RAM:|Lost RAM:|ZRAM:"
    if ($summary) {
        Write-Host "RAM Summary:" -ForegroundColor White
        foreach ($line in $summary) {
            Write-Host "  $line" -ForegroundColor Green
        }
    }

    Write-Host ""
    Write-Host "Top 10 Memory Consuming Processes:" -ForegroundColor White
    $meminfo | Select-String -Pattern "^\s*\d+ kB:\s+" | Select-Object -First 10 | ForEach-Object {
        Write-Host "  $($_)" -ForegroundColor Cyan
    }
}

function Install-TvCleanerApk {
    Write-Header "Install TV Cleaner APK to Chromecast"
    
    $candidateApk = @(
        "$PSScriptRoot\TVCleaner-debug.apk",
        "$PSScriptRoot\app\build\outputs\apk\debug\app-debug.apk",
        "$PSScriptRoot\app\build\outputs\apk\release\app-release.apk",
        "$PSScriptRoot\app-debug.apk",
        "$PSScriptRoot\app-release.apk"
    )

    $foundApk = $null
    foreach ($path in $candidateApk) {
        if (Test-Path $path) {
            $foundApk = $path
            break
        }
    }

    if (-not $foundApk) {
        Write-Warn "No built APK found in standard build directories."
        $foundApk = Read-Host "Enter full path to the TV Cleaner .apk file"
    }

    if (Test-Path $foundApk) {
        Write-Info "Installing APK: $foundApk ..."
        $res = & $AdbExe install -r "$foundApk" 2>&1
        if ($res -match "Success") {
            Write-Success "TV Cleaner installed successfully on your Chromecast!"
            Write-Info "Launching TV Cleaner on TV screen..."
            & $AdbExe shell monkey -p com.custom.tvcleaner -c android.intent.category.LEANBACK_LAUNCHER 1
        } else {
            Write-Danger "Installation failed: $res"
        }
    } else {
        Write-Danger "APK file not found: $foundApk"
    }
}

function Pair-Android11WirelessDebugging {
    Write-Header "Android 11+ Wireless Debugging Pairing"
    Write-Info "Chromecast with Google TV (Android 11 / 12) supports dynamic Wireless Debugging pairing:"
    Write-Host "  1. On TV: Settings -> System -> Developer Options -> Wireless Debugging." -ForegroundColor Gray
    Write-Host "  2. Select 'Pair device with pairing code'." -ForegroundColor Gray
    Write-Host "  3. Note the IP address, pairing PORT, and 6-digit Wi-Fi pairing code." -ForegroundColor Gray
    Write-Host ""

    $pairIp = Read-Host "Enter TV IP (e.g. 192.168.1.150)"
    $pairPort = Read-Host "Enter Pairing Port (displayed on TV pairing dialog)"
    $pairCode = Read-Host "Enter 6-digit Pairing Code"

    if (-not [string]::IsNullOrWhiteSpace($pairIp) -and -not [string]::IsNullOrWhiteSpace($pairPort)) {
        Write-Info "Pairing with ${pairIp}:${pairPort} using code ${pairCode}..."
        & $AdbExe pair "${pairIp}:${pairPort}" "$pairCode"
        Start-Sleep -Seconds 2
        $connectPort = Read-Host "Enter Connection Port (displayed under Wireless Debugging main screen, NOT pairing port)"
        if (-not [string]::IsNullOrWhiteSpace($connectPort)) {
            & $AdbExe connect "${pairIp}:${connectPort}"
        }
    }
}

function Set-CustomLauncherDefault {
    Write-Header "Set Minimalist Launcher as Default Home"
    Write-Info "Activating TV Clean Home hardware Home button takeover..."
    Write-Host ""

    # Ensure TV Cleaner is installed first
    $checkInstalled = & $AdbExe shell pm list packages com.custom.tvcleaner
    if (-not ($checkInstalled -match "com.custom.tvcleaner")) {
        Write-Warn "TV Clean Launcher is not installed yet! Installing it now..."
        Install-TvCleanerApk
    }

    Write-Info "Granting accessibility permissions..."
    & $AdbExe shell appops set com.custom.tvcleaner ACCESS_RESTRICTED_SETTINGS allow
    & $AdbExe shell settings put secure enabled_accessibility_services com.custom.tvcleaner/com.custom.tvcleaner.TvHomeAccessibilityService
    & $AdbExe shell settings put secure accessibility_enabled 1
    
    Write-Success "TV Clean Home successfully activated as default Home!"
    Write-Info "Starting launcher and simulating HOME press..."
    & $AdbExe shell am start -n com.custom.tvcleaner/.LauncherActivity
    & $AdbExe shell input keyevent KEYCODE_HOME

    Write-Success "Done! Pressing Home on your TV remote will now instantly open TV Clean Home with zero black screens."
}

function Restore-StockLauncher {
    Write-Header "Restore Stock Google TV Home"
    Write-Info "Disabling TV Clean Home Accessibility Service..."
    & $AdbExe shell settings put secure enabled_accessibility_services '""'
    & $AdbExe shell input keyevent KEYCODE_HOME
    Write-Success "Stock Google TV Home restored."
}

# -----------------------------------------------------------------------------
# Interactive CLI Menu
# -----------------------------------------------------------------------------
function Show-Menu {
    Write-Host ""
    Write-Host "==================================================================" -ForegroundColor Cyan
    Write-Host "    CHROMECAST WITH GOOGLE TV ADB DEBLOATER & UTILITY" -ForegroundColor White
    Write-Host "==================================================================" -ForegroundColor Cyan
    Write-Host "  [1] Quick Safe Debloat (Pre-vetted bloatware, preserves Cast/Remote)" -ForegroundColor Green
    Write-Host "  [2] Aggressive Debloat (LauncherX ads/recommendations, Assistant)" -ForegroundColor Yellow
    Write-Host "  [3] Custom Package Debloat (Specify package name)" -ForegroundColor White
    Write-Host "  [4] Restore / Re-enable All Debloated Packages" -ForegroundColor Cyan
    Write-Host "  [5] Live Memory (RAM) & Process Inspector" -ForegroundColor White
    Write-Host "  [6] Install / Update TV Clean Launcher & Cleaner APK" -ForegroundColor Magenta
    Write-Host "  [7] Pair Device via Android 11+ Wireless Debugging Code" -ForegroundColor White
    Write-Host "  [8] Set Minimalist TV Clean Home as Default (Freeze Stock LauncherX)" -ForegroundColor Green
    Write-Host "  [9] Restore Stock Google TV LauncherX" -ForegroundColor Yellow
    Write-Host "  [10] Reboot Chromecast" -ForegroundColor White
    Write-Host "  [0] Disconnect & Exit" -ForegroundColor Red
    Write-Host "==================================================================" -ForegroundColor Cyan
}

# -----------------------------------------------------------------------------
# Main Execution Loop
# -----------------------------------------------------------------------------
if (-not (Ensure-DeviceConnection)) {
    Write-Danger "Could not establish ADB connection. Exiting."
    exit 1
}

$running = $true
while ($running) {
    Show-Menu
    $choice = Read-Host "Select an option [0-10]"
    switch ($choice) {
        "1" { Run-SafeDebloat }
        "2" { Run-AggressiveDebloat }
        "3" { Run-CustomDebloat }
        "4" { Run-RestoreAll }
        "5" { Show-MemoryDiagnostics }
        "6" { Install-TvCleanerApk }
        "7" { Pair-Android11WirelessDebugging }
        "8" { Set-CustomLauncherDefault }
        "9" { Restore-StockLauncher }
        "10" {
            $ans = Read-Host "Reboot Chromecast now? (y/N)"
            if ($ans -match "^[yY]") {
                Write-Info "Rebooting Chromecast..."
                & $AdbExe reboot
                $running = $false
            }
        }
        "0" {
            Write-Info "Disconnecting ADB session..."
            & $AdbExe disconnect
            $running = $false
        }
        default {
            Write-Warn "Invalid choice. Please select a number between 0 and 10."
        }
    }
}

Write-Success "Finished. Enjoy your clean, smooth Chromecast with Google TV!"
