#!/bin/bash
# Redo the 4B arms' checkpoint choice with the non-thinking prompt (on the pod).
#
#   cd /workspace/Praxis-Chess && git pull && bash training/tools/pod_reselect_4b.sh
#
# The first grid prompted the 4B in its template's default thinking mode, so both
# epochs scored 0 and epoch-2 won only by the tie rule. This regenerates the 200
# validation answers per epoch (select_checkpoint now passes enable_thinking=False),
# lets the verifier choose, and re-exports the q4_K_M only if the choice changes.
set -euo pipefail
W=/workspace
cd $W/Praxis-Chess/training
# shellcheck disable=SC1091
source $W/venv/bin/activate
export HF_HOME=$W/hf PYTHONPATH=src LOMBOK_JAR=$W/Praxis-Chess/training/.javabuild/lib/lombok.jar
export PYTORCH_CUDA_ALLOC_CONF=expandable_segments:True
RESULTS=$W/results/grid_v1
mkdir -p $W/results/reselect
for arm in 4b-r3 4b-r0; do
    out=outputs/grid_v1/$arm
    dest=$RESULTS/$arm
    before=$(python -c "import json; print(json.load(open('$dest/selection.json'))['selected'])")
    ls -d $out/lora/epoch-1 $out/lora/epoch-2 >/dev/null
    rm -rf $out/lora/select
    echo "=== $arm: choosing again (was $before by the tie rule)"
    python -m praxis_train.select_checkpoint --config config/grid_v1/$arm.yaml 2>&1 | grep -E "answers in|claim_precision|selected"
    after=$(python -c "import json; print(json.load(open('$out/lora/selection.json'))['selected'])")
    cp $out/lora/selection.json $dest/selection.json
    rm -rf $dest/selection_answers && cp -r $out/lora/select $dest/selection_answers
    if [ "$after" != "$before" ]; then
        echo "=== $arm: $after wins; re-exporting"
        python -m praxis_train.export --config config/grid_v1/$arm.yaml --outtype Q4_K_M \
            --quantize $W/llama.cpp/build/bin/llama-quantize --llama-cpp $W/llama.cpp --relative --merge-on-gpu \
            > $out/export_report.json 2>> $W/results/reselect/export.log
        cp $out/*.gguf $out/*.Modelfile $out/export_report.json $dest/
        rm -rf $dest/adapter && cp -r $out/lora/selected $dest/adapter
        ln -f $out/*.gguf $W/results/reselect/
        rm -rf $out/merged
    else
        echo "=== $arm: $after confirmed; the downloaded model stands"
    fi
done
cd $RESULTS && tar -cf $W/results/reselect/reselect_small.tar 4b-r3/selection.json 4b-r3/selection_answers \
    4b-r0/selection.json 4b-r0/selection_answers $(ls 4b-r3/export_report.json 4b-r0/export_report.json 2>/dev/null)
cd $W/results/reselect && sha256sum * > SHA256SUMS 2>/dev/null || true
ls -la $W/results/reselect
echo "=== done. Download everything in /workspace/results/reselect/"
