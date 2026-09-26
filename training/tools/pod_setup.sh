#!/bin/bash
# Phase 7: prepare a rented GPU (RunPod, "Runpod Pytorch" template) for the grid.
#
#   cd /workspace && git clone --branch <BRANCH> https://github.com/praxis-chess/Praxis-Chess.git
#   (upload praxis_bundle.tar.gz to /workspace, then)
#   bash /workspace/Praxis-Chess/training/tools/pod_setup.sh
#
# Idempotent: rerun it after a Stop (the container disk, and so Java, is wiped;
# the Python environment, llama.cpp and the model cache live on /workspace).
set -euo pipefail
W=/workspace
REPO=$W/Praxis-Chess
export HF_HOME=$W/hf

echo "[setup] system packages (Java for the verifier, a compiler for llama.cpp and the Triton kernels)"
if ! command -v javac >/dev/null 2>&1 || ! command -v cmake >/dev/null 2>&1; then
    apt-get update -qq && apt-get install -y -qq openjdk-21-jdk-headless cmake build-essential >/dev/null
fi
javac -version

echo "[setup] Python environment on the volume, pinned to the laptop's working versions"
if [ ! -x $W/venv/bin/python ]; then python3 -m venv $W/venv; fi
# shellcheck disable=SC1091
source $W/venv/bin/activate
pip install -q --upgrade pip
# torch 2.14 is built for CUDA 13.0, not 12.8: deploy on a machine whose driver
# offers CUDA 13.0 or newer (the deploy page's CUDA filter).
pip install --progress-bar on torch==2.14.0 --index-url https://download.pytorch.org/whl/cu130
pip install transformers==5.17.0 peft==0.21.0 trl==1.13.0 datasets==5.0.1 accelerate==1.15.0 \
    pyyaml safetensors sentencepiece numpy
# The fast path for Qwen3.5's linear attention (the laptop never had it). Optional:
# without it training still runs, on the slow torch fallback, and train_report
# records which path was used.
pip install -q kernels flash-linear-attention || echo "[setup] fast kernels unavailable; the torch fallback will be used"
python -c "import torch; print('[setup] torch', torch.__version__, 'CUDA', torch.cuda.is_available(), torch.cuda.get_device_name(0))"

echo "[setup] llama.cpp: the converter, and llama-quantize for q4_K_M"
if [ ! -d $W/llama.cpp ]; then git clone -q --depth 1 https://github.com/ggml-org/llama.cpp $W/llama.cpp; fi
if [ ! -x $W/llama.cpp/build/bin/llama-quantize ]; then
    cmake -S $W/llama.cpp -B $W/llama.cpp/build -DGGML_CUDA=OFF -DLLAMA_CURL=OFF >/dev/null
    cmake --build $W/llama.cpp/build --target llama-quantize -j "$(nproc)" >/dev/null
fi
ls -la $W/llama.cpp/build/bin/llama-quantize

echo "[setup] data bundle"
if [ -f $W/praxis_bundle.tar.gz ] && [ ! -f $REPO/training/data/phase7/val_gold.jsonl ]; then
    # --no-same-owner: the archive carries the Windows user's id, and the pod's
    # volume refuses to hand files to an unknown user (tar then exits non-zero).
    tar --no-same-owner -xzf $W/praxis_bundle.tar.gz -C $REPO/training
fi
python $REPO/training/tools/prereg_hashes.py --check --present-only

echo "[setup] the verifier compiles and runs"
cd $REPO/training
export LOMBOK_JAR=$REPO/training/.javabuild/lib/lombok.jar
bash tools/javacli.sh EvalCli verify --testset data/phase7/val_gold.jsonl \
    --outputs /dev/null --out /tmp/verify_smoke.jsonl >/dev/null && echo "[setup] verifier OK"

echo "[setup] done. Pilot:  PILOT=1 bash training/tools/pod_grid.sh"
