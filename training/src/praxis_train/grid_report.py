"""Phase 8: the pre-registered verdicts (PREREGISTRATION.md §6–§9) -> reports/grid_v1.md.

    python -m praxis_train.grid_report                 (defaults below; run from training/)

Reads verified answers only (EvalCli verify output), never raw model text:
  T_C, the grid:        results_private/phase8/grid_tc/verified.jsonl
  T_C, frozen Phase 5:  results_private/phase5/full/verified.jsonl   (prompted arms, S_rules)
  T_AB, the grid:       results_private/phase8/grid_ab/verified.jsonl
  fresh labels:         data/phase8/fresh_labels.jsonl                (fresh_labels.py)
  quantisation delta:   results_private/phase8/quant/verified.jsonl   (optional)

Everything here is written down in the pre-registration: the metric definitions
(§6), paired bootstrap with 1,000 resamples and seed 0 (§7), the H1–H5 tests
(§8) and the ship rules (a)–(d) (§9). A verdict needs every system it compares;
a missing arm makes it "not computable", never estimated (§10).
"""

from __future__ import annotations

import argparse
import json
import math
import random
import sys
from pathlib import Path

from praxis_train.baseline_metrics import MECHANISM_RULES, interval, latest, load_jsonl, pct, score_system
from praxis_train.grid import arms

BOOT, SEED = 1000, 0


# ── metrics over a set of items (§6) ─────────────────────────────────────────

def claim_precision(rows: dict, ids) -> float:
    num = den = 0
    for i in ids:
        r = rows[i]
        if r.get("parsed"):
            num += r.get("claims_true", 0)
            den += r.get("claims", 0)
    return num / den if den else 0.0


def mechanism_vs_rules(rows: dict, items: dict, ids) -> float:
    hits = [1.0 if rows[i].get("parsed") and rows[i].get("mechanism") == items[i]["rules"]["mechanism"] else 0.0
            for i in ids]
    return sum(hits) / len(hits) if hits else 0.0


def agreement(rows: dict, labels: dict, ids) -> float:
    hits = [1.0 if rows[i].get("parsed") and rows[i].get("mechanism") == labels[i] else 0.0 for i in ids]
    return sum(hits) / len(hits) if hits else 0.0


def invented_cause(rows: dict, ids) -> float:
    hits = [1.0 if rows[i].get("parsed") and rows[i].get("mechanism") in MECHANISM_RULES else 0.0 for i in ids]
    return sum(hits) / len(hits) if hits else 0.0


def named(rows: dict, items: dict, i: str) -> bool:
    r = rows[i]
    return bool(r.get("parsed")) and r.get("mechanism") in set(items[i]["rules"]["fired"])


# ── statistics (§7) ──────────────────────────────────────────────────────────

def boot(ids: list[str], fn) -> dict:
    """Point value and paired 95% bootstrap interval of fn over the items."""
    rng = random.Random(SEED)
    n = len(ids)
    vals = sorted(fn([ids[rng.randrange(n)] for _ in range(n)]) for _ in range(BOOT))
    return {"n": n, "value": round(fn(ids), 4),
            "ci": [round(vals[int(0.025 * BOOT)], 4), round(vals[int(0.975 * BOOT) - 1], 4)]}


def direction(result: dict) -> str:
    lo, hi = result["ci"]
    return "up" if lo > 0 else "down" if hi < 0 else "none"


def wilson(k: int, n: int, z: float = 1.96) -> list[float]:
    if n == 0:
        return [0.0, 0.0]
    p = k / n
    centre = (p + z * z / (2 * n)) / (1 + z * z / n)
    half = z * math.sqrt(p * (1 - p) / n + z * z / (4 * n * n)) / (1 + z * z / n)
    return [round(centre - half, 4), round(centre + half, 4)]


# ── data ─────────────────────────────────────────────────────────────────────

def systems(verified: list[dict]) -> dict[str, dict[str, dict]]:
    """name -> item id -> the verified answer. Names: lora-2b-r3, qwen3.5-2b R0, S_rules."""
    out: dict[str, dict[str, dict]] = {}
    for (arm, level, item), r in latest(verified).items():
        name = "S_rules" if arm == "rules" else arm if arm.startswith(("lora-", "bf16-", "gguf-")) else f"{arm} {level}"
        out.setdefault(name, {})[item] = r
    return out


def common(*systems_rows: dict) -> list[str]:
    ids = set(systems_rows[0])
    for s in systems_rows[1:]:
        ids &= set(s)
    return sorted(ids)


# ── the report ───────────────────────────────────────────────────────────────

def main() -> None:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    ap = argparse.ArgumentParser()
    ap.add_argument("--tc-run", default="results_private/phase8/grid_tc")
    ap.add_argument("--ab-run", default="results_private/phase8/grid_ab")
    ap.add_argument("--baseline", default="results_private/phase5/full/verified.jsonl")
    ap.add_argument("--tc-data", default="data/phase5/testset_ablations.jsonl")
    ap.add_argument("--ab-data", default="data/phase6_v1/test_ab_ablations.jsonl")
    ap.add_argument("--fresh", default="data/phase8/fresh_labels.jsonl")
    ap.add_argument("--quant", default="results_private/phase8/quant/verified.jsonl")
    ap.add_argument("--out", default="reports/grid_v1.md")
    ap.add_argument("--disclosures", default="reports/grid_v1_disclosures.md",
                    help="what the reader must know that the numbers do not show")
    args = ap.parse_args()

    tc_items = {i["id"]: i for i in load_jsonl(Path(args.tc_data))}
    ab_items = {i["id"]: i for i in load_jsonl(Path(args.ab_data))}
    tc = systems(load_jsonl(Path(args.baseline)) + load_jsonl(Path(args.tc_run) / "verified.jsonl"))
    ab = systems(load_jsonl(Path(args.ab_run) / "verified.jsonl")) if Path(args.ab_run, "verified.jsonl").exists() else {}
    fresh = [f for f in load_jsonl(Path(args.fresh)) if f["in_test_set"]] if Path(args.fresh).exists() else []

    grid = {a.name: a for a in arms()}
    have = lambda s: s in tc and len(tc[s]) > 0                                  # noqa: E731
    missing = [a.system for a in grid.values() if not have(a.system)]
    m: dict = {"missing_arms": missing, "hypotheses": {}, "rules": {}}
    L = ["# Phase 8 — the grid against its pre-registration", "",
         "Every number below is defined in `training/PREREGISTRATION.md` (tag `prereg-v1`) and computed from "
         "the app's verifier. 95% paired bootstrap intervals in brackets (1,000 resamples, seed 0). "
         "T_C is the player's own 890 mistakes (primary); T_AB the 838 held-out Lichess positions (replication).", ""]
    if missing:
        L += [f"**Missing arms** (§10, reported, not estimated): {', '.join(missing)}.", ""]

    def cp(name, ids):
        return lambda sample: claim_precision(tc[name], sample)

    # H1 — claim precision rises with evidence (2B-LoRA R0..R3) ------------------
    h1_names = [f"lora-2b-r{k}" for k in range(4)]
    if all(have(n) for n in h1_names):
        ids = common(*(tc[n] for n in h1_names))
        diff = lambda a, b: boot(ids, lambda s: claim_precision(tc[a], s) - claim_precision(tc[b], s))   # noqa: E731
        d30, d31 = diff("lora-2b-r3", "lora-2b-r0"), diff("lora-2b-r3", "lora-2b-r1")
        steps = {f"R{k + 1}-R{k}": diff(f"lora-2b-r{k + 1}", f"lora-2b-r{k}") for k in range(3)}
        falls = [k for k, v in steps.items() if direction(v) == "down"]
        verdict = ("supported" if direction(d30) == "up" and direction(d31) == "up" and not falls
                   else "refuted" if direction(d31) == "down" else "not resolved")
        # Length control: tertiles of prompt length over the R1 and R3 prompts together.
        toks = sorted([tc["lora-2b-r1"][i].get("tokens_in", 0) for i in ids] +
                      [tc["lora-2b-r3"][i].get("tokens_in", 0) for i in ids])
        cuts = [toks[len(toks) // 3], toks[2 * len(toks) // 3]]
        tert = lambda t: 0 if t <= cuts[0] else 1 if t <= cuts[1] else 2   # noqa: E731
        by_len = {}
        for k in range(3):
            same = [i for i in ids if tert(tc["lora-2b-r1"][i].get("tokens_in", 0)) == k ==
                    tert(tc["lora-2b-r3"][i].get("tokens_in", 0))]
            if len(same) >= 20:
                by_len[f"tertile {k + 1}"] = boot(same, lambda s: claim_precision(tc["lora-2b-r3"], s)
                                                 - claim_precision(tc["lora-2b-r1"], s))
        length_caveat = not any(direction(v) == "up" for v in by_len.values())
        m["hypotheses"]["H1"] = {"verdict": verdict, "R3-R0": d30, "R3-R1": d31, "steps": steps,
                                 "by_length": by_len, "length_caveat": length_caveat,
                                 "precision": {n: round(claim_precision(tc[n], ids), 4) for n in h1_names}}
    else:
        m["hypotheses"]["H1"] = {"verdict": "not computable"}

    # H2 — the LoRA gain is largest at R3 ---------------------------------------
    pr = {k: f"qwen3.5-2b R{k}" for k in range(4)}
    if all(have(n) for n in h1_names) and all(have(n) for n in pr.values()):
        ids = common(*(tc[n] for n in h1_names), *(tc[n] for n in pr.values()))
        gain = lambda s, k: claim_precision(tc[f"lora-2b-r{k}"], s) - claim_precision(tc[pr[k]], s)   # noqa: E731
        vs = {f"gain(R3)-gain(R{k})": boot(ids, lambda s, k=k: gain(s, 3) - gain(s, k)) for k in range(3)}
        verdict = ("supported" if all(direction(v) == "up" for v in vs.values())
                   else "refuted" if any(direction(v) == "down" for v in vs.values()) else "not resolved")
        m["hypotheses"]["H2"] = {"verdict": verdict, **vs,
                                 "gains": {f"R{k}": round(gain(ids, k), 4) for k in range(4)}}
    else:
        m["hypotheses"]["H2"] = {"verdict": "not computable"}

    # H3 — evidence substitutes for scale ---------------------------------------
    h3 = ["lora-2b-r0", "lora-2b-r3", "lora-4b-r0", "lora-4b-r3"]
    if all(have(n) for n in h3):
        ids = common(*(tc[n] for n in h3))
        gap = lambda s, k: claim_precision(tc[f"lora-4b-r{k}"], s) - claim_precision(tc[f"lora-2b-r{k}"], s)   # noqa: E731
        d = boot(ids, lambda s: gap(s, 0) - gap(s, 3))
        m["hypotheses"]["H3"] = {"verdict": {"up": "supported", "down": "refuted"}.get(direction(d), "not resolved"),
                                 "gap(R0)-gap(R3)": d, "gap_R0": round(gap(ids, 0), 4), "gap_R3": round(gap(ids, 3), 4)}
    else:
        m["hypotheses"]["H3"] = {"verdict": "not computable"}

    # H4 — beyond the rules on composites (T_C + T_AB pooled) --------------------
    def h4(name: str) -> dict:
        pool = [(tc[name], tc_items, i) for i in tc.get(name, {}) if tc_items[i]["rules"]["composite"]]
        pool += [(ab[name], ab_items, i) for i in ab.get(name, {}) if ab_items[i]["rules"]["composite"]]
        n = len(pool)
        named_ = [(rows, items, i) for rows, items, i in pool if named(rows, items, i)]
        passed = sum(1 for rows, _, i in named_ if rows[i].get("claim_passed"))
        verified_rate = sum(1 for rows, _, i in pool if rows[i].get("claim_passed")) / n if n else 0.0
        cov = passed / n if n else 0.0
        prec = passed / len(named_) if named_ else 0.0
        lo = wilson(passed, n)[0]
        ok = n > 0 and cov >= 0.10 and lo > 0.05 and prec >= 0.80
        return {"verdict": "supported" if ok else "not supported", "composites": n, "named": len(named_),
                "named_and_passed": passed, "named_coverage": round(cov, 4), "wilson_low": lo,
                "named_precision": round(prec, 4), "verified_rate": round(verified_rate, 4),
                "t_ab_included": bool(ab.get(name))}
    m["hypotheses"]["H4"] = h4("lora-2b-r3") if have("lora-2b-r3") else {"verdict": "not computable"}

    # H5 — the counterfactual matters most ---------------------------------------
    abl = {"CF": "lora-2b-r3-nocf", "T": "lora-2b-r3-not", "DELTA": "lora-2b-r3-nodelta", "D": "lora-2b-r3-nod"}
    if have("lora-2b-r3") and all(have(n) for n in abl.values()):
        ids = [i for i in common(tc["lora-2b-r3"], *(tc[n] for n in abl.values())) if tc_items[i]["rules"]["single_cause"]]
        acc = lambda n, s: mechanism_vs_rules(tc[n], tc_items, s)   # noqa: E731
        drops = {x: round(acc("lora-2b-r3", ids) - acc(n, ids), 4) for x, n in abl.items()}
        vs = {f"drop(CF)-drop({x})": boot(ids, lambda s, n=n: acc(n, s) - acc(abl["CF"], s))
              for x, n in abl.items() if x != "CF"}
        verdict = ("supported" if all(direction(v) == "up" for v in vs.values())
                   else "refuted" if any(direction(v) == "down" for v in vs.values()) else "not resolved")
        m["hypotheses"]["H5"] = {"verdict": verdict, "single_cause_items": len(ids), "drops": drops, **vs}
    else:
        m["hypotheses"]["H5"] = {"verdict": "not computable"}

    # Ship rules (a)–(d) ---------------------------------------------------------
    labels = {f["id"]: f["mechanism"] for f in fresh if f["mechanism"] in MECHANISM_RULES}

    def ship(name: str) -> dict:
        if not have(name):
            return {"computable": False}
        ids = sorted(tc[name])
        a = boot(ids, lambda s: claim_precision(tc[name], s))
        lab_ids = [i for i in labels if i in tc[name] and i in tc.get("S_rules", {})]
        if len(lab_ids) >= 30:
            b = boot(lab_ids, lambda s: agreement(tc[name], labels, s) - agreement(tc["S_rules"], labels, s))
            b_ok = b["ci"][0] > -0.05
        else:
            b, b_ok = {"n": len(lab_ids), "note": "fewer than 30 fresh single-cause labels"}, False
        nc = [i for i in ids if tc_items[i]["rules"]["consequence"] == "NOT_CONCRETE"]
        c = invented_cause(tc[name], nc)
        d = h4(name)["verdict"] == "supported"
        res = {"a_claim_precision": a, "a": a["value"] >= 0.95 and a["ci"][0] >= 0.93,
               "b_fresh_labels": b, "b": b_ok, "c_invented_cause": round(c, 4), "c": c <= 0.10, "d": d}
        res["all"] = res["a"] and res["b"] and res["c"] and res["d"]
        return res

    m["rules"] = {"lora-2b-r3": ship("lora-2b-r3"), "lora-4b-r3": ship("lora-4b-r3")}
    s2, s4 = m["rules"]["lora-2b-r3"], m["rules"]["lora-4b-r3"]
    if s2.get("all"):
        decision = "Ship the model (2B-R3-LoRA); the rules stay as the fallback, every answer verified live."
    elif s4.get("all") and not s2.get("all"):
        decision = "Document it: the 4B meets the bar and the 2B does not. Rules stay in production; 4B serving is a V3 hardware question."
    elif s2.get("computable", True) is not False and s2.get("a") and s2.get("b") and s2.get("c"):
        decision = "Ship the rules: the model is about S_rules with no composite gain. The graph and the Why? panel ship regardless."
    else:
        failed = [k for k in "abcd" if s2.get("computable", True) is not False and not s2.get(k)]
        decision = f"Ship the rules: 2B-R3-LoRA does not meet {', '.join(f'({k})' for k in failed) or '(a)–(d)'}."
    if m["hypotheses"]["H1"].get("verdict") == "refuted":
        decision += " H1 is refuted, which is the headline finding."
    m["decision"] = decision

    # ── write ──
    L += ["## Decision (§9)", "", f"**{decision}**", "",
          "| Criterion | 2B-R3-LoRA | 4B-R3-LoRA |", "|---|---|---|"]
    def cell(s, key, show):
        if not s.get("computable", True):
            return "missing"
        return ("✓ " if s[key] else "✗ ") + show(s)
    L.append("| (a) claim precision ≥ 95%, lower bound ≥ 93% | " + " | ".join(
        cell(s, "a", lambda s: f"{pct(s['a_claim_precision']['value'])}{interval(s['a_claim_precision']['ci'])}") for s in (s2, s4)) + " |")
    L.append("| (b) vs `S_rules` on fresh labels, lower bound > −5 pts, n ≥ 30 | " + " | ".join(
        cell(s, "b", lambda s: (f"{100 * s['b_fresh_labels']['value']:+.1f} pts{interval(s['b_fresh_labels']['ci'])}, n={s['b_fresh_labels']['n']}"
                                if "value" in s["b_fresh_labels"] else s["b_fresh_labels"]["note"] + f" (n={s['b_fresh_labels']['n']})")) for s in (s2, s4)) + " |")
    L.append("| (c) invented cause on NOT_CONCRETE ≤ 10% | " + " | ".join(
        cell(s, "c", lambda s: pct(s["c_invented_cause"])) for s in (s2, s4)) + " |")
    L.append("| (d) H4 supported | " + " | ".join(cell(s, "d", lambda s: "") for s in (s2, s4)) + " |")

    notes = Path(args.disclosures)
    if notes.exists():
        L += ["", "## Disclosures", "", notes.read_text(encoding="utf-8").strip()]
    L += ["", "## Hypotheses (§8)", "", "| | Verdict | Test |", "|---|---|---|"]
    def fmt(d):
        return f"{100 * d['value']:+.1f} pts{interval(d['ci'])}"
    H = m["hypotheses"]
    tests = {
        "H1": lambda h: f"R3−R0 {fmt(h['R3-R0'])}; R3−R1 {fmt(h['R3-R1'])}" + ("; length caveat" if h.get("length_caveat") else ""),
        "H2": lambda h: "; ".join(f"{k} {fmt(v)}" for k, v in h.items() if k.startswith("gain(")),
        "H3": lambda h: f"gap(R0)−gap(R3) {fmt(h['gap(R0)-gap(R3)'])}",
        "H4": lambda h: (f"named coverage {pct(h['named_coverage'])} (Wilson low {pct(h['wilson_low'])}), "
                         f"named precision {pct(h['named_precision'])}, n={h['composites']}"),
        "H5": lambda h: "; ".join(f"{k} {fmt(v)}" for k, v in h.items() if k.startswith("drop(CF)")),
    }
    for k in ["H1", "H2", "H3", "H4", "H5"]:
        h = H[k]
        L.append(f"| {k} | **{h['verdict']}** | {tests[k](h) if h['verdict'] != 'not computable' else '—'} |")

    # Every system on T_C, descriptive.
    order = ["S_rules", *(f"qwen3.5-2b R{k}" for k in range(4)), "qwen3.5-4b R0", "qwen3.5-4b R3",
             *(a.system for a in grid.values())]
    rng = random.Random(SEED)
    L += ["", "## Every system on T_C (890)", "",
          "| System | Items | Claim precision | Chain valid | Mechanism vs rules | Invents a cause | vs your labels (100, in-sample) | p50 latency |",
          "|---|---|---|---|---|---|---|---|"]
    m["t_c"] = {}
    for name in order:
        if not have(name):
            continue
        s = score_system(list(tc[name].values()), tc_items, rng)
        m["t_c"][name] = s
        lat = "—" if s["latency_p50_ms"] is None else f"{s['latency_p50_ms'] / 1000:.1f} s"
        L.append(f"| {name} | {s['items']} | {pct(s['claim_precision'])}{interval(s['claim_precision_ci'])} | "
                 f"{pct(s['chain_validity'])} | {pct(s['mechanism_vs_rules_single_cause'])} | {pct(s['invented_cause_rate'])} | "
                 f"{pct(s['consequence_vs_human'])} / {pct(s['mechanism_vs_human'])} | {lat} |")
    if ab:
        L += ["", "## Replication on T_AB (838)", "", "| System | Items | Claim precision | Chain valid | Mechanism vs rules | Invents a cause |",
              "|---|---|---|---|---|---|"]
        m["t_ab"] = {}
        for name in [n for n in order if n in ab]:
            s = score_system(list(ab[name].values()), ab_items, rng)
            m["t_ab"][name] = s
            L.append(f"| {name} | {s['items']} | {pct(s['claim_precision'])}{interval(s['claim_precision_ci'])} | "
                     f"{pct(s['chain_validity'])} | {pct(s['mechanism_vs_rules_single_cause'])} | {pct(s['invented_cause_rate'])} |")

    # Quantisation delta, descriptive.
    q = systems(load_jsonl(Path(args.quant))) if Path(args.quant).exists() else {}
    if "bf16-2b-r3-free" in q and "gguf-2b-r3-free" in q:
        ids = common(q["bf16-2b-r3-free"], q["gguf-2b-r3-free"])
        d = boot(ids, lambda s: claim_precision(q["gguf-2b-r3-free"], s) - claim_precision(q["bf16-2b-r3-free"], s))
        m["quantisation_delta"] = {"items": len(ids), "bf16": round(claim_precision(q["bf16-2b-r3-free"], ids), 4),
                                   "q4_k_m": round(claim_precision(q["gguf-2b-r3-free"], ids), 4), "q4-bf16": d}
        L += ["", "## Quantisation delta (§3, descriptive)", "",
              f"2B-R3 on {len(ids)} T_AB items, no JSON schema on either side: bf16 "
              f"{pct(m['quantisation_delta']['bf16'])}, q4_K_M {pct(m['quantisation_delta']['q4_k_m'])} claim precision; "
              f"q4_K_M − bf16 {fmt(d)}."]

    Path(args.out).parent.mkdir(parents=True, exist_ok=True)
    Path(args.out).write_text("\n".join(L) + "\n", encoding="utf-8")
    Path(args.out).with_suffix(".json").write_text(json.dumps(m, indent=1), encoding="utf-8")
    print("\n".join(L))


if __name__ == "__main__":
    main()
