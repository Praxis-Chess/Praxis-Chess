/**
 * Shader contract — Contract §5.
 *
 * The full uniform set is declared here in Phase 0 even though motion values are
 * held static, so that Phase 1 is pure parameter wiring with no shader restructure.
 *
 * Note: `aRest` is uploaded as the built-in `position` attribute (Three needs it
 * to compute a bounding sphere), and aliased back on the first line of main().
 */

/** Ashima Arts simplex noise 3D — public domain / MIT. Used for drift from Phase 1. */
const SIMPLEX_3D = /* glsl */ `
vec3 mod289(vec3 x) { return x - floor(x * (1.0 / 289.0)) * 289.0; }
vec4 mod289(vec4 x) { return x - floor(x * (1.0 / 289.0)) * 289.0; }
vec4 permute(vec4 x) { return mod289(((x * 34.0) + 1.0) * x); }
vec4 taylorInvSqrt(vec4 r) { return 1.79284291400159 - 0.85373472095314 * r; }

float snoise(vec3 v) {
  const vec2 C = vec2(1.0 / 6.0, 1.0 / 3.0);
  const vec4 D = vec4(0.0, 0.5, 1.0, 2.0);

  vec3 i  = floor(v + dot(v, C.yyy));
  vec3 x0 = v - i + dot(i, C.xxx);

  vec3 g = step(x0.yzx, x0.xyz);
  vec3 l = 1.0 - g;
  vec3 i1 = min(g.xyz, l.zxy);
  vec3 i2 = max(g.xyz, l.zxy);

  vec3 x1 = x0 - i1 + C.xxx;
  vec3 x2 = x0 - i2 + C.yyy;
  vec3 x3 = x0 - D.yyy;

  i = mod289(i);
  vec4 p = permute(permute(permute(
             i.z + vec4(0.0, i1.z, i2.z, 1.0))
           + i.y + vec4(0.0, i1.y, i2.y, 1.0))
           + i.x + vec4(0.0, i1.x, i2.x, 1.0));

  float n_ = 0.142857142857;
  vec3 ns = n_ * D.wyz - D.xzx;

  vec4 j = p - 49.0 * floor(p * ns.z * ns.z);

  vec4 x_ = floor(j * ns.z);
  vec4 y_ = floor(j - 7.0 * x_);

  vec4 x = x_ * ns.x + ns.yyyy;
  vec4 y = y_ * ns.x + ns.yyyy;
  vec4 h = 1.0 - abs(x) - abs(y);

  vec4 b0 = vec4(x.xy, y.xy);
  vec4 b1 = vec4(x.zw, y.zw);

  vec4 s0 = floor(b0) * 2.0 + 1.0;
  vec4 s1 = floor(b1) * 2.0 + 1.0;
  vec4 sh = -step(h, vec4(0.0));

  vec4 a0 = b0.xzyw + s0.xzyw * sh.xxyy;
  vec4 a1 = b1.xzyw + s1.xzyw * sh.zzww;

  vec3 p0 = vec3(a0.xy, h.x);
  vec3 p1 = vec3(a0.zw, h.y);
  vec3 p2 = vec3(a1.xy, h.z);
  vec3 p3 = vec3(a1.zw, h.w);

  vec4 norm = taylorInvSqrt(vec4(dot(p0, p0), dot(p1, p1), dot(p2, p2), dot(p3, p3)));
  p0 *= norm.x; p1 *= norm.y; p2 *= norm.z; p3 *= norm.w;

  vec4 m = max(0.6 - vec4(dot(x0, x0), dot(x1, x1), dot(x2, x2), dot(x3, x3)), 0.0);
  m = m * m;
  return 42.0 * dot(m * m, vec4(dot(p0, x0), dot(p1, x1), dot(p2, x2), dot(p3, x3)));
}

vec3 snoise3v(vec3 p) {
  return vec3(snoise(p), snoise(p + 19.19), snoise(p + 71.71));
}
`

export const PRAX_VERTEX = /* glsl */ `
${SIMPLEX_3D}

attribute vec3  aCluster;
attribute float aSeed;
attribute float aPhase;
attribute float aSize;

uniform float uTime;
uniform float uEnergy;
uniform float uTurbulence;
uniform float uCoherence;
uniform float uBreathing;
uniform float uExpansion;
uniform float uInsight;
uniform vec3  uPointer;
uniform float uPointerStrength;
uniform vec3  uLightDir;
uniform float uPixelRatio;
uniform float uParticlePx;
uniform float uCameraZ;
uniform float uNoiseFreq;
uniform float uNoiseSpeed;
uniform float uBreathRate;
uniform float uSweep;      // ANALYZE  — sweeping examination
uniform float uCrater;     // SYNC     — structural disturbance
uniform vec3  uCraterDir;
uniform float uBristle;    // QUERY    — fine high-frequency quills
uniform float uBristleRate;
uniform float uBristleAmp;
uniform float uBristleCoverage;

varying float vLit;
varying float vRim;
varying float vEnergy;
varying float vWave;

void main() {
  // position IS aRest — see Contract §5.
  vec3 p = position;
  vEnergy = uEnergy;

  // 1 - breathing. Per-particle phase keeps the pulse from reading as mechanical.
  p *= 1.0 + sin(uTime * uBreathRate + aPhase) * uBreathing * 0.02;

  // 2 - drift. Coherence decorrelates the noise sample per particle:
  //     high coherence -> all particles sample nearly the same noise -> moves as one body
  //     low  coherence -> each offset by aSeed -> independent shimmer
  //     Energy sets how fast the field churns through the noise.
  vec3 nc = p * uNoiseFreq
          + uTime * uNoiseSpeed * (0.4 + uEnergy * 1.8)
          + aSeed * (1.0 - uCoherence) * 10.0;
  p += snoise3v(nc) * uTurbulence * 0.15;

  // 3 - insight. Contraction toward the pre-baked cluster centroid.
  p = mix(p, aCluster, uInsight * 0.35);

  // 3b - ANALYZE: a band of raised attention travelling through the field.
  //      Reads as examination — Prax working across the games, not waiting on
  //      a network call. Distinct from the insight contraction above.
  float sweepY = sin(uTime * 0.55) * 1.25;
  float band = exp(-pow((p.y - sweepY) * 2.2, 2.0));
  p += normalize(p) * band * uSweep * 0.11;

  //      Only the OUTER shell tints. Measured on the rest radius, not the
  //      displaced one, so the colour band doesn't wobble with drift — the
  //      deformation spreads particles across ~0.73..1.47, so there is a real
  //      outer layer to isolate rather than an arbitrary cutoff.
  float outer = smoothstep(1.02, 1.32, length(position));
  vWave = band * uSweep * outer;

  // 3c - SYNC: a physical crater. Particles near uCraterDir are pressed inward,
  //      then recover — a structural dent rather than a pulse or a glow.
  float prox = max(dot(normalize(p), normalize(uCraterDir)), 0.0);
  p -= normalize(p) * pow(prox, 4.0) * uCrater * 0.38;

  // 3d - QUERY: fine quills. A sparse subset of particles oscillates rapidly
  //      along its own normal while a question is in flight.
  //
  //      Two separate frequencies on purpose. The SELECTION drifts slowly, so
  //      the spikes migrate around the body instead of the same points twitching
  //      in place; the OSCILLATION is fast, which is what reads as agitation.
  //      Per-particle phase from aPhase keeps them from firing in unison — in
  //      lockstep the whole body would simply pulse, which is breathing, the
  //      resting behaviour this has to stay distinguishable from.
  if (uBristle > 0.001) {
    float pick   = snoise(position * 5.5 + uTime * 0.8);
    float chosen = smoothstep(1.0 - uBristleCoverage, 1.0, pick * 0.5 + 0.5);
    float osc    = sin(uTime * uBristleRate + aPhase * 6.2831853);
    // abs() so a quill pushes OUT and returns, rather than also denting inward —
    // spikes from the surface, not a body wobbling through itself.
    p += normalize(p) * chosen * abs(osc) * uBristle * uBristleAmp;
  }

  // 4 - expansion. Sustained radius offset.
  p *= 1.0 + uExpansion * 0.15;

  // 5 - pointer influence. The field swells outward where the cursor is near
  //     and leans toward it. Falloff is deliberately wide (0.8, not 3.0) — a
  //     tight gaussian left all but a handful of particles untouched.
  vec3 toPointer = uPointer - p;
  float pd = length(toPointer);
  float infl = exp(-pd * pd * 0.8) * uPointerStrength;
  p += normalize(p) * infl * 0.26;                    // bulge along the normal
  p += normalize(toPointer + 1e-6) * infl * 0.12;     // lean toward the cursor

  // Lighting is faked from the surface normal. No Three.js lights.
  vec3 n = normalize(p);
  vLit = max(dot(n, normalize(uLightDir)), 0.0);
  vRim = pow(1.0 - abs(n.z), 2.0);

  vec4 mv = modelViewMatrix * vec4(p, 1.0);
  gl_Position = projectionMatrix * mv;

  // Particle size is a real pixel diameter, NOT derived from world units.
  // Tying it to -mv.z alone made each point larger than the whole object.
  // Depth contributes only a subtle 0.9..1.12 nudge so the field reads as volume.
  float depth = clamp(uCameraZ / -mv.z, 0.88, 1.14);
  gl_PointSize = aSize * uPixelRatio * uParticlePx * depth * mix(0.66, 1.16, vLit);
}
`

export const PRAX_FRAGMENT = /* glsl */ `
precision mediump float;

uniform vec3  uBaseColor;
uniform vec3  uRimColor;
uniform vec3  uSpeakColor;
uniform float uSpeaking;
uniform float uRimIntensity;
uniform float uBrightness;

varying float vLit;
varying float vRim;
varying float vEnergy;
varying float vWave;

void main() {
  float d = length(gl_PointCoord - vec2(0.5));
  if (d > 0.5) discard;

  // Tight falloff — only the outermost ~12% of the radius softens. A wider
  // gradient would bleed neighbouring points into each other.
  float disc = smoothstep(0.5, 0.38, d);

  // Lighting drives opacity so shadow-side points recede rather than filling in.
  // Energy lifts the whole field slightly — a more active Prax reads brighter.
  float alpha = disc * mix(0.20, 0.90, vLit) * (0.82 + vEnergy * 0.34 + uBrightness);
  // Speaking tints the BODY. Lighting is preserved by modulating the accent
  // with vLit, so the lit side reads as brighter pink and the shadow side as
  // darker pink — an uneven 3D organism in the accent colour, not a flat disc.
  vec3 speak = uSpeakColor * mix(0.45, 1.15, vLit);
  vec3 body  = mix(uBaseColor, speak, clamp(uSpeaking, 0.0, 1.0));

  // The examination wave tints only the particles it is passing over, and only
  // on the outer shell. Capped well short of full orchid and modulated by
  // lighting, so the band reads as travelling over a lit surface rather than as
  // a flat colour key. Reuses uRimColor deliberately: one orchid, not two.
  vec3 waveCol = uRimColor * mix(0.5, 1.15, vLit);
  body = mix(body, waveCol, clamp(vWave, 0.0, 1.0) * 0.72);

  // Insight rim sits on top: grayscale body + pink rim stays distinct from
  // pink body, which is what separates "discovered" from "expressing".
  vec3 col = mix(body, uRimColor, clamp(vRim * uRimIntensity, 0.0, 1.0));

  gl_FragColor = vec4(col, alpha);
}
`
