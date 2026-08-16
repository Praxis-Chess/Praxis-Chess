# Prax evaluation

Judging an agent by demos measures the demos. This scores the **mechanics** of a
run against a fixed question set, so a change can be shown to help rather than
argued to.

```bash
python eval/prax_eval.py                          # everything
python eval/prax_eval.py --category blunders      # one group
python eval/prax_eval.py --id drills-today        # one case
python eval/prax_eval.py --limit 5                # smoke test
```

Needs the backend on `:8086` with Ollama and Stockfish running. Nothing is
mocked — the point is to measure the real thing. Conversation history is reset
before every case, because it leaks: a greeting once replayed the previous
answer's statistics verbatim, with no tool calls behind them.

## Workflow

```bash
python eval/prax_eval.py --out eval/runs/before.json
# ... make a change, restart the backend ...
python eval/prax_eval.py --out eval/runs/after.json
python eval/prax_eval.py --compare eval/runs/before.json eval/runs/after.json
```

The comparison lists only what changed state, labelled FIXED or REGRESSED. That
is the number to quote when deciding whether a prompt edit was worth it.

## What is measured

| Metric | Meaning |
|---|---|
| `toolSelectionRate` | cases with no missed and no forbidden tools |
| `missedCallCases` | a tool the question required was never called |
| `unnecessaryCallCases` | tools called that the question did not need |
| `badArgCases` | malformed arguments (`analyze_position` without `playedMove`, `color: "both"`, …) |
| `repeatedCallCases` | the same tool called twice with identical arguments |
| `overLongLoops` | more calls than the case allows |
| `ungroundedCases` | figures in the prose that appear in no citation or finding |
| `groundedness` | cited figures / total figures across the whole run |
| `avgLoopLength` | mean tool calls per question |

`groundedness` is the one to watch. An answer can read perfectly and still assert
seven numbers with nothing behind them — which is invisible in a demo and is
exactly what this architecture exists to prevent.

## Editing the set

`questions.json` declares what a **correct** run looks like, not what the current
model happens to do. When a case fails, the first question is whether the
expectation is wrong; only then whether the agent is.

Keep `id` stable when you reword a question, or the comparison loses its history.

`expectTools: []` with a populated `allowTools` means *"any reasonable subset is
fine, but these are the only sensible tools"* — used where several routes to an
answer are equally valid. `expectTools: []` with `allowTools: []` means **call
nothing**, which is the correct handling of small talk.
