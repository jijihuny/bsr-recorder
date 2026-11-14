@echo off
rem Build the debug APK, make sure the Pleos emulator is up, install and launch the app.
rem Logs go to tools\logs. Double-click to run.
setlocal
set ROOT=%~dp0..
set LOG=%~dp0logs
set JAVA_HOME=C:\Program Files\Android\Android Studio\jbr
set ADB=C:\andoidsdk\platform-tools\adb.exe
set TEMP=C:\andoidsdk\tmp
set TMP=C:\andoidsdk\tmp
set ANDROID_AVD_HOME=C:\andoidsdk\avd
set ANDROID_USER_HOME=C:\andoidsdk\userhome
set ANDROID_EMULATOR_HOME=C:\andoidsdk\userhome
echo START %DATE% %TIME% > "%LOG%\status.txt"

cd /d "%ROOT%"
call gradlew.bat assembleDebug --console=plain -Pkotlin.compiler.execution.strategy=in-process > "%LOG%\build.log" 2>&1
set BUILD_ERR=%ERRORLEVEL%
rem Free RAM before the emulator runs: the Gradle daemon (2 GB heap) starves the 4 GB emulator and it gets killed.
call gradlew.bat --stop > nul 2>&1
powershell -NoProfile -Command "$o=Get-CimInstance Win32_OperatingSystem; 'FreeRAM_MB=' + [int]($o.FreePhysicalMemory/1024) + ' CommitFree_MB=' + [int]($o.FreeVirtualMemory/1024)" >> "%LOG%\status.txt"
cmd /c exit %BUILD_ERR%
if errorlevel 1 (
  echo BUILD_FAILED >> "%LOG%\status.txt"
  goto :end
)
echo BUILD_OK >> "%LOG%\status.txt"

"%ADB%" start-server > nul 2>&1
rem Decide by process, not by adb: right after start-server adb may not list the emulator yet,
rem and launching a second instance of the same AVD crashes both.
tasklist /FI "IMAGENAME eq qemu-system-x86_64.exe" | findstr /I qemu > nul
if errorlevel 1 (
  echo STARTING_EMULATOR >> "%LOG%\status.txt"
  start "emulator" /min cmd /c ""C:\andoidsdk\emulator\emulator.exe" -avd Pleos_Connect_v2.1.0 -gpu auto -no-snapshot-load -no-audio -verbose > "%LOG%\emulator.log" 2>&1"
)
"%ADB%" wait-for-device
:waitboot
for /f "delims=" %%b in ('"%ADB%" shell getprop sys.boot_completed 2^>nul') do set BOOT=%%b
if not "%BOOT%"=="1" (
  timeout /t 3 > nul
  goto :waitboot
)
echo BOOTED >> "%LOG%\status.txt"
timeout /t 15 > nul

powershell -NoProfile -Command "$p = Start-Process -FilePath '%ADB%' -ArgumentList 'install','-r','-g','-t','%ROOT%\app\build\outputs\apk\debug\app-debug.apk' -NoNewWindow -PassThru -RedirectStandardOutput '%LOG%\install.log' -RedirectStandardError '%LOG%\install_err.log'; if (-not $p.WaitForExit(120000)) { $p.Kill(); exit 9 }; exit $p.ExitCode"
if errorlevel 1 (
  echo INSTALL_FAILED >> "%LOG%\status.txt"
  goto :end
)
if not exist "%~dp0nolaunch.flag" "%ADB%" shell am start --user current -n com.bsr.noisereport/.MainActivity > "%LOG%\launch.log" 2>&1
echo LAUNCHED >> "%LOG%\status.txt"
:end
echo END %DATE% %TIME% >> "%LOG%\status.txt"
endlocal
