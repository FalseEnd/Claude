@echo off
rem Start the XiaoLiangKou server automatically when you sign in to Windows.
rem Run it again to turn automatic start off.
setlocal
cd /d "%~dp0"
call "%~dp0start-windows.bat" --install-autostart
if not errorlevel 1 pause
