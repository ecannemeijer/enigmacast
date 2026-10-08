@echo off
setlocal EnableExtensions
cd /d "%~dp0"

set "XAMPP=C:\xampp"
set "HTTPD=%XAMPP%\apache\bin\httpd.exe"
set "SITE=C:\xampp\htdocs\enigmacast"

if not exist "%HTTPD%" (
    echo XAMPP Apache niet gevonden: %HTTPD%
    echo Pas XAMPP= aan als XAMPP ergens anders staat.
    exit /b 1
)

for %%I in ("%~dp0..") do set "REPO=%%~fI"

if /I not "%REPO%"=="%SITE%" if not exist "%SITE%" (
    echo Junction %SITE% -^> %REPO%
    mklink /J "%SITE%" "%REPO%"
    if errorlevel 1 (
        echo Junction mislukt. De bestanden worden gekopieerd.
        mkdir "%SITE%"
    )
)

if not exist "%SITE%\dist" mkdir "%SITE%\dist"
if not exist "%SITE%\dist\enigmacast.json" copy /Y "%REPO%\dist\enigmacast.json" "%SITE%\dist\enigmacast.json" >nul
if not exist "%SITE%\install.php" copy /Y "%REPO%\install.php" "%SITE%\install.php" >nul
if not exist "%SITE%\index.html" copy /Y "%REPO%\index.html" "%SITE%\index.html" >nul
if not exist "%SITE%\.htaccess" copy /Y "%REPO%\.htaccess" "%SITE%\.htaccess" >nul

powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0patch-vhosts.ps1"
if errorlevel 1 (
    echo httpd-vhosts.conf kon niet worden bijgewerkt. Start dit script als administrator.
    exit /b 1
)

powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0patch-htaccess.ps1"
if errorlevel 1 (
    echo .htaccess kon niet worden bijgewerkt. Start dit script als administrator.
    exit /b 1
)

"%HTTPD%" -t
if errorlevel 1 (
    echo Apache-configuratie is ongeldig. Er is niets herstart.
    exit /b 1
)

"%HTTPD%" -k restart >nul 2>&1
if errorlevel 1 (
    echo Configuratie staat in httpd-vhosts.conf. Herstart Apache via het XAMPP Control Panel.
)

echo.
echo Start EnigmaCast op:
echo   http://localhost/enigmacast/install.php
exit /b 0
