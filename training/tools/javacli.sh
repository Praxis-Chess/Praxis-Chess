#!/bin/sh
# Run the backend's dataset tools (EvalCli, DatasetCli) from the command line.
#
#   bash training/tools/javacli.sh DatasetCli graphs --in ... --out ...
#
# Compiles the backend into training/.javabuild/classes the first time, and again
# whenever a Java source is newer than the last build. It never writes to the
# backend's target/ folder, so it cannot disturb the backend IntelliJ is running.
# Needs no network: the jars live in training/.javabuild/lib (gitignored).
#
# --stockfish defaults to STOCKFISH_PATH, else the path this machine uses.
set -e

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
BUILD="$ROOT/training/.javabuild"
STOCKFISH="${STOCKFISH_PATH:-D:/Tanm/stockfish-windows-x86-64-avx2/stockfish/stockfish-windows-x86-64-avx2.exe}"
LOMBOK="${LOMBOK_JAR:-$HOME/.m2/repository/org/projectlombok/lombok/1.18.38/lombok-1.18.38.jar}"

# Two homes: Git Bash on the Windows laptop, and Linux on a rented GPU (Phase 7,
# where the verifier chooses each checkpoint). cygpath exists only in Git Bash.
if command -v cygpath >/dev/null 2>&1; then
    SEP=";"
    JAVA_HOME_26="${JAVA_HOME_26:-/c/Program Files/Java/jdk-26}"
    JAVAC="$JAVA_HOME_26/bin/javac"; JAVA="$JAVA_HOME_26/bin/java"
else
    SEP=":"
    JAVAC="${JAVA_HOME:+$JAVA_HOME/bin/}javac"; JAVA="${JAVA_HOME:+$JAVA_HOME/bin/}java"
fi

# Windows tools want Windows paths.
winpath() { if command -v cygpath >/dev/null 2>&1; then cygpath -m "$1"; else printf '%s' "$1"; fi; }

SRC="$ROOT/backend/src/main/java"
STAMP="$BUILD/classes/.built"
if [ ! -f "$STAMP" ] || [ -n "$(find "$SRC" -name '*.java' -newer "$STAMP" -print -quit)" ]; then
    echo "[javacli] compiling the backend into training/.javabuild ..." >&2
    rm -rf "$BUILD/classes" && mkdir -p "$BUILD/classes"
    # Relative source paths, compiled from inside backend/: javac is a Windows
    # program and cannot read /d/... paths. A subshell, so the caller's working
    # folder (which their relative --in/--out paths depend on) is untouched.
    # -proc:full: JDK 23+ skips annotation processing by default, and without it
    # Lombok never runs and hundreds of getters "cannot be found".
    (cd "$ROOT/backend" \
        && find src/main/java -name '*.java' > "$BUILD/sources.txt" \
        && "$JAVAC" -nowarn --release 21 -proc:full \
            -cp "$(winpath "$BUILD/lib")/*${SEP}$(winpath "$LOMBOK")" \
            -d "$(winpath "$BUILD/classes")" @"$(winpath "$BUILD/sources.txt")" 2>&1 \
            | grep -v '^Note:\|^WARNING' || true)
    [ -d "$BUILD/classes/com" ] || { echo "[javacli] compile failed" >&2; exit 1; }
    touch "$STAMP"
fi

CLI="$1"; shift
case "$CLI" in
    EvalCli|DatasetCli) ;;
    *) echo "usage: javacli.sh EvalCli|DatasetCli <command> [--flag value ...]" >&2; exit 2 ;;
esac
# Add --stockfish unless the caller passed one; commands that don't need it ignore it.
case " $* " in *" --stockfish "*) EXTRA="" ;; *) EXTRA="--stockfish $STOCKFISH" ;; esac

CP="$(winpath "$BUILD/classes")${SEP}$(winpath "$ROOT/backend/src/main/resources")${SEP}$(winpath "$BUILD/lib")/*"
# shellcheck disable=SC2086
exec "$JAVA" -Dfile.encoding=UTF-8 -cp "$CP" "com.praxis.evidence.eval.$CLI" "$@" $EXTRA
