@echo off
title MoodTunes - Spring Boot Backend
color 0A

echo ============================================
echo   MoodTunes - Starting Spring Boot Backend
echo ============================================
echo.

:: Check Java
java -version >nul 2>&1
if %errorlevel% neq 0 (
    color 0C
    echo [ERROR] Java is not installed or not in PATH.
    echo         Download Java 17+ from: https://adoptium.net/
    pause
    exit /b 1
)
echo [OK] Java found.

:: Check Maven
mvn -version >nul 2>&1
if %errorlevel% neq 0 (
    color 0C
    echo [ERROR] Maven is not installed or not in PATH.
    echo         Download Maven from: https://maven.apache.org/download.cgi
    echo         Then add Maven\bin to your System PATH.
    pause
    exit /b 1
)
echo [OK] Maven found.

:: Check MySQL (optional — just a port check)
netstat -an | find "3306" >nul 2>&1
if %errorlevel% neq 0 (
    color 0E
    echo [WARN] MySQL may not be running on port 3306.
    echo        Start MySQL service before continuing.
    echo        In Services (services.msc) start: MySQL80 or MySQL
    echo.
    echo Press any key to try starting the backend anyway...
    pause >nul
) else (
    echo [OK] MySQL detected on port 3306.
)

echo.
echo Starting Spring Boot on http://localhost:8080 ...
echo (First run downloads dependencies — may take 2-3 minutes)
echo.

cd /d "%~dp0backend"
mvn spring-boot:run

echo.
if %errorlevel% neq 0 (
    color 0C
    echo [FAILED] Backend did not start. Check error above.
) else (
    echo [STOPPED] Backend stopped cleanly.
)
pause
