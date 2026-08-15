@echo off
setlocal

set JAR=praxis-chess-1.0.0.jar

echo.
echo === Praxis Chess — startup ===
echo.

:: Step 1 — PostgreSQL via Docker
where docker >nul 2>&1
if %errorlevel% neq 0 (
    echo [ERROR] Docker not found. Install Docker Desktop from https://www.docker.com/products/docker-desktop/
    pause
    exit /b 1
)
echo [1/3] Starting PostgreSQL...
docker compose up -d
if %errorlevel% neq 0 (
    echo [ERROR] docker compose up -d failed. Is Docker Desktop running?
    pause
    exit /b 1
)

:wait_postgres
docker compose exec -T postgres pg_isready -U praxis_chess_user -d praxis_chess >nul 2>&1
if %errorlevel% neq 0 (
    timeout /t 2 /nobreak >nul
    goto wait_postgres
)
echo       PostgreSQL is ready.
echo.

:: Step 2 — Ollama model
where ollama >nul 2>&1
if %errorlevel% neq 0 (
    echo [ERROR] Ollama not found. Install from https://ollama.com then re-run this script.
    pause
    exit /b 1
)
echo [2/3] Pulling Ollama model qwen2.5:7b (skips if already downloaded)...
ollama pull qwen2.5:7b
echo.

:: Step 3 — Launch app
if not exist "%JAR%" (
    echo [ERROR] %JAR% not found in the current directory.
    pause
    exit /b 1
)
echo [3/3] Starting Praxis Chess at http://localhost:8086 ...
echo       Check application.yml -- fill in your Chess.com username and Stockfish path before first use.
echo.
java -jar "%JAR%" --spring.config.location=./application.yml

pause
