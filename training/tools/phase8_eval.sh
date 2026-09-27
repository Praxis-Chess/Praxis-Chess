#!/bin/bash
# Phase 8 on the laptop: the downloaded grid, examined and judged (PREREGISTRATION.md).
#
#   bash training/tools/phase8_eval.sh import     # load the 11 GGUFs into Ollama (~5 min)
#   bash training/tools/phase8_eval.sh tc         # every arm on the player's 890 mistakes (~1 day, GPU)
#   bash training/tools/phase8_eval.sh ab         # 2B-R3 on the 838 held-out positions (H4 pools them)
#   bash training/tools/phase8_eval.sh quant      # the q4_K_M half of the quantisation delta (300 T_AB)
#   bash training/tools/phase8_eval.sh verify     # the verifier over everything answered so far
#   bash training/tools/phase8_eval.sh fresh      # labels made after prereg-v1 (backend running)
#   bash training/tools/phase8_eval.sh report     # -> training/reports/grid_v1.md
#   python training/tools/phase5_status.py training/results_private/phase8/grid_tc    (progress)
#
# Every run resumes where it stopped. The player's games are answered here, on
# this machine, and nowhere else (§4). AB_ARMS="lora-2b-r1,..." adds more arms to
# the T_AB replication when there is time; only 2B-R3 is needed for a verdict.
set -euo pipefail
cd "$(dirname "$0")/.."
export PYTHONPATH=src
OUT=results_private/phase8
ALL=$(python -c "from praxis_train.grid import arms; print(','.join(a.system for a in arms()))")

case "${1:-}" in
  import)
    python -m praxis_train.grid_import --results outputs/grid_v1_results ;;
  tc)
    python -m praxis_train.baselines run --data data/phase5 --run-dir $OUT/grid_tc --arms "$ALL" \
        --testset data/phase5/testset_ablations.jsonl ;;
  ab)
    python -m praxis_train.baselines run --data data/phase5 --run-dir $OUT/grid_ab --arms "${AB_ARMS:-lora-2b-r3}" \
        --testset data/phase6_v1/test_ab_ablations.jsonl ;;
  quant)
    python -m praxis_train.baselines run --data data/phase5 --run-dir $OUT/quant --arms gguf-2b-r3-free \
        --testset data/phase6_v1/test_ab.jsonl
    # The bf16 half comes from the rented GPU (quant_delta.py); both are judged together.
    if [ -f outputs/grid_v1_results/quant_delta_bf16.jsonl ]; then
        cat outputs/grid_v1_results/quant_delta_bf16.jsonl $OUT/quant/outputs.jsonl > $OUT/quant/both.jsonl
    fi ;;
  verify)
    [ -f $OUT/grid_tc/outputs.jsonl ] && bash tools/javacli.sh EvalCli verify --testset data/phase5/testset_ablations.jsonl \
        --outputs $OUT/grid_tc/outputs.jsonl --out $OUT/grid_tc/verified.jsonl
    [ -f $OUT/grid_ab/outputs.jsonl ] && bash tools/javacli.sh EvalCli verify --testset data/phase6_v1/test_ab_ablations.jsonl \
        --outputs $OUT/grid_ab/outputs.jsonl --out $OUT/grid_ab/verified.jsonl
    [ -f $OUT/quant/both.jsonl ] && bash tools/javacli.sh EvalCli verify --testset data/phase6_v1/test_ab.jsonl \
        --outputs $OUT/quant/both.jsonl --out $OUT/quant/verified.jsonl
    true ;;
  fresh)
    python -m praxis_train.fresh_labels ;;
  report)
    python tools/prereg_hashes.py --check
    python -m praxis_train.grid_report ;;
  *)
    sed -n '2,15p' "$0"; exit 2 ;;
esac
