"""praxis_eval: the Praxis Chess diagnosis verifier, in Python.

A port of the Java verifier (DiagnosisVerifier, DiagnosisRules) that judged
every published number, so anyone can re-score answers without the Praxis
backend. Needs only python-chess.

    from praxis_eval import parse_diagnosis, verify
    report = verify(graph, parse_diagnosis(model_text))          # claim mode
    report.passed, report.claims, report.claims_true, report.violations

    python -m praxis_eval verify --testset test.jsonl --outputs outputs.jsonl --out verified.jsonl
"""

from praxis_eval.jackson import NotADiagnosis, parse_diagnosis
from praxis_eval.rules import diagnose, label
from praxis_eval.verifier import Report, Violation, verify

__all__ = ["NotADiagnosis", "Report", "Violation", "diagnose", "label", "parse_diagnosis", "verify"]
