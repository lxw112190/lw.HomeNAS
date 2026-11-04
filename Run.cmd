@echo off
chcp 65001 >nul
if exist "%~dp0out\Release\NasServer.exe" (
  "%~dp0out\Release\NasServer.exe"
) else if exist "%~dp0build\app\Release\NasServer.exe" (
  "%~dp0build\app\Release\NasServer.exe"
) else (
  echo 请先打开 lw.HomeNAS.sln 编译 Release x64，或运行 Build.cmd。
)
pause
