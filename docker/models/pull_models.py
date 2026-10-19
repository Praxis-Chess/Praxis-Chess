#!/usr/bin/env python3
"""pull_models — make sure every model Praxis is configured for exists in Ollama.

Run by the one-shot "models" service in docker/compose.yaml, then exits.

  Ollama models   pulled by name (PRAXIS_MOVE_MODEL, PRAXIS_REPORT_MODEL,
                  PRAXIS_REASONING_MODEL; duplicates once) through /api/pull,
                  which streams per-layer byte counts.
  commentary      PRAXIS_COMMENTARY_MODEL is BUILT, not pulled: its Modelfile
                  carries the training template, system message and stop
                  token, which `ollama pull hf.co/...` would drop. The GGUF and
                  Modelfile come from the pinned Hugging Face release, the GGUF
                  is checked against its published SHA-256, uploaded with
                  /api/blobs and assembled with /api/create. Empty: skipped.

Talks to Ollama only over HTTP, so this image needs Python and nothing else —
the ollama image it replaced weighed 3.6 GB for a CLI. Standard library only.

Safe to re-run: anything present is skipped, and an interrupted GGUF download
resumes. Progress goes to $PRAXIS_SETUP_DIR/models.json, which the app reads
to draw a bar per model.
"""
import hashlib
import json
import os
import re
import sys
import tempfile
import time
import urllib.error
import urllib.request

OLLAMA = os.environ.get("OLLAMA_HOST", "http://ollama:11434").rstrip("/")
STATUS_DIR = os.environ.get("PRAXIS_SETUP_DIR", "/setup")

COMMENTARY = os.environ.get("PRAXIS_COMMENTARY_MODEL", "praxis-grid-2b-r3")
HF_REPO = os.environ.get("PRAXIS_COMMENTARY_REPO", "praxis-chess/praxis-chess-reasoner-qwen3.5-2b-GGUF")
HF_REV = os.environ.get("PRAXIS_COMMENTARY_REVISION", "v1.0")
GGUF = "praxis-chess-reasoner-qwen3.5-2b-Q4_K_M.gguf"
# From the release's file listing (Hugging Face LFS oid) at revision v1.0.
GGUF_SHA256 = "120b35e4416217761dd050eca57d3013bd4206a677022922ed9f8b4372360770"
GGUF_BYTES = 1_274_396_192

UA = {"User-Agent": "praxis-chess-models/1.3"}


class Status:
    """One small JSON file, replaced atomically so the app never reads half of it."""

    def __init__(self, names):
        self.rows = {n: {"name": n, "state": "waiting", "completed": 0, "total": 0, "message": ""} for n in names}
        self.order = list(names)
        self._last = 0.0

    def set(self, name, force=True, **kw):
        self.rows[name].update(kw)
        now = time.monotonic()
        if force or now - self._last >= 1.0:
            self._last = now
            self.write()

    def write(self):
        os.makedirs(STATUS_DIR, exist_ok=True)
        body = {"updated": int(time.time()), "models": [self.rows[n] for n in self.order]}
        fd, tmp = tempfile.mkstemp(dir=STATUS_DIR, prefix=".models-")
        with os.fdopen(fd, "w", encoding="utf-8") as f:
            json.dump(body, f)
        os.chmod(tmp, 0o644)
        os.replace(tmp, os.path.join(STATUS_DIR, "models.json"))


def log(msg):
    print(f"[models] {msg}", flush=True)


def call(method, path, body=None, timeout=30, stream=False, data=None, headers=None):
    h = {"Content-Type": "application/json", **UA, **(headers or {})}
    payload = data if data is not None else (json.dumps(body).encode() if body is not None else None)
    req = urllib.request.Request(OLLAMA + path, data=payload, method=method, headers=h)
    res = urllib.request.urlopen(req, timeout=timeout)
    return res if stream else res.read()


def present(name):
    try:
        call("POST", "/api/show", {"model": name}, timeout=10)
        return True
    except urllib.error.HTTPError:
        return False


def pull(name, status):
    status.set(name, state="downloading", message="")
    log(f"pulling {name}")
    totals, done = {}, {}
    try:
        with call("POST", "/api/pull", {"model": name, "stream": True}, timeout=600, stream=True) as res:
            for raw in res:
                line = json.loads(raw.decode("utf-8") or "{}")
                if line.get("error"):
                    raise RuntimeError(line["error"])
                d = line.get("digest")
                if d and line.get("total"):
                    totals[d] = line["total"]
                    done[d] = line.get("completed", 0)
                status.set(name, force=False, total=sum(totals.values()), completed=sum(done.values()),
                           message=line.get("status", ""))
    except Exception as e:  # noqa: BLE001 — every failure is reported the same way
        status.set(name, state="failed", message=str(e))
        log(f"FAILED {name}: {e}")
        return False
    if not present(name):
        status.set(name, state="failed", message="pull finished but Ollama does not list the model")
        return False
    t = sum(totals.values())
    status.set(name, state="done", completed=t, total=t, message="")
    log(f"{name} ready")
    return True


def download(url, path, name, status, expected_bytes):
    """Resumable download, reporting bytes. Returns the file's SHA-256."""
    have = os.path.getsize(path) if os.path.exists(path) else 0
    sha = hashlib.sha256()
    if have:
        with open(path, "rb") as f:
            for chunk in iter(lambda: f.read(1 << 20), b""):
                sha.update(chunk)
    if have < expected_bytes:
        req = urllib.request.Request(url, headers={**UA, **({"Range": f"bytes={have}-"} if have else {})})
        with urllib.request.urlopen(req, timeout=120) as res:
            if have and res.status != 206:  # server ignored the range: start over
                have, sha = 0, hashlib.sha256()
                mode = "wb"
            else:
                mode = "ab" if have else "wb"
            with open(path, mode) as f:
                for chunk in iter(lambda: res.read(1 << 20), b""):
                    f.write(chunk)
                    sha.update(chunk)
                    have += len(chunk)
                    status.set(name, force=False, completed=have)
    status.set(name, completed=have)
    return sha.hexdigest()


def parse_modelfile(text):
    """TEMPLATE, SYSTEM and PARAMETER lines → the fields /api/create takes."""
    out, params = {}, {}
    for key in ("TEMPLATE", "SYSTEM"):
        m = re.search(rf'^{key}\s+"""(.*?)"""', text, re.S | re.M) or re.search(rf'^{key}\s+"(.*?)"\s*$', text, re.M)
        if m:
            out[key.lower()] = m.group(1)
    for k, v in re.findall(r"^PARAMETER\s+(\S+)\s+(.+?)\s*$", text, re.M):
        v = v.strip()
        if len(v) >= 2 and v[0] == v[-1] == '"':
            v = v[1:-1]
        if k == "stop":
            params.setdefault("stop", []).append(v)
            continue
        try:
            v = int(v)
        except ValueError:
            try:
                v = float(v)
            except ValueError:
                pass
        params[k] = v
    if params:
        out["parameters"] = params
    return out


def build_commentary(name, status):
    base = f"https://huggingface.co/{HF_REPO}/resolve/{HF_REV}"
    work = os.path.join(tempfile.gettempdir(), "praxis-commentary")
    os.makedirs(work, exist_ok=True)
    gguf_path = os.path.join(work, GGUF)
    digest = "sha256:" + GGUF_SHA256
    status.set(name, state="downloading", total=GGUF_BYTES, message=f"huggingface.co/{HF_REPO}@{HF_REV}")
    try:
        with urllib.request.urlopen(urllib.request.Request(base + "/Modelfile", headers=UA), timeout=60) as r:
            fields = parse_modelfile(r.read().decode("utf-8"))
        if "template" not in fields:
            raise RuntimeError("the release's Modelfile has no TEMPLATE")

        # Ollama may already hold this exact file (an earlier, interrupted run).
        try:
            call("HEAD", f"/api/blobs/{digest}", timeout=10)
            have_blob = True
        except urllib.error.HTTPError:
            have_blob = False

        if not have_blob:
            log(f"downloading {GGUF} ({HF_REPO}@{HF_REV})")
            got = download(f"{base}/{GGUF}", gguf_path, name, status, GGUF_BYTES)
            if got != GGUF_SHA256:
                os.remove(gguf_path)
                raise RuntimeError("downloaded file does not match the published SHA-256; deleted, re-run to retry")
            status.set(name, state="creating", message="uploading to Ollama")
            with open(gguf_path, "rb") as f:
                call("POST", f"/api/blobs/{digest}", data=f, timeout=1800,
                     headers={"Content-Type": "application/octet-stream", "Content-Length": str(GGUF_BYTES)})

        status.set(name, state="creating", completed=GGUF_BYTES, message="ollama create")
        log(f"creating {name}")
        call("POST", "/api/create", {"model": name, "files": {GGUF: digest}, "stream": False, **fields}, timeout=1800)
    except Exception as e:  # noqa: BLE001
        status.set(name, state="failed", message=str(e))
        log(f"FAILED {name}: {e}")
        return False
    finally:
        if os.path.exists(gguf_path) and present(name):
            os.remove(gguf_path)
    if not present(name):
        status.set(name, state="failed", message="create finished but Ollama does not list the model")
        return False
    status.set(name, state="done", message="")
    log(f"{name} ready")
    return True


def main():
    names = []
    for n in (os.environ.get("PRAXIS_MOVE_MODEL", "qwen3:4b-instruct"),
              os.environ.get("PRAXIS_REPORT_MODEL", "qwen3:4b-instruct"),
              os.environ.get("PRAXIS_REASONING_MODEL", "qwen3:4b-instruct"),
              COMMENTARY):
        if n and n not in names:
            names.append(n)
    status = Status(names)
    status.write()

    log(f"waiting for Ollama at {OLLAMA}")
    for _ in range(120):
        try:
            call("GET", "/api/version", timeout=3)
            break
        except Exception:  # noqa: BLE001
            time.sleep(1)
    else:
        for n in names:
            status.set(n, state="failed", message=f"Ollama not reachable at {OLLAMA}")
        log(f"Ollama not reachable at {OLLAMA}")
        return 1

    failures = 0
    for n in names:
        if present(n):
            status.set(n, state="present")
            log(f"{n} already present")
        elif n == COMMENTARY:
            failures += not build_commentary(n, status)
        else:
            failures += not pull(n, status)

    if failures:
        log(f"{failures} model(s) failed - see above. Re-run: docker compose -f docker/compose.yaml up models")
        return 1
    log("all models ready")
    return 0


if __name__ == "__main__":
    sys.exit(main())
