"""
The Prax voice.

Calm, intelligent, restrained, slightly dry. Someone who has already thought
about the position before speaking — never enthusiastic, never needy.

Kokoro's voice inventory changes between releases; treat `voice` as a starting
point and audition the current list against the brief:
    medium-low pitch, low energy, clear articulation, minimal emotional range.
"""

PRAX_VOICE = {
    # British male tends to land closest to "dry and precise" with no extra tuning.
    "voice": "bm_george",
    # Slower than default. Unhurried, not sluggish.
    "speed": 0.88,
    # A beat before speaking reads as consideration rather than latency.
    "lead_in_ms": 120,
    "trim_silence": True,
}

SAMPLE_RATE = 24000  # Kokoro's native rate
