@echo off
rem XiaoLiangKou server for Windows. Double-click to start.
rem The first run sets up the PostgreSQL database; later runs just start the server.
rem (Keep this file ASCII-only: cmd.exe misreads UTF-8 text in batch files.)
setlocal
cd /d "%~dp0"
title XiaoLiangKou server
if exist "%~dp0server\scripts\launcher.js" goto extracted
echo.
echo   Please extract the ZIP file first:
echo   right-click the ZIP file, choose "Extract All", then open the extracted
echo   folder and double-click start-windows.bat there.
echo.
pause
exit /b 1

:extracted
set "NODE_EXE=node"
where node >nul 2>nul
if not errorlevel 1 goto run
set "NODE_EXE=%ProgramFiles%\nodejs\node.exe"
if exist "%NODE_EXE%" goto run
echo.
echo   Node.js is not installed yet. Opening the install guide...
echo.
start "" "%~dp0server\scripts\need-node.html"
pause
exit /b 1

:run
"%NODE_EXE%" "%~dp0server\scripts\launcher.js" %*
if errorlevel 1 pause
