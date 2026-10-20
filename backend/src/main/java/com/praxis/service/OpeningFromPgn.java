package com.praxis.service;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A game's opening as soon as it is synced (POLISH_PLAN.md N11), not only once it
 * is analysed: Chess.com's PGN carries an ECO header, and the ECO table names it.
 * Analysis keeps what is set here (GameAnalysisTransactionService sets a name only
 * when there is none).
 */
public final class OpeningFromPgn {

    private OpeningFromPgn() {}

    private static final Pattern ECO = Pattern.compile("\\[ECO \"([A-E]\\d\\d)\"\\]");

    /** The ECO code in a PGN's headers, or null. */
    public static String eco(String pgn) {
        if (pgn == null) return null;
        Matcher m = ECO.matcher(pgn);
        return m.find() ? m.group(1) : null;
    }
}
