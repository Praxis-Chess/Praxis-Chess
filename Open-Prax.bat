@echo off
title Open-Prax

echo.
echo =========================
echo      Starting Prax
echo =========================
echo.

echo [1/3] Starting Ollama...
start "Ollama Serve" cmd /k "ollama serve"

echo [2/3] Starting Praxis backend infrastructure...
start "Praxis Backend Infra" cmd /k "cd /d D:\Tanm\Projects\Praxis-Chess\backend && bash -lc ""make infra-up; exec bash -i"""

echo [3/3] Starting SearXNG...
start "SearXNG" cmd /k "cd /d D:\Tanm\Projects\Praxis-Chess\searxng && docker compose up -d searxng"

echo.
echo All Prax startup commands have been launched.
echo.
pause
