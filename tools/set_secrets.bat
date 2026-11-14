@echo off
rem Registers the release signing key as GitHub Actions secrets on jijihuny/bsr-recorder.
rem Run "gh auth login" once in a terminal before double-clicking this.
setlocal
cd /d "%~dp0.."
set OUT=%~dp0logs\set_secrets.txt
set REPO=jijihuny/bsr-recorder
echo === %DATE% %TIME% > "%OUT%"
for /f "tokens=1,* delims==" %%a in (keystore.properties) do (
  if "%%a"=="storeFile" set SF=%%b
  if "%%a"=="storePassword" set SP=%%b
  if "%%a"=="keyAlias" set KA=%%b
  if "%%a"=="keyPassword" set KP=%%b
)
set SF=%SF:/=\%
certutil -f -encodehex "%SF%" "%TEMP%\bsr_ks.b64" 0x40000001 > nul
gh secret set KEYSTORE_BASE64 --repo %REPO% < "%TEMP%\bsr_ks.b64" >> "%OUT%" 2>&1
del "%TEMP%\bsr_ks.b64"
gh secret set KEYSTORE_PASSWORD --repo %REPO% --body "%SP%" >> "%OUT%" 2>&1
gh secret set KEY_ALIAS --repo %REPO% --body "%KA%" >> "%OUT%" 2>&1
gh secret set KEY_PASSWORD --repo %REPO% --body "%KP%" >> "%OUT%" 2>&1
gh secret list --repo %REPO% >> "%OUT%" 2>&1
endlocal
