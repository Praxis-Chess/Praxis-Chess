# Teacher instructions, v3

Versioned: a change here is a new dataset version (plan §12). Two tasks share
one set of rules; `praxis_train.teacher` sends the matching section.

v3, after the v2 pilot on Qwen3.5-27B-FP8 (2026-09-26):
- composite: 66% of claims true (v1: 0%), but only 2 of 100 answers passed.
  Most false claims were one kind: MATERIAL_CHANGE written as "-3 pawns" where
  the verifier reads the integer 3. v2 named each claim's arguments but never
  said what goes in them. v3 gives each argument's format and where in the
  evidence to copy it from, written from the verifier's own checks, and the
  client constrains each argument's type in the schema.
- prose: 71 of 100 passed; unchanged in v3.

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

Each claim is {"type", "args", "cites", "text"}. `cites` holds the IDs of the
evidence lines it rests on, without brackets (e.g. ["T1", "R1"]). `text` is
one short sentence.

Every argument is copied from the evidence, never computed. Moves are SAN
exactly as written in the evidence ("Qxf7#"); squares are like "f7"; numbers
are plain integers with no units or words. Brackets name the evidence line to
copy from. Arguments marked (optional) may be left out; leave them out if
unsure.

- THREAT_EXISTS: move = the threatening move in [T1], and only if [T1] says it
  was already a threat.
- DOES_NOT_ADDRESS: move = the move played ([M]); threat = that threat's move
  ([T1] or [R1]).
- CRITICAL_REPLY: move = the critical reply in [R1]; after (optional) = the
  move played; result (optional) = MATE, MATERIAL or NONE; ply (optional) =
  the consequence ply in [PC].
- MATERIAL_CHANGE: amount = the net pawns the player LOSES in the played line,
  the number in [PC] ("loses 3 pawns net" → 3; "no material lost" → 0).
  With line = "BEST" it is the best line's net for the player instead, where a
  gain is negative ("best line wins 1 net" in [B] → -1). line (optional) =
  PLAYED or BEST.
- MATE_IN: n = the number of moves to mate stated in the evidence; line
  (optional) = PLAYED or BEST.
- COUNTERFACTUAL: alt_move = the best move in [B]; effect = REPLY_FAILS when
  [CF1] says the reply "does not work", REPLY_ILLEGAL when [CF1] says it is not
  legal, WINS_MATERIAL when [B] says the best line wins material, MATES when
  [B] says it mates; played (optional) = the move played; reply (optional) =
  the move in [R1].
- MOVED_INTO_ATTACK: square = where the played piece lands, only if [M.e…]
  gives a negative SEE there.
- LOSING_CAPTURE: move = the move played, only if it captures and its SEE is
  negative.
- DEFENDER_REMOVED: square = the square in a [Δ…] line; defender = a square in
  that line's "defenders [before]" list that is missing from the "after" list.
- TACTIC_GEOMETRY: kind, by and targets copied from one [G…] line ("rook on a6
  skewers c6 to e6" → kind SKEWER, by "a6", targets ["c6", "e6"]); move
  (optional) = the move in [R1].
- OPENS_LINE / BLOCKS_LINE: slider = the sliding piece's square and target =
  the square it now reaches, from a [Δ…] "line opened" / "line blocked" item
  ("queen on d8 now reaches b8" → slider "d8", target "b8").
- ATTACK_DEFENSE: square = a square with a piece on it; attackers and
  defenders = the complete lists of squares attacking and defending it. Avoid
  this claim unless the evidence lists them.
- VISIBILITY: band = the band in [D1]; depth (optional) = the depth in [D1].

Before answering, check the explanation: "already"/"ignored" only if your chain
has a THREAT_EXISTS claim; "allows"/"because"/"creates"/"enables"/"lets" only
if it has a COUNTERFACTUAL claim; every move and square in it appears in your
claims' arguments. The worked example shows the shape of a correct answer.
