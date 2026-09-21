package com.praxis.domain.enums;

/**
 * Where a game came from.
 * Exists to keep practice games against the engine out of every statistic that
 * describes real play. Rating trend, win rate, opening performance, accuracy
 * trend and every answer Prax gives are about CHESS_COM games; silently folding
 * Stockfish games into them would be correct arithmetic over the wrong
 * population — the same failure as the accuracy zeros.
 */
public enum GameSource {
    /** Synced from Chess.com. The player's real games. */
    CHESS_COM,
    /** Played inside Praxis against the engine. */
    PRACTICE
}
