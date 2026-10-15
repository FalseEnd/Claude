@echo off
rem Forgot an app password? This makes a temporary password for an account.
setlocal
cd /d "%~dp0"
if exist "%~dp0start-windows.bat" goto extracted
echo.
echo   Please extract the ZIP file first ("Extract All"), then run this file
echo   from the extracted folder.
echo.
pause
exit /b 1

:extracted
call "%~dp0start-windows.bat" --reset-password "%~1"
if not errorlevel 1 pause
