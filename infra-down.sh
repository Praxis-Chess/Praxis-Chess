#!/usr/bin/env bash
#
# Stops what infra-up.sh started.
#
#   ./infra-down.sh          stop the containers, leave data alone
#   ./infra-down.sh --all    also stop Ollama and the voice service
#   ./infra-down.sh --wipe   DESTROY the database volume as well
#
# Ollama is left alone by default: it is a machine-wide service you may be using
# for something else, and stopping it from a project script would be presumptuous.

set -uo pipefail
cd "$(dirname "$0")"

if [ -t 1 ]; then
  BOLD=$'\e[1m'; DIM=$'\e[2m'; RED=$'\e[31m'; GREEN=$'\e[32m'; RESET=$'\e[0m'
else
  BOLD=''; DIM=''; RED=''; GREEN=''; RESET=''
fi
ok()   { printf '  %s[ ok ]%s %s\n' "$GREEN" "$RESET" "$1"; }
step() { printf '\n%s%s%s\n' "$BOLD" "$1" "$RESET"; }

STOP_ALL=false
WIPE=false
for arg in "$@"; do
  case "$arg" in
    --all)  STOP_ALL=true ;;
    --wipe) WIPE=true ;;
    -h|--help) sed -n '2,11p' "$0" | sed 's/^#//;s/^ //'; exit 0 ;;
    *) printf 'unknown option: %s\n' "$arg"; exit 2 ;;
  esac
done

printf '%sPraxis Chess — stopping infrastructure%s\n' "$BOLD" "$RESET"

step 'Containers'
if $WIPE; then
  # Every synced game, every analysis, every conversation. Ask first — this is
  # hours of Stockfish work, not a cache.
  printf '  %sThis deletes the database volume: all synced games, analyses and\n' "$RED"
  printf '  conversations. Re-syncing and re-analysing takes hours.%s\n' "$RESET"
  read -r -p '  Type WIPE to confirm: ' reply
  if [ "$reply" = 'WIPE' ]; then
    docker compose --profile web down -v >/dev/null 2>&1 && ok 'stopped and volumes removed'
  else
    printf '  cancelled — nothing was removed\n'
    exit 0
  fi
else
  # --profile web so SearXNG is included; without it Compose ignores the
  # profiled service and leaves it running.
  docker compose --profile web down >/dev/null 2>&1 && ok 'stopped (data kept)'
fi

if $STOP_ALL; then
  step 'Ollama'
  if pgrep -f 'ollama serve' >/dev/null 2>&1; then
    pkill -f 'ollama serve' && ok 'stopped'
  else
    printf '         %snot started by this script — leaving it alone%s\n' "$DIM" "$RESET"
  fi

  step 'Prax voice'
  if pgrep -f 'tts-service' >/dev/null 2>&1 || pgrep -f 'main.py' >/dev/null 2>&1; then
    pkill -f 'tts-service/main.py' 2>/dev/null && ok 'stopped' || printf '         nothing to stop\n'
  else
    printf '         nothing running\n'
  fi
fi

printf '\n%sDone.%s\n' "$GREEN" "$RESET"
