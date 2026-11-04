@echo off
setlocal
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0service.ps1" -Action Console -AllowInsecureLan
set "RESULT=%ERRORLEVEL%"
echo.
pause
exit /b %RESULT%
