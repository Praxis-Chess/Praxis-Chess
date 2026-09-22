"""Does the spike model read its evidence, or make things up?

Run: python -m praxis_train.spike_eval --model praxis-phase1 --compare qwen2.5:7b

The real evaluation harness is §16 and arrives with the verifier. This measures
the two things Phase 1 can honestly check without one, both taken from
`reports/baseline.md` so the numbers line up with what shipped today:

  invented squares — a square named in the answer that appears nowhere in the
      evidence block. The baseline's model called `Bg5` "a fork on f6" with
      nothing to support it; this counts that class of error directly.

  stock opening rate — the share of answers that begin with the same five words.
      The shipped system sits at 95.1%.

Neither is a quality score. A model that says "Nf3 is bad" every time scores
perfectly on both. They are floor conditions: fail either and nothing else about
the answer matters.
"""

from __future__ import annotations

import argparse
import json
import re
import statistics
import time
import urllib.request
from collections import Counter
from pathlib import Path

SQUARE = re.compile(r"\b[a-h][1-8]\b")
OLLAMA = "http://localhost:11434/api/generate"


def generate(model: str, prompt: str, timeout: int = 300) -> tuple[str, float]:
    body = json.dumps(
        {
            "model": model,
            "prompt": prompt,
            "stream": False,
            "options": {"temperature": 0, "top_p": 1, "num_ctx": 2048, "num_predict": 128},
        }
    ).encode()
    req = urllib.request.Request(OLLAMA, body, {"Content-Type": "application/json"})
    started = time.time()
    with urllib.request.urlopen(req, timeout=timeout) as r:
        payload = json.load(r)
    return payload.get("response", "").strip(), time.time() - started


def score(rows: list[dict], answers: list[str], latencies: list[float]) -> dict:
    invented = 0
    grounded = 0
    for row, answer in zip(rows, answers):
        evidence = set(SQUARE.findall(row["messages"][0]["content"]))
        said = set(SQUARE.findall(answer))
        if said - evidence:
            invented += 1
        if said & evidence:
            grounded += 1

    stems = Counter(" ".join(a.split()[:5]).lower() for a in answers if a)
    n = max(1, len(answers))
    return {
        "answers": len(answers),
        "invented_square_rate": round(invented / n, 3),
        "cites_evidence_square_rate": round(grounded / n, 3),
        "stock_opening_rate": round((stems.most_common(1)[0][1] / n) if stems else 0.0, 3),
        "median_seconds": round(statistics.median(latencies), 2) if latencies else None,
        "mean_chars": round(sum(len(a) for a in answers) / n),
        "empty": sum(1 for a in answers if not a),
    }


def run(model: str, rows: list[dict]) -> tuple[dict, list[str]]:
    answers, latencies = [], []
    for row in rows:
        answer, seconds = generate(model, row["messages"][0]["content"])
        answers.append(answer)
        latencies.append(seconds)
    return score(rows, answers, latencies), answers


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--model", default="praxis-phase1")
    ap.add_argument("--compare", nargs="*", default=[])
    ap.add_argument("--val", type=Path, default=Path("data/phase1/val.jsonl"))
    ap.add_argument("--out", type=Path, default=Path("reports/phase1_spike.json"))
    ap.add_argument("--show", type=int, default=4)
    args = ap.parse_args()

    rows = [json.loads(line) for line in args.val.read_text(encoding="utf-8").splitlines() if line]

    results: dict[str, dict] = {}
    samples: dict[str, list[str]] = {}
    for model in [args.model, *args.compare]:
        print(f"--- {model} ---", flush=True)
        results[model], samples[model] = run(model, rows)
        print(json.dumps(results[model], indent=2), flush=True)

    for i in range(min(args.show, len(rows))):
        print(f"\n=== case {i + 1} [{rows[i]['meta']['mechanism']}] ===")
        print("target:", rows[i]["messages"][1]["content"])
        for model in samples:
            print(f"{model}:", samples[model][i])

    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(
        json.dumps(
            {
                "val_rows": len(rows),
                "results": results,
                "samples": {m: a[: args.show] for m, a in samples.items()},
                "targets": [r["messages"][1]["content"] for r in rows[: args.show]],
            },
            indent=2,
        ),
        encoding="utf-8",
    )


if __name__ == "__main__":
    main()
