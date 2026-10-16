# Praxis Chess with Docker

The short version is in the [README](../README.md#quick-start-docker). This
page covers the choices, upgrades, backups and what to do when something does
not start. Every command runs from the repository root.

## What runs

| Service | What it is | Published |
|---|---|---|
| `app` | Praxis: backend, web UI and Stockfish 19 | `127.0.0.1:8086` only |
| `postgres` | Your games, analyses and drills (volume `praxis_db`) | no |
| `ollama` | The language models (volume `praxis_models`) | no |
| `models` | One-shot: downloads the models, then exits | no |
| `voice` | Optional: Prax's voice (profile `voice`) | no |
| `searxng` | Optional: web research (profile `web`) | no |
| `backup` | Automatic backups into `backups/`, and restores | no |

This stack is separate from the developer setup in `docker-compose.yml`
(project `praxis`, its own containers and volumes), so both can live on one
machine. Don't run both apps at once: both want port 8086.

## Choosing how to start

```bash
# Build from this clone (always works)
docker compose -f docker/compose.yaml up -d --build

# Use published images instead (from v1.3.0 on)
docker compose -f docker/compose.yaml up -d

# NVIDIA GPU (Linux, or Windows with Docker Desktop's WSL 2 engine)
docker compose -f docker/compose.yaml -f docker/compose.gpu.yaml up -d

# Ollama installed on this computer (Mac; or reuse models you already have)
docker compose -f docker/compose.yaml -f docker/compose.host-ollama.yaml up -d
```

Use the same `-f` files for every later command (`ps`, `logs`, `down`), or
Compose will not know about the add-on. Optional settings go in `docker/.env`
— copy [`docker/.env.example`](.env.example), which explains each one.

## Watching the first start

The Today page shows a setup checklist with a bar per model. From a terminal:

```bash
docker compose -f docker/compose.yaml ps
docker compose -f docker/compose.yaml logs -f models
```

`models` ends with `all models ready` and exits with code 0. It is safe to
run again at any time; it skips what is already there:

```bash
docker compose -f docker/compose.yaml up models
```

## Upgrading

```bash
docker compose -f docker/compose.yaml exec backup backup snapshot   # first, always
git pull
docker compose -f docker/compose.yaml up -d --build       # or: pull, then up -d
```

The database schema updates itself on start. Your games, settings and models
stay in their volumes.

## Backups

The `backup` service runs whenever the stack does. Nothing to set up:

- **Daily.** Every hour it checks; when the last check is over 24 hours old it
  takes a snapshot. A laptop that was asleep catches up at the next check.
- **Only when something changed.** A day with no changes writes no file.
- **Before risky actions.** "Re-analyse" in the app takes one first, and so does
  a restore.
- **Tested.** Once a week the newest snapshot is restored into a scratch
  database and every table's row count is compared.
- **Never deleted.** Praxis never removes a snapshot. Thinning old ones is your
  decision, made by hand.

Snapshots are plain SQL, gzipped: `backups/praxis-YYYYMMDD-HHMMSS.sql.gz` (UTC),
each with a `.sha256` beside it. `backups/index.jsonl` lists every snapshot
with its row counts. Settings → Backups shows the same list, a "Back up now"
button, and the restore commands. Nothing leaves this computer. To keep them on
another drive, set `PRAXIS_BACKUP_DIR` in `docker/.env` (a second disk survives
a dead one; a folder on the same disk does not).

```bash
docker compose -f docker/compose.yaml exec backup backup list       # every snapshot
docker compose -f docker/compose.yaml exec backup backup snapshot   # one now
```

### Restoring

```bash
docker/backup/restore.sh praxis-20261012-090000.sql.gz          # replace the current data
docker/backup/restore.sh --side praxis-20261012-090000.sql.gz   # look at it beside the current data
```

A full restore stops the app, saves the current data as a new snapshot, loads
the file into a separate database, checks every table's row count, swaps it in,
starts the app, and prints the command that undoes it. `--side` loads the
snapshot into the database `praxis_restore` and changes nothing else.

The script needs bash (Git Bash on Windows). In PowerShell:

```powershell
docker compose -f docker/compose.yaml stop app
docker compose -f docker/compose.yaml exec backup backup restore praxis-20261012-090000.sql.gz
docker compose -f docker/compose.yaml start app
```

**Moving from the developer setup:** copy a snapshot from `backups/dev/` into
`backups/`, start only the database and the backup service
(`docker compose -f docker/compose.yaml up -d postgres backup`), restore it with
`docker/backup/restore.sh <file>`, then `docker compose -f docker/compose.yaml up -d`.

## Stopping and uninstalling

```bash
docker compose -f docker/compose.yaml down        # stop; keeps everything
docker compose -f docker/compose.yaml down -v     # ALSO DELETES your games and the downloaded models
```

## When something is wrong

The Setup section in **Settings** lists every check with its fix. The same
lines are in the app's log: `docker compose -f docker/compose.yaml logs app`.

| Symptom | Cause and fix |
|---|---|
| `models` exited with code 1 | Read `logs models`. Usually the network; run `up models` again — downloads resume. "does not match the published SHA-256": the commentary file was corrupted in transit; it is deleted, run again. |
| Explanations take minutes | Ollama is on the CPU. Add `compose.gpu.yaml` (NVIDIA), or on a Mac use `compose.host-ollama.yaml`. Check with `exec ollama ollama ps` — PROCESSOR should say GPU. |
| "could not select device driver nvidia" | No NVIDIA driver visible to Docker. Drop `compose.gpu.yaml`, or install the driver (Windows: a current NVIDIA driver plus Docker Desktop's WSL 2 engine). |
| Models fail to load, or the app is killed, on Windows | WSL 2 gives Docker half the machine's memory by default; the 7B model needs about 6 GB on the CPU. Raise it in `%UserProfile%\.wslconfig` (`[wsl2]` then `memory=12GB`), run `wsl --shutdown`, restart Docker Desktop. Or use the light model set. |
| `compose.host-ollama.yaml` on Linux: Ollama not reachable | Ollama listens on 127.0.0.1 by default, which containers can't reach on Linux. Set `OLLAMA_HOST=0.0.0.0` for the Ollama service and restart it. |
| Port 8086 is already in use | Another Praxis (the developer backend?) is running. Stop it, or set `PRAXIS_PORT=8090` in `docker/.env`. |
| Sync says to add a username | Settings → Player. |
| Practice streak rolls over at the wrong hour | Set `TZ=Your/Zone` in `docker/.env` (e.g. `TZ=Europe/London`). |

## Licences

Praxis is MIT. The app image contains Stockfish 19 (GPL-3.0), built unmodified
from <https://github.com/official-stockfish/Stockfish/tree/sf_19>; its licence
is at `/usr/share/doc/stockfish/COPYING` in the image. The models are
downloaded at first start under their own licences (Qwen: Apache-2.0; the
Praxis commentary model: Apache-2.0, see its Hugging Face card).
