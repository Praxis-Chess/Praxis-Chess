#!/bin/bash
# Phase 7: the LoRA grid on a rented GPU, one arm after another, unattended.
#
#   RUPEES_PER_HOUR=48 bash training/tools/pod_grid.sh             # every arm, in config/grid_v1/ORDER
#   ARMS="2b-r3 2b-r1" RUPEES_PER_HOUR=48 bash training/tools/pod_grid.sh
#   PILOT=1 bash training/tools/pod_grid.sh                        # 30 steps of 2b-r3 and 4b-r3: time and memory
#   python training/tools/grid_status.py                           # progress, from another terminal
#
# Per arm: train 2 epochs (train_sft) -> choose the epoch by the verifier on 200
# validation rows (select_checkpoint) -> merge -> GGUF -> q4_K_M (export) ->
# collect into results/grid_v1/<arm>/ -> DONE. Resumable: a finished arm is
# skipped, and an interrupted one starts again from its training.
#
# Only public rows are here: the player's games (the T_C test set) never leave
# the laptop, and the grid is evaluated there (PREREGISTRATION.md §4).
set -uo pipefail
W=/workspace
REPO=$W/Praxis-Chess
cd $REPO/training
# shellcheck disable=SC1091
source $W/venv/bin/activate
export HF_HOME=$W/hf PYTHONPATH=src LOMBOK_JAR=$REPO/training/.javabuild/lib/lombok.jar
# Variable-length batches fragment the allocator; this lets freed blocks be reused.
export PYTORCH_CUDA_ALLOC_CONF=expandable_segments:True
RESULTS=$W/results/grid_v1
LOG=$RESULTS/grid_log.tsv
RATE="${RUPEES_PER_HOUR:-0}"
mkdir -p "$RESULTS"
[ -f "$LOG" ] || printf 'arm\tstarted\tfinished\tseconds\trupees\tgpu\tgit\tstatus\n' > "$LOG"
GPU="$(nvidia-smi --query-gpu=name --format=csv,noheader | head -1)"
GIT="$(git -C $REPO rev-parse --short HEAD)"

if [ "${PILOT:-0}" = "1" ]; then
    # 2b-r3 end to end on a 30-step adapter (training, selection by the verifier
    # on 16 rows, merge, q4_K_M), so every stage is proven before the long run;
    # 4b-r3 trains only, for its time and memory. The pilot's files are removed.
    set -e
    for arm in 2b-r3 4b-r3; do
        echo "=== pilot $arm (30 steps) ==="
        python -m praxis_train.train_sft --config config/grid_v1/$arm.yaml --max-steps 30 2>&1 | tail -25
        cp outputs/grid_v1/$arm/lora-pilot/train_report.json "$RESULTS/pilot-$arm.json"
        # PILOT_TRAIN_ONLY=1: time and memory only (selection and export already proven).
        if [ $arm = 2b-r3 ] && [ "${PILOT_TRAIN_ONLY:-0}" != 1 ]; then
            echo "=== pilot $arm: selection (16 rows) and export ==="
            python -m praxis_train.select_checkpoint --config config/grid_v1/$arm.yaml \
                --adapters outputs/grid_v1/$arm/lora-pilot --rows 16 2>&1 | tail -12
            python -m praxis_train.export --config config/grid_v1/$arm.yaml --outtype Q4_K_M \
                --adapter outputs/grid_v1/$arm/lora-pilot/selected \
                --quantize $W/llama.cpp/build/bin/llama-quantize --llama-cpp $W/llama.cpp --relative --merge-on-gpu 2>&1 | tail -12
            ls -la outputs/grid_v1/$arm/*.gguf outputs/grid_v1/$arm/*.Modelfile
        fi
        rm -rf outputs/grid_v1/$arm/lora-pilot outputs/grid_v1/$arm/merged outputs/grid_v1/$arm/*.gguf
    done
    set +e
    python - <<'EOF'
import json, glob
for f in sorted(glob.glob("/workspace/results/grid_v1/pilot-*.json")):
    r = json.load(open(f))
    print(f"{f.split('pilot-')[1][:-5]:6s} {r['seconds_per_step']} s/step  peak {r['peak_vram_gb']} GB  "
          f"full 2 epochs ~{r['projected_seconds_full'] / 3600:.1f} h  kernels: {r['kernel_backend']}")
EOF
    exit 0
fi

ARMS="${ARMS:-$(cat config/grid_v1/ORDER)}"
for arm in $ARMS; do
    out=outputs/grid_v1/$arm
    dest=$RESULTS/$arm
    if [ -f "$dest/DONE" ]; then echo "=== $arm: done, skipping"; continue; fi
    mkdir -p "$out" "$dest"
    start=$(date +%s)
    echo "=== $arm: started $(date -u +%FT%TZ)" | tee -a "$RESULTS/grid.log"
    status=ok
    {
        rm -rf "$out/lora" &&
        python -m praxis_train.train_sft --config config/grid_v1/$arm.yaml &&
        python -m praxis_train.select_checkpoint --config config/grid_v1/$arm.yaml &&
        python -m praxis_train.export --config config/grid_v1/$arm.yaml --outtype Q4_K_M \
            --quantize $W/llama.cpp/build/bin/llama-quantize --llama-cpp $W/llama.cpp --relative --merge-on-gpu \
            > "$out/export_report.json"
    } >> "$dest/run.log" 2>&1 || status=failed
    end=$(date +%s)
    secs=$((end - start))
    rupees=$(python -c "print(round($RATE * $secs / 3600, 1))")
    if [ "$status" = ok ]; then
        cp "$out"/*.gguf "$out"/*.Modelfile "$dest"/
        cp "$out/lora/train_report.json" "$out/lora/selection.json" "$out/export_report.json" "$dest"/
        # The chosen adapter too (small): the bf16 side of the quantisation delta,
        # and what a Hub release publishes.
        rm -rf "$dest/adapter" && cp -r "$out/lora/selected" "$dest/adapter"
        cp -r "$out/lora/select" "$dest/selection_answers"
        rm -rf "$out/merged" "$out"/*.gguf       # the volume holds ~60 GB; merged weights are ~4-8 GB each
        touch "$dest/DONE"
    fi
    printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' "$arm" "$(date -u -d @$start +%FT%TZ)" "$(date -u -d @$end +%FT%TZ)" \
        "$secs" "$rupees" "$GPU" "$GIT" "$status" >> "$LOG"
    echo "=== $arm: $status in $((secs / 60)) min (~Rs $rupees)" | tee -a "$RESULTS/grid.log"
done
echo "=== grid finished. Download $RESULTS (see training/README.md, Phase 7)."
