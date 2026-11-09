@echo off
setlocal
set "SRC=%~dp0"
if "%SRC:~-1%"=="\" set "SRC=%SRC:~0,-1%"
set "DEST=C:\xampp\htdocs\enigmacast"

if not exist "C:\xampp\htdocs\" (
    echo XAMPP htdocs was not found at C:\xampp\htdocs
    exit /b 1
)

if exist "%DEST%\" (
    echo %DEST% already exists. Remove that folder first if it is not a link to this website.
    exit /b 1
)

mklink /J "%DEST%" "%SRC%"
if errorlevel 1 (
    echo Could not create the junction. Run this prompt as administrator if Windows asks for it.
    exit /b 1
)

echo Site linked. Open http://localhost/enigmacast/install.php
exit /b 0
