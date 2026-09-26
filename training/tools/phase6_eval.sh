#!/bin/sh
# Phase 6: the trained dev model on both test sets, verified and scored.
#
#   bash training/tools/phase6_eval.sh            (from the repo root)
#   python training/tools/phase5_status.py training/results_private/phase6/dev_c    (watch)
#   python training/tools/phase5_status.py training/results_private/phase6/dev_ab
#
# Resumable: a rerun skips every answer already written. Needs Ollama running
# with praxis-phase6-dev created (see the README's Phase 6 table).
set -e
cd "$(dirname "$0")/../.."
ARM="${ARM:-praxis-0p8b-dev}"
OUT=training/results_private/phase6

# 1. The player's own mistakes: the Phase 5 test set, paired with the baselines.
PYTHONPATH=training/src python -m praxis_train.baselines run \
    --run-dir "$OUT/dev_c" --arms "$ARM"
# 2. Held-out Lichess positions.
PYTHONPATH=training/src python -m praxis_train.baselines run \
    --run-dir "$OUT/dev_ab" --arms "$ARM" --testset training/data/phase6/test_ab.jsonl

bash training/tools/javacli.sh EvalCli verify --testset training/data/phase5/testset.jsonl \
    --outputs "$OUT/dev_c/outputs.jsonl" --out "$OUT/dev_c/verified.jsonl"
bash training/tools/javacli.sh EvalCli verify --testset training/data/phase6/test_ab.jsonl \
    --outputs "$OUT/dev_ab/outputs.jsonl" --out "$OUT/dev_ab/verified.jsonl"

PYTHONPATH=training/src python -m praxis_train.trained_metrics --arm "$ARM" \
    --c-run "$OUT/dev_c" --ab-run "$OUT/dev_ab" --out training/reports/phase6_dev_eval.md
