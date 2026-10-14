#!/usr/bin/env bash
#
# scan-image.sh IMAGE [CANARY]
#
# Fails if a built image carries anything secret. Run by .github/workflows/
# docker.yml on every image BEFORE it is pushed: a registry keeps layers even
# after a tag is deleted, so the only safe moment to catch a leak is before
# the push.
#
#   1. Forbidden files   application.yml outside /app/config, .env, private
#                        keys, credential stores — anywhere in the filesystem,
#                        and application.yml inside the Spring Boot JAR.
#   2. Image metadata    environment variables and labels whose name says
#                        secret (KEY, TOKEN, SECRET, PASSWORD…) with a value.
#   3. Build history     every layer's command, where a build argument or an
#                        inline `export TOKEN=…` would survive.
#   4. Content           gitleaks over the exported filesystem, archives
#                        (the JAR) opened, with the repository's own rules.
#   5. Canary            optional: a string planted in a decoy secret file in
#                        the build context must not appear anywhere.
#
# Needs: docker, gitleaks, unzip, python3.

set -euo pipefail

IMAGE="$1"
CANARY="${2:-}"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
FAILED=0

fail() { printf '  [fail] %s\n' "$*"; FAILED=1; }
ok()   { printf '  [ ok ] %s\n' "$*"; }

echo "== scanning $IMAGE"

# The filesystem, flattened.
cid="$(docker create "$IMAGE")"
docker export "$cid" | tar -x -C "$WORK" --exclude='proc/*' --exclude='sys/*' --exclude='dev/*' 2>/dev/null || true
docker rm "$cid" >/dev/null

# ── 1. forbidden files ──────────────────────────────────────────────────────
found="$(cd "$WORK" && find . -type f \( \
      -name '.env' -o -name '*.env' -o -name 'application.yml' -o -name 'application-*.yml' \
   -o -name 'id_rsa' -o -name 'id_ed25519' -o -name '*.pem' -o -name '*.p12' -o -name '*.pfx' \
   -o -name '.npmrc' -o -name '.pypirc' -o -name '.netrc' -o -name '.git-credentials' \
   -o -name 'credentials.json' -o -name 'settings.yml' \) \
   -not -path './app/config/application.yml' \
   -not -path './etc/ssl/*' -not -path './usr/share/ca-certificates/*' \
   -not -path './usr/lib/ssl/*' -not -path './usr/local/lib/python*/site-packages/*' \
   -not -path './usr/lib/python3*/*' -not -path './opt/java/openjdk/*' 2>/dev/null || true)"
if [ -n "$found" ]; then
  fail "files that should never be in an image:"; printf '         %s\n' $found
else
  ok "no secret-shaped files"
fi

if [ -f "$WORK/app/app.jar" ]; then
  if unzip -l "$WORK/app/app.jar" | grep -E 'BOOT-INF/classes/(application(-[^/]*)?\.ya?ml|\.env)$'; then
    fail "the JAR carries a classpath application config"
  else
    ok "the JAR carries no application config"
  fi
fi

# ── 2. image metadata ───────────────────────────────────────────────────────
docker image inspect "$IMAGE" --format '{{json .Config}}' > "$WORK/.config.json"
python3 - "$WORK/.config.json" <<'PY' || FAILED=1
import json, re, sys
cfg = json.load(open(sys.argv[1]))
secretish = re.compile(r'(KEY|TOKEN|SECRET|PASSWORD|PASSWD|CREDENTIAL|PRIVATE)', re.I)
# Public by design: the official python images set GPG_KEY to the fingerprint
# of the CPython release signing key, which is published for verification.
PUBLIC = {'GPG_KEY'}
bad = []
for kv in cfg.get('Env') or []:
    k, _, v = kv.partition('=')
    if k in PUBLIC:
        continue
    if secretish.search(k) and v.strip():
        bad.append('env ' + k)
for k, v in (cfg.get('Labels') or {}).items():
    if secretish.search(k) and str(v).strip():
        bad.append('label ' + k)
if bad:
    print('  [fail] secret-named settings with values: ' + ', '.join(bad))
    sys.exit(1)
print('  [ ok ] no secret-named environment variables or labels')
PY

# ── 3. build history ────────────────────────────────────────────────────────
if docker history --no-trunc --format '{{.CreatedBy}}' "$IMAGE" \
   | grep -Ei '(api[_-]?key|token|secret|password)[[:space:]]*[=:][[:space:]]*[^[:space:]$"'\'']{8,}'; then
  fail "a layer command carries a secret-looking value (above)"
else
  ok "no secret-looking values in layer commands"
fi

# ── 4. content ──────────────────────────────────────────────────────────────
CONFIG=()
[ -f .gitleaks.toml ] && CONFIG=(--config .gitleaks.toml)
# OS packages and the JDK ship test keys and certificates that are public by
# design; the app, its config and its scripts are what this is about.
for dir in app usr/local/bin usr/share/doc/stockfish; do
  [ -d "$WORK/$dir" ] || continue
  if gitleaks dir "$WORK/$dir" "${CONFIG[@]}" --max-archive-depth 3 --redact --no-banner --exit-code 1; then
    ok "gitleaks: /$dir clean"
  else
    fail "gitleaks found something in /$dir (redacted above)"
  fi
done

# ── 5. canary ───────────────────────────────────────────────────────────────
if [ -n "$CANARY" ]; then
  if grep -rIl --binary-files=without-match "$CANARY" "$WORK" 2>/dev/null | head -5 | grep -q . ; then
    fail "the canary from the decoy secret file reached the image"
  elif [ -f "$WORK/app/app.jar" ] && unzip -p "$WORK/app/app.jar" 2>/dev/null | grep -aq "$CANARY"; then
    fail "the canary from the decoy secret file is inside the JAR"
  else
    ok "canary absent: .dockerignore kept the decoy out"
  fi
fi

if [ "$FAILED" -ne 0 ]; then
  echo "== $IMAGE: NOT safe to publish"
  exit 1
fi
echo "== $IMAGE: clean"
