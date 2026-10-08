@echo off
setlocal EnableExtensions
cd /d "%~dp0"

set "XAMPP=C:\xampp"
set "HTTPD=%XAMPP%\apache\bin\httpd.exe"
set "APACHE_CONF=%XAMPP%\apache\conf\httpd.conf"
set "TARGET_CONF=%XAMPP%\apache\conf\extra\httpd-enigmacast.conf"
set "SITE=C:\xampp\htdocs\enigmacast"
set "INCLUDE_LINE=Include conf/extra/httpd-enigmacast.conf"

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
if not exist "%SITE%\index.html" copy /Y "%REPO%\index.html" "%SITE%\index.html" >nul
if not exist "%SITE%\.htaccess" copy /Y "%REPO%\.htaccess" "%SITE%\.htaccess" >nul

copy /Y "%~dp0enigmacast.conf" "%TARGET_CONF%" >nul
if errorlevel 1 (
    echo Kon %TARGET_CONF% niet schrijven. Start dit script als administrator.
    exit /b 1
)

findstr /L /C:"httpd-enigmacast.conf" "%APACHE_CONF%" >nul
if errorlevel 1 (
    >> "%APACHE_CONF%" echo.
    >> "%APACHE_CONF%" echo # EnigmaCast
    >> "%APACHE_CONF%" echo %INCLUDE_LINE%
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
    echo Configuratie staat goed. Herstart Apache via het XAMPP Control Panel.
)

echo.
echo EnigmaCast is bereikbaar op:
echo   http://localhost/enigmacast/
echo   http://localhost/enigmacast/dist/enigmacast.json
exit /b 0
