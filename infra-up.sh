#!/usr/bin/env bash
#
# Brings up everything Praxis Chess needs, except the app itself.
#
#   ./infra-up.sh          start PostgreSQL, SearXNG and Ollama
#   ./infra-up.sh --tts    also start the Prax voice service
#   ./infra-up.sh --status just report, change nothing
#   ./infra-down.sh        stop the containers again
#
# The backend is NOT started here — it runs from IntelliJ on JDK 26, and
# starting a second copy on port 8086 would fail confusingly.
#
# Safe to re-run: every step checks before it acts, so this is the right thing
# to run after a reboot, after Docker Desktop restarts, or when something feels
# wrong and you are not sure what.

set -uo pipefail

cd "$(dirname "$0")"

# ── output ───────────────────────────────────────────────────────────────────
if [ -t 1 ]; then
  BOLD=$'\e[1m'; DIM=$'\e[2m'; RED=$'\e[31m'; GREEN=$'\e[32m'; YELLOW=$'\e[33m'; RESET=$'\e[0m'
else
  BOLD=''; DIM=''; RED=''; GREEN=''; YELLOW=''; RESET=''
fi

ok()    { printf '  %s[ ok ]%s %s\n'   "$GREEN"  "$RESET" "$1"; }
warn()  { printf '  %s[warn]%s %s\n'   "$YELLOW" "$RESET" "$1"; }
fail()  { printf '  %s[fail]%s %s\n'   "$RED"    "$RESET" "$1"; }
step()  { printf '\n%s%s%s\n' "$BOLD" "$1" "$RESET"; }
hint()  { printf '         %s%s%s\n' "$DIM" "$1" "$RESET"; }

STATUS_ONLY=false
WANT_TTS=false
PROBLEMS=0

for arg in "$@"; do
  case "$arg" in
    --status) STATUS_ONLY=true ;;
    --tts)    WANT_TTS=true ;;
    -h|--help)
      sed -n '2,16p' "$0" | sed 's/^#//;s/^ //'
      exit 0 ;;
    *) fail "unknown option: $arg"; exit 2 ;;
  esac
done

# Waits for a URL to answer 200, up to N seconds. Containers report "started"
# well before they are ready to serve, and a script that returns before that is
# the reason "it was running, honestly" turns into a debugging session.
wait_for() {
  local url="$1" seconds="$2" label="$3" i=0
  while [ "$i" -lt "$seconds" ]; do
    if curl -fsS -o /dev/null --max-time 2 "$url" 2>/dev/null; then return 0; fi
    sleep 1
    i=$((i + 1))
  done
  return 1
}

printf '%sPraxis Chess — infrastructure%s\n' "$BOLD" "$RESET"

# ── Docker ───────────────────────────────────────────────────────────────────
step 'Docker'
if ! command -v docker >/dev/null 2>&1; then
  fail 'docker is not on PATH'
  hint 'Install Docker Desktop, then re-run this script.'
  exit 1
fi
if ! docker info >/dev/null 2>&1; then
  fail 'Docker is installed but not running'
  hint 'Start Docker Desktop and wait for the whale icon to settle, then re-run.'
  exit 1
fi
ok "Docker $(docker version --format '{{.Server.Version}}' 2>/dev/null)"

# ── PostgreSQL ───────────────────────────────────────────────────────────────
step 'PostgreSQL  (port 5432)'
pg_state=$(docker inspect -f '{{.State.Status}}' praxis-chess-postgres 2>/dev/null || echo 'missing')
if [ "$pg_state" = 'running' ]; then
  ok 'already running'
elif $STATUS_ONLY; then
  fail "not running (state: $pg_state)"; PROBLEMS=$((PROBLEMS + 1))
else
  printf '         starting…\n'
  if docker compose up -d postgres >/dev/null 2>&1; then
    # pg_isready rather than a port check: an accepting socket is not the same
    # as a database that will answer a query.
    for _ in $(seq 1 30); do
      if docker exec praxis-chess-postgres pg_isready -q 2>/dev/null; then break; fi
      sleep 1
    done
    if docker exec praxis-chess-postgres pg_isready -q 2>/dev/null; then
      ok 'started and accepting connections'
    else
      warn 'container is up but not accepting connections yet'
      hint 'Give it a few more seconds, then re-run with --status.'
      PROBLEMS=$((PROBLEMS + 1))
    fi
  else
    fail 'could not start'; PROBLEMS=$((PROBLEMS + 1))
    hint 'docker compose up -d postgres'
  fi
fi

# ── SearXNG ──────────────────────────────────────────────────────────────────
#
# The one that catches people out. It sits behind a Compose profile, so a plain
# `docker compose up -d` starts PostgreSQL and silently leaves this behind. The
# symptom is Prax refusing every general question with "the search service isn't
# responding".
step 'SearXNG  (port 8888, web research)'
WEB_ENABLED=$(grep -A3 '^  web:' backend/src/main/resources/application.yml 2>/dev/null \
              | grep -m1 'enabled:' | tr -d ' ' | cut -d: -f2)

if [ "${WEB_ENABLED:-false}" != 'true' ]; then
  printf '         %sweb research is disabled in application.yml — skipping%s\n' "$DIM" "$RESET"
else
  sx_state=$(docker inspect -f '{{.State.Status}}' praxis-chess-searxng 2>/dev/null || echo 'missing')
  if [ "$sx_state" = 'running' ] && curl -fsS -o /dev/null --max-time 3 \
       'http://localhost:8888/search?format=json&q=test' 2>/dev/null; then
    ok 'already running, JSON API responding'
  elif $STATUS_ONLY; then
    fail "not serving JSON (state: $sx_state)"; PROBLEMS=$((PROBLEMS + 1))
  else
    printf '         starting…\n'
    # Named explicitly, which starts it despite the profile.
    if docker compose up -d searxng >/dev/null 2>&1; then
      if wait_for 'http://localhost:8888/search?format=json&q=test' 45 'searxng'; then
        ok 'started, JSON API responding'
      else
        code=$(curl -s -o /dev/null -w '%{http_code}' --max-time 3 \
               'http://localhost:8888/search?format=json&q=test' 2>/dev/null)
        if [ "$code" = '403' ]; then
          # Looks exactly like a network fault, is not one.
          fail 'SearXNG returned 403 — the JSON format is not enabled'
          hint 'Check searxng/settings.yml is mounted and contains: formats: [html, json]'
        else
          fail "SearXNG is not serving JSON (HTTP ${code:-none})"
          hint 'docker logs praxis-chess-searxng'
        fi
        PROBLEMS=$((PROBLEMS + 1))
      fi
    else
      fail 'could not start'; PROBLEMS=$((PROBLEMS + 1))
      hint 'docker compose up -d searxng'
    fi
  fi
fi

# ── Ollama ───────────────────────────────────────────────────────────────────
#
# Usually already running: the Windows installer registers a background service,
# so `ollama serve` would fail with "address already in use". Probe first, and
# only start it if nothing answers.
step 'Ollama  (port 11434)'
if curl -fsS -o /dev/null --max-time 3 http://localhost:11434/api/tags 2>/dev/null; then
  ok 'already running'
elif $STATUS_ONLY; then
  fail 'not responding'; PROBLEMS=$((PROBLEMS + 1))
elif ! command -v ollama >/dev/null 2>&1; then
  fail 'ollama is not on PATH and nothing is listening on 11434'
  hint 'Install from https://ollama.com/download, or start the Ollama app.'
  PROBLEMS=$((PROBLEMS + 1))
else
  printf '         starting…\n'
  # Detached, with its log kept: a model that fails to load says why in there,
  # and a background process whose output went to /dev/null is unfixable.
  mkdir -p .infra
  nohup ollama serve > .infra/ollama.log 2>&1 &
  if wait_for http://localhost:11434/api/tags 30 'ollama'; then
    ok 'started'
  else
    fail 'did not come up within 30s'
    hint 'See .infra/ollama.log'
    PROBLEMS=$((PROBLEMS + 1))
  fi
fi

# ── Models ───────────────────────────────────────────────────────────────────
#
# Checked, never pulled automatically: these are multi-gigabyte downloads and
# starting one from a script that looked like it would take five seconds is a
# rude surprise.
if curl -fsS -o /dev/null --max-time 3 http://localhost:11434/api/tags 2>/dev/null; then
  step 'Ollama models'
  installed=$(curl -fsS --max-time 5 http://localhost:11434/api/tags 2>/dev/null)
  for key in 'model' 'reasoning-model'; do
    # Strip only the KEY prefix. Splitting on ':' would also split the value —
    # "qwen2.5:7b" became "qwen2.5", which is not a model anyone has installed.
    want=$(grep -m1 "^ *${key}:" backend/src/main/resources/application.yml 2>/dev/null \
           | sed 's/#.*//' | sed "s/^ *${key}: *//" | tr -d ' \r"')
    [ -z "$want" ] && continue
    if printf '%s' "$installed" | grep -q "\"${want}\""; then
      ok "$want"
    else
      warn "$want is configured but not installed"
      hint "ollama pull $want"
      PROBLEMS=$((PROBLEMS + 1))
    fi
  done
fi

# ── Prax voice (optional) ────────────────────────────────────────────────────
if $WANT_TTS; then
  step 'Prax voice  (port 8087)'
  if curl -fsS -o /dev/null --max-time 3 http://127.0.0.1:8087/health 2>/dev/null \
     || curl -fsS -o /dev/null --max-time 3 http://127.0.0.1:8087/ 2>/dev/null; then
    ok 'already running'
  elif [ ! -f tts-service/main.py ]; then
    warn 'tts-service/main.py not found — skipping'
  else
    printf '         starting…\n'
    mkdir -p .infra
    ( cd tts-service && nohup python main.py > ../.infra/tts.log 2>&1 & )
    # Kokoro loads an 82M model on CPU, so first start is genuinely slow.
    if wait_for http://127.0.0.1:8087/ 60 'tts'; then
      ok 'started'
    else
      warn 'did not come up within 60s — Prax will stay silent'
      hint 'See .infra/tts.log'
    fi
  fi
fi

# ── Summary ──────────────────────────────────────────────────────────────────
printf '\n'
if [ "$PROBLEMS" -eq 0 ]; then
  printf '%sInfrastructure ready.%s Start the backend from IntelliJ, then:\n' "$GREEN" "$RESET"
  printf '  cd frontend && npm run dev\n'
  exit 0
else
  printf '%s%d problem(s) above.%s The app will still start — each missing piece\n' \
    "$YELLOW" "$PROBLEMS" "$RESET"
  printf 'only disables its own feature, and the backend logs which at startup.\n'
  exit 1
fi
