<#
.SYNOPSIS
    Amazon Fire TV Stick Wireless ADB Debloater, RAM Booster & Manager.
.DESCRIPTION
    Automates wireless ADB connection, safe bloatware disabling (pm disable-user --user 0),
    1-click restoration, and memory inspection for Amazon Fire TV Stick
    (Fire OS 6, 7, and 8 devices).
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
# Curated Package Definitions for Amazon Fire TV Stick
# -----------------------------------------------------------------------------
$SafeBloatPackages = @(
    @{ Name = "com.amazon.advertising.firetv"; Desc = "Fire TV Ad Delivery & Sponsored Banners" },
    @{ Name = "com.amazon.device.sale.service"; Desc = "Amazon Sale Service & In-app Promos" },
    @{ Name = "com.amazon.visualbuys"; Desc = 'Visual Buys "Buy on Amazon" Shopping Overlays' },
    @{ Name = "com.amazon.tv.forcedotaupdates.v2"; Desc = "Forced Silent OTA Updater Daemon (Fire OS 6/7)" },
    @{ Name = "com.amazon.tv.forcedotaupdater.v2"; Desc = "Forced Silent OTA Updater Daemon (Fire OS 8)" },
    @{ Name = "com.amazon.shoptv.client"; Desc = "Amazon ShopTV Shopping Recommendations" },
    @{ Name = "com.amazon.shoptv.firetv.client"; Desc = "Amazon ShopTV Fire TV Sponsored Products" },
    @{ Name = "com.amazon.device.telemetry.emitter"; Desc = "Device Telemetry & Analytics Emitter" },
    @{ Name = "com.amazon.minitv.android.app"; Desc = "Amazon miniTV Sponsored Video & Ad Delivery" },
    @{ Name = "com.amazon.gamehub"; Desc = "Amazon GameHub Sponsored Promos" },
    @{ Name = "com.amazon.device.software.ota"; Desc = "Firmware OTA Background Downloader" },
    @{ Name = "com.amazon.device.software.ota.override"; Desc = "OTA System Update Override Controller" },
    @{ Name = "com.amazon.kor.demo"; Desc = "Retail Store Demo Loop Player" },
    @{ Name = "com.amazon.shpm"; Desc = "Amazon Shipping & Package Tracking Alerts" },
    @{ Name = "com.amazon.kato"; Desc = "Voice & Search Telemetry Reporter" },
    @{ Name = "com.amazon.device.metrics"; Desc = "Crash Analytics & Usage Metrics Uploader" },
    @{ Name = "com.amazon.bueller.music"; Desc = "Amazon Music Background Preloader" },
    @{ Name = "com.amazon.firehomedashboardservice"; Desc = "Alexa Smart Home Dashboard Overlay" }
)

$RecommendedExtraPackages = @(
    @{ Name = "com.amazon.cardinal"; Desc = "Amazon News Live Feed Integration" },
    @{ Name = "com.amazon.ftv.screensaver"; Desc = "Screensaver High-Res Image Cache Daemon" }
)

$MaxExtraPackages = @(
    @{ Name = "com.amazon.vizzini"; Desc = "Ambient Photo Screensaver Backdrop" },
    @{ Name = "com.amazon.tv.livetv"; Desc = "Fire OS Live TV Guide Aggregator" },
    @{ Name = "com.amazon.whisperplay.contracts"; Desc = "Miracast Screen Mirroring Receiver" },
    @{ Name = "com.amazon.speech.simplesearch"; Desc = "Alexa Voice Search Card Overlays" }
)

$NeverTouchPackages = @(
    "com.amazon.tv.settings",
    "com.amazon.tv.settings.v2",
    "com.amazon.device.bluetooth",
    "com.amazon.inputmethod.latin",
    "com.android.packageinstaller",
    "com.amazon.tv.auth",
    "com.amazon.tv.support",
    "com.esaba.downloader",
    "com.custom.tvcleaner"
)

# -----------------------------------------------------------------------------
# Locate ADB Binary
# -----------------------------------------------------------------------------
$scriptDir = if ($PSScriptRoot) { $PSScriptRoot } else { Split-Path -Parent $MyInvocation.MyCommand.Path }
$adbCandidates = @(
    (Join-Path $scriptDir "platform-tools\adb.exe"),
    "C:\Users\velaz\Documents\antigravity\happy-hypatia\platform-tools\adb.exe",
    (Join-Path $env:USERPROFILE "Downloads\platform-tools\adb.exe")
)

$adbPath = ""
foreach ($cand in $adbCandidates) {
    if (Test-Path $cand) {
        $adbPath = $cand
        break
    }
}

if (-not $adbPath) {
    $adbCmd = Get-Command "adb" -ErrorAction SilentlyContinue
    if ($adbCmd) {
        $adbPath = $adbCmd.Source
    } else {
        Write-Danger "Could not find adb.exe. Please ensure platform-tools folder is in the same folder."
        Read-Host "Press Enter to exit"
        exit 1
    }
}

# -----------------------------------------------------------------------------
# ADB Helpers
# -----------------------------------------------------------------------------
function Invoke-Adb {
    param([string]$Arguments)
    $fullArgs = "-s $global:TargetDevice $Arguments"
    $psi = New-Object System.Diagnostics.ProcessStartInfo
    $psi.FileName = $adbPath
    $psi.Arguments = $fullArgs
    $psi.RedirectStandardOutput = $true
    $psi.RedirectStandardError = $true
    $psi.UseShellExecute = $false
    $psi.CreateNoWindow = $true

    $p = [System.Diagnostics.Process]::Start($psi)
    $stdout = $p.StandardOutput.ReadToEnd()
    $stderr = $p.StandardError.ReadToEnd()
    $p.WaitForExit()

    return ($stdout + $stderr).Trim()
}

function Connect-FireTv {
    if (-not $DeviceIp) {
        Write-Header "FIRE TV STICK WIRELESS ADB CONNECTION"
        Write-Host "Make sure Fire TV is on the same Wi-Fi and Developer Options > ADB is ON."
        Write-Host ""
        $script:DeviceIp = Read-Host "Enter Fire TV IP Address (e.g. 192.168.1.120)"
    }

    if (-not $script:DeviceIp) {
        Write-Danger "No IP entered. Exiting."
        exit 1
    }

    $target = "$($script:DeviceIp):$Port"
    $global:TargetDevice = $target

    Write-Info "Connecting to $target..."
    & $adbPath connect $target | Out-Null
    Start-Sleep -Seconds 1

    $state = & $adbPath -s $target get-state 2>$null
    if ($state -ne "device") {
        Write-Warn "Device not in ready state ($state)."
        Write-Warn "LOOK AT YOUR FIRE TV SCREEN NOW: Check 'Always allow' and click OK if prompted!"
        Read-Host "Press Enter once you have accepted the TV prompt"
        & $adbPath connect $target | Out-Null
        $state = & $adbPath -s $target get-state 2>$null
    }

    if ($state -eq "device") {
        Write-Success "Connected to Fire TV at $target!"
        
        $brand = Invoke-Adb "shell getprop ro.product.brand"
        $model = Invoke-Adb "shell getprop ro.product.model"
        $fireos = Invoke-Adb "shell getprop ro.build.version.fireos"
        Write-Info "Hardware: $brand $model (Fire OS $fireos)"
    } else {
        Write-Danger "Failed to establish ADB connection to $target."
        exit 1
    }
}

function Get-InstalledPackages {
    $raw = Invoke-Adb "shell pm list packages -u"
    $list = @()
    foreach ($line in ($raw -split "`r?`n")) {
        if ($line -match "^package:(.+)$") {
            $list += $Matches[1].Trim()
        }
    }
    return $list
}

function Get-DisabledPackages {
    $raw = Invoke-Adb "shell pm list packages -d"
    $list = @()
    foreach ($line in ($raw -split "`r?`n")) {
        if ($line -match "^package:(.+)$") {
            $list += $Matches[1].Trim()
        }
    }
    return $list
}

function Disable-PackageSafe {
    param([string]$Pkg, [string]$Desc = "")
    if ($NeverTouchPackages -contains $Pkg) {
        Write-Danger "BLOCKED: $Pkg is critical system software and cannot be disabled."
        return
    }
    $res = Invoke-Adb "shell pm disable-user --user 0 $Pkg"
    if ($res -match "disabled-user|new state: disabled") {
        Write-Success "Disabled: $Pkg ($Desc)"
    } else {
        Write-Warn "Result for ${Pkg}: $res"
    }
}

function Enable-PackageSafe {
    param([string]$Pkg, [string]$Desc = "")
    $res = Invoke-Adb "shell pm enable $Pkg"
    if ($res -match "enabled|new state: default|new state: enabled") {
        Write-Success "Restored: $Pkg ($Desc)"
    } else {
        Write-Warn "Result for ${Pkg}: $res"
    }
}

# -----------------------------------------------------------------------------
# Actions
# -----------------------------------------------------------------------------
function Apply-PresetSafe {
    Write-Header "APPLYING SAFE DEBLOAT PRESET (Ads, Telemetry, Forced OTA)"
    $installed = Get-InstalledPackages
    foreach ($item in $SafeBloatPackages) {
        if ($installed -contains $item.Name) {
            Disable-PackageSafe -Pkg $item.Name -Desc $item.Desc
        }
    }
    Write-Success "Safe debloat complete! Ads and telemetry disabled."
}

function Apply-PresetRecommended {
    Write-Header "APPLYING RECOMMENDED DEBLOAT PRESET"
    Apply-PresetSafe
    $installed = Get-InstalledPackages
    foreach ($item in $RecommendedExtraPackages) {
        if ($installed -contains $item.Name) {
            Disable-PackageSafe -Pkg $item.Name -Desc $item.Desc
        }
    }
    Write-Success "Recommended debloat complete!"
}

function Apply-PresetMax {
    Write-Header "APPLYING MAXIMUM DEBLOAT PRESET"
    Apply-PresetRecommended
    $installed = Get-InstalledPackages
    foreach ($item in $MaxExtraPackages) {
        if ($installed -contains $item.Name) {
            Disable-PackageSafe -Pkg $item.Name -Desc $item.Desc
        }
    }
    Write-Success "Maximum debloat complete!"
}

function Restore-AllCatalog {
    Write-Header "RESTORING ALL PACKAGES (UNDO / REVERT)"
    $allKnown = $SafeBloatPackages + $RecommendedExtraPackages + $MaxExtraPackages
    foreach ($item in $allKnown) {
        Enable-PackageSafe -Pkg $item.Name -Desc $item.Desc
    }
    Write-Success "All debloated packages have been restored to default enabled state!"
}

function Show-RamAndStatus {
    Write-Header "FIRE TV RAM & SYSTEM STATUS"
    $memInfo = Invoke-Adb "shell cat /proc/meminfo"
    $totalKb = 0
    $availKb = 0
    foreach ($line in ($memInfo -split "`r?`n")) {
        if ($line -match "MemTotal:\s+(\d+)\s+kB") { $totalKb = [int]$Matches[1] }
        if ($line -match "MemAvailable:\s+(\d+)\s+kB") { $availKb = [int]$Matches[1] }
    }
    if ($totalKb -gt 0) {
        $totalMb = [math]::Round($totalKb / 1024)
        $availMb = [math]::Round($availKb / 1024)
        $usedMb = $totalMb - $availMb
        $pct = [math]::Round(($usedMb / $totalMb) * 100)
        Write-Host "Total RAM: $totalMb MB" -ForegroundColor White
        Write-Host "Used RAM:  $usedMb MB ($pct%)" -ForegroundColor Yellow
        Write-Host "Free RAM:  $availMb MB" -ForegroundColor Green
    }

    Write-Host ""
    Write-Host "Currently Disabled Packages:" -ForegroundColor White
    $disabled = Get-DisabledPackages
    if ($disabled.Count -eq 0) {
        Write-Host "  (None)" -ForegroundColor DarkGray
    } else {
        foreach ($d in $disabled) {
            Write-Host "  [-] $d" -ForegroundColor Red
        }
    }
}

# -----------------------------------------------------------------------------
# Main Interactive Menu
# -----------------------------------------------------------------------------
Connect-FireTv

while ($true) {
    Write-Header "FIRE TV CLEANER & DEBLOATER CONTROL PANEL"
    Write-Host "Device: $global:TargetDevice" -ForegroundColor Green
    Write-Host ""
    Write-Host "1. [SAFE] Debloat Ads, Telemetry & Forced OTA (Recommended for all users)" -ForegroundColor Green
    Write-Host "2. [RECOMMENDED] Safe + Amazon News & Screensaver Cache" -ForegroundColor Cyan
    Write-Host "3. [MAXIMUM] Aggressive Debloat (Safe + News + LiveTV + Casting)" -ForegroundColor Yellow
    Write-Host "4. [RESTORE ALL] 1-Click Revert / Re-enable all packages" -ForegroundColor Magenta
    Write-Host "5. [STATUS] View RAM & currently disabled packages" -ForegroundColor White
    Write-Host "6. [LAUNCH TV CLEAN HOME] Start launcher on TV screen" -ForegroundColor Cyan
    Write-Host "0. Exit" -ForegroundColor Red
    Write-Host ""
    
    $choice = Read-Host "Select an option (0-6)"
    switch ($choice) {
        "1" { Apply-PresetSafe; Read-Host "Press Enter to continue" }
        "2" { Apply-PresetRecommended; Read-Host "Press Enter to continue" }
        "3" { Apply-PresetMax; Read-Host "Press Enter to continue" }
        "4" { Restore-AllCatalog; Read-Host "Press Enter to continue" }
        "5" { Show-RamAndStatus; Read-Host "Press Enter to continue" }
        "6" { 
            Invoke-Adb "shell am start -n com.custom.tvcleaner/.LauncherActivity"
            Write-Success "TV Clean Home launched on TV screen!"
            Read-Host "Press Enter to continue"
        }
        "0" { exit 0 }
        default { Write-Warn "Invalid option." }
    }
}
