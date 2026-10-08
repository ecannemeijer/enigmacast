@echo off
setlocal EnableExtensions
cd /d "%~dp0"

set "XAMPP=C:\xampp"
set "HTTPD=%XAMPP%\apache\bin\httpd.exe"
set "SITE=C:\xampp\htdocs\enigmacast"
set "HOSTFILE=%TEMP%\enigmacast-host.txt"

if not exist "%HTTPD%" (
    echo XAMPP Apache niet gevonden: %HTTPD%
    exit /b 1
)

for %%I in ("%~dp0..") do set "REPO=%%~fI"

if /I not "%REPO%"=="%SITE%" if not exist "%SITE%" (
    mklink /J "%SITE%" "%REPO%"
    if errorlevel 1 (
        mkdir "%SITE%"
    )
)

if not exist "%SITE%\dist" mkdir "%SITE%\dist"
if not exist "%SITE%\dist\enigmacast.json" copy /Y "%REPO%\dist\enigmacast.json" "%SITE%\dist\enigmacast.json" >nul
if not exist "%SITE%\install.php" copy /Y "%REPO%\install.php" "%SITE%\install.php" >nul
if not exist "%SITE%\index.html" copy /Y "%REPO%\index.html" "%SITE%\index.html" >nul
if not exist "%SITE%\.htaccess" copy /Y "%REPO%\.htaccess" "%SITE%\.htaccess" >nul

powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0patch-vhosts.ps1" > "%HOSTFILE%"
if errorlevel 1 (
    echo De virtual host kon niet in httpd-vhosts.conf gezet worden.
    echo Start dit script als administrator.
    exit /b 1
)
set /p SITEHOST=<"%HOSTFILE%"

"%HTTPD%" -t
if errorlevel 1 (
    echo Apache-configuratie is ongeldig. Er is niets herstart.
    exit /b 1
)

"%HTTPD%" -k restart >nul 2>&1
if errorlevel 1 (
    echo Herstart Apache via het XAMPP Control Panel.
)

echo.
echo Open:
echo   http://%SITEHOST%/install.php
exit /b 0
