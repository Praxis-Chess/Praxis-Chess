package com.praxis.play.patterns;

/**
 * How much weight a pattern's evidence can carry.
 *
 * Clearing the recurrence floor is not the same as being certain: three of five
 * games and twelve of twenty both satisfy the rule, and the first is the
 * thinnest thing that counts as a pattern at all. Without this the two render
 * in the same confident voice.
 *
 * A threshold, honestly labelled — not a probability. There is no statistic
 * worth computing from five games, and a confidence interval over that sample
 * would be a more sophisticated way of overclaiming rather than a fix for it.
 */
public enum Strength {
    /** At or near the floor. The UI hedges: "early signal". */
    EMERGING,
    /** Enough games that the tendency can be stated plainly. */
    ESTABLISHED
}
