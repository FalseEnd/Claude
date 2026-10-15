@echo off
rem Restore a backup: drag a .sql file from the "backups" folder onto this file.
setlocal
cd /d "%~dp0"
call "%~dp0start-windows.bat" --restore "%~1"
if not errorlevel 1 pause
