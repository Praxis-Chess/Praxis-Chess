"""Phase 11: stage, check and upload the Hugging Face release (plan §19).

    python tools/hf_release.py stage            # build release/staging/: cards, configs, manifests, plan
    python tools/hf_release.py upload [REPO]    # create PRIVATE repos and upload (default: all six)
    python tools/hf_release.py cards [REPO]     # after a card edit: re-upload only each README.md
    python tools/hf_release.py check-remote     # every uploaded file's sha256 against the local file
    python tools/hf_release.py tag              # tag v1.0 on every repo
    python tools/hf_release.py publish          # make them public, and group them in a collection

Run from training/. Upload needs `hf auth login` with a write token for the
praxis-chess org; the token stays in the Hugging Face cache, never in a file
here.

Every number in a card is filled from a measured file: reports/public_test_v1.json
(public test), reports/grid_v1.json (the author's games, aggregates only),
outputs/grid_v1_results/*/{train_report,selection}.json (training). A card
with an unfilled {{placeholder}} stops the stage.

Big files (adapters, GGUFs, the dataset) are uploaded from where they are;
staging copies only what it generates. Nothing from the author's games is
uploaded: stage scans every text file for paths, secrets and Chess.com
references, and checks every dataset row's source is Lichess.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import shutil
import sys
from decimal import ROUND_HALF_UP, Decimal
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "src"))

from praxis_train.build_dataset6 import SYSTEM  # noqa: E402

ORG = "praxis-chess"
RESULTS = ROOT / "outputs/grid_v1_results"
STAGE = ROOT / "release/staging"
CARDS = ROOT / "release/cards"
TAG = "v1.0"
PRAXIS_TAG = "lora-v1.0"     # the Praxis repo already uses v1.0.0 etc. for app releases
ORG_URL = f"https://huggingface.co/{ORG}"

LORA = {"2b": "praxis-chess-reasoner-qwen3.5-2b-lora", "4b": "praxis-chess-reasoner-qwen3.5-4b-lora"}
GGUF = {"2b": "praxis-chess-reasoner-qwen3.5-2b-GGUF", "4b": "praxis-chess-reasoner-qwen3.5-4b-GGUF"}
ARMS = "praxis-chess-grid-v1-arms"
DATASET = "praxis-chess-evidence-graphs"
ARM_NAMES = ["2b-r0", "2b-r1", "2b-r2", "4b-r0"]
ADAPTER_FILES = ["adapter_config.json", "adapter_model.safetensors", "tokenizer.json",
                 "tokenizer_config.json", "chat_template.jinja"]
INPUT = {"R0": "R0: board and move", "R1": "R1: + engine numbers", "R2": "R2: + flat facts",
         "R3": "R3: evidence graph"}


def load(p: Path):
    return json.loads(p.read_text(encoding="utf-8"))


def jsonl(p: Path) -> list[dict]:
    return [json.loads(l) for l in p.read_text(encoding="utf-8").splitlines() if l.strip()]


def sha256(p: Path) -> str:
    h = hashlib.sha256()
    with p.open("rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def r(x: float, places: int) -> Decimal:
    """Percent, rounded half-up like the write-up (binary floats would round 48.45 down)."""
    return (Decimal(repr(x)) * 100).quantize(Decimal(1).scaleb(-places), rounding=ROUND_HALF_UP)


def p1(x: float) -> str:
    return str(r(x, 1))


def ci1(c) -> str:
    return f"{r(c[0], 1)}–{r(c[1], 1)}"


def p0(x) -> str:
    return "—" if x is None else f"{r(x, 0)}%"


def pts(x: float) -> str:
    v = r(x, 1)
    return ("+" if v >= 0 else "−") + str(abs(v))


def fill(template: str, values: dict) -> str:
    out = re.sub(r"\{\{(\w+)\}\}", lambda m: str(values[m.group(1)]) if m.group(1) in values else m.group(0), template)
    left = sorted(set(re.findall(r"\{\{(\w+)\}\}", out)))
    if left:
        raise SystemExit(f"unfilled placeholders: {left}")
    return out


# ── the measured numbers ─────────────────────────────────────────────────────

class Numbers:
    def __init__(self) -> None:
        self.pub = load(ROOT / "reports/public_test_v1.json")
        self.grid = load(ROOT / "reports/grid_v1.json")
        self.tc = self.grid["t_c"]
        self.train = {a: load(RESULTS / a / "train_report.json") for a in ARM_NAMES + ["2b-r3", "4b-r3"]}
        self.select = {a: load(RESULTS / a / "selection.json") for a in ARM_NAMES + ["2b-r3", "4b-r3"]}
        self.manifest6 = load(ROOT / "data/phase6_v1/manifest.json")

    def pub_row(self, name: str, label: str, level: str, bold: bool) -> str:
        s = self.pub[name]
        cells = [label, INPUT.get(level, level), f"{p1(s['claim_precision'])}% ({ci1(s['claim_precision_ci'])})",
                 f"{p1(s['chain_validity'])}% ({ci1(s['chain_validity_ci'])})",
                 p0(s["mechanism_vs_rules_single_cause"]), p0(s["invented_cause_rate"])]
        if bold:
            cells = [f"**{c}**" for c in cells]
        return "| " + " | ".join(cells) + " |"

    def pub_rows(self, size: str) -> str:
        rows = [self.pub_row("S_rules", "Rules (no model)", "R3", False)]
        for s in ("2b", "4b"):
            for lvl in ("r0", "r1", "r2", "r3"):
                name = f"lora-{s}-{lvl}"
                if name in self.pub:
                    rows.append(self.pub_row(name, f"{s.upper()} LoRA", lvl.upper(), s == size and lvl == "r3"))
        return "\n".join(rows)

    def tc_rows(self) -> str:
        order = [("S_rules", "Rules (no model)"), ("qwen3.5-2b R0", "2B prompted, R0"), ("qwen3.5-2b R3", "2B prompted, R3"),
                 ("qwen3.5-4b R0", "4B prompted, R0"), ("qwen3.5-4b R3", "4B prompted, R3"),
                 ("lora-2b-r0", "2B LoRA, R0"), ("lora-2b-r1", "2B LoRA, R1"), ("lora-2b-r2", "2B LoRA, R2"),
                 ("lora-2b-r3", "2B LoRA, R3"), ("lora-4b-r0", "4B LoRA, R0"), ("lora-4b-r3", "4B LoRA, R3")]
        out = []
        for key, label in order:
            s = self.tc[key]
            out.append(f"| {label} | {s['items']} | {p1(s['claim_precision'])}% ({ci1(s['claim_precision_ci'])}) | "
                       f"{p1(s['chain_validity'])}% ({ci1(s['chain_validity_ci'])}) | "
                       f"{p0(s['mechanism_vs_rules_single_cause'])} | {p0(s['invented_cause_rate'])} |")
        return "\n".join(out)

    def ship_rows(self, size: str) -> str:
        r = self.grid["rules"][f"lora-{size}-r3"]
        a, b = r["a_claim_precision"], r["b_fresh_labels"]
        mark = lambda ok: "✓" if ok else "✗"
        h4 = self.grid["hypotheses"]["H4"]
        d_detail = (f"not supported ({h4['named_and_passed']} of {h4['composites']} named and verified, "
                    f"at {p0(h4['named_precision'])} precision)" if size == "2b" else "not met")
        return "\n".join([
            f"| (a) Claim precision ≥ 95%, lower bound ≥ 93% | {mark(r['a'])} {p1(a['value'])}% ({ci1(a['ci'])}) |",
            f"| (b) Not worse than the rules against fresh human labels (n ≥ 30; lower bound above −5 points) | "
            f"{mark(r['b'])} {pts(b['value'])} points ({pts(b['ci'][0])} to {pts(b['ci'][1])}), n = {b['n']} |",
            f"| (c) Invents a cause on at most 10% of nothing-concrete mistakes | {mark(r['c'])} {p1(r['c_invented_cause'])}% |",
            f"| (d) Names single causes where the rules cannot (H4) | {mark(r['d'])} {d_detail} |",
        ])

    def model_values(self, size: str) -> dict:
        arm = f"{size}-r3"
        pub3, pub0 = self.pub[f"lora-{size}-r3"], self.pub[f"lora-{size}-r0"]
        tr, sel = self.train[arm], self.select[arm]
        cfg = (ROOT / f"config/grid_v1/{arm}.yaml").read_text(encoding="utf-8")
        mods = re.search(r"target_modules:\n((?:\s+- .+\n)+)", cfg).group(1)
        targets = [m.strip()[2:] for m in mods.splitlines()]
        h = self.grid["hypotheses"]
        h1len = h["H1"]["by_length"]["tertile 2"]
        drops = h["H5"]["drops"]
        mech = {k: self.tc[f"lora-2b-r3-{k}"]["mechanism_vs_rules_single_cause"] for k in ("nocf", "not", "nodelta", "nod")}
        others = sorted(mech[k] for k in ("nocf", "nodelta", "nod"))
        q = self.grid["quantisation_delta"]["q4-bf16"]
        quant = (f"{pts(q['value'])}", f"{pts(q['ci'][0])} to {pts(q['ci'][1])}")
        cv = pub3["chain_validity"]
        epochs = sel["epochs"]
        chosen = sel["selected"]
        other = next(e for e in epochs if e != chosen)
        base = tr["base"]
        return {
            "base": base, "base_revision": tr["base_revision"], "size": size.upper(), "size_lower": size,
            "repo_name": LORA[size], "gguf_repo_name": GGUF[size], "collection_url": ORG_URL,
            "pub_r3_cp": p1(pub3["claim_precision"]), "pub_r3_cv": p1(cv), "pub_r0_cp": p1(pub0["claim_precision"]),
            "pub_r3_cp_ci": ci1(pub3["claim_precision_ci"]), "pub_r3_cv_ci": ci1(pub3["chain_validity_ci"]),
            "pub_r3_latency": f"{pub3['latency_p50_ms'] / 1000:.1f}", "pub_r3_invent": p1(pub3["invented_cause_rate"]),
            "pub_nc_n": pub3["not_concrete_n"], "pub_sc_n": pub3["single_cause_n"],
            "pub_rows": self.pub_rows(size), "tc_rows": self.tc_rows(), "ship_rows": self.ship_rows(size),
            "h1_r3_r0": f"{pts(h['H1']['R3-R0']['value'])} ({ci1(h['H1']['R3-R0']['ci'])})".lstrip("+"),
            "h1_r3_r1": f"{pts(h['H1']['R3-R1']['value'])} ({ci1(h['H1']['R3-R1']['ci'])})".lstrip("+"),
            "h1_len": f"{pts(h1len['value'])} ({ci1(h1len['ci'])})".lstrip("+"), "h1_len_n": h1len["n"],
            "h2_gain_r3": p1(h["H2"]["gains"]["R3"]), "h2_gain_r0": p1(h["H2"]["gains"]["R0"]),
            "h3_gap_r0": pts(h["H3"]["gap_R0"]), "h3_gap_r3": pts(h["H3"]["gap_R3"]),
            "h4_n": h["H4"]["composites"], "h4_named": h["H4"]["named"], "h4_passed": h["H4"]["named_and_passed"],
            "h4_precision": f"{100 * h['H4']['named_precision']:.0f}",
            "h5_not": f"{100 * mech['not']:.0f}", "h5_others": f"{100 * others[0]:.0f}–{100 * others[-1]:.0f}%",
            "system_prompt": SYSTEM, "lora_r": 16, "lora_alpha": 32, "target_modules": "`, `".join(targets),
            "epochs": tr["epochs"], "batch_note": "4 per step × 2 accumulated" if size == "2b" else "2 per step × 4 accumulated",
            "selected": (f"{chosen.replace('-', ' ')} (validation claim precision {p1(epochs[chosen]['claim_precision'])}% "
                         f"against {p1(epochs[other]['claim_precision'])}%)"),
            "train_rows": f"{tr['train_rows']:,}", "train_minutes": round(tr["seconds"] / 60),
            "trainable_params": f"{tr['trainable_params'] / 1e6:.1f} M",
            "v_torch": tr["versions"]["torch"], "v_transformers": tr["versions"]["transformers"],
            "v_peft": tr["versions"]["peft"], "v_trl": tr["versions"]["trl"],
            "quant_sentence": (
                f"On 300 public positions, the q4_K_M GGUF scored {quant[0]} points of claim precision against "
                f"the bf16 adapter (95% CI {quant[1]}), both without the JSON schema." if size == "2b" else
                f"Measured once, on the 2B-R3 adapter: on 300 public positions its q4_K_M GGUF scored {quant[0]} "
                f"points of claim precision against bf16 (95% CI {quant[1]}). The 4B's own gap was not measured."),
            "app_use": ("This model writes an optional commentary on each mistake, and a commentary is shown "
                        "only when every one of its claims passes the checker." if size == "2b" else
                        "The app uses the 2B-R3 adapter for its optional, checked commentary; this 4B is not "
                        "used in the app."),
            "one_in": round(1 / (1 - cv)),
        }


# ── staging ──────────────────────────────────────────────────────────────────

def training_config(arm: str, base_revision: str) -> str:
    cfg = (ROOT / f"config/grid_v1/{arm}.yaml").read_text(encoding="utf-8")
    cfg = cfg.replace("  revision: main", f"  revision: {base_revision}   # resolved from 'main' at training time")
    head = (f"# Praxis Chess LoRA v1, arm {arm}: the configuration it was trained with\n"
            f"# (training/config/grid_v1/{arm}.yaml in the Praxis repo, tag {PRAXIS_TAG}),\n"
            f"# with the base revision pinned. Train: python -m praxis_train.train_sft --config <this file>\n"
            f"# from training/, with the dataset's data/<R>/ files at the data: paths.\n")
    return head + "\n".join(l for l in cfg.splitlines() if not l.startswith("# Generated")) + "\n"


def modelfile(arm: str, gguf_name: str) -> str:
    text = (RESULTS / arm / f"praxis-grid-{arm}.Modelfile").read_text(encoding="utf-8")
    return re.sub(r"^FROM .*$", f"FROM ./{gguf_name}", text, count=1, flags=re.M)


def run_manifest(numbers: Numbers, arm: str, files: dict[str, Path], extra: dict | None = None) -> str:
    tr, sel = numbers.train[arm], numbers.select[arm]
    m = {
        "arm": arm,
        "base": {"repo": tr["base"], "revision": tr["base_revision"], "licence": "apache-2.0 (checked at this revision)"},
        "dataset": {"repo": f"{ORG}/{DATASET}", "revision": TAG, "build": numbers.manifest6["dataset"],
                    "files_sha256": {k: v for k, v in numbers.manifest6["files"].items()}},
        "praxis": {"repo": "https://github.com/Praxis-Chess/Praxis-Chess", "release_tag": PRAXIS_TAG,
                   "preregistration_tag": "prereg-v1", "graph_builder_commit": numbers.manifest6["backend_git_sha"],
                   "graph_version": numbers.manifest6["graph_version"], "engine": numbers.manifest6["engine"]},
        "prompt": {"system": SYSTEM, "format": "Qwen chat format, thinking block closed before the answer"},
        "verifier": "DiagnosisVerifier (Java), claim mode; praxis_eval reproduces its verdicts",
        "training": {k: tr[k] for k in ("gpu", "versions", "train_rows", "val_rows", "epochs", "trainable_params",
                                         "total_steps", "seconds", "peak_vram_gb", "final_loss")},
        "checkpoint_selection": {"rule": "higher claim precision on the first 200 validation rows (PREREGISTRATION.md §3)",
                                 "epochs": sel["epochs"], "selected": sel["selected"]},
        "files_sha256": {name: sha256(p) for name, p in files.items()},
    }
    if extra:
        m.update(extra)
    return json.dumps(m, indent=1, ensure_ascii=False) + "\n"


def stage() -> dict:
    n = Numbers()
    if STAGE.exists():
        shutil.rmtree(STAGE)
    plan: dict[str, dict] = {}

    def repo(name: str, kind: str) -> tuple[Path, list]:
        d = STAGE / name
        d.mkdir(parents=True)
        plan[name] = {"type": kind, "files": []}
        return d, plan[name]["files"]

    def gen(d: Path, files: list, path: str, text: str) -> None:
        p = d / path
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_text(text, encoding="utf-8", newline="\n")
        files.append([path, str(p)])

    model_t = (CARDS / "model.md").read_text(encoding="utf-8")
    gguf_t = (CARDS / "gguf.md").read_text(encoding="utf-8")

    for size in ("2b", "4b"):
        arm = f"{size}-r3"
        values = n.model_values(size)
        # ── the adapter ──
        d, files = repo(LORA[size], "model")
        adapter = {f: RESULTS / arm / "adapter" / f for f in ADAPTER_FILES}
        for f, p in adapter.items():
            files.append([f, str(p)])
        gen(d, files, "README.md", fill(model_t, values))
        gen(d, files, "training_config.yaml", training_config(arm, values["base_revision"]))
        gen(d, files, "run_manifest.json", run_manifest(n, arm, adapter))
        # ── the GGUF ──
        d, files = repo(GGUF[size], "model")
        gguf_name = f"praxis-chess-reasoner-qwen3.5-{size}-Q4_K_M.gguf"
        gguf = RESULTS / arm / f"praxis-grid-{arm}-q4_k_m.gguf"
        files.append([gguf_name, str(gguf)])
        # llama.cpp's log lines come first in this file; the report is the JSON after them.
        raw = (RESULTS / arm / "export_report.json").read_text(encoding="utf-8")
        export = json.loads(raw[raw.index("{"):])
        gsha = sha256(gguf)
        gen(d, files, "README.md", fill(gguf_t, {**values, "lora_repo_name": LORA[size], "repo_name": GGUF[size],
                                                  "gguf_file": gguf_name, "gguf_sha256": gsha,
                                                  "gguf_mb": f"{export['gguf_mb']:,.0f}"}))
        gen(d, files, "Modelfile", modelfile(arm, gguf_name))
        gen(d, files, "run_manifest.json", run_manifest(n, arm, {gguf_name: gguf}, {
            "adapter": {"repo": f"{ORG}/{LORA[size]}", "revision": TAG},
            "quantisation": {"outtype": export["outtype"], "tool": "llama.cpp convert_hf_to_gguf + llama-quantize",
                             "merge": "adapter merged into the bf16 base before conversion"}}))

    # ── the comparison arms ──
    d, files = repo(ARMS, "model")
    rows = []
    for arm in ARM_NAMES:
        for f in ADAPTER_FILES:
            files.append([f"{arm}/adapter/{f}", str(RESULTS / arm / "adapter" / f)])
        gguf_name = f"praxis-grid-{arm}-Q4_K_M.gguf"
        gguf = RESULTS / arm / f"praxis-grid-{arm}-q4_k_m.gguf"
        files.append([f"{arm}/{gguf_name}", str(gguf)])
        gen(d, files, f"{arm}/Modelfile", modelfile(arm, gguf_name))
        gen(d, files, f"{arm}/training_config.yaml", training_config(arm, n.train[arm]["base_revision"]))
        gen(d, files, f"{arm}/run_manifest.json", run_manifest(
            n, arm, {**{f"adapter/{f}": RESULTS / arm / "adapter" / f for f in ADAPTER_FILES}, gguf_name: gguf}))
        s = n.pub[f"lora-{arm}"]
        lvl = arm.split("-")[1].upper()
        rows.append(f"| `{arm}/` | Qwen3.5-{arm[:2].upper()} | {INPUT[lvl]} | "
                    f"{p1(s['claim_precision'])}% ({ci1(s['claim_precision_ci'])}) | "
                    f"{p1(s['chain_validity'])}% ({ci1(s['chain_validity_ci'])}) |")
    gen(d, files, "README.md", fill((CARDS / "arms.md").read_text(encoding="utf-8"), {
        "arm_rows": "\n".join(rows),
        "pub_2b_r3_cp": p1(n.pub["lora-2b-r3"]["claim_precision"]), "pub_2b_r3_cv": p1(n.pub["lora-2b-r3"]["chain_validity"]),
        "pub_4b_r3_cp": p1(n.pub["lora-4b-r3"]["claim_precision"]), "pub_4b_r3_cv": p1(n.pub["lora-4b-r3"]["chain_validity"]),
        "base_2b_revision": n.train["2b-r3"]["base_revision"], "base_4b_revision": n.train["4b-r3"]["base_revision"]}))

    # ── the dataset ──
    d, files = repo(DATASET, "dataset")
    src6 = ROOT / "data/phase6_v1"
    data_files = {}
    for lvl in ("R0", "R1", "R2", "R3"):
        data_files[f"data/{lvl}/train.jsonl"] = src6 / lvl / "train.jsonl"
        data_files[f"data/{lvl}/validation.jsonl"] = src6 / lvl / "val.jsonl"
    data_files["data/test/test.jsonl"] = src6 / "test_ab_ablations.jsonl"
    data_files["results/outputs.jsonl"] = ROOT / "results_private/phase8/grid_ab/outputs.jsonl"
    data_files["results/verified.jsonl"] = ROOT / "results_private/phase8/grid_ab/verified.jsonl"
    data_files["results/public_test_v1.json"] = ROOT / "reports/public_test_v1.json"
    data_files["results/public_test_v1.md"] = ROOT / "reports/public_test_v1.md"
    data_files["schema/graph_schema.json"] = ROOT / "release/graph_schema.json"
    data_files["schema/answer_schema.json"] = ROOT / "data/phase5/schema.json"
    data_files["verifier/verifier_test_vectors.jsonl.gz"] = ROOT / "tests/verifier_test_vectors.jsonl.gz"
    for path, p in data_files.items():
        files.append([path, str(p)])
    # The graph behind every training and validation row, by its source id. gold.jsonl
    # holds public slices only; taking exactly the published rows' ids keeps it that way.
    split_of_id = {}
    for split, fname in (("train", "train"), ("validation", "val")):
        for row in jsonl(src6 / "R3" / f"{fname}.jsonl"):
            split_of_id[row["meta"]["source_id"]] = split
    graph_rows = {"train": [], "validation": []}
    with (ROOT / "data/phase6/gold.jsonl").open(encoding="utf-8") as f:
        for line in f:
            g = json.loads(line)
            if g["id"] in split_of_id:
                graph_rows[split_of_id[g["id"]]].append(json.dumps(
                    {k: g[k] for k in ("id", "slice", "severity", "fen_key", "rules", "graph")}, ensure_ascii=False))
    if sum(map(len, graph_rows.values())) != len(split_of_id):
        raise SystemExit("a training or validation row has no graph in gold.jsonl")
    for split, rows_ in graph_rows.items():
        gen(d, files, f"data/graphs/{split}.jsonl", "".join(r + "\n" for r in rows_))
        data_files[f"data/graphs/{split}.jsonl"] = d / f"data/graphs/{split}.jsonl"
    sp = n.manifest6["splits"]
    t = n.manifest6["teacher"]
    def split_row(name: str, s: dict) -> str:
        c = s["consequence"]
        return (f"| {name} | {s['rows']:,} | {s['slice'].get('A', 0):,} | {s['slice'].get('B', 0):,} | "
                f"{c.get('LOST_MATERIAL', 0):,} | {c.get('MISSED_MATERIAL', 0):,} | {c.get('MATED', 0):,} | "
                f"{c.get('MISSED_MATE', 0):,} | {c.get('NOT_CONCRETE', 0):,} | {s.get('composite', 0):,} |")
    table_md = (ROOT / "reports/public_test_v1.md").read_text(encoding="utf-8")
    table_md = table_md[table_md.index("| System"):].strip()
    counts = {k: sum(1 for _ in Path(v).open(encoding="utf-8")) for k, v in
              {"outputs": data_files["results/outputs.jsonl"], "verified": data_files["results/verified.jsonl"]}.items()}
    import gzip
    with gzip.open(data_files["verifier/verifier_test_vectors.jsonl.gz"], "rt", encoding="utf-8") as f:
        n_vectors = sum(1 for _ in f)
    gen(d, files, "README.md", fill((CARDS / "dataset.md").read_text(encoding="utf-8"), {
        "n_train": f"{sp['train']['rows']:,}", "n_val": f"{sp['val']['rows']:,}", "n_test": f"{sp['test_ab']['rows']:,}",
        "n_outputs": f"{counts['outputs']:,}", "n_verified": f"{counts['verified']:,}", "n_vectors": f"{n_vectors:,}",
        "system_prompt": SYSTEM,
        "a_total": f"{sum(sp[s]['slice'].get('A', 0) for s in ('train', 'val', 'test_ab')):,}",
        "b_total": f"{sum(sp[s]['slice'].get('B', 0) for s in ('train', 'val', 'test_ab')):,}",
        "backend_sha": n.manifest6["backend_git_sha"][:12],
        "teacher_prose_passed": f"{t['teacher_qwen27b_v3_prose']['passed'] + t['teacher_qwen27b_v3_prose_retry']['passed']:,}",
        "teacher_prose_total": f"{t['teacher_qwen27b_v3_prose']['answers'] + t['teacher_qwen27b_v3_prose_retry']['answers']:,}",
        "teacher_comp_passed": f"{t['teacher_qwen27b_v3_composite']['passed'] + t['teacher_qwen27b_v3_composite_retry']['passed']:,}",
        "teacher_comp_total": f"{t['teacher_qwen27b_v3_composite']['answers'] + t['teacher_qwen27b_v3_composite_retry']['answers']:,}",
        "teacher_comp_added": f"{n.manifest6['dropped_or_capped']['teacher: composite rows added (train+val)']:,}",
        "teacher_train": f"{sp['train']['teacher_targets']:,}",
        "dropped_private": n.manifest6["dropped_or_capped"]["dropped: position in the player's test set or few-shot"],
        "split_rows": "\n".join([split_row("Train", sp["train"]), split_row("Validation", sp["val"]),
                                 split_row("Test", sp["test_ab"])]),
        "public_table": table_md}))
    gen(d, files, "manifest.json", json.dumps({
        "dataset": f"{ORG}/{DATASET}", "version": TAG, "built_as": n.manifest6["dataset"],
        "targets": n.manifest6["targets"], "teacher": n.manifest6["teacher"], "graph_version": n.manifest6["graph_version"],
        "graph_builder_commit": n.manifest6["backend_git_sha"], "engine": n.manifest6["engine"],
        "splits": n.manifest6["splits"], "dropped_or_capped": n.manifest6["dropped_or_capped"],
        "files_sha256": {path: sha256(p) for path, p in data_files.items()},
    }, indent=1, ensure_ascii=False) + "\n")

    (STAGE / "plan.json").write_text(json.dumps(plan, indent=1), encoding="utf-8")
    return plan


# ── checks ───────────────────────────────────────────────────────────────────

SUSPECT = [r"chess\.com", r"[A-Za-z]:\\\\(?:Users|Tanm)", r"[A-Za-z]:/(?:Users|Tanm)", r"/home/\w+", r"/workspace/",
           r"\bhf_[A-Za-z0-9]{30,}", r"praxis_chess_pass", r"(?i)api[_-]?key\s*[:=]\s*\S{8,}", r"Rakshit"]


def check(plan: dict) -> list[str]:
    problems = []
    for name, spec in plan.items():
        size = 0
        for path, local in spec["files"]:
            p = Path(local)
            if not p.exists():
                problems.append(f"{name}: missing {local}")
                continue
            size += p.stat().st_size
            if p.suffix in (".md", ".json", ".jsonl", ".yaml", ".jinja", "") and p.stat().st_size < 400_000_000:
                text = p.read_text(encoding="utf-8", errors="replace")
                for pat in SUSPECT:
                    m = re.search(pat, text)
                    if m:
                        problems.append(f"{name}/{path}: matches {pat!r} near {text[max(0, m.start() - 40):m.end() + 40]!r}")
            if path.endswith(".jsonl") and spec["type"] == "dataset":
                for i, row in enumerate(jsonl(p)):
                    sid = row.get("id") or row.get("item_id") or row.get("meta", {}).get("source_id")
                    if not str(sid).startswith("lichess:"):
                        problems.append(f"{name}/{path}:{i + 1}: source {sid!r} is not Lichess")
                        break
        spec["bytes"] = size
    return problems


# ── the Hub ──────────────────────────────────────────────────────────────────

def hub():
    from huggingface_hub import HfApi
    return HfApi()


def upload(plan: dict, only: str | None, cards_only: bool = False) -> None:
    from huggingface_hub import CommitOperationAdd
    api = hub()
    for name, spec in plan.items():
        if only and name != only:
            continue
        repo_id = f"{ORG}/{name}"
        files = [f for f in spec["files"] if f[0] == "README.md"] if cards_only else spec["files"]
        if cards_only:
            api.create_commit(repo_id, repo_type=spec["type"], commit_message="Update the card",
                              operations=[CommitOperationAdd(path_in_repo=p, path_or_fileobj=l) for p, l in files])
            print(f"{repo_id}: card updated")
            continue
        api.create_repo(repo_id, repo_type=spec["type"], private=True, exist_ok=True)
        ops = [CommitOperationAdd(path_in_repo=path, path_or_fileobj=local) for path, local in files]
        print(f"{repo_id}: uploading {len(ops)} files ({spec.get('bytes', 0) / 1e9:.2f} GB) ...", flush=True)
        api.create_commit(repo_id, repo_type=spec["type"], operations=ops,
                          commit_message=f"Praxis Chess LoRA {TAG} (release candidate)")
        print(f"{repo_id}: done, private: https://huggingface.co/{'datasets/' if spec['type'] == 'dataset' else ''}{repo_id}")


def check_remote(plan: dict) -> bool:
    api = hub()
    ok = True
    for name, spec in plan.items():
        repo_id = f"{ORG}/{name}"
        remote = {}
        for f in api.list_repo_tree(repo_id, repo_type=spec["type"], recursive=True, expand=True):
            if getattr(f, "lfs", None):
                remote[f.path] = f.lfs.sha256
            elif hasattr(f, "size"):
                remote[f.path] = None
        bad = []
        for path, local in spec["files"]:
            if path not in remote:
                bad.append(f"missing {path}")
            elif remote[path] is not None and remote[path] != sha256(Path(local)):
                bad.append(f"sha256 differs: {path}")
        ok &= not bad
        print(f"{repo_id}: {'ok' if not bad else '; '.join(bad)} ({len(spec['files'])} files)")
    return ok


def tag(plan: dict) -> None:
    api = hub()
    for name, spec in plan.items():
        api.create_tag(f"{ORG}/{name}", tag=TAG, repo_type=spec["type"], tag_message=f"Praxis Chess LoRA {TAG}",
                       exist_ok=True)
        print(f"{ORG}/{name}: tagged {TAG}")


def publish(plan: dict) -> None:
    api = hub()
    for name, spec in plan.items():
        api.update_repo_settings(f"{ORG}/{name}", repo_type=spec["type"], private=False)
        print(f"{ORG}/{name}: public")
    c = api.create_collection(title="Praxis Chess LoRA v1", namespace=ORG, exists_ok=True,
                              description="Small models that explain chess mistakes from verified engine evidence: "
                                          "adapters, GGUFs, the comparison arms and the dataset.")
    notes = {LORA["2b"]: "The released model: 2B, full evidence graph",
             LORA["4b"]: "The 4B sibling: more valid chains, 3x slower",
             GGUF["2b"]: "2B for Ollama / llama.cpp (q4_K_M)", GGUF["4b"]: "4B for Ollama / llama.cpp (q4_K_M)",
             ARMS: "The comparison arms (R0-R2, 4B-R0), for reproducing the table",
             DATASET: "Evidence graphs, renders, verified targets, every published answer"}
    for name, spec in plan.items():
        api.add_collection_item(c.slug, item_id=f"{ORG}/{name}", item_type=spec["type"], note=notes[name],
                                exists_ok=True)
    print(f"collection: https://huggingface.co/collections/{c.slug}")


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("cmd", choices=["stage", "upload", "cards", "check-remote", "tag", "publish"])
    ap.add_argument("repo", nargs="?", help="upload: only this repo (its name without the org)")
    args = ap.parse_args()
    if args.cmd == "stage":
        plan = stage()
        problems = check(plan)
        (STAGE / "plan.json").write_text(json.dumps(plan, indent=1), encoding="utf-8")
        for name, spec in plan.items():
            print(f"{ORG}/{name} ({spec['type']}): {len(spec['files'])} files, {spec['bytes'] / 1e9:.2f} GB")
        if problems:
            print("\nPROBLEMS (nothing may be uploaded until these are fixed):")
            for p in problems:
                print("  " + p)
            sys.exit(1)
        print(f"\nchecks passed; cards and generated files in {STAGE.relative_to(ROOT)}")
        return
    plan = load(STAGE / "plan.json")
    if args.cmd == "upload":
        upload(plan, args.repo)
    elif args.cmd == "cards":
        upload(plan, args.repo, cards_only=True)
    elif args.cmd == "check-remote":
        sys.exit(0 if check_remote(plan) else 1)
    elif args.cmd == "tag":
        tag(plan)
    elif args.cmd == "publish":
        publish(plan)


if __name__ == "__main__":
    main()
