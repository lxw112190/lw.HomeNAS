@echo off
if exist "%~dp0out\Release\NasServer.exe" (
  "%~dp0out\Release\NasServer.exe"
) else if exist "%~dp0build\app\Release\NasServer.exe" (
  "%~dp0build\app\Release\NasServer.exe"
) else (
  echo Build lw.HomeNAS.sln as Release x64 first, or run Build.cmd.
)
pause
