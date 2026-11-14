@echo off
rem Replace the installed app with apk\BEQUIET_BSR_Report_release.apk and launch it (debug data is removed: signatures differ).
set ROOT=%~dp0..
set ADB=C:\andoidsdk\platform-tools\adb.exe
set LOG=%~dp0logs\release_install.txt
echo === %DATE% %TIME% > "%LOG%"
"%ADB%" uninstall com.bsr.noisereport >> "%LOG%" 2>&1
"%ADB%" install -g "%ROOT%\apk\BEQUIET_BSR_Report_release.apk" >> "%LOG%" 2>&1
echo INSTALL_EXIT=%ERRORLEVEL% >> "%LOG%"
"%ADB%" shell am start --user current -n com.bsr.noisereport/.MainActivity >> "%LOG%" 2>&1
"%ADB%" shell dumpsys package com.bsr.noisereport | findstr /C:"versionName" /C:"pkgFlags" >> "%LOG%" 2>&1
