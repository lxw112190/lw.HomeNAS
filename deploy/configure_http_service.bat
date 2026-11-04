@echo off
setlocal
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0service.ps1" -Action Configure -AllowInsecureLan
set "RESULT=%ERRORLEVEL%"
echo.
pause
exit /b %RESULT%
