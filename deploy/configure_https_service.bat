@echo off
setlocal
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0service.ps1" -Action Configure -DisableInsecureLan
set "RESULT=%ERRORLEVEL%"
echo.
pause
exit /b %RESULT%
