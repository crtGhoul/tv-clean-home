@echo off
title Amazon Fire TV Stick Debloater and Optimizer
color 0b
echo ======================================================================
echo    AMAZON FIRE TV STICK - DEBLOATER AND OPTIMIZER
echo ======================================================================
echo.

set SCRIPT_DIR=%~dp0
set PS_SCRIPT=%SCRIPT_DIR%debloat_firetv.ps1

if not exist "%PS_SCRIPT%" (
    set PS_SCRIPT=C:\Users\velaz\Documents\antigravity\happy-hypatia\debloat_firetv.ps1
)

powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%PS_SCRIPT%"

if %errorlevel% neq 0 (
    echo.
    echo [!] Script exited with code %errorlevel%.
)

echo.
pause
