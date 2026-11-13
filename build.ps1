<#
.SYNOPSIS
    Quick standalone 1-second APK builder for TV Cleaner (Zero-Gradle required).
#>
$ErrorActionPreference = "Stop"

$SdkPath = "C:\Users\velaz\workspace\android-sdk"
if (-not (Test-Path $SdkPath)) {
    if ($env:ANDROID_HOME -and (Test-Path $env:ANDROID_HOME)) {
        $SdkPath = $env:ANDROID_HOME
    } elseif ($env:LOCALAPPDATA -and (Test-Path "$env:LOCALAPPDATA\Android\Sdk")) {
        $SdkPath = "$env:LOCALAPPDATA\Android\Sdk"
    } else {
        throw "Android SDK not found. Please set ANDROID_HOME or install SDK."
    }
}

$BuildToolsDir = Get-ChildItem "$SdkPath\build-tools" | Sort-Object Name -Descending | Select-Object -First 1 -ExpandProperty FullName
$PlatformJar = Get-ChildItem "$SdkPath\platforms" -Filter "android.jar" -Recurse | Sort-Object FullName -Descending | Select-Object -First 1 -ExpandProperty FullName

Write-Host "[1/5] Using Build Tools: $BuildToolsDir" -ForegroundColor Cyan
Write-Host "[1/5] Using Platform Jar: $PlatformJar" -ForegroundColor Cyan

# Prepare output folders
$BuildDir = "$PSScriptRoot\build"
$OutDir = "$PSScriptRoot\app\build\outputs\apk\debug"
New-Item -ItemType Directory -Force -Path "$BuildDir\obj", "$BuildDir\dex", $OutDir | Out-Null

# Step 1: Compile Java
Write-Host "[2/5] Compiling Java classes..." -ForegroundColor Cyan
$javaFiles = Get-ChildItem -Path "$PSScriptRoot\app\src\main\java" -Recurse -Filter "*.java" | ForEach-Object { $_.FullName }
& javac -encoding UTF-8 -source 17 -target 17 -cp "$PlatformJar" -d "$BuildDir\obj" $javaFiles
if ($LASTEXITCODE -ne 0) { throw "javac failed" }

# Step 2: Dex with D8
Write-Host "[3/5] Converting bytecode to Dalvik Executable (classes.dex)..." -ForegroundColor Cyan
$classFiles = Get-ChildItem -Path "$BuildDir\obj" -Recurse -Filter "*.class" | ForEach-Object { $_.FullName }
& "$BuildToolsDir\d8.bat" --min-api 26 --output "$BuildDir\dex" $classFiles --lib "$PlatformJar"

# Step 3: Link Resources & Manifest
Write-Host "[4/5] Packaging resources with AAPT2..." -ForegroundColor Cyan
& "$BuildToolsDir\aapt2.exe" compile --dir "$PSScriptRoot\app\src\main\res" -o "$BuildDir\res.zip"
& "$BuildToolsDir\aapt2.exe" link -I "$PlatformJar" -R "$BuildDir\res.zip" --auto-add-overlay --min-sdk-version 26 --target-sdk-version 34 --manifest "$PSScriptRoot\app\src\main\AndroidManifest.xml" -o "$BuildDir\base.apk"
if ($LASTEXITCODE -ne 0) { throw "aapt2 link failed" }
& jar uf "$BuildDir\base.apk" -C "$BuildDir\dex" classes.dex

# Step 4: Zipalign & Sign
Write-Host "[5/5] Aligning and signing APK..." -ForegroundColor Cyan
Remove-Item "$BuildDir\aligned.apk", "$OutDir\app-debug.apk", "$PSScriptRoot\TVCleaner-debug.apk" -ErrorAction SilentlyContinue
& "$BuildToolsDir\zipalign.exe" -p -f 4 "$BuildDir\base.apk" "$BuildDir\aligned.apk"

if (-not (Test-Path "$BuildDir\debug.keystore")) {
    & keytool -genkeypair -v -keystore "$BuildDir\debug.keystore" -storepass android -alias androiddebugkey -keypass android -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Android Debug,O=Android,C=US" | Out-Null
}

& "$BuildToolsDir\apksigner.bat" sign --ks "$BuildDir\debug.keystore" --ks-pass pass:android --key-pass pass:android --ks-key-alias androiddebugkey --out "$OutDir\app-debug.apk" "$BuildDir\aligned.apk"

Copy-Item "$OutDir\app-debug.apk" "$PSScriptRoot\TVCleaner-debug.apk" -Force

Write-Host ""
Write-Host "SUCCESS! Built signed APK:" -ForegroundColor Green
Get-Item "$PSScriptRoot\TVCleaner-debug.apk" | Select-Object Name, Length, LastWriteTime | Format-Table -AutoSize
