@echo off
setlocal
set "SRC=%~dp0"
if "%SRC:~-1%"=="\" set "SRC=%SRC:~0,-1%"
set "DEST=C:\xampp\htdocs\enigmacast"

if not exist "C:\xampp\htdocs\" (
    echo XAMPP htdocs niet gevonden op C:\xampp\htdocs
    exit /b 1
)

if exist "%DEST%\" (
    echo %DEST% bestaat al. Verwijder die map eerst als het geen koppeling naar deze website is.
    exit /b 1
)

mklink /J "%DEST%" "%SRC%"
if errorlevel 1 (
    echo Junction mislukt. Start deze prompt als administrator als Windows dat vraagt.
    exit /b 1
)

echo Site gekoppeld. Open http://localhost/enigmacast/install.php
exit /b 0
