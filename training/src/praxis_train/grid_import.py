"""Phase 8: load the downloaded grid into Ollama.

    python -m praxis_train.grid_import [--results outputs/grid_v1_results]

Expects the pod's results/grid_v1/<arm>/ folders copied to --results: each holds
a q4_K_M GGUF and a Modelfile whose FROM is relative ("./<file>.gguf"), so
`ollama create` runs inside the folder. Checks every arm finished on the pod
(its DONE marker) and was chosen by the verifier (selection.json) before loading.
"""

from __future__ import annotations

import argparse
import json
import subprocess
from pathlib import Path

from praxis_train.grid import arms


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--results", type=Path, default=Path("outputs/grid_v1_results"))
    args = ap.parse_args()
    missing = []
    for a in arms():
        folder = args.results / a.name
        modelfile = folder / f"{a.model}.Modelfile"
        if not (folder / "DONE").exists() or not modelfile.exists() or not list(folder.glob("*.gguf")):
            missing.append(a.name)
            print(f"MISSING  {a.name}: no finished run in {folder}")
            continue
        selection = json.loads((folder / "selection.json").read_text(encoding="utf-8"))
        subprocess.run(["ollama", "create", a.model, "-f", modelfile.name], cwd=folder, check=True,
                       stdout=subprocess.DEVNULL)
        prec = {e: r["claim_precision"] for e, r in selection["epochs"].items()}
        print(f"loaded   {a.model}  (selected {selection['selected']}; validation claim precision {prec})")
    if missing:
        print(f"{len(missing)} arm(s) missing: {', '.join(missing)} (PREREGISTRATION.md §10: reported as missing)")


if __name__ == "__main__":
    main()
