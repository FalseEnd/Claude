@echo off
rem Forgot an app password? This makes a temporary password for an account.
setlocal
cd /d "%~dp0"
call "%~dp0start-windows.bat" --reset-password "%~1"
if not errorlevel 1 pause
