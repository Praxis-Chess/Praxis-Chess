#!/usr/bin/env bash
set -e

JAR="praxis-chess-1.0.0.jar"

echo ""
echo "=== Praxis Chess — startup ==="
echo ""

# Step 1 — PostgreSQL via Docker
if ! command -v docker &>/dev/null; then
  echo "[ERROR] Docker not found. Install Docker Desktop from https://www.docker.com/products/docker-desktop/"
  exit 1
fi
echo "[1/3] Starting PostgreSQL..."
docker compose up -d
echo "      Waiting for Postgres to be ready..."
until docker compose exec -T postgres pg_isready -U praxis_chess_user -d praxis_chess &>/dev/null; do
  sleep 1
done
echo "      PostgreSQL is ready."
echo ""

# Step 2 — Ollama model
if ! command -v ollama &>/dev/null; then
  echo "[ERROR] Ollama not found. Install from https://ollama.com then re-run this script."
  exit 1
fi
echo "[2/3] Pulling Ollama model qwen2.5:7b (skips if already downloaded)..."
ollama pull qwen2.5:7b
echo ""

# Step 3 — Launch app
if [ ! -f "$JAR" ]; then
  echo "[ERROR] $JAR not found in the current directory."
  exit 1
fi
echo "[3/3] Starting Praxis Chess at http://localhost:8086 ..."
echo "      Check application.yml — fill in your Chess.com username and Stockfish path before first use."
echo ""
java -jar "$JAR" --spring.config.location=./application.yml
