@echo off
REM Stops both halves of DSK Remote:
REM   the phone front door (proxy) on <PORT>       - 3284
REM   goose itself                 on <PORT>+1     - 3285 (loopback only)
setlocal EnableExtensions EnableDelayedExpansion
title DSK Remote - stop

set "PORT="
for /f "usebackq eol=# tokens=1,* delims==" %%A in ("%~dp0settings.txt") do if /i "%%A"=="PORT" set "PORT=%%B"
if "%PORT%"=="" set "PORT=3284"
set /a GOOSEPORT=PORT+1

echo Stopping DSK Remote ...
set "KILLED="
for %%X in (%PORT% %GOOSEPORT%) do (
    for /f "tokens=5" %%P in ('netstat -ano ^| findstr /R /C:":%%X .*LISTENING"') do (
        echo   - port %%X  PID %%P
        taskkill /F /PID %%P >nul 2>&1
        set "KILLED=1"
    )
)

REM The two window titles are only useful before they exit; harmless if missing.
taskkill /F /FI "WINDOWTITLE eq DSK goose" >nul 2>&1
taskkill /F /FI "WINDOWTITLE eq DSK proxy" >nul 2>&1

if not defined KILLED echo   nothing was listening on ports %PORT% / %GOOSEPORT%.

ping -n 3 127.0.0.1 >nul 2>&1
set "LEFT="
for %%X in (%PORT% %GOOSEPORT%) do (
    for /f "tokens=5" %%P in ('netstat -ano ^| findstr /R /C:":%%X .*LISTENING"') do (
        echo   [!] port %%X still held by PID %%P - run this file as administrator.
        set "LEFT=1"
    )
)

echo.
echo The phone app can no longer connect until you start DSK-Remote.bat again.
if not defined LEFT echo All stopped.
ping -n 5 127.0.0.1 >nul 2>&1
exit /b 0
