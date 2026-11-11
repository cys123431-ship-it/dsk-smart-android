@echo off
REM ============================================================================
REM  DSK Remote  -  the PC side of the phone app
REM
REM  Two processes always run:
REM    goose   on 127.0.0.1:3285   (goose only trusts loopback connections,
REM                                 so it must never face the network itself)
REM    proxy   on 0.0.0.0:3284     (what the phone actually dials: Wi-Fi or
REM                                 Tailscale; it forwards to goose)
REM
REM  That split is why the phone used to get "403 Forbidden" no matter which
REM  address it was given: goose refuses every client that is not the PC itself.
REM ============================================================================
setlocal EnableExtensions EnableDelayedExpansion

cd /d "%~dp0"

set "SETTINGS=%~dp0settings.txt"
set "SECRET="
set "PORT=3284"
set "WORKDIR=%~dp0workspace"
set "GOOSE="

if not exist "%SETTINGS%" (
    echo [!] settings.txt not found: %SETTINGS%
    pause
    exit /b 1
)

REM ---- read settings (KEY=VALUE, # comments ignored) ----
for /f "usebackq eol=# tokens=1* delims==" %%A in ("%SETTINGS%") do (
    set "K=%%A"
    set "V=%%B"
    if /i "!K!"=="SECRET"  set "SECRET=!V!"
    if /i "!K!"=="PORT"    set "PORT=!V!"
    if /i "!K!"=="WORKDIR" if not "!V!"=="" set "WORKDIR=!V!"
    if /i "!K!"=="GOOSE"   set "GOOSE=!V!"
)

if not defined SECRET (
    echo [!] SECRET is empty in settings.txt
    pause
    exit /b 1
)
if not exist "!GOOSE!" (
    echo [!] goose.exe not found: !GOOSE!
    echo     Check the GOOSE line in settings.txt
    pause
    exit /b 1
)
if not exist "!WORKDIR!" mkdir "!WORKDIR!" >nul 2>&1

set /a GOOSEPORT=PORT+1

REM ---- network addresses for the banner ----
set "LANLIST="
set "HASLAN="
set "IPCFG=%TEMP%\dsk_ipaddrs.tmp"
if exist "%IPCFG%" del "%IPCFG%" >nul 2>&1
if exist "%~dp0list-addrs.ps1" (
    powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0list-addrs.ps1" >nul 2>&1
)
if exist "%IPCFG%" (
    for /f "usebackq delims=" %%T in ("%IPCFG%") do (
        set "T=%%T"
        set "T=!T: =!"
        if defined T (
            set "LANLIST=!LANLIST! !T!"
            set "HASLAN=1"
        )
    )
    del "%IPCFG%" >nul 2>&1
)

set "TSLIST="
set "HASTS="
set "TSEXE="
if exist "%ProgramFiles%\Tailscale\tailscale.exe" set "TSEXE=%ProgramFiles%\Tailscale\tailscale.exe"
set "PF86=%ProgramFiles(x86)%"
if not defined TSEXE if defined PF86 if exist "%PF86%\Tailscale\tailscale.exe" set "TSEXE=%PF86%\Tailscale\tailscale.exe"
if not defined TSEXE for /f "delims=" %%W in ('where tailscale.exe 2^>nul') do if not defined TSEXE set "TSEXE=%%W"
if defined TSEXE if exist "%~dp0get-tailscale-ip.ps1" (
    powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0get-tailscale-ip.ps1" -Tailscale "!TSEXE!" >nul 2>&1
)
set "TSOUT=%TEMP%\dsk_ts.tmp"
if exist "!TSOUT!" (
    for /f "usebackq delims=" %%T in ("!TSOUT!") do (
        set "T=%%T"
        set "T=!T: =!"
        if defined T (
            set "TSLIST=!TSLIST! !T!"
            set "HASTS=1"
        )
    )
    del "!TSOUT!" >nul 2>&1
)

REM ------------------------------------------------------------------
REM  --hidden : what the startup shortcut runs. Starts both halves with
REM             no window at all and exits immediately.
REM ------------------------------------------------------------------
if /i "%~1"=="--hidden" goto hidden

title DSK Remote
echo.
echo  ================================================================
echo   DSK Remote - PC goose server for the phone app
echo  ================================================================
echo.
echo   Secret Key : %SECRET%
echo   Port       : %PORT%
echo   Workdir    : %WORKDIR%
echo.
echo   Phone address (type this in the app):
if defined HASLAN for %%L in (%LANLIST%) do echo       Wi-Fi      : %%L:%PORT%
if defined HASTS for %%L in (%TSLIST%) do echo       Tailscale  : %%L:%PORT%
if not defined HASLAN if not defined HASTS echo       (no network address found - check Wi-Fi)
echo.
echo   Emulator   : 127.0.0.1:%PORT%
echo.

set "BUSY="
for /f "tokens=5" %%P in ('netstat -ano ^| findstr /R /C:":%PORT% .*LISTENING"') do set "BUSY=%%P"
set "GBUSY="
for /f "tokens=5" %%P in ('netstat -ano ^| findstr /R /C:":%GOOSEPORT% .*LISTENING"') do set "GBUSY=%%P"
if not "!BUSY!"=="" if not "!GBUSY!"=="" (
    echo   [!] DSK Remote is already running ^(front door PID !BUSY!, goose PID !GBUSY!^).
    echo       The phone can connect now. To restart: Stop-DSK-Remote.bat
    echo.
    pause
    exit /b 1
)
REM The front door can outlive goose. It used to be reported as "already running" while
REM every phone connection failed; start the missing half instead.
if not "!BUSY!"=="" if "!GBUSY!"=="" (
    echo   [!] The front door ^(port %PORT%^) is up but goose ^(port %GOOSEPORT%^) is not running.
    echo       Starting goose again ...
    start /d "%~dp0" "DSK Remote server" /min cmd /c ""%~f0"" --hidden
    ping -n 8 127.0.0.1 >nul 2>&1
    echo       Done. If the phone still cannot connect: Stop-DSK-Remote.bat, then start again.
    echo.
    call :showqr
    pause
    exit /b 0
)

echo   A QR window is opening next to this one. In the phone app:
echo     Settings - PC goose remote - "PC 화면 QR 찍기" - point at that window.
echo   Scanning once is all it takes: no address to type, no key to copy.
echo.
call :showqr

echo   1. Start in background  ^(keeps running after this window and the goose app close^)
echo   2. Start in this window ^(closing this window stops it^)
echo   3. Stop the server
echo.
set "MODE="
set /p "MODE=Choose 1/2/3 [1]: "
if "%MODE%"=="" set "MODE=1"
if "%MODE%"=="2" goto fg
if "%MODE%"=="3" goto stop

REM ---------- 1: background ----------
start /d "%~dp0" "DSK Remote server" /min cmd /c ""%~f0"" --hidden
echo.
echo   Started in the background.
echo     - Closing this window: it keeps running.
echo     - Closing the goose desktop app: it keeps running.
echo     - Stopping it: Stop-DSK-Remote.bat
echo.
ping -n 5 127.0.0.1 >nul 2>&1

set "OK="
for /f "tokens=5" %%P in ('netstat -ano ^| findstr /R /C:":%PORT% .*LISTENING"') do set "OK=%%P"
if defined OK (
    echo   Ready. Phone can connect.
    echo   Scan the QR window with the phone app, or type the address above.
) else (
    echo   [!] The server did not come up. See server.log and proxy.log next to this file.
)
echo.
pause
exit /b 0

REM ---------- hidden launcher: goose (loopback) + proxy (network) ----------
:hidden
set "GOOSE_SERVER__SECRET_KEY=%SECRET%"
set "GOOSE_WORKING_DIR=%WORKDIR%"
set "GOOSES="
for %%I in ("%GOOSE%") do set "GOOSES=%%~sI"
if not defined GOOSES set "GOOSES=%GOOSE%"

REM ---- every address the phone may dial must be an allowed Origin ----
REM goose answers 403 to an Origin it does not know, even with the right key.
set "ORIGINS=--allowed-origin http://127.0.0.1:%PORT% --allowed-origin http://localhost:%PORT%"
for %%L in (%LANLIST%) do set "ORIGINS=!ORIGINS! --allowed-origin http://%%L:%PORT%"
for %%L in (%TSLIST%) do set "ORIGINS=!ORIGINS! --allowed-origin http://%%L:%PORT%"

set "GOOSEARGS=serve --host 127.0.0.1 --port %GOOSEPORT% --platform desktop %ORIGINS%"

REM Start only the half that is missing. After a crash one of the two can still be alive,
REM and a second copy of it would only fail on the busy port.
set "GUP="
for /f "tokens=5" %%P in ('netstat -ano ^| findstr /R /C:":%GOOSEPORT% .*LISTENING"') do set "GUP=%%P"
set "PUP="
for /f "tokens=5" %%P in ('netstat -ano ^| findstr /R /C:":%PORT% .*LISTENING"') do set "PUP=%%P"
if not defined GUP if not defined PUP (
    if exist "%~dp0server.log" del "%~dp0server.log" >nul 2>&1
    if exist "%~dp0proxy.log" del "%~dp0proxy.log" >nul 2>&1
)

REM goose: loopback only. never 0.0.0.0, or every phone gets 403.
if not defined GUP start "DSK goose" /min cmd /c ""%GOOSES%" %GOOSEARGS% >> "%~dp0server.log" 2>&1"

REM wait for goose to bind before the proxy tries to reach it
set "UP="
for /l %%N in (1,1,40) do (
    if not defined UP (
        for /f "tokens=5" %%P in ('netstat -ano ^| findstr /R /C:":%GOOSEPORT% .*LISTENING"') do set "UP=%%P"
        if not defined UP ping -n 2 127.0.0.1 >nul 2>&1
    )
)

if not defined UP (
    echo [%DATE% %TIME%] goose did not start on port %GOOSEPORT% >> "%~dp0proxy.log"
    exit /b 1
)

echo [%DATE% %TIME%] goose up on 127.0.0.1:%GOOSEPORT% >> "%~dp0proxy.log"

REM proxy: the front door the phone talks to. -GooseExe / -GooseArgs let it start goose
REM again by itself when goose is gone (the phone user may be far from this PC).
REM It writes proxy.log, not server.log: the ">>" above keeps server.log locked for goose,
REM and every line the door tried to add there was silently lost.
if not defined PUP start "DSK proxy" /min powershell -NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File "%~dp0proxy.ps1" -Port %PORT% -LogPath "%~dp0proxy.log" -GooseExe "%GOOSES%" -GooseArgs "%GOOSEARGS%"
exit /b 0

REM ---------- 2: this window ----------
:fg
set "GOOSE_SERVER__SECRET_KEY=%SECRET%"
set "GOOSE_WORKING_DIR=%WORKDIR%"
set "GOOSES="
for %%I in ("%GOOSE%") do set "GOOSES=%%~sI"
if not defined GOOSES set "GOOSES=%GOOSE%"

REM ---- every address the phone may dial must be an allowed Origin ----
REM goose answers 403 to an Origin it does not know, even with the right key.
set "ORIGINS=--allowed-origin http://127.0.0.1:%PORT% --allowed-origin http://localhost:%PORT%"
for %%L in (%LANLIST%) do set "ORIGINS=!ORIGINS! --allowed-origin http://%%L:%PORT%"
for %%L in (%TSLIST%) do set "ORIGINS=!ORIGINS! --allowed-origin http://%%L:%PORT%"

echo.

set "GOOSEARGS=serve --host 127.0.0.1 --port %GOOSEPORT% --platform desktop %ORIGINS%"
echo   Starting goose on 127.0.0.1:%GOOSEPORT% ...
start "DSK goose" /min cmd /c ""%GOOSES%" %GOOSEARGS% >> "%~dp0server.log" 2>&1"
ping -n 6 127.0.0.1 >nul 2>&1

echo   Starting the phone front door on 0.0.0.0:%PORT% ...
echo   Close this window or press Ctrl+C to stop both.
echo.
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0proxy.ps1" -Port %PORT% -LogPath "%~dp0proxy.log" -GooseExe "%GOOSES%" -GooseArgs "%GOOSEARGS%"
exit /b 0

REM ---------- 3: stop ----------
:stop
call "%~dp0Stop-DSK-Remote.bat"
exit /b 0
REM ------------------------------------------------------------------
REM  :showqr - draw the pairing QR in its own small window.
REM
REM  The window is started detached so this launcher never waits for it,
REM  and it closes on its own once scanned-and-forgotten: the user simply
REM  closes it. If python is absent the console addresses still work.
REM ------------------------------------------------------------------
:showqr
set "QRLIST="
for %%L in (%LANLIST%) do set "QRLIST=!QRLIST! -Lan %%L"
for %%L in (%TSLIST%) do set "QRLIST=!QRLIST! -Ts %%L"
if not exist "%~dp0show-qr.ps1" exit /b 0
if "!QRLIST!"=="" exit /b 0
REM the key goes in through the environment, not the command line (process lists show that)
set "DSK_QR_SECRET=%SECRET%"
start "" /d "%~dp0" powershell -NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File "%~dp0show-qr.ps1" -Port %PORT% !QRLIST!
set "DSK_QR_SECRET="
exit /b 0
