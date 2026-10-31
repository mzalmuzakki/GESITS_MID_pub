@echo off
REM One-time setup for the Gesits MID toolkit on Windows.
REM Double-click this file (inside the unzipped repo folder), or run it from a terminal.
setlocal
cd /d "%~dp0"

echo === 1/4 Checking Python ===
where python >nul 2>nul
if errorlevel 1 (
    echo Python not found. Installing Python 3.12 with winget...
    winget install -e --id Python.Python.3.12 --accept-package-agreements --accept-source-agreements
    if errorlevel 1 (
        echo.
        echo Could not install automatically. Install Python from https://www.python.org/downloads/
        echo and TICK "Add python.exe to PATH", then run this file again.
        pause
        exit /b 1
    )
    echo.
    echo Python installed. CLOSE this window and double-click setup_windows.bat again
    echo so Windows picks up the new PATH.
    pause
    exit /b 0
)
python --version

echo.
echo === 2/4 Installing packages (pillow, pyserial, bleak) ===
python -m pip install --upgrade pip
python -m pip install pillow pyserial bleak
if errorlevel 1 (
    echo Package install failed - check your internet connection and try again.
    pause
    exit /b 1
)

echo.
echo === 3/4 Checking the toolkit ===
python -c "import PIL, serial, bleak, gesits; print('All packages OK')"
if errorlevel 1 (
    echo Something is missing - send a screenshot of this window.
    pause
    exit /b 1
)

echo.
echo === 4/4 Quick test with the built-in fake bike (no motorcycle needed) ===
start "fake bike" /min python -m gesits sim --port 3333
timeout /t 3 /nobreak >nul
python -m gesits monitor tcp://127.0.0.1:3333 --once
taskkill /fi "WINDOWTITLE eq fake bike*" /f >nul 2>nul

echo.
echo ============================================================
echo  Setup done. Next: pair the bike and run the self-test,
echo  see docs\LAPTOP_TEST.md (step 2 and 3).
echo ============================================================
pause
