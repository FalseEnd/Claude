@echo off
rem Back up the XiaoLiangKou database into the "backups" folder.
setlocal
cd /d "%~dp0"
call "%~dp0start-windows.bat" --backup
if not errorlevel 1 pause
