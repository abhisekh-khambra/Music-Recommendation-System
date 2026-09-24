@echo off
title MoodTunes - React Frontend
color 0B

echo ============================================
echo   MoodTunes - Starting React Frontend
echo ============================================
echo.

:: Check Node.js
node -v >nul 2>&1
if %errorlevel% neq 0 (
    color 0C
    echo [ERROR] Node.js is not installed.
    echo         Download from: https://nodejs.org/
    pause
    exit /b 1
)
echo [OK] Node.js found.

cd /d "%~dp0frontend"

:: Install deps if node_modules is missing
if not exist "node_modules" (
    echo [INFO] Installing npm packages (first time only)...
    npm install
)

echo.
echo Starting React at http://localhost:3000 ...
echo.
npm start
pause
