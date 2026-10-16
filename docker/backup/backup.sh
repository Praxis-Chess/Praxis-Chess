#!/usr/bin/env bash
#
# Praxis database backups. Runs inside the "backup" service (postgres:17-alpine)
# of both stacks: docker-compose.yml (dev) and docker/compose.yaml (install).
#
#   backup daemon                     the service: hourly check, app requests, weekly verify
#   backup snapshot [reason]          take one now (nothing written if nothing changed)
#   backup verify [file]              load a snapshot into a scratch database and compare
#   backup list                       every snapshot in the index
#   backup restore <file>             replace the live database (stop the app first)
#   backup restore-side <file> [db]   load a snapshot beside the live one (praxis_restore)
#   backup counts [db]                rows per table
#
# Inside the container `backup` runs this script. From the host:
#   docker compose exec backup backup list                          (dev stack)
#   docker compose -f docker/compose.yaml exec backup backup list   (Docker install)
# or docker/backup/restore.sh, which also stops and starts the app.
#
# Nothing here ever deletes a snapshot. Files in $BACKUP_DIR:
#   praxis-YYYYMMDD-HHMMSS.sql.gz   plain SQL (pg_dump --no-owner), gzip; UTC time
#   praxis-….sql.gz.sha256          checksum, `sha256sum -c` format
#   index.jsonl                     one line per snapshot, append-only
#   verify.jsonl                    one line per verification, append-only
#   status.json                     the service's last check, rewritten each time
# A day whose data did not change writes no snapshot: the dump is compared with
# the newest one, header comments excluded, and discarded if equal.

set -uo pipefail

BACKUP_DIR=${BACKUP_DIR:-/backups}
REQUESTS_DIR=${REQUESTS_DIR:-$BACKUP_DIR/.requests}
WORK=$BACKUP_DIR/.work
DB=${PGDATABASE:-praxis_chess}
INTERVAL_HOURS=${BACKUP_INTERVAL_HOURS:-24}
VERIFY_DAYS=${BACKUP_VERIFY_DAYS:-7}
LOCK=/tmp/praxis-backup.lock
INDEX=$BACKUP_DIR/index.jsonl
VERIFY_LOG=$BACKUP_DIR/verify.jsonl
STATUS=$BACKUP_DIR/status.json

export PGDATABASE=$DB

log()  { printf '%s [backup] %s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$*" >&2; }
die()  { log "ERROR: $*"; exit "${2:-1}"; }
now()  { date -u +%s; }
iso()  { date -u -d "@$1" +%Y-%m-%dT%H:%M:%SZ; }
# A JSON string, or null when empty. Values are file names, reasons, error text.
jstr() {
  if [ -z "${1:-}" ]; then printf 'null'
  else printf '"%s"' "$(printf '%s' "$1" | tr '\n\r\t' '   ' | sed 's/\\/\\\\/g; s/"/\\"/g')"; fi
}
# One field from the last line of a JSONL file (optionally the last line matching $3).
jfield() {
  grep -- "${3:-.}" "$1" 2>/dev/null | tail -n 1 |
    sed -n "s/.*\"$2\":\"\{0,1\}\([^\",}]*\)\"\{0,1\}[,}].*/\1/p"
}

# One operation at a time: the service and anything run with `exec` share /tmp.
with_lock() {
  exec 9>"$LOCK"
  flock 9
  "$@"
  local rc=$?
  flock -u 9
  return $rc
}

# psql on the maintenance database, for CREATE/DROP DATABASE.
admin() { psql -X -q -v ON_ERROR_STOP=1 -d postgres "$@"; }

# A snapshot by name or path → its path; empty when there is no such file.
resolve() {
  local f=${1:-}
  [ -n "$f" ] || return 1
  [ -f "$f" ] || f=$BACKUP_DIR/$(basename "$f")
  [ -f "$f" ] && printf '%s' "$f"
}

# "table rows" per line, sorted, from a plain-SQL dump on stdin: the lines
# between each "COPY … FROM stdin;" and its "\." terminator. COPY escapes
# newlines inside values, so one line is one row.
dump_counts() {
  awk '
    /^COPY .* FROM stdin;$/ { t = $2; sub(/^public\./, "", t); gsub(/"/, "", t); n = 0; inb = 1; next }
    inb && $0 == "\\." { print t, n; inb = 0; next }
    inb { n++ }
  ' | sort
}

# The same shape, counted in a database.
db_counts() {
  local db=$1 t
  psql -X -At -d "$db" -c "select table_name from information_schema.tables
                           where table_schema = 'public' and table_type = 'BASE TABLE' order by 1" |
  while read -r t; do
    printf '%s %s\n' "$t" "$(psql -X -At -d "$db" -c "select count(*) from public.\"$t\"")"
  done | sort
}

counts_json() { awk 'BEGIN { printf "{" } { printf "%s\"%s\":%s", (NR > 1 ? "," : ""), $1, $2 } END { printf "}" }'; }

# The dump minus what differs between two dumps of the same data: the header
# comments (versions, times) and PostgreSQL 17.6+'s random \restrict key.
content_hash() {
  grep -v -e '^--' -e '^\\restrict' -e '^\\unrestrict' "$1" | sha256sum | cut -d' ' -f1
}

# ── status.json ─────────────────────────────────────────────────────────────

LAST_CHECK=0; LAST_RESULT=""; LAST_ERROR=""; LAST_FILE=""
write_status() {
  local v_at v_ok v_err
  v_at=$(jfield "$VERIFY_LOG" verified_at)
  v_ok=$(jfield "$VERIFY_LOG" ok)
  v_err=$(jfield "$VERIFY_LOG" error)
  [ "$v_err" = null ] && v_err=""
  printf '{"service":"running","updated_at":%s,"updated_epoch":%s,"last_check_at":%s,"last_check_epoch":%s,"last_check_result":%s,"last_error":%s,"last_snapshot":%s,"last_verify_at":%s,"last_verify_ok":%s,"last_verify_error":%s,"interval_hours":%s,"verify_days":%s}\n' \
    "$(jstr "$(iso "$(now)")")" "$(now)" \
    "$( [ "$LAST_CHECK" -gt 0 ] && jstr "$(iso "$LAST_CHECK")" || printf null)" "$LAST_CHECK" \
    "$(jstr "$LAST_RESULT")" "$(jstr "$LAST_ERROR")" "$(jstr "$LAST_FILE")" \
    "$(jstr "$v_at")" "${v_ok:-null}" "$(jstr "$v_err")" "$INTERVAL_HOURS" "$VERIFY_DAYS" > "$STATUS.tmp" &&
    mv -f "$STATUS.tmp" "$STATUS"
}

# ── snapshot ────────────────────────────────────────────────────────────────

# Sets SNAP_FILE (the new file, or the newest one when nothing changed),
# SNAP_UNCHANGED (1/0) and, on failure, SNAP_ERROR. Never exits the shell.
SNAP_FILE=""; SNAP_UNCHANGED=0; SNAP_ERROR=""
take_snapshot() {
  local reason=${1:-manual} started ts name tmp gz hash prev bytes sum rows
  SNAP_FILE=""; SNAP_UNCHANGED=0; SNAP_ERROR=""
  case $reason in *[!a-z-]*|"") SNAP_ERROR="reason must be lowercase words and dashes: $reason"; return 1 ;; esac
  mkdir -p "$WORK" 2>/dev/null || { SNAP_ERROR="cannot write to $BACKUP_DIR"; return 1; }
  started=$(now)
  ts=$(date -u -d "@$started" +%Y%m%d-%H%M%S)
  name=praxis-$ts.sql.gz
  tmp=$WORK/praxis-$ts.sql
  gz=$WORK/$name

  if ! pg_dump --no-owner --no-privileges -d "$DB" > "$tmp" 2> "$WORK/pg_dump.err"; then
    SNAP_ERROR="pg_dump failed: $(tail -n 2 "$WORK/pg_dump.err" | tr '\n' ' ')"
    rm -f "$tmp"; return 1
  fi

  hash=$(content_hash "$tmp")
  prev=$(jfield "$INDEX" content_sha256)
  if [ -n "$prev" ] && [ "$hash" = "$prev" ]; then
    rm -f "$tmp"
    SNAP_UNCHANGED=1
    SNAP_FILE=$(jfield "$INDEX" file)
    LAST_FILE=$SNAP_FILE
    log "no change since $SNAP_FILE: nothing written ($reason)"
    return 0
  fi

  rows=$(dump_counts < "$tmp" | counts_json)
  if ! gzip -6 -c "$tmp" > "$gz"; then
    rm -f "$tmp" "$gz"; SNAP_ERROR="gzip failed (is the disk full?)"; return 1
  fi
  rm -f "$tmp"
  gzip -t "$gz" 2>/dev/null || { rm -f "$gz"; SNAP_ERROR="the new snapshot failed gzip -t"; return 1; }
  mv -f "$gz" "$BACKUP_DIR/$name" || { SNAP_ERROR="cannot write to $BACKUP_DIR"; return 1; }
  sum=$(sha256sum "$BACKUP_DIR/$name" | cut -d' ' -f1)
  printf '%s  %s\n' "$sum" "$name" > "$BACKUP_DIR/$name.sha256"
  bytes=$(wc -c < "$BACKUP_DIR/$name" | tr -d ' ')

  printf '{"file":%s,"taken_at":%s,"taken_epoch":%s,"reason":%s,"bytes":%s,"sha256":"%s","content_sha256":"%s","rows":%s,"seconds":%s,"pg_version":%s,"app_version":%s}\n' \
    "$(jstr "$name")" "$(jstr "$(iso "$started")")" "$started" "$(jstr "$reason")" "$bytes" "$sum" "$hash" \
    "$rows" "$(( $(now) - started ))" "$(jstr "$(psql -X -At -c 'show server_version' 2>/dev/null)")" \
    "$(jstr "${PRAXIS_VERSION:-}")" >> "$INDEX"
  SNAP_FILE=$name
  LAST_FILE=$name
  log "saved $name ($bytes bytes, $(( $(now) - started ))s, $reason)"
}

# Snapshots written before the index existed (the old on-demand service) are
# indexed once as "imported", so the app counts them. They are never moved.
import_unindexed() {
  local f name tmp sum hash rows bytes mtime
  for f in "$BACKUP_DIR"/praxis-*.sql.gz; do
    [ -f "$f" ] || continue
    name=$(basename "$f")
    grep -q "\"file\":\"$name\"" "$INDEX" 2>/dev/null && continue
    gzip -t "$f" 2>/dev/null || { log "skipping unreadable $name"; continue; }
    tmp=$WORK/import.sql
    gunzip -c "$f" > "$tmp"
    sum=$(sha256sum "$f" | cut -d' ' -f1)
    [ -f "$f.sha256" ] || printf '%s  %s\n' "$sum" "$name" > "$f.sha256"
    hash=$(content_hash "$tmp"); rows=$(dump_counts < "$tmp" | counts_json); rm -f "$tmp"
    bytes=$(wc -c < "$f" | tr -d ' ')
    mtime=$(stat -c %Y "$f")
    printf '{"file":%s,"taken_at":%s,"taken_epoch":%s,"reason":"imported","bytes":%s,"sha256":"%s","content_sha256":"%s","rows":%s,"seconds":null,"pg_version":null,"app_version":null}\n' \
      "$(jstr "$name")" "$(jstr "$(iso "$mtime")")" "$mtime" "$bytes" "$sum" "$hash" "$rows" >> "$INDEX"
    log "indexed existing $name"
  done
}

# ── verify ──────────────────────────────────────────────────────────────────

# Checksum and gzip integrity. Prints the problem, if any.
check_file() {
  local f=$1
  if [ -f "$f.sha256" ]; then
    (cd "$(dirname "$f")" && sha256sum -c -s "$(basename "$f").sha256") || { echo "checksum mismatch"; return 1; }
  fi
  gzip -t "$f" 2>/dev/null || { echo "gzip -t failed"; return 1; }
}

# Load a snapshot into database $2, dropping it first. Prints the error, if any.
load_into() {
  local f=$1 db=$2
  admin -c "drop database if exists \"$db\"" 2> "$WORK/load.err" &&
    admin -c "create database \"$db\"" 2>> "$WORK/load.err" || { tail -n 2 "$WORK/load.err"; return 1; }
  gunzip -c "$f" | psql -X -q -v ON_ERROR_STOP=1 -d "$db" > /dev/null 2> "$WORK/load.err" ||
    { tail -n 2 "$WORK/load.err" | tr '\n' ' '; return 1; }
}

# Do the loaded tables hold exactly the rows the dump holds? Prints differences.
compare_counts() {
  local want=$1 db=$2 got
  got=$(db_counts "$db" | awk 'NR == FNR { w[$1] = 1; next } ($1 in w)' <(printf '%s\n' "$want") -)
  [ "$want" = "$got" ] && return 0
  diff <(printf '%s\n' "$want") <(printf '%s\n' "$got") | grep '^[-+][a-z_]' | head -n 6 | tr '\n' ' '
  return 1
}

# Appends one line to verify.jsonl. Returns 1 when the snapshot fails.
verify_file() {
  local f started err="" ok=true want="" tables=0 total=0 name
  name=${1:-$(jfield "$INDEX" file)}
  [ -n "$name" ] || { log "no snapshot to verify yet"; return 1; }
  mkdir -p "$WORK"
  started=$(now)
  if ! f=$(resolve "$name"); then
    ok=false; err="file not found: $name"; f=$name
  else
    log "verifying $(basename "$f") in a scratch database"
    err=$(check_file "$f") || ok=false
    if $ok; then
      want=$(gunzip -c "$f" | dump_counts)
      tables=$(printf '%s\n' "$want" | grep -c .)
      total=$(printf '%s\n' "$want" | awk '{ s += $2 } END { print s + 0 }')
      err=$(load_into "$f" praxis_verify) || ok=false
    fi
    if $ok; then err=$(compare_counts "$want" praxis_verify) || { ok=false; err="row counts differ: $err"; }; fi
    admin -c 'drop database if exists praxis_verify' 2>/dev/null
  fi
  printf '{"file":%s,"verified_at":%s,"verified_epoch":%s,"ok":%s,"tables":%s,"rows":%s,"seconds":%s,"error":%s}\n' \
    "$(jstr "$(basename "$f")")" "$(jstr "$(iso "$(now)")")" "$(now)" "$ok" "$tables" "$total" \
    "$(( $(now) - started ))" "$(jstr "$err")" >> "$VERIFY_LOG"
  if $ok; then log "verified $(basename "$f"): $tables tables, $total rows"
  else log "VERIFICATION FAILED for $(basename "$f"): $err"; return 1; fi
}

# ── restore ─────────────────────────────────────────────────────────────────

other_connections() {
  psql -X -At -d postgres -c "select count(*) from pg_stat_activity where datname = '$DB' and pid <> pg_backend_pid()"
}

restore_live() {
  local f n err want
  f=$(resolve "${1:-}") || die "no such snapshot: ${1:-} (see: backup list)" 2
  mkdir -p "$WORK"
  err=$(check_file "$f") || die "$(basename "$f"): $err"
  n=$(other_connections)
  [ "$n" = "0" ] || die "the app is still connected to $DB ($n connections). Stop it first, then run this again." 3

  log "1/4 safety snapshot of the current data"
  take_snapshot before-restore || die "safety snapshot failed, nothing was changed: $SNAP_ERROR"
  local undo=$SNAP_FILE

  log "2/4 loading $(basename "$f") into praxis_incoming"
  want=$(gunzip -c "$f" | dump_counts)
  err=$(load_into "$f" praxis_incoming) ||
    { admin -c 'drop database if exists praxis_incoming'; die "load failed, live data untouched: $err"; }
  err=$(compare_counts "$want" praxis_incoming) ||
    { admin -c 'drop database if exists praxis_incoming'; die "row counts differ, live data untouched: $err"; }

  log "3/4 swapping it in"
  n=$(other_connections)
  [ "$n" = "0" ] || { admin -c 'drop database if exists praxis_incoming'; die "something connected meanwhile; live data untouched" 3; }
  admin -c "drop database \"$DB\"" || die "could not drop $DB; the restored copy is in praxis_incoming"
  admin -c "alter database praxis_incoming rename to \"$DB\"" ||
    die "rename failed. Run: psql -d postgres -c 'alter database praxis_incoming rename to $DB'"

  log "4/4 restored $(basename "$f"). Rows per table:"
  db_counts "$DB" | awk '{ printf "    %-40s %s\n", $1, $2 }' >&2
  log "to undo: backup restore $undo"
}

restore_side() {
  local f db=${2:-praxis_restore} err
  f=$(resolve "${1:-}") || die "no such snapshot: ${1:-} (see: backup list)" 2
  case $db in "$DB"|postgres|template0|template1|*[!a-z0-9_]*|"") die "not a side database name: $db" 2 ;; esac
  mkdir -p "$WORK"
  err=$(check_file "$f") || die "$(basename "$f"): $err"
  err=$(load_into "$f" "$db") || die "load failed: $err"
  log "loaded $(basename "$f") into $db; the live database is untouched. Rows per table:"
  db_counts "$db" | awk '{ printf "    %-40s %s\n", $1, $2 }' >&2
}

# ── requests from the app ───────────────────────────────────────────────────

record_check() {
  LAST_CHECK=$(now)
  if [ "$1" = 0 ]; then
    LAST_RESULT=$([ "$SNAP_UNCHANGED" = 1 ] && echo unchanged || echo created); LAST_ERROR=""
  else
    LAST_RESULT=error; LAST_ERROR=$SNAP_ERROR; log "ERROR: $SNAP_ERROR"
  fi
  write_status
}

# The app writes <id>.json holding {"reason":"…"}; the answer is <id>.done.json.
handle_requests() {
  local r id reason ok rc
  for r in "$REQUESTS_DIR"/*.json; do
    [ -f "$r" ] || continue
    case $r in *.done.json) continue ;; esac
    id=$(basename "$r" .json)
    reason=$(sed -n 's/.*"reason" *: *"\([a-z-]*\)".*/\1/p' "$r")
    rm -f "$r"
    log "request from the app: ${reason:-manual}"
    take_snapshot "${reason:-manual}"; rc=$?
    record_check $rc
    if [ $rc = 0 ]; then ok=true; else ok=false; fi
    printf '{"ok":%s,"file":%s,"unchanged":%s,"error":%s}\n' "$ok" "$(jstr "$SNAP_FILE")" \
      "$([ "$SNAP_UNCHANGED" = 1 ] && echo true || echo false)" "$(jstr "$SNAP_ERROR")" > "$REQUESTS_DIR/$id.done.tmp"
    chmod 666 "$REQUESTS_DIR/$id.done.tmp" 2>/dev/null
    mv -f "$REQUESTS_DIR/$id.done.tmp" "$REQUESTS_DIR/$id.done.json"
  done
  # Answers nobody collected (the app restarted while waiting).
  find "$REQUESTS_DIR" -maxdepth 1 -name '*.done.json' -mmin +60 -exec rm -f {} + 2>/dev/null
  return 0
}

# ── the service ─────────────────────────────────────────────────────────────

daemon() {
  local last_ok next_verify=0 tick=0 rc
  mkdir -p "$BACKUP_DIR" "$WORK" "$REQUESTS_DIR" || die "cannot create $BACKUP_DIR"
  # The app runs as another user and must be able to leave requests here.
  chmod 777 "$REQUESTS_DIR" 2>/dev/null
  rm -f "$WORK"/*.sql "$WORK"/*.sql.gz 2>/dev/null   # half-written leftovers, never snapshots
  printf '#!/bin/sh\nexec bash %s "$@"\n' "$(readlink -f "$0")" > /usr/local/bin/backup && chmod +x /usr/local/bin/backup

  until pg_isready -q -d "$DB"; do sleep 2; done
  with_lock import_unindexed

  LAST_CHECK=$(sed -n 's/.*"last_check_epoch":\([0-9]*\).*/\1/p' "$STATUS" 2>/dev/null); LAST_CHECK=${LAST_CHECK:-0}
  LAST_RESULT=$(sed -n 's/.*"last_check_result":"\([a-z]*\)".*/\1/p' "$STATUS" 2>/dev/null)
  LAST_FILE=$(jfield "$INDEX" file)
  log "running. Snapshot when the last check is ${INTERVAL_HOURS}h old; verification every ${VERIFY_DAYS} days; folder $BACKUP_DIR"
  write_status

  while true; do
    with_lock handle_requests

    if [ $(( $(now) - LAST_CHECK )) -ge $(( INTERVAL_HOURS * 3600 )) ]; then
      with_lock take_snapshot daily; rc=$?
      record_check $rc
    fi

    last_ok=$(jfield "$VERIFY_LOG" verified_epoch '"ok":true'); last_ok=${last_ok:-0}
    if [ -s "$INDEX" ] && [ "$(now)" -ge "$next_verify" ] &&
       [ $(( $(now) - last_ok )) -ge $(( VERIFY_DAYS * 86400 )) ]; then
      # A failure is shown in the app and retried a day later, not every loop.
      with_lock verify_file || next_verify=$(( $(now) + 86400 ))
      write_status
    fi

    tick=$(( tick + 1 ))
    [ $(( tick % 200 )) -eq 0 ] && write_status   # heartbeat, about every 10 minutes
    sleep 3
  done
}

# ── main ────────────────────────────────────────────────────────────────────

cmd=${1:-help}; shift || true
case $cmd in
  daemon)       daemon ;;
  snapshot)     with_lock take_snapshot "${1:-manual}" || die "$SNAP_ERROR"
                if [ "$SNAP_UNCHANGED" = 1 ]; then echo "unchanged since $SNAP_FILE"; else echo "$SNAP_FILE"; fi ;;
  verify)       with_lock verify_file "${1:-}" ;;
  restore)      with_lock restore_live "${1:-}" ;;
  restore-side) with_lock restore_side "${1:-}" "${2:-}" ;;
  list)         [ -s "$INDEX" ] || die "no snapshots yet" 2
                sed 's/^{"file":"\([^"]*\)","taken_at":"\([^"]*\)","taken_epoch":[0-9]*,"reason":"\([^"]*\)","bytes":\([0-9]*\).*/\1  \2  \3  \4 bytes/' "$INDEX" ;;
  counts)       db_counts "${1:-$DB}" ;;
  *)            sed -n '5,13p' "$0" | sed 's/^# \{0,1\}//'; [ "$cmd" = help ] ;;
esac
