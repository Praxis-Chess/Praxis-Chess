"""The Phase 7 grid, read from its configs: one place that knows every arm.

Each arm's evaluation level follows from its training data: data/phase6_v1/R<n>
is R<n>; an ablation folder is R3 with that component removed, which the test
files carry as render levels "R3-CF", "R3-T", "R3-DELTA", "R3-D" (ablate.py).
"""

from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path

import yaml

CONFIGS = Path(__file__).resolve().parents[2] / "config" / "grid_v1"
ABLATION_LEVEL = {"nocf": "R3-CF", "not": "R3-T", "nodelta": "R3-DELTA", "nod": "R3-D"}


@dataclass(frozen=True)
class Arm:
    name: str          # "2b-r3"
    model: str         # the Ollama model, "praxis-grid-2b-r3"
    size: str          # "2B" or "4B"
    level: str         # the render level it trained on and is tested at
    rank: int

    @property
    def system(self) -> str:
        """The name used in outputs and reports: "lora-2b-r3"."""
        return f"lora-{self.name}"


def arms() -> list[Arm]:
    out = []
    for name in (CONFIGS / "ORDER").read_text().split():
        cfg = yaml.safe_load((CONFIGS / f"{name}.yaml").read_text(encoding="utf-8"))
        data = Path(cfg["data"]["train"]).parent        # .../R3 or .../ablations/nocf/R3
        level = ABLATION_LEVEL.get(data.parent.name, data.name)
        out.append(Arm(name, cfg["output"]["ollama_model"], cfg["model"]["base"].rsplit("-", 1)[1],
                       level, cfg["lora"]["r"]))
    return out


def arm(name: str) -> Arm:
    return next(a for a in arms() if a.name == name)
