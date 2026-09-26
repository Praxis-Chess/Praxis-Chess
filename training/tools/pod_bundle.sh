#!/bin/bash
# Phase 7, on the laptop: pack the data a rented GPU needs, and nothing else.
#
#   bash training/tools/pod_bundle.sh        -> training/pod_bundle/praxis_bundle.tar.gz
#
# In: dataset v1 train/val (public: slices A and B), the ablated R3 train/val,
# the 200 validation rows' graphs for choosing checkpoints, and the jars the
# verifier compiles against. Never in: the player's test set (T_C), the Phase 5
# answers on it, or anything under results_private/. The script refuses to
# finish if any of those slipped in.
set -euo pipefail
cd "$(dirname "$0")/.."
OUT=pod_bundle
mkdir -p "$OUT" data/phase7

# The 200 validation rows every arm's checkpoint is chosen on: the same ids at
# every R level and ablation (they are the first 200 of one shuffled list).
python - <<'EOF'
import json
ids = []
for line in open("data/phase6_v1/R3/val.jsonl", encoding="utf-8"):
    ids.append(json.loads(line)["meta"]["source_id"])
    if len(ids) == 200:
        break
for lvl in ["R0", "R1", "R2"]:
    with open(f"data/phase6_v1/{lvl}/val.jsonl", encoding="utf-8") as f:
        first = [json.loads(next(f))["meta"]["source_id"] for _ in range(200)]
    assert first == ids, f"{lvl} val order differs from R3"
want, rows = set(ids), []
for line in open("data/phase6/gold.jsonl", encoding="utf-8"):
    r = json.loads(line)
    if r["id"] in want:
        assert r["slice"] in ("A", "B"), r["id"]
        rows.append(line)
with open("data/phase7/val_gold.jsonl", "w", encoding="utf-8") as w:
    w.writelines(rows)
print(f"val_gold.jsonl: {len(rows)} graphs")
EOF

LOMBOK="${LOMBOK_JAR:-$HOME/.m2/repository/org/projectlombok/lombok/1.18.38/lombok-1.18.38.jar}"
cp "$LOMBOK" .javabuild/lib/lombok.jar

tar -czf "$OUT/praxis_bundle.tar.gz" \
    data/phase6_v1/manifest.json \
    data/phase6_v1/R0 data/phase6_v1/R1 data/phase6_v1/R2 data/phase6_v1/R3 \
    data/phase7/ablations data/phase7/val_gold.jsonl \
    .javabuild/lib

# The guard: nothing private in the archive.
if tar -tzf "$OUT/praxis_bundle.tar.gz" | grep -E 'testset|results_private|phase5/|slice_c'; then
    echo "REFUSING: private files in the bundle" >&2; rm "$OUT/praxis_bundle.tar.gz"; exit 1
fi
ls -la "$OUT/praxis_bundle.tar.gz"
echo "Upload it to the pod's /workspace (Jupyter: drag it into the file browser at /workspace)."
