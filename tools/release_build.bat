@echo off
rem Create the release key if missing, build the signed release APK, copy it to apk\, install it on the emulator.
setlocal
set ROOT=%~dp0..
set LOG=%~dp0logs\release.txt
set JAVA_HOME=C:\Program Files\Android\Android Studio\jbr
set ADB=C:\andoidsdk\platform-tools\adb.exe
set TEMP=C:\andoidsdk\tmp
set TMP=C:\andoidsdk\tmp
echo === %DATE% %TIME% > "%LOG%"
cd /d "%ROOT%"
for /f "tokens=1,* delims==" %%a in (keystore.properties) do if "%%a"=="storePassword" set KPW=%%b
if not exist C:\andoidsdk\keys mkdir C:\andoidsdk\keys
if not exist C:\andoidsdk\keys\bsr-release.jks (
  "%JAVA_HOME%\bin\keytool" -genkeypair -keystore C:\andoidsdk\keys\bsr-release.jks -alias bsr -keyalg RSA -keysize 2048 -validity 10000 -storepass %KPW% -keypass %KPW% -dname "CN=BEQUIET, O=BEQUIET, C=KR" >> "%LOG%" 2>&1
)
call gradlew.bat assembleRelease --console=plain -Pkotlin.compiler.execution.strategy=in-process >> "%LOG%" 2>&1
set ERR=%ERRORLEVEL%
call gradlew.bat --stop > nul 2>&1
if not "%ERR%"=="0" ( echo RELEASE_BUILD_FAILED >> "%LOG%" & goto :end )
if not exist apk mkdir apk
copy /Y app\build\outputs\apk\release\app-release.apk apk\BEQUIET_BSR_Report_release.apk >> "%LOG%" 2>&1
call "C:\andoidsdk\build-tools\36.0.0\apksigner.bat" verify --print-certs apk\BEQUIET_BSR_Report_release.apk >> "%LOG%" 2>&1
echo RELEASE_OK >> "%LOG%"
rem Debug and release signatures differ, so the debug install has to go first (its local records are removed).
"%ADB%" uninstall com.bsr.noisereport >> "%LOG%" 2>&1
powershell -NoProfile -Command "$p = Start-Process -FilePath '%ADB%' -ArgumentList 'install','-g','%ROOT%\apk\BEQUIET_BSR_Report_release.apk' -NoNewWindow -PassThru -RedirectStandardOutput '%~dp0logs\release_install.txt' -RedirectStandardError '%~dp0logs\release_install_err.txt'; if (-not $p.WaitForExit(120000)) { $p.Kill(); exit 9 }; exit $p.ExitCode"
echo INSTALL_EXIT=%ERRORLEVEL% >> "%LOG%"
"%ADB%" shell am start --user current -n com.bsr.noisereport/.MainActivity >> "%LOG%" 2>&1
:end
echo END %DATE% %TIME% >> "%LOG%"
endlocal
