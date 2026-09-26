"""LoRA SFT: the 0.8B locally, and the Phase 7 grid (2B, 4B) on a rented GPU.

Run: python -m praxis_train.train_sft --config config/dev_0p8b.yaml
     python -m praxis_train.train_sft --config config/grid_v1/2b-r3.yaml --max-steps 30   (pilot)

Each epoch's adapter is saved to <adapter_dir>/epoch-N; select_checkpoint.py
chooses between them. A pilot (--max-steps) saves only its timing report.

The plan's training path is Unsloth (§12). This uses plain transformers + PEFT +
TRL instead, because Phase 1's job is a go/no-go on the *Qwen3.5* toolchain and
blocking that decision on a third-party kernel package's support for a new
architecture would answer the wrong question. The Unsloth extra is declared in
pyproject.toml and is a drop-in for this file once 2B training moves to RunPod.
"""

from __future__ import annotations

import argparse
import json
import os
import shutil
import time
from pathlib import Path


def _select_kernel_backend() -> str:
    """Pick the Hub (Triton) kernels or the pure-PyTorch fallback, before import.

    Qwen3.5 is a hybrid: 18 of its 24 layers are gated-delta-net linear attention,
    whose fast path is a Triton kernel fetched from the Hub. Triton JIT-compiles,
    so it needs a C compiler on PATH — and a stock WSL2 Ubuntu image has none,
    which fails at the first forward pass with "Failed to find C compiler", not at
    import, so it costs a model load and a dataset pass before it shows up.

    transformers ships readable torch implementations of the same operators and
    falls back to them when `USE_HUB_KERNELS` is off. They are much slower (the
    transformers source says more than an order of magnitude on an H100), which
    is fine for a 178-row spike and not fine for the real runs — so this reports
    which path it took rather than choosing silently.

    Must run before transformers is imported: the flag is read at import time.
    """
    if "USE_HUB_KERNELS" in os.environ:
        return f"USE_HUB_KERNELS={os.environ['USE_HUB_KERNELS']} (from the environment)"
    compiler = shutil.which("cc") or shutil.which("gcc") or os.environ.get("CC")
    if compiler:
        return f"Hub kernels enabled (C compiler: {compiler})"
    os.environ["USE_HUB_KERNELS"] = "NO"
    return "no C compiler found — using the torch fallback for linear attention (slower)"


_KERNEL_BACKEND = _select_kernel_backend()

import torch
import yaml
from datasets import load_dataset
from peft import LoraConfig, get_peft_model
from transformers import AutoModelForCausalLM, AutoTokenizer


def load_config(path: Path) -> dict:
    return yaml.safe_load(path.read_text(encoding="utf-8"))


def _load_base(cfg: dict):
    name = cfg["model"]["base"]
    tok = AutoTokenizer.from_pretrained(name, revision=cfg["model"]["revision"])
    model = AutoModelForCausalLM.from_pretrained(
        name,
        revision=cfg["model"]["revision"],
        dtype=getattr(torch, cfg["model"]["dtype"]),
        device_map={"": 0},
    )
    model.config.use_cache = False
    return model, tok


def as_prompt_completion(data):
    """Split each chat row into prompt (system + user) and completion (assistant).

    TRL masks the loss to the answer only for prompt-completion rows. For a
    plain `messages` row it trains on every token whatever `completion_only_loss`
    says, which is what the Phase 6 dev loop did: it learned to predict the
    evidence block as well as the answer. Found before the Phase 7 grid;
    PREREGISTRATION.md §3 requires loss on the answer only.
    """
    def split(row):
        return {"prompt": row["messages"][:-1], "completion": row["messages"][-1:]}
    return data.map(split, remove_columns=[c for c in data["train"].column_names if c not in ("prompt", "completion")])


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--config", type=Path, default=Path("config/dev_0p8b.yaml"))
    ap.add_argument("--max-steps", type=int, default=0,
                    help="pilot: stop after N optimiser steps, save nothing but the timing report")
    args = ap.parse_args()
    cfg = load_config(args.config)
    print(f"[kernels] {_KERNEL_BACKEND}")

    from transformers import TrainerCallback
    from trl import SFTConfig, SFTTrainer

    model, tok = _load_base(cfg)

    lora = LoraConfig(
        r=cfg["lora"]["r"],
        lora_alpha=cfg["lora"]["alpha"],
        lora_dropout=cfg["lora"]["dropout"],
        target_modules=cfg["lora"]["target_modules"],
        bias="none",
        task_type="CAUSAL_LM",
    )
    model = get_peft_model(model, lora)
    trainable = sum(p.numel() for p in model.parameters() if p.requires_grad)
    total = sum(p.numel() for p in model.parameters())
    print(f"trainable {trainable:,} / {total:,} ({100 * trainable / total:.2f}%)")

    data = load_dataset(
        "json",
        data_files={"train": cfg["data"]["train"], "validation": cfg["data"]["val"]},
    )
    data = as_prompt_completion(data) if cfg["train"]["completion_only"] else data

    t = cfg["train"]
    out = Path(cfg["output"]["adapter_dir"])
    if args.max_steps:
        out = out.with_name(out.name + "-pilot")

    # TRL 1.13 dropped `warmup_ratio` in favour of `warmup_steps`, so the ratio in
    # the config is resolved against the real schedule length here.
    effective_batch = t["per_device_batch_size"] * t["gradient_accumulation_steps"]
    steps_per_epoch = max(1, -(-len(data["train"]) // effective_batch))
    total_steps = steps_per_epoch * t["epochs"]
    warmup_steps = max(1, round(t["warmup_ratio"] * total_steps))
    print(f"{total_steps} optimiser steps ({steps_per_epoch}/epoch), warmup {warmup_steps}")

    sft = SFTConfig(
        output_dir=str(out),
        num_train_epochs=t["epochs"],
        per_device_train_batch_size=t["per_device_batch_size"],
        gradient_accumulation_steps=t["gradient_accumulation_steps"],
        learning_rate=t["learning_rate"],
        warmup_steps=warmup_steps,
        lr_scheduler_type=t["lr_scheduler_type"],
        logging_steps=t["logging_steps"],
        seed=t["seed"],
        gradient_checkpointing=t["gradient_checkpointing"],
        bf16=True,
        max_length=cfg["model"]["max_seq_length"],
        report_to=[],
        save_strategy="no",
        eval_strategy="no" if args.max_steps else "epoch",
        max_steps=args.max_steps or -1,
        # Supervise the answer only; the evidence block is context, not a target.
        completion_only_loss=t["completion_only"],
    )

    # Every epoch's adapter is kept: the checkpoint is chosen afterwards by the
    # verifier on generated validation answers, not by loss
    # (PREREGISTRATION.md §3; select_checkpoint.py).
    epoch_seconds: list[float] = []

    class SaveEachEpoch(TrainerCallback):
        def on_epoch_end(self, _args, state, control, **kwargs):
            if args.max_steps:
                return
            n = round(state.epoch)
            model.save_pretrained(out / f"epoch-{n}")
            tok.save_pretrained(out / f"epoch-{n}")
            epoch_seconds.append(round(time.time() - started, 1))

    trainer = SFTTrainer(
        model=model,
        args=sft,
        train_dataset=data["train"],
        eval_dataset=data["validation"],
        processing_class=tok,
        callbacks=[SaveEachEpoch()],
    )

    torch.cuda.reset_peak_memory_stats()
    started = time.time()
    result = trainer.train()
    seconds = time.time() - started
    peak_gb = torch.cuda.max_memory_allocated() / 1e9

    out.mkdir(parents=True, exist_ok=True)
    if not args.max_steps:
        model.save_pretrained(out)
        tok.save_pretrained(out)
    else:
        # The pilot's adapter, so the pilot can also exercise selection and export.
        model.save_pretrained(out / "epoch-1")
        tok.save_pretrained(out / "epoch-1")

    import peft
    import transformers
    import trl

    steps_done = int(trainer.state.global_step)
    report = {
        "base": cfg["model"]["base"],
        # The exact snapshot "main" resolved to, so a rerun can pin it.
        "base_revision": getattr(model.config, "_commit_hash", None),
        "kernel_backend": _KERNEL_BACKEND,
        "gpu": torch.cuda.get_device_name(0),
        "versions": {"torch": torch.__version__, "transformers": transformers.__version__,
                     "peft": peft.__version__, "trl": trl.__version__},
        "train_rows": len(data["train"]),
        "val_rows": len(data["validation"]),
        "epochs": t["epochs"],
        "trainable_params": trainable,
        "final_loss": round(float(result.training_loss), 4),
        "eval_loss": None if args.max_steps else round(float(trainer.evaluate()["eval_loss"]), 4),
        "total_steps": total_steps,
        "steps_done": steps_done,
        "seconds": round(seconds, 1),
        "seconds_per_step": round(seconds / max(1, steps_done), 2),
        "projected_seconds_full": round(seconds / max(1, steps_done) * total_steps),
        "epoch_end_seconds": epoch_seconds,
        "peak_vram_gb": round(peak_gb, 2),
        "adapter_dir": str(out),
    }
    (out / "train_report.json").write_text(json.dumps(report, indent=2), encoding="utf-8")
    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    main()
