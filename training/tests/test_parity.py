"""praxis_eval gives the Java verifier's verdict on every test vector.

    python -m pytest tests/test_parity.py        (from training/, with src on the path)
    python tests/test_parity.py                  (no pytest needed)

The vectors (tools/make_verifier_vectors.py) are real published answers and
answers broken on purpose, each judged by the Java verifier. Messages are not
compared: Java prints a HashSet in hash order, and the verdict cannot depend
on it.
"""

from __future__ import annotations

import gzip
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "src"))

from praxis_eval import NotADiagnosis, parse_diagnosis, verify  # noqa: E402

VECTORS = Path(__file__).with_name("verifier_test_vectors.jsonl.gz")


def vectors() -> list[dict]:
    with gzip.open(VECTORS, "rt", encoding="utf-8") as f:
        return [json.loads(line) for line in f if line.strip()]


def verdict(v: dict) -> dict:
    """What praxis_eval says about one vector, in the vector's terms."""
    try:
        d = parse_diagnosis(v["answer"])
        error = False
    except NotADiagnosis:
        d, error = None, True
    out = {"parsed": d is not None, "parse_error": error}
    if d is not None:
        claim = verify(v["graph"], d, "CLAIM")
        out.update(claim_passed=claim.passed, claims=claim.claims, claims_true=claim.claims_true,
                   violation_rules=sorted(x.rule for x in claim.violations))
        if v["level"] == "R3":
            out["citation_passed"] = verify(v["graph"], d, "CITATION").passed
    return out


def mismatches() -> list[tuple[str, dict, dict]]:
    return [(v["id"], v["expected"], got) for v in vectors() if (got := verdict(v)) != v["expected"]]


def test_every_vector_matches_the_java_verdict():
    bad = mismatches()
    assert not bad, f"{len(bad)} vectors differ; first: {bad[0]}"


def test_the_vectors_cover_every_rule_and_both_kinds():
    vs = vectors()
    rules = {r for v in vs for r in v["expected"].get("violation_rules", [])}
    assert rules >= {1, 2, 3, 4, 5, 6, 8}
    assert any(not v["expected"].get("citation_passed", True) for v in vs)            # rule 7
    assert any(v["expected"]["parse_error"] for v in vs)
    assert any(not v["expected"]["parsed"] and not v["expected"]["parse_error"] for v in vs)   # JSON null
    assert {v["kind"] for v in vs} == {"real", "mutated"}


if __name__ == "__main__":
    bad = mismatches()
    total = len(vectors())
    print(f"{total - len(bad)} / {total} vectors match the Java verifier")
    for vid, want, got in bad[:10]:
        print(" ", vid, "\n    java  ", want, "\n    python", got)
    sys.exit(1 if bad else 0)
