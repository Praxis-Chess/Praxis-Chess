#!/usr/bin/env bash
#
# Restore a Praxis backup. Run from anywhere in the repository (Git Bash on
# Windows).
#
#   docker/backup/restore.sh <file>                Docker install: stops the app, restores, starts it
#   docker/backup/restore.sh --dev <file>          dev stack (stop the backend in IntelliJ first)
#   docker/backup/restore.sh [--dev] --side <file> load into praxis_restore; live data untouched
#   docker/backup/restore.sh [--dev] --list        the snapshots
#
# <file> is a name from --list, e.g. praxis-20261015-093000.sql.gz.
#
# A full restore first takes a safety snapshot of the current data, loads the
# file into a separate database, checks every table's row count, and only then
# swaps it in. It prints the command that undoes it.

set -euo pipefail
cd "$(dirname "$0")/../.."

stack=docker side=false list=false file=""
for a in "$@"; do
  case $a in
    --dev)    stack=dev ;;
    --docker) stack=docker ;;
    --side)   side=true ;;
    --list)   list=true ;;
    -h|--help) sed -n '3,15p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    -*)       echo "unknown option: $a" >&2; exit 2 ;;
    *)        file=$a ;;
  esac
done

if [ "$stack" = dev ]; then dc=(docker compose); else dc=(docker compose -f docker/compose.yaml); fi

if [ -z "$("${dc[@]}" ps -q backup 2>/dev/null)" ]; then
  echo "The backup service is not running. Start it with: ${dc[*]} up -d backup" >&2
  exit 1
fi
run() { "${dc[@]}" exec -T backup backup "$@"; }

if $list; then run list; exit; fi
[ -n "$file" ] || { sed -n '3,15p' "$0" | sed 's/^# \{0,1\}//' >&2; exit 2; }
file=$(basename "$file")

if $side; then run restore-side "$file"; exit; fi

if [ "$stack" = docker ]; then
  echo "Stopping the app…"
  "${dc[@]}" stop app
  rc=0; run restore "$file" || rc=$?
  echo "Starting the app…"
  "${dc[@]}" start app
  exit $rc
fi

rc=0; run restore "$file" || rc=$?
if [ $rc = 3 ]; then
  echo >&2
  echo "The backend is still connected. Stop it in IntelliJ, run this again, then start it." >&2
elif [ $rc = 0 ]; then
  echo "Done. Start the backend in IntelliJ." >&2
fi
exit $rc
