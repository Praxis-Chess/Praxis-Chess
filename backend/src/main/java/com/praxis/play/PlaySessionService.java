package com.praxis.play;

import com.github.bhlangonijr.chesslib.Board;
import com.github.bhlangonijr.chesslib.Side;
import com.github.bhlangonijr.chesslib.move.Move;
import com.github.bhlangonijr.chesslib.move.MoveList;
import com.praxis.config.AppProperties;
import com.praxis.config.PraxisClock;
import com.praxis.play.OpponentProfileService.OpponentProfile;
import com.praxis.play.domain.PracticeGame;
import com.praxis.play.domain.enums.PracticeStatus;
import com.praxis.play.repository.PracticeGameRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The game in progress. Server-authoritative on purpose.
 *
 * The browser validates moves too, with chess.js, but only so the board feels
 * instant. The truth is here: a client that sends an illegal move, or a move out
 * of turn, is refused. Anything else and the archived PGN — which becomes the
 * measurement — could be a fiction.
 */
@Service
public class PlaySessionService {

    private static final Logger log = LoggerFactory.getLogger(PlaySessionService.class);

    /** Opponent thinking time. Long enough to play sensibly, short enough to feel live. */
    private static final int MOVE_MS = 300;

    private final PracticeGameRepository games;
    private final OpponentProfileService profiles;
    private final OpponentService opponent;
    private final AppProperties props;
    private final PraxisClock clock;

    public PlaySessionService(PracticeGameRepository games, OpponentProfileService profiles,
                              OpponentService opponent, AppProperties props, PraxisClock clock) {
        this.games = games;
        this.profiles = profiles;
        this.opponent = opponent;
        this.props = props;
        this.clock = clock;
    }

    /** What the client needs after any state change. */
    public record MoveResult(
            String fen,
            String opponentMove,     // SAN, or null when the game ended on the player's move
            String sanMoves,
            PracticeStatus status,
            String result,           // "win" | "loss" | "draw" from the player's side
            String endReason,
            boolean playerToMove
    ) {}

    @Transactional
    public PracticeGame start(String colorPreference, Integer requestedSkill) {
        String username = props.chessCom().username();
        OpponentProfile profile = profiles.build(username, requestedSkill);

        String colour = resolveColour(colorPreference);
        Board board = new Board();

        PracticeGame game = PracticeGame.builder()
                .username(username)
                .playerColor(colour)
                .skillLevel(profile.skillLevel())
                .targetEco(profile.targetEco())
                .targetOpening(profile.targetOpening())
                .targetedWeakness(profile.targetedWeakness())
                .currentFen(board.getFen())
                .sanMoves("")
                .status(PracticeStatus.IN_PROGRESS)
                .build();
        games.save(game);

        // Playing black means the engine opens, so make its move before the
        // player ever sees the board.
        if ("black".equals(colour)) {
            applyOpponentMove(game, board, profile);
            games.save(game);
        }
        return game;
    }

    public Optional<PracticeGame> find(UUID id) {
        return games.findById(id);
    }

    /**
     * Apply the player's move, then the opponent's.
     *
     * @param uci the player's move, e.g. e2e4 or e7e8q
     */
    @Transactional
    public MoveResult move(PracticeGame game, String uci) {
        if (game.getStatus() != PracticeStatus.IN_PROGRESS) {
            return snapshot(game, null);
        }

        Board board = replay(game);
        if (!isPlayersTurn(game, board)) {
            throw new IllegalStateException("Not your move");
        }

        Move parsed = resolve(board, uci);
        if (parsed == null) {
            throw new IllegalArgumentException("Illegal move: " + uci);
        }

        appendMove(game, board, parsed);

        if (finishIfOver(game, board)) {
            return snapshot(game, null);
        }

        OpponentProfile profile = profiles.build(game.getUsername(), game.getSkillLevel());
        String opponentSan = applyOpponentMove(game, board, profile);
        finishIfOver(game, board);
        games.save(game);

        return snapshot(game, opponentSan);
    }

    /**
     * Take back the player's last move and the opponent's reply.
     *
     * Permitted, but it costs the game its rating: an undone game still gets
     * analysed and shown, and is excluded from improvement tracking. Counting it
     * silently would corrupt every later comparison, which is worse than not
     * offering undo at all.
     */
    @Transactional
    public MoveResult undo(PracticeGame game) {
        if (game.getStatus() != PracticeStatus.IN_PROGRESS) return snapshot(game, null);

        List<String> moves = sanList(game);
        int drop = Math.min(moves.size(), moves.size() >= 2 ? 2 : 1);
        if (drop == 0) return snapshot(game, null);

        List<String> kept = moves.subList(0, moves.size() - drop);
        Board board = new Board();
        MoveList list = new MoveList();
        try {
            if (!kept.isEmpty()) {
                list.loadFromSan(String.join(" ", kept));
                for (Move m : list) board.doMove(m);
            }
        } catch (Exception e) {
            log.warn("[play] undo replay failed for {}: {}", game.getId(), e.getMessage());
            return snapshot(game, null);
        }

        game.setSanMoves(String.join(" ", kept));
        game.setCurrentFen(board.getFen());
        game.setRated(false);
        game.setUndoCount(game.getUndoCount() + 1);
        games.save(game);

        return snapshot(game, null);
    }

    @Transactional
    public MoveResult resign(PracticeGame game) {
        if (game.getStatus() == PracticeStatus.IN_PROGRESS) {
            // Resignation is a real outcome, so the game is FINISHED rather than
            // ABANDONED — it gets analysed and counted like any loss.
            game.setStatus(PracticeStatus.FINISHED);
            game.setResult("loss");
            game.setEndReason("RESIGNATION");
            game.setFinishedAt(clock.now());
            games.save(game);
        }
        return snapshot(game, null);
    }

    @Transactional
    public void abandon(PracticeGame game) {
        if (game.getStatus() == PracticeStatus.IN_PROGRESS) {
            game.setStatus(PracticeStatus.ABANDONED);
            game.setEndReason("ABANDONED");
            game.setFinishedAt(clock.now());
            games.save(game);
        }
    }

    // --- internals ---

    private String applyOpponentMove(PracticeGame game, Board board, OpponentProfile profile) {
        String uci = opponent.chooseMove(board, game, profile, MOVE_MS);
        if (uci == null) {
            log.warn("[play] opponent could not move in {}", board.getFen());
            return null;
        }
        Move m = resolve(board, uci);
        if (m == null) return null;
        String san = toSan(board, m);
        appendMove(game, board, m);
        return san;
    }

    /** SAN must be produced BEFORE the move is played — it depends on the prior position. */
    private static String toSan(Board board, Move move) {
        try {
            MoveList one = new MoveList(board.getFen());
            one.add(move);
            return one.toSan().trim();
        } catch (Exception e) {
            return move.toString();
        }
    }

    private void appendMove(PracticeGame game, Board board, Move move) {
        String san = toSan(board, move);
        board.doMove(move);
        String moves = game.getSanMoves() == null || game.getSanMoves().isBlank()
                ? san : game.getSanMoves() + " " + san;
        game.setSanMoves(moves);
        game.setCurrentFen(board.getFen());
    }

    /** Detects every way a chess game can stop, not just checkmate. */
    private boolean finishIfOver(PracticeGame game, Board board) {
        String reason = null;
        String result = null;

        boolean playerIsWhite = "white".equals(game.getPlayerColor());
        Side toMove = board.getSideToMove();
        boolean playerToMove = (toMove == Side.WHITE) == playerIsWhite;

        if (board.isMated()) {
            reason = "CHECKMATE";
            result = playerToMove ? "loss" : "win";   // the side to move is the one mated
        } else if (board.isStaleMate()) {
            reason = "STALEMATE";
            result = "draw";
        } else if (board.isInsufficientMaterial()) {
            reason = "INSUFFICIENT_MATERIAL";
            result = "draw";
        } else if (board.isRepetition()) {
            reason = "REPETITION";
            result = "draw";
        } else if (board.getHalfMoveCounter() >= 100) {
            reason = "FIFTY_MOVE";
            result = "draw";
        }

        if (reason == null) return false;

        game.setStatus(PracticeStatus.FINISHED);
        game.setResult(result);
        game.setEndReason(reason);
        game.setFinishedAt(clock.now());
        games.save(game);
        return true;
    }

    private static boolean isPlayersTurn(PracticeGame game, Board board) {
        boolean whiteToMove = board.getSideToMove() == Side.WHITE;
        return whiteToMove == "white".equals(game.getPlayerColor());
    }

    /** Resolve against the legal move list, so an illegal string can never be applied. */
    private static Move resolve(Board board, String uci) {
        if (uci == null || uci.isBlank()) return null;
        String t = uci.trim().toLowerCase();
        for (Move m : board.legalMoves()) {
            if (m.toString().equalsIgnoreCase(t)) return m;
        }
        return null;
    }

    public Board replay(PracticeGame game) {
        Board board = new Board();
        List<String> moves = sanList(game);
        if (moves.isEmpty()) return board;
        try {
            MoveList list = new MoveList();
            list.loadFromSan(String.join(" ", moves));
            for (Move m : list) board.doMove(m);
        } catch (Exception e) {
            // Fall back to the stored FEN. Legality still holds; only the
            // repetition and fifty-move history are lost.
            log.warn("[play] replay failed for {}, using stored FEN: {}", game.getId(), e.getMessage());
            board.loadFromFen(game.getCurrentFen());
        }
        return board;
    }

    private static List<String> sanList(PracticeGame game) {
        String s = game.getSanMoves();
        if (s == null || s.isBlank()) return List.of();
        return List.of(s.trim().split("\\s+"));
    }

    private MoveResult snapshot(PracticeGame game, String opponentSan) {
        Board board = new Board();
        board.loadFromFen(game.getCurrentFen());
        boolean playerToMove = isPlayersTurn(game, board);
        return new MoveResult(game.getCurrentFen(), opponentSan, game.getSanMoves(),
                game.getStatus(), game.getResult(), game.getEndReason(), playerToMove);
    }

    /**
     * Which colour the player takes.
     *
     * "weak" hands them whichever colour their record is worse in, which targets
     * better; anything else is taken literally, because being able to choose
     * matters more than an extra percentage point of targeting.
     */
    private String resolveColour(String preference) {
        if ("white".equalsIgnoreCase(preference)) return "white";
        if ("black".equalsIgnoreCase(preference)) return "black";
        return weakerColour();
    }

    private String weakerColour() {
        return profiles.weakerColour(props.chessCom().username());
    }
}
