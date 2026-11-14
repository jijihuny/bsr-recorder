@echo off
rem Restart the emulator with its window placed at the top-left at half size, then reinstall and launch the app.
set ADB=C:\andoidsdk\platform-tools\adb.exe
set INI=C:\andoidsdk\avd\Pleos_Connect_v2.1.0.avd\emulator-user.ini
"%ADB%" emu kill > nul 2>&1
timeout /t 8 > nul
taskkill /IM qemu-system-x86_64.exe /F > nul 2>&1
timeout /t 2 > nul
powershell -NoProfile -Command "$p='%INI%'; (Get-Content $p) -replace '^window.x = .*','window.x = 0' -replace '^window.y = .*','window.y = 0' -replace '^window.scale = .*','window.scale = 0.520000' | Set-Content -Encoding ascii $p"
call "%~dp0build_run.bat"
