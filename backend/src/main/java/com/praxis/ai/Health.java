package com.praxis.ai;

/** Whether a provider answers, and if not, why. */
public record Health(boolean ok, String detail) {
    public static Health up() {
        return new Health(true, null);
    }

    public static Health down(String detail) {
        return new Health(false, detail);
    }
}
