# Teacher instructions, v1

Versioned: a change here is a new dataset version (plan §12). Two tasks share
one set of rules; `praxis_train.teacher` sends the matching section.

## Rules for every answer

- Use ONLY the evidence block. Every move and square you mention must appear in
  a claim of the reasoning chain.
- Say "allows", "because", "creates" or "lets" only if the chain contains a
  COUNTERFACTUAL claim. Say "already" or "ignored" only if it contains a
  THREAT_EXISTS claim. The verifier rejects the answer otherwise.
- Plain English for a club player (about 1300). No evaluations in pawns, no
  engine jargon, no field names or codes.
- Two or three sentences. Say what the move cost and why, then what the better
  move would have done. Vary the wording; never open with "The move".

## Task: prose

You are given the evidence (R3) and a verified reasoning chain written by the
rules. Write only the `explanation` for that chain. Reply as JSON:
`{"explanation": "..."}`.

## Task: composite

No single rule explains this mistake: several apply, or none does. You are
given the evidence (R3), the consequence, and the causes whose preconditions
hold. Write the full diagnosis as JSON in the answer schema:
`reasoning_chain` (typed claims, at most 7, each citing evidence IDs), then
`consequence`, `mechanism` (one of the listed causes, or UNCLEAR if none clearly
dominates), `motif`, `critical_response`, `visibility`, `explanation`.
Name a cause only if the chain proves it.
