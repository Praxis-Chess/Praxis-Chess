# Meet PRAX

```
A visual presence that thinks, reacts, and moves with what’s happening around it. Its motion is more than decoration; it’s how Prax shows what it’s noticing, doing, and feeling in the moment.
```

A working reference for **changing** Prax: adding a behaviour, wiring a new
signal, or reshaping it toward something more alive. It documents what is there
now and, where it matters, why it is that way — several of the rules below exist
because the obvious alternative was tried and produced something wrong.

For the reasoning agent behind `Ask Prax` (tools, evidence, grounding), see
[ARCHITECTURE.md](ARCHITECTURE.md#prax--the-reasoning-layer). This file is about
the body.

---

## 1. The premise

Prax is not a chatbot with an avatar. It is a **visual thinking presence**: one
persistent particle field, rendered on every page, whose motion is a readout of
what the system is actually doing. Nothing about it is decorative — every
deformation means something, and every meaning has exactly one deformation.

Concretely: ~1,796 particles, a single `THREE.Points`, one draw call, one WebGL
canvas that survives navigation.

---

## 2. The rule everything else follows

```
   EVENT            STATE              MOTION             GPU
  (what             (what it           (what its          (what you
   happened)    →    means)        →    body does)   →     see)

  praxBus         PraxRuntime        MotionModel        uniforms
  emit()          FSM + flags        springs/envelopes   shaders.ts
```

Each layer only knows the one below it. The consequences are load-bearing:

- **React never animates.** It mounts the canvas and emits events. The loop runs
  in `PraxCanvas` via `requestAnimationFrame` and writes uniforms directly. A
  `setState` per frame would re-render the tree 60×/second.
- **Pages never touch motion.** A page emits `INSIGHT_FOUND`; it does not decide
  that insight means "contract toward the cluster centroid". Change the meaning
  in one place and every page follows.
- **The shader never decides anything.** It receives numbers.

If you find yourself reaching two layers down, the design has drifted.

---

## 3. File map

```
prax/
  PraxHost.tsx          everything Prax mounts + the public export surface
  core/
    constants.ts        ALL tunables. Start here when adjusting feel.
    events.ts           PraxEvent union + praxBus
    random.ts           mulberry32 — seeded, so the form is reproducible
  geometry/
    generate.ts         particle positions, clusters, seeds, sizes
  motion/
    integrate.ts        Spring, Envelope, EnvelopeFollower
    model.ts            STATE_TARGETS, MotionModel, the channels
  state/
    runtime.ts          the FSM, presence, praxRuntime singleton
    renderPolicy.ts     full / reduced / frozen
    routerBridge.ts     navigation → events
    narrationStore.ts   the current progress message
    progressNarrator.ts deterministic narration from analysis events
  renderer/
    PraxCanvas.tsx      the render loop; the only place uniforms are written
    createPraxPoints.ts geometry + material + uniform declarations
    shaders.ts          GLSL. Vertex assembly order is a contract.
    screenPos.ts        render loop → DOM bridge (mutable, not state)
  anchor/
    registry.ts         where each page wants Prax
    AnchorController.ts smooth relocation between anchors
    viewport.ts         the ONLY screen→world conversion
    cardPlacement.ts    where the DOM cards go
  interaction/
    pointer.ts          pointer position + dwell
    interactions.ts     semantic UI interactions → events
    useFocusIntent.ts   hover-with-intent hook for page elements
  ui/
    PraxStack.tsx       the single positioned column beside Prax
    PraxAsk.tsx         ask a question, render the answer
    PraxProgress.tsx    narration during analysis
    PraxThought.tsx     insight card — CURRENTLY UNMOUNTED (see §11)
    PraxHitTarget.tsx   invisible click disc over the body
    PraxDebugPanel.tsx  force states and fire events by hand
    thoughts.ts         insight content registry
  voice/
    index.ts            praxSpeak / stopSpeaking / band stepping
    KokoroVoice.ts      POSTs /api/voice/speak
    MockVoice.ts        silent fallback
    audioGraph.ts       WebAudio analyser → low/mid/high bands
```

---

## 4. Layer 1 — events

`core/events.ts` holds the whole vocabulary. Anything can emit; the runtime is
the only subscriber that matters.

```ts
praxBus.emit({ type: "ANALYSIS_STARTED" });
```

| Group      | Events                                                                            |
| ---------- | --------------------------------------------------------------------------------- |
| Navigation | `NAVIGATION_START`, `NAVIGATION_END`                                              |
| Analysis   | `ANALYSIS_STARTED`, `ANALYSIS_PROGRESS`, `ANALYSIS_FINISHED`, `ANALYSIS_STOPPING` |
| Findings   | `PATTERN_DETECTED`, `INSIGHT_FOUND`, `INSIGHT_DISMISSED`                          |
| Attention  | `USER_FOCUS`, `USER_FOCUS_END`                                                    |
| Drills     | `DRILL_CORRECT`, `DRILL_WRONG`                                                    |
| Query      | `QUERY_STARTED`, `QUERY_FINISHED`                                                 |
| Speech     | `RESPONSE_STARTED`, `RESPONSE_FINISHED`                                           |

`interaction/interactions.ts` sits above this with a **semantic** vocabulary for
UI actions — `praxInteract('PRIMARY_ACTION')`, `'SYNC_STARTED'`,
`'GAME_SELECTED'`, `'EXAMINE'`, `'DISMISS'`, `'FILTER_CHANGED'`. Pages call
`praxInteract`; it decides which bus event (if any) that becomes. Prefer it over
`praxBus.emit` from page code: it keeps "what the user did" separate from "what
Prax does about it".

---

## 5. Layer 2 — state

### The FSM

Five states, in `state/runtime.ts`:

```
dormant ── USER_FOCUS ──▶ aware
   │                        │
   ├── ANALYSIS_STARTED ────┤
   ├── QUERY_STARTED ───────┴──▶ thinking ── INSIGHT_FOUND ──▶ insight
                                     │                            │
                                     │                   RESPONSE_STARTED
                                     ▼                            ▼
                                  dormant ◀── RESPONSE_FINISHED ─ speaking
```

Transitions live in one table (`TRANSITIONS`). An event with no entry for the
current state is simply ignored — that is the intended way to say "not
applicable here".

### Condition flags beat edge triggers

`analysisRunning` is set on `ANALYSIS_STARTED` and cleared on
`ANALYSIS_FINISHED`. It exists because an edge-triggered signal is **lost
forever** if the FSM happens to be in a state with no transition for it — which
is exactly what once left Prax dormant through an entire analysis run. There is
a related rule at the bottom of `send()`: leaving a thought while a run is still
going returns to `thinking`, not `dormant`, because the work has not stopped
just because a card was dismissed.

**If you add a long-running activity, give it a flag, not just an event.**

### Presence and render policy

`presence` (`absent | ambient | focused | engaged`) is about where Prax sits on
the page. `renderPolicy` (`full | reduced | frozen`) is about how hard it works:
`frozen` when the document is hidden, `reduced` (20 fps) when it is not the
focus. Both are separate from the FSM on purpose — being busy and being visible
are unrelated questions.

---

## 6. Layer 3 — motion

### Primitives (`motion/integrate.ts`)

| Primitive          | Shape                                     | Use for                                                |
| ------------------ | ----------------------------------------- | ------------------------------------------------------ |
| `Spring`           | critically damped, symmetric              | converging to a target value                           |
| `Envelope`         | shaped rise → fall, one-shot              | impulses with a specific feel                          |
| `EnvelopeFollower` | asymmetric attack/release toward a target | sustained states that must ramp differently in and out |

They are not interchangeable. A spring is symmetric — if a behaviour needs to
arrive fast and leave slowly, it cannot be a spring.

### Channels

`STATE_TARGETS` maps each FSM state to the five **continuous** parameters:

```ts
                energy  turbulence  coherence  breathing  expansion
  dormant        0.15      0.03       0.85       0.08       0.15
  aware          0.22      0.08       0.90       0.10       0.02
  thinking       0.35      0.30       0.55       0.16       0.04
  insight        0.75      0.12       0.95       0.12      -0.20
  speaking       0.45      1.00       0.70       0.25       0.02
```

- **energy** — how fast the noise field churns
- **turbulence** — noise _amplitude_
- **coherence** — noise _correlation_. High: all particles sample nearly the
  same noise and the body moves as one. Low: each is offset by `aSeed` and the
  field shimmers independently. This is the parameter that makes Prax read as a
  body rather than a cloud.
- **breathing** — resting pulse
- **expansion** — sustained radius offset (negative contracts)

Alongside these are **discrete channels**, each owning one meaning:

| Channel        | Driver                                 | Means                                              |
| -------------- | -------------------------------------- | -------------------------------------------------- |
| `insight`      | `Envelope`                             | contraction toward cluster centroids               |
| `sweep`        | `EnvelopeFollower`, on `thinking`      | a band travelling the body — examining the library |
| `bristle`      | `EnvelopeFollower`, on `setQuerying()` | fine high-frequency quills — a question in flight  |
| `crater`       | `Envelope` + random direction          | a structural dent — sync                           |
| `speaking`     | `EnvelopeFollower`                     | body colour toward orchid                          |
| `rimIntensity` | floor + impulse                        | pink rim — "there is something for you"            |

**One meaning, one channel.** `bristle` is separate from `sweep` rather than
being "louder thinking" precisely because analysis and answering a question are
different acts and must not borrow each other's reading.

### Voice → motion

`voice/audioGraph.ts` runs a WebAudio analyser over the speech buffer and
produces low/mid/high bands. `stepVoiceEnergy()` pushes them into
`motion.setSpeaking(low, mid, high)` every frame, where they add to expansion,
brightness and turbulence. Colour, though, is driven by the **state**, not the
amplitude — the tint means "Prax is expressing itself", so it must hold steady
between syllables rather than flicker with the waveform.

---

## 7. Layer 4 — GPU

### Geometry (`geometry/generate.ts`)

Generated once, seeded with `mulberry32(SEED)` so the form is identical across
reloads. Per-particle attributes:

- `position` — the rest shape (aliased as `aRest` in the shader; Three needs the
  name `position` to compute a bounding sphere)
- `aCluster` — pre-baked cluster centroid, the insight contraction target
- `aSeed` — decorrelation seed
- `aPhase` — per-particle phase offset, so pulses never fire in unison
- `aSize` — base point size

The rest shape uses **fBm octaves** where amplitude falls as frequency rises.
Equal-amplitude waves cancel back into a sphere — that is why `DEFORM_OCTAVES`
is a descending list and not a flat one.

### Vertex assembly order — a contract

`shaders.ts` applies deformations in a fixed sequence. Order matters because
each step operates on the output of the last:

```
1   breathing        p *= 1 + sin(...) * uBreathing
2   drift            coherence-decorrelated simplex noise
3   insight          mix(p, aCluster, uInsight)
3b  sweep band       ANALYZE — radial push in a travelling band
3c  crater           SYNC — inward press near uCraterDir
3d  bristle          QUERY — fast radial quills on a sparse subset
4   expansion        p *= 1 + uExpansion
5   pointer          bulge + lean toward the cursor
```

Then lighting is faked from `normalize(p)` — there are no Three.js lights — and
`gl_PointSize` is computed.

**`gl_PointSize` is a real pixel diameter**, from `uParticlePx * uPixelRatio`
with only a subtle depth nudge. Deriving it from world units once produced
points larger than the object itself, which rendered as a solid blob.

### Adding a uniform

Four files, always:

1. `constants.ts` — the tunable
2. `shaders.ts` — `uniform float uX;` and the block that uses it
3. `createPraxPoints.ts` — `uX: { value: 0 }`
4. `PraxCanvas.tsx` — `u.uX.value = m.x` in the loop

Miss #4 and it silently stays zero. Several parameters were scaffolded and
never connected that way.

---

## 8. The DOM layer

The canvas is fixed at `zIndex 50`, full viewport, `pointerEvents: none`.
Everything else layers on top.

### Where Prax sits

Pages declare an anchor with `<PraxAnchor x={0.68} y={0.46} />`.
`AnchorController` eases the organism between anchors on navigation rather than
teleporting; `anchor/viewport.ts` holds the **only** screen→world conversion in
the system (perspective, not orthographic — volume-without-mesh is the whole
rendering premise, and orthographic discards the depth cue that produces it).

Each frame the loop writes Prax's projected screen position to
`renderer/screenPos.ts`. That module is a **plain mutable object, not React
state** — it updates 60×/second and must never trigger a render.

### Where the cards sit

`PraxStack` owns position exactly once and the cards flow inside it. Before
that, each card anchored itself and they rendered on top of each other.

`anchor/cardPlacement.ts` computes placement from live measurements — never
per-page offsets:

- **safe top** — measured bottom of `nav` and `[data-prax-avoid]`
- **content edge** — the real `<main>` box (max-width 1280, centred, so a wide
  screen has a genuine free gutter beside it)
- **card height** — `ResizeObserver`, because the answer arrives after mount

Horizontal preference: **content gutter → right of Prax → below Prax.** Never
left; left is where page content lives. Vertical: centred on Prax, clamped so a
long answer grows upward and can never leave the viewport.

`computePlacement()` takes its measurements as arguments, so it is testable
without a browser.

### Cards float over content

All three cards paint an **opaque** base under the surface tint
(`--surface` is `rgba(255,255,255,0.035)` — designed for a card sitting _on_ the
page, not floating over it). Without that, charts and game rows read straight
through the text.

---

## 9. Backend connections

Prax touches the backend at exactly three points. Everything else reaches it
through events emitted by page-level hooks.

| Path                    | Caller           | Shape                                                                |
| ----------------------- | ---------------- | -------------------------------------------------------------------- |
| `POST /api/prax/ask`    | `PraxAsk.tsx`    | `{question}` → `{answer, findings, evidence, steps, partial, model}` |
| `GET /api/prax/status`  | app startup      | `{available}` — false hides the entry point                          |
| `POST /api/voice/speak` | `KokoroVoice.ts` | `{text, voice, speed}` → WAV                                         |
| `GET /api/voice/status` | `voice/index.ts` | `{available}` — false selects `MockVoice`                            |

Indirect, via events:

| Source                                                       | Emits                                              |
| ------------------------------------------------------------ | -------------------------------------------------- |
| `hooks/useAnalysisProgress` (polls `/api/analysis/progress`) | `ANALYSIS_STARTED/PROGRESS/FINISHED`               |
| the same hook (reads `/api/insights`)                        | `PATTERN_DETECTED`                                 |
| `pages/Today`                                                | `INSIGHT_FOUND`                                    |
| `pages/Session`                                              | `DRILL_CORRECT`, `DRILL_WRONG`                     |
| `components/SyncStatusBanner`                                | `praxInteract('SYNC_STARTED' \| 'PRIMARY_ACTION')` |

There is **no socket and no server push.** Analysis progress is polled and
converted to events client-side. The narration you see during a run is generated
by `progressNarrator.ts` from those structured counts — _not_ by the LLM. A
message about work happening right now cannot wait on a model, and must not be
free to invent numbers about it.

`QUERY_STARTED` / `QUERY_FINISHED` bracket the `fetch` in `PraxAsk`, with
`QUERY_FINISHED` in a `finally` so a failed request settles the body too.

---

## 10. Cookbook — adding a behaviour

Say you want Prax to shiver when a drill is answered wrong.

**1. Is there an event?** `DRILL_WRONG` exists. If not, add it to the
`PraxEvent` union in `core/events.ts`.

**2. Does it change what Prax _is_, or just what it _does_ right now?**

- _Is_ → add a transition to `TRANSITIONS`. If it lasts, add a condition flag.
- _Does_ → an impulse in the `switch` inside `send()`. No state change.

A shiver is momentary: impulse.

**3. Pick a primitive.** Momentary and shaped → `Envelope`. Sustained with
different in/out feel → `EnvelopeFollower`. Converging → `Spring`.

**4. Add the channel** in `MotionModel`: the private driver, a field on
`current`, and a line in `step()`.

**5. Add the uniform** — all four files from §7.

**6. Write the deformation** in the vertex shader, in assembly order, with a
comment saying what it _means_, not what it does.

**7. Put the tunables in `constants.ts`.** Not inline in the shader.

**8. Verify it.** `PraxDebugPanel` can force states and fire events. For the
state→motion path, the model is pure TypeScript and can be stepped in Node
without a browser:

```ts
praxRuntime.send({ type: "QUERY_STARTED" });
for (let t = 0; t < 200; t += 16) praxRuntime.motion.step(0.016);
console.log(praxRuntime.motion.current.bristle); // → 0.727
```

That is how the bristle channel was checked, including the negative case —
that `ANALYSIS_STARTED` raises `sweep` and leaves `bristle` alone.

---

## 11. Invariants

Break these and something subtle goes wrong later.

1. **React never runs the animation loop.**
2. **One meaning, one channel.** Reusing a channel for a second meaning makes
   both unreadable.
3. **Motion targets live in `STATE_TARGETS`**, not at call sites.
4. **`screenPos` is mutable, never state.**
5. **`viewport.ts` is the only screen→world conversion.**
6. **Sustained activity needs a condition flag**, not just an edge event.
7. **Every uniform needs the per-frame write.**
8. **Per-particle phase on anything rhythmic.** In lockstep the body pulses, and
   pulsing is breathing — the resting behaviour everything else must stay
   distinguishable from.
9. **Narration is deterministic.** Do not route progress messages through a model.
10. **Amplitude is not the only dial.** Rate, coverage and correlation carry more
    character per unit of visual noise. `bristle` reads as agitation because it
    is fast and sparse, not because it is big.

## Current state and where the organic refactor goes

Working and wired: the full event→state→motion→GPU path, anchors and relocation,
pointer reactivity with dwell, deterministic narration, the ask/answer card,
local TTS with audio-driven bands.

Deliberately unmounted: `PraxThought` (the insight card with Listen / Examine /
Dismiss). The component and the `praxThoughts` registry are intact and still
being populated — only the render is removed, pending speech. Restoring it is
adding `<PraxThought />` back in `PraxStack` and folding `hasThought` into
`visible`.

If the goal is a more organic being, the honest assessment of what is missing:

- **No idle intent.** Prax reacts; it never initiates. There is no drive,
  fatigue, curiosity or boredom — nothing that would make it move when nothing
  has happened. This is the biggest gap between "reactive visualisation" and
  "creature".
- **State is instantaneous.** The FSM has no memory and no momentum. A creature
  that has been working for ten minutes should not look identical to one that
  just started.
- **Deformations are independent.** They sum. A real body's responses interact —
  one should suppress, amplify or colour another. A blend/priority layer between
  the channels and the uniforms is where that would live.
- **`§6 reduced` deviates from spec.** It currently triggers on anchor
  absent/partial rather than strictly "scrolled out of view".
- **Voice is one-way.** Speech drives motion; nothing drives speech except an
  explicit call. No breath before speaking, no hesitation.
