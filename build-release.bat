@echo off
setlocal
echo ========================================
echo    AirMic Release Builder
echo ========================================
echo.

powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0build-release.ps1"

if %ERRORLEVEL% NEQ 0 (
    echo.
    echo [ERROR] Release build failed with error code %ERRORLEVEL%.
    pause
    exit /b %ERRORLEVEL%
)

echo.
echo Build complete. Check the 'release' directory.
echo.
pause
