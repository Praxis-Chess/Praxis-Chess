# Teacher instructions, v2

Versioned: a change here is a new dataset version (plan §12). Two tasks share
one set of rules; `praxis_train.teacher` sends the matching section.

v2, after the v1 pilot on Qwen3.5-27B-FP8 (2026-09-26):
- composite passed 0 of 100: the chain used claim types that do not exist
  (FACT, CLAIM, EVIDENCE) and left out required arguments. v1 never listed
  the vocabulary. v2 lists it (filled in from the verifier's own schema), adds
  one worked example, and the client asks the server for schema-constrained
  JSON.
- prose passed 63 of 100: every claim was true; the explanations named moves
  the chain never earned, or said "already"/"allows" without the claim that
  licenses the word. v2 gives each item its own list of nameable moves and
  forbidden words.

## Rules for every answer

- Use ONLY the evidence block. Every move and square you mention must appear in
  the arguments of a claim in the reasoning chain.
- Say "allows", "because", "creates", "enables" or "lets" only if the chain
  contains a COUNTERFACTUAL claim. Say "already" or "ignored" only if it
  contains a THREAT_EXISTS claim. The verifier rejects the answer otherwise.
- Plain English for a club player (about 1300). No evaluations in pawns, no
  engine jargon, no field names or codes.
- Two or three sentences. Say what the move cost and why, then what the better
  move would have done. Vary the wording; never open with "The move".

## Task: prose

You are given the evidence (R3), a verified reasoning chain written by the
rules, the moves and squares you may name, and the words you may not use for
this item. Write only the `explanation` for that chain. Reply as JSON:
`{"explanation": "..."}`.

## Task: composite

No single rule explains this mistake: several apply, or none does. You are
given the evidence (R3), the consequence, and the causes whose preconditions
hold. Write the full diagnosis as JSON in the answer schema:
`reasoning_chain` (typed claims, at most {max_chain}), then `consequence`,
`mechanism` (one of the listed causes, or UNCLEAR if none clearly dominates),
`motif`, `critical_response` (the move in [R1]), `visibility` (the band in
[D1]), `explanation`. Name a cause only if the chain proves it.

Each claim is {"type", "args", "cites", "text"}:
- `type`: EXACTLY one of the types below. No other type exists.
- `args`: EXACTLY the arguments listed for that type. Moves in SAN ("Qxf7#"),
  squares like "f7", lists as JSON arrays.
- `cites`: the IDs of the evidence lines it rests on, without brackets
  (e.g. ["T1", "R1"]).
- `text`: one short sentence.

Claim types and their required args:
{claim_reference}
COUNTERFACTUAL `effect` is one of: {effects}.

Copy facts from the evidence; never compute new ones. The worked example shows
the shape of a correct answer.
