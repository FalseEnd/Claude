@echo off
rem Restore a backup: drag a .sql file from the "backups" folder onto this file.
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
call "%~dp0start-windows.bat" --restore "%~1"
if not errorlevel 1 pause
