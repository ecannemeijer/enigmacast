@echo off
setlocal EnableExtensions
cd /d "%~dp0"

set "XAMPP=C:\xampp"
set "HTTPD=%XAMPP%\apache\bin\httpd.exe"
set "APACHE_CONF=%XAMPP%\apache\conf\httpd.conf"
set "TARGET_CONF=%XAMPP%\apache\conf\extra\httpd-enigmacast.conf"
set "SITE=C:\xampp\htdocs\enigmacast"
set "HOSTS=%SystemRoot%\System32\drivers\etc\hosts"
set "INCLUDE_LINE=Include conf/extra/httpd-enigmacast.conf"

if not exist "%HTTPD%" (
    echo XAMPP Apache niet gevonden: %HTTPD%
    echo Pas XAMPP= aan als XAMPP ergens anders staat.
    exit /b 1
)

for %%I in ("%~dp0..") do set "REPO=%%~fI"

if /I "%REPO%"=="%SITE%" goto :site_ready
if exist "%SITE%" (
    echo Map bestaat al: %SITE%
    echo Die map wordt gebruikt. Er is geen junction gemaakt.
    goto :site_ready
)
echo Junction %SITE% -^> %REPO%
mklink /J "%SITE%" "%REPO%"
if errorlevel 1 (
    echo Junction mislukt. Kopieer het project naar %SITE% en start dit script opnieuw.
    exit /b 1
)

:site_ready
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

findstr /L /C:"enigmacast.local" "%HOSTS%" >nul
if errorlevel 1 (
    >> "%HOSTS%" echo 127.0.0.1 enigmacast.local
    if errorlevel 1 (
        echo Kon %HOSTS% niet bijwerken. Start dit script als administrator.
        exit /b 1
    )
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
echo   http://enigmacast.local/
echo   http://localhost/enigmacast/dist/enigmacast.json
exit /b 0
