# Ambient background: implementation plan

**Status:** built (20 Oct), G1–G6. **Default changed to Off** the same day: none of the six styles is final; the player is designing their own (Paper Shaders, ShaderGradient or Unicorn Studio) to replace them.
**Why:** an animated mesh gradient replaces Prax's three.js particle body as the thing that "is" Prax on screen. Not an organic being for now: it is calm while you read, and its speed and volatility rise while you talk to Prax.

Done already (20 Oct): the canvas and the nav are pure black (`--canvas`, `--canvas-deep` = `#000`), and the sync banner is a glass slab (`.glass` in `index.css`). Glass only reads as glass over something to blur, which is what this background provides.

---

## 1. What it is

- One full-screen layer behind every page: `position: fixed; inset: 0; z-index: 0`, under `main`, the nav and the glass, `pointer-events: none`, `aria-hidden`.
- Black ground; plain white and grey light only, dimmed so text stays readable.
- Driven by one number, **energy** (0 to 1), eased so it never jumps:
  - **speed**: how fast the field drifts;
  - **volatility**: how far it warps and how sharp its edges get;
  - **brightness**: a little, capped (section 5).

## 2. Energy: what Prax is doing

Prax already has a state machine (`src/prax/state/runtime.ts`: dormant, aware, thinking, insight, speaking) and a voice analyser (`voice/audioGraph.ts`). The background reads them; no new events are needed.

| Prax state | When | Energy target |
|---|---|---|
| dormant | Reading any page | 0.10 |
| aware | Focus on an insight or card | 0.20 |
| thinking | A question sent to Prax (`QUERY_STARTED`), an analysis running | 0.60 |
| insight | An answer or finding arrives | 0.45 |
| speaking | Prax's reply streams or is spoken (`RESPONSE_STARTED`) | 0.70, plus the voice level (0 to 0.25) while audio plays |

- Easing: a critically damped spring, about 1.2 s to settle, so a burst of events never strobes.
- Time is integrated (`t += dt × speed`), never `t × speed`, so a change of speed never makes the field jump.
- `prefers-reduced-motion`: one still frame; energy changes brightness only, slowly.

## 3. Style options

**Round 1 (20 Oct):** A Nebula, B Aurora, C Orbs and D Silk (monochrome) were rejected. Only **E, Orchid nebula**, was kept. Taste references: a rich blue, violet, magenta and peach mesh gradient; plexus networks (points and thin lines on a 3D wave, depth of field, bokeh); colourful particle sculptures.

**Round 2** (the orchid family: E1 orchid nebula, E2 dusk mesh, P1 orchid plexus, P2 bokeh) was rejected: **plain white on black**, no colour.

**Round 3** (white on black: W1 nebula, W2 plexus wave, W3 plexus over nebula, W4 bokeh over nebula) was rejected: "not good".

**Round 4, six different forms, white on black:**

| # | Style | Looks like | Reacts to Prax as |
|---|---|---|---|
| **1** | **Dot matrix** | A fine grid of dots; waves ripple through it from two points | Ripples grow, tighten and speed up, like sound |
| **2** | **Contours** | A topographic map whose contour lines slowly move | The terrain shifts faster and gains finer folds |
| **3** | **Ridge lines** | Stacked horizontal lines with peaks in the middle (an audio-waveform look) | The peaks rise and jitter, like a voice |
| **4** | **Flow trails** | Hundreds of thin threads following a current, leaving trails | The current speeds up and turns turbulent |
| **5** | **Starfield** | Stars drifting toward you | Drift becomes warp: stars stretch into streaks |
| **6** | **Chess horizon** | A chessboard in perspective, scrolling toward a lit horizon | It moves faster and squares pulse |

## 4. Architecture

- **Renderer: one WebGL fragment shader** on a full-screen triangle (no three.js scene graph). Each style is a function in the same shader (`uStyle`); uniforms: `uTime`, `uEnergy`, `uVolatility`, `uBrightness`, `uResolution`, `uSeed`. Blobs are mesh-gradient control points (Nebula, Orbs), sine-band folds (Aurora) or domain-warped fbm noise (Silk).
- **Resolution**: drawn at 25–35% of the viewport and scaled up by CSS: the image is blurry by design, so this is free quality and 10× fewer pixels.
- **Frame budget**: 30 fps at energy below 0.3, 60 fps above; paused when the tab is hidden; one frame when reduced motion is on.
- **Fallback**: no WebGL, or a context loss, gives a static CSS radial-gradient image of the same style.
- **Files**:
  - `src/background/AmbientBackground.tsx`: mounted once in `App` next to `PraxHost`, never unmounted;
  - `src/background/shader.ts`: the GLSL, one function per style;
  - `src/background/energy.ts`: subscribes to `praxRuntime` and the voice level and exposes the eased energy;
  - `src/background/preference.ts`: style and motion, in `localStorage` like `Show Prax body` (a choice of this browser).
- **Glass everywhere it fits**: `.card` already blurs what is behind it; the sync banner is `.glass`; popovers stay opaque (they must read over anything).

## 5. Readability rules (non-negotiable)

- The brightest the field may get behind `main` is capped in the shader (target: luminance ≤ 0.12 at energy 0, ≤ 0.18 at energy 1), so body text keeps at least 12:1 contrast and secondary text at least 4.5:1.
- A vertical vignette darkens the field under the nav and the top 120 px, where the takeaway sits.
- A test samples the canvas behind `main` at energy 0 and 1 and fails if the cap is exceeded. The parity and reading harness is unaffected: the layer is `aria-hidden` with no text.

## 6. Settings → Display

- **Background**: the default from B1, plus the styles chosen in B2, and Off.
- **Motion**: Reacts to Prax (default) · Calm (energy fixed at 0.10) · Still (one frame).
- Applied at once, kept in this browser, no Save; a small live preview swatch beside the select.

## 7. Retiring the particle body

- Phase 1 keeps the three.js body behind `Show Prax body` (off by default) so nothing is lost while the background lands.
- Phase 3 removes it: `PraxCanvas`, `createPraxPoints`, `motion/`, `PraxHitTarget`, the `Show Prax body` setting, and the body's tests. `PraxAnchor` calls in pages go (they position the body). Prax's cards (`PraxStack`: thoughts, progress) stay, restyled as glass and docked bottom right; they no longer follow a body.

## 8. Milestones

| | Milestone | Done when |
|---|---|---|
| **G1** | Renderer and the default style | The chosen style draws behind every page at 30 fps, under 3% GPU on a laptop at idle; reduced motion gives one frame; no WebGL gives the CSS fallback |
| **G2** | Energy from Prax | Sending a question in Ask Prax visibly speeds the field up within 0.5 s and settles 1–2 s after the reply; tests drive `praxBus` events and read the eased energy |
| **G3** | Settings → Display | Background and Motion selects with a live swatch; persisted; tests for each value |
| **G4** | Other styles | Each chosen extra style passes the contrast test |
| **G5** | Retire the body | Particle code and its setting removed; Prax cards are glass, bottom right; suite green |
| **G6** | Docs | `DESIGN_SYSTEM.md`: principle 1 (ground and glass), tokens, the background component, the storyboard; screenshots recaptured |

## 9. Decisions needed

- **B1. Default style:** one of round 4 (1–6).
- **B2. Which styles Settings offers:** the default, plus which others; Off is always there.
- **B3. Motion setting:** Reacts to Prax · Calm · Still (recommended), or only on/off.
- **B4. Colour:** settled: plain white on black.
- **B5. Retire the particle body in this change (G5) or later:** recommended later, after the background has run a week.
- **B6. Where the energy reacts:** everywhere (recommended: Prax can answer from any page) or only on Ask Prax.

---

## 10. Results

Decisions taken: all six round-4 styles plus Off, **Contours** the default (the calmest to read over); Motion: Reacts to Prax · Calm · Still; reacts on every page; the particle body retired in this change.

| Milestone | What shipped |
|---|---|
| **G1** | `src/background/`: `styles.ts` (six painters), `AmbientBackground.tsx` (one canvas, mounted once in `App`, `z-index: -1`), half resolution scaled up, CSS `blur(7px)` and opacity 0.7, darker under the nav. 30 fps below energy 0.3; reduced motion draws one still frame (after a short warm-up so trails and stars have something to show) |
| **G2** | `energy.ts`: Prax's state (dormant 0.1, aware 0.2, thinking 0.6, insight 0.45, speaking 0.7) plus a quarter of the voice level, through a critically damped spring; time is integrated. Tested: a question lifts it above 0.4 within 1.5 s; it is back under 0.15 within 3 s of the answer |
| **G3** | Settings → Display: Background and Motion selects (applied at once, `localStorage` key `praxis.background`), Motion disabled when Off. Tested for every value and across a reload |
| **G4** | All six styles pass the readability test: mean luminance × opacity under 0.12 at high energy |
| **G5** | Removed: `prax/renderer/` (three.js canvas, shaders), `prax/geometry/`, `AnchorController`, the anchor registry, `PraxAnchor` (and its calls in 13 pages), `PraxHitTarget`, the render policy, `Show Prax body`, and the `three` dependency. Prax's cards are glass, docked bottom right, on Today, Progress and Library only, as before |
| **G6** | `DESIGN_SYSTEM.md`: principle 1, the canvas tokens, depth, the Prax and Ambient background components |

**Changed from the plan:**
- **Canvas 2D, not a WebGL shader.** The chosen styles are line and point drawings (contour tracing, trails, ridges), which 2D draws directly; at half resolution each frame is cheap, and there is no WebGL fallback to maintain.
- **The question card has no opener on the page any more.** It opened by clicking the body. The Ask Prax page is the way to ask; the card still shows answers and analysis progress. A small glass "Ask" button bottom right would bring it back (decision for later).
- **Kept:** `prax/motion/` (the runtime's state model and the voice followers) and `core/constants.ts`, which the state machine and focus intent still use; the body-only constants in them are now unused.

