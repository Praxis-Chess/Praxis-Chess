"""Adapter -> merged weights -> GGUF -> an Ollama Modelfile.

Run: python -m praxis_train.export --config config/dev_0p8b.yaml

This is the half of Phase 1 that actually answers the go/no-go question. Training
a LoRA proves very little on its own; what decides whether Qwen3.5 can carry the
project is whether the result converts and loads in the runtime Praxis already
speaks to. Each stage is separately checkable, so a failure names itself.

Quantisation is q8_0, not the plan's q4_K_M: `convert_hf_to_gguf.py` writes
f32/f16/bf16/q8_0 directly, and anything else needs the compiled `llama-quantize`
binary — a C++ build this box has no toolchain for. At 0.8B, q8_0 is ~800 MB and
fits the 4 GB card with room to spare, so the spike loses nothing by it. The
real runs quantise on RunPod, where the toolchain exists.
"""

from __future__ import annotations

import argparse
import json
import subprocess
import sys
import time
from pathlib import Path

import torch
import yaml

LLAMA_CPP_REPO = "https://github.com/ggml-org/llama.cpp"


def load_config(path: Path) -> dict:
    return yaml.safe_load(path.read_text(encoding="utf-8"))


def merge(cfg: dict) -> Path:
    """Fold the LoRA into the base weights. GGUF has no concept of an adapter."""
    from peft import PeftModel
    from transformers import AutoModelForCausalLM, AutoTokenizer

    base_name = cfg["model"]["base"]
    adapter = Path(cfg["output"]["adapter_dir"])
    merged = Path(cfg["output"]["merged_dir"])

    # On the CPU in fp16: merging needs the whole model resident, and the 4 GB
    # card is better spent on the training run than on an arithmetic step.
    base = AutoModelForCausalLM.from_pretrained(
        base_name, revision=cfg["model"]["revision"], dtype=torch.float16, device_map="cpu"
    )
    model = PeftModel.from_pretrained(base, str(adapter))
    model = model.merge_and_unload()
    merged.mkdir(parents=True, exist_ok=True)
    model.save_pretrained(merged)
    AutoTokenizer.from_pretrained(str(adapter)).save_pretrained(merged)
    return merged


def ensure_llama_cpp(where: Path) -> Path:
    """Shallow-clone llama.cpp for its converter. Nothing here is compiled."""
    script = where / "convert_hf_to_gguf.py"
    if script.exists():
        return script
    where.parent.mkdir(parents=True, exist_ok=True)
    subprocess.run(
        ["git", "clone", "--depth", "1", LLAMA_CPP_REPO, str(where)],
        check=True,
    )
    return script


def to_gguf(merged: Path, out: Path, converter: Path, outtype: str = "q8_0") -> Path:
    out.parent.mkdir(parents=True, exist_ok=True)
    # Absolute: the converter runs from the llama.cpp checkout, so a path
    # relative to the training directory would resolve against the wrong root.
    merged, out = merged.resolve(), out.resolve()
    subprocess.run(
        [
            sys.executable,
            str(converter),
            str(merged),
            "--outfile",
            str(out),
            "--outtype",
            outtype,
            # This checkpoint has no multi-token-prediction block. Without the
            # flag the converter assumes one, writes block_count = layers + 1 and
            # a recurrent-layer pattern with a trailing entry, and the resulting
            # GGUF declares a 25th layer whose tensors do not exist. llama.cpp's
            # own loader tolerates it; Ollama refuses with "layer 24 missing
            # attn_qkv/attn_gate projections", which reads like a broken export
            # rather than an extra layer nobody asked for.
            "--no-mtp",
        ],
        check=True,
        cwd=str(converter.parent),
    )
    return out


def write_modelfile(cfg: dict, gguf: Path, windows_gguf: str) -> Path:
    """A Modelfile Ollama can build from.

    The FROM path is written in Windows form: Ollama runs as a Windows service
    here, and `/mnt/d/...` means nothing to it.

    TEMPLATE is pinned rather than inherited from the GGUF, and that is the whole
    reason this function exists. Qwen3.5 is a thinking model, so its own chat
    template opens a `<think>` block. TRL trained on the template's non-thinking
    form, where the block is opened and closed empty before the answer. Inherit
    the GGUF template and the model is asked to continue from a prefix it never
    saw in training: every reply comes back empty after one token, which looks
    exactly like a broken export and is not.

    No SYSTEM line, for the same reason — the training rows are a bare user turn
    and an assistant turn, so a system message at inference is a prompt format the
    adapter has never seen. The instruction lives in the rendered evidence block
    (`render.PROMPT_INSTRUCTION`) where training put it.
    """
    path = gguf.parent / "Modelfile"
    path.write_text(
        "\n".join(
            [
                f"FROM {windows_gguf}",
                "",
                "# The training format, exactly: a user turn, then an assistant turn",
                "# whose thinking block is already opened and closed.",
                'TEMPLATE """<|im_start|>user',
                "{{ .Prompt }}<|im_end|>",
                "<|im_start|>assistant",
                "<think>",
                "",
                "</think>",
                "",
                '"""',
                "",
                "# Deterministic: this model's job is to read an evidence block and",
                "# state what it contains. Sampling would invent the parts it is",
                "# meant to be citing.",
                "PARAMETER temperature 0",
                "PARAMETER top_p 1",
                "PARAMETER num_ctx 2048",
                'PARAMETER stop "<|im_end|>"',
                "",
            ]
        ),
        encoding="utf-8",
    )
    return path


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--config", type=Path, default=Path("config/dev_0p8b.yaml"))
    ap.add_argument("--llama-cpp", type=Path, default=Path.home() / "src" / "llama.cpp")
    ap.add_argument("--outtype", default="q8_0")
    ap.add_argument("--skip-merge", action="store_true")
    args = ap.parse_args()
    cfg = load_config(args.config)

    report: dict[str, object] = {}

    if args.skip_merge:
        merged = Path(cfg["output"]["merged_dir"])
    else:
        t0 = time.time()
        merged = merge(cfg)
        report["merge_seconds"] = round(time.time() - t0, 1)
    report["merged_dir"] = str(merged)

    converter = ensure_llama_cpp(args.llama_cpp)
    t0 = time.time()
    # The filename states the quantisation actually written, so a q8_0 file can
    # never be mistaken for the q4_K_M the plan's config names.
    gguf_path = Path(cfg["output"]["gguf"])
    gguf_path = gguf_path.with_name(
        gguf_path.name.replace("q4_k_m", args.outtype).replace(".gguf", "")
        + ("" if args.outtype in gguf_path.name else "")
        + ".gguf"
    )
    gguf = to_gguf(merged, gguf_path, converter, args.outtype)
    report["convert_seconds"] = round(time.time() - t0, 1)
    report["gguf"] = str(gguf)
    report["gguf_mb"] = round(gguf.stat().st_size / 1e6, 1)
    report["outtype"] = args.outtype

    # /mnt/d/Tanm/... -> D:\Tanm\...
    windows_gguf = str(gguf.resolve())
    if windows_gguf.startswith("/mnt/"):
        drive = windows_gguf[5]
        windows_gguf = drive.upper() + ":" + windows_gguf[6:].replace("/", "\\")
    modelfile = write_modelfile(cfg, gguf, windows_gguf)
    report["modelfile"] = str(modelfile)
    # Built by string, not pathlib: `windows_gguf` uses backslashes, which a
    # PosixPath treats as ordinary characters, collapsing the whole thing to a
    # bare filename.
    windows_modelfile = windows_gguf.rsplit("\\", 1)[0] + "\\Modelfile"
    report["ollama_create"] = (
        f'ollama create {cfg["output"]["ollama_model"]} -f "{windows_modelfile}"'
    )

    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    main()
