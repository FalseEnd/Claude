@echo off
rem Start the XiaoLiangKou server automatically when you sign in to Windows.
rem Run it again to turn automatic start off.
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
call "%~dp0start-windows.bat" --install-autostart
if not errorlevel 1 pause
