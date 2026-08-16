package com.praxis.prax.tools;

import com.praxis.config.AppProperties;
import com.praxis.prax.intelligence.ChessIntelligence;
import com.praxis.domain.enums.CardStatus;
import com.praxis.repository.CardRepository;
import com.praxis.service.analysis.StockfishService;
import com.praxis.service.analysis.MultiPVResult;
import com.praxis.prax.evidence.ChessFact;
import com.praxis.prax.evidence.PositionEvidenceBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * The tool catalogue (Reasoning Plan §4).
 *
 * Tools retrieve and compute — nothing here reasons, and nothing here calls a
 * model. Each returns a ToolResult carrying its own sample size so the evidence
 * validator can judge how much weight a claim can bear.
 */
@Service
public class ToolRegistry {

    private static final Logger log = LoggerFactory.getLogger(ToolRegistry.class);

    private final ChessIntelligence intel;
    private final AppProperties props;
    private final CardRepository cardRepository;
    private final StockfishService stockfish;
    private final PositionEvidenceBuilder evidenceBuilder;

    public ToolRegistry(ChessIntelligence intel, AppProperties props, CardRepository cardRepository,
                        StockfishService stockfish, PositionEvidenceBuilder evidenceBuilder) {
        this.intel = intel;
        this.props = props;
        this.cardRepository = cardRepository;
        this.stockfish = stockfish;
        this.evidenceBuilder = evidenceBuilder;
    }

    private String user() {
        return props.chessCom().username();
    }

    /** JSON schemas handed to Ollama's /api/chat `tools` array. */
    public List<Map<String, Object>> schemas() {
        return List.of(
                tool("get_player_profile",
                        "Overall profile: game counts, per-colour win rate and accuracy, opening "
                        + "families, phase performance, top mistake motifs. Call this first to "
                        + "orient before drilling in. Also the starting point for open-ended "
                        + "questions like 'what am I good at', 'where am I weak', 'how am I doing' "
                        + "— answer those from data, never from impression.",
                        Map.of()),

                tool("get_opening_performance",
                        "Win rate and average accuracy per opening. Filter by opening name/ECO and "
                        + "by colour. Use this for 'which openings suit me', 'what do I play best' "
                        + "and any strength/weakness question about openings.",
                        Map.of("opening", str("Opening name or ECO code, e.g. 'Sicilian' or 'B20'. Omit for all."),
                               "color", str("Exactly 'white' or 'black'. Leave this out entirely to cover both colours."),
                               "minGames", intp("Minimum games for an opening to be included. Default 3."))),

                tool("get_phase_performance",
                        "Error and blunder counts per game phase (opening/middlegame/endgame), "
                        + "optionally scoped to one opening or colour. Use this to say which part "
                        + "of the game is the player's strongest or weakest.",
                        Map.of("opening", str("Opening name or ECO. Omit for all."),
                               "color", str("Exactly 'white' or 'black'. Leave this out entirely to cover both colours."))),

                tool("get_mistake_patterns",
                        "Most frequent tactical motifs in the player's mistakes, with blunder counts and example game ids.",
                        Map.of("motif", str("Filter to one motif, e.g. FORK, PIN, HANGING_PIECE. Omit for all."),
                               "phase", str("OPENING, MIDDLEGAME or ENDGAME. Omit for all."),
                               "opening", str("Scope to one opening. Omit for all."),
                               "limit", intp("How many motifs to return. Default 5."))),

                tool("find_games",
                        "Search the player's analysed games by opening, colour, result and recency. The general-purpose escape hatch.",
                        Map.of("opening", str("Opening name or ECO."),
                               "color", str("Exactly 'white' or 'black'. Leave out to cover both."),
                               "result", str("Exactly 'win', 'loss' or 'draw'. Leave out to cover all results."),
                               "sinceDays", intp("Only games in the last N days."),
                               "limit", intp("Max games to return. Default 10, cap 50."))),

                tool("get_game",
                        "Full detail for one game including every flagged mistake with its move, better move, severity, motif and FEN.",
                        Map.of("gameId", str("The game's UUID, as returned by find_games."))),

                tool("get_recent_games",
                        "The most recently played analysed games, newest first.",
                        Map.of("limit", intp("How many. Default 10."))),

                tool("recommend_openings",
                        "Deterministic ranking of which openings this player should invest in, with a score and rationale. Explain this ranking; do not invent your own.",
                        Map.of("color", str("Exactly 'white' or 'black'. Leave this out entirely to cover both colours."))),

                tool("get_progress",
                        "The player's DRILL and PRACTICE activity: how many drills/cards are done, "
                        + "due, learning, in review or suspended, and their recall rate per phase. "
                        + "Use this for any question about drills, practice, training, cards, "
                        + "revision or 'what have I done' — it is the only source for that. "
                        + "You DO have access to this; never tell the player you don't.",
                        Map.of()),

                tool("find_mistakes",
                        "The player's worst individual moves across all games, worst first. "
                        + "USE THIS for any question about a specific blunder or mistake — it is "
                        + "the only tool that finds one. Each row carries the fen and movePlayed "
                        + "to pass straight to analyze_position.",
                        Map.of("severity", str("Optional: BLUNDER, MISTAKE or INACCURACY. Omit for all."),
                               "phase", str("Optional: OPENING, MIDDLEGAME or ENDGAME. Omit for all."),
                               "limit", intp("How many to return. Default 5, maximum 10."))),

                // V2 — the engine supplies the calculation, you supply the explanation.
                tool("analyze_position",
                        "Run the chess engine on a position. Returns the evaluation in pawns from White's "
                        + "perspective, the best move, and the top candidate lines. Use this to explain WHY a "
                        + "move was a mistake — get the FEN from get_game first. Never estimate an evaluation "
                        + "yourself; call this.",
                        Map.of("fen", str("FEN of the position, as returned by get_game."),
                               "playedMove", str("The move the player actually made, from get_game. "
                                       + "SAN (Bb4+) or UCI (e7b4) both work. ALWAYS supply this when "
                                       + "explaining a mistake — without it the engine can only describe "
                                       + "the best move, not what the played move cost."),
                               "depth", intp("Search depth. Default 16; higher is slower.")))
        );
    }

    /**
     * A second call this result cannot be useful without, or empty.
     *
     * find_mistakes finds the move but says nothing about why it was bad, and
     * the model would not take the next step on its own: told the reason was
     * unavailable, it relayed that to the player — "I would need to call
     * analyze_position" — rather than calling it. Prompting harder was not
     * going to fix a 4B model's follow-through, so the chain runs here.
     *
     * Only the top row, and only one extra engine run, because both share the
     * single Stockfish process.
     */
    public Optional<Map<String, Object>> followUp(String tool, ToolResult result) {
        if (!"find_mistakes".equals(tool) || !stockfish.isAvailable()) return Optional.empty();
        if (!(result.data() instanceof Map<?, ?> m)
                || !(m.get("mistakes") instanceof List<?> rows) || rows.isEmpty()
                || !(rows.get(0) instanceof Map<?, ?> top)) {
            return Optional.empty();
        }
        if (!(top.get("fen") instanceof String fen) || fen.isBlank()) return Optional.empty();

        Map<String, Object> args = new LinkedHashMap<>();
        args.put("fen", fen);
        if (top.get("movePlayed") instanceof String played && !played.isBlank()) {
            args.put("playedMove", played);
        }
        // Shallower than a direct call: this one is speculative, not requested.
        args.put("depth", 14);
        return Optional.of(args);
    }

    /** Executes one call. Never throws into the agent loop — errors come back typed. */
    public ToolResult execute(String name, Map<String, Object> args) {
        try {
            return switch (name) {
                case "get_player_profile" -> {
                    var p = intel.playerProfile(user());
                    yield ToolResult.playerData(name, p, asInt(p.get("analyzedGames"), 0));
                }
                case "get_opening_performance" -> {
                    var r = intel.openingPerformance(user(), s(args, "opening"), s(args, "color"),
                            i(args, "minGames", 3));
                    yield ToolResult.playerData(name, r, r.stream().mapToInt(x -> x.games()).sum());
                }
                case "get_phase_performance" -> {
                    var r = intel.phasePerformance(user(), s(args, "opening"), s(args, "color"));
                    yield ToolResult.playerData(name, r, r.isEmpty() ? 0 : r.get(0).games());
                }
                case "get_mistake_patterns" -> {
                    var r = intel.mistakePatterns(user(), s(args, "motif"), s(args, "phase"),
                            s(args, "opening"), i(args, "limit", 5));
                    yield ToolResult.playerData(name, r, r.stream().mapToInt(x -> x.count()).sum());
                }
                case "find_games" -> {
                    var r = intel.findGames(user(), s(args, "opening"), s(args, "color"),
                            s(args, "result"), iOrNull(args, "sinceDays"), i(args, "limit", 10));
                    yield ToolResult.playerData(name, r, r.size());
                }
                case "get_game" -> {
                    String id = s(args, "gameId");
                    if (id == null) yield ToolResult.error(name, "gameId is required");
                    yield intel.gameDetail(id)
                            .map(d -> ToolResult.singleObject(name, d))
                            .orElseGet(() -> ToolResult.error(name, "No game with id " + id));
                }
                case "get_recent_games" -> {
                    var r = intel.findGames(user(), null, null, null, null, i(args, "limit", 10));
                    yield ToolResult.playerData(name, r, r.size());
                }
                case "recommend_openings" -> {
                    var r = intel.recommendOpenings(user(), s(args, "color"));
                    yield ToolResult.playerData(name, r, r.stream().mapToInt(x -> x.games()).sum());
                }
                case "get_progress" -> {
                    String u = user();
                    var deck = Map.of(
                            "totalCards", cardRepository.countActiveByUsername(u),
                            "newCards", cardRepository.countByUsernameAndStatus(u, CardStatus.NEW),
                            "learningCards", cardRepository.countByUsernameAndStatus(u, CardStatus.LEARNING)
                                    + cardRepository.countByUsernameAndStatus(u, CardStatus.RELEARNING),
                            "reviewCards", cardRepository.countByUsernameAndStatus(u, CardStatus.REVIEW),
                            "suspendedCards", cardRepository.countByUsernameAndStatus(u, CardStatus.SUSPENDED));
                    yield ToolResult.playerData(name, deck, (int) cardRepository.countActiveByUsername(u));
                }
                case "find_mistakes" -> {
                    var rows = intel.worstMistakes(user(), s(args, "severity"),
                            s(args, "phase"), i(args, "limit", 5));
                    // Specific moves, not a population — "(only 3 games)" would be
                    // nonsense on a list of three individual blunders.
                    yield ToolResult.singleObject(name, Map.of("mistakes", rows));
                }
                case "analyze_position" -> {
                    String fen = s(args, "fen");
                    if (fen == null) yield ToolResult.error(name, "fen is required");
                    if (!stockfish.isAvailable()) {
                        yield ToolResult.error(name, "Chess engine is not running.");
                    }
                    int depth = i(args, "depth", 16);
                    MultiPVResult r = stockfish.evaluateWithMultiPV(fen, depth, 3);
                    // Facts, not a raw eval. Everything below was computed from
                    // the board - the model selects and phrases, never authors.
                    var ev = evidenceBuilder.build(fen, r.score(), r.bestMoveUci(), r.pvLines());
                    Map<String, Object> data = new LinkedHashMap<>();
                    data.put("sideToMove", ev.sideToMove());
                    data.put("evaluation", ev.evaluation());
                    data.put("bestMove", ev.bestMoveSan() != null ? ev.bestMoveSan() : ev.bestMoveUci());
                    data.put("mate", ev.mate());
                    // Facts about the move the player actually chose, so the
                    // model never has to reason about it unaided.
                    var facts = new ArrayList<>(ev.facts());
                    String playedToken = s(args, "playedMove");
                    if (playedToken != null) {
                        // Resolved against the legal move list, so SAN and UCI
                        // both work. Silently swallowing a parse failure here is
                        // what let the model go back to inventing reasons.
                        var played = evidenceBuilder.resolveMove(fen, playedToken);
                        if (played == null) {
                            log.warn("[prax] analyze_position: playedMove '{}' is not legal in {}",
                                    playedToken, fen);
                        } else {
                            var board = new com.github.bhlangonijr.chesslib.Board();
                            board.loadFromFen(fen);
                            board.doMove(played);
                            // Same depth as the baseline above. evaluate() would
                            // search 100ms and the two numbers would not be
                            // comparable — see StockfishService.evaluateAtDepth.
                            Double after = stockfish.evaluateAtDepth(board.getFen(), depth);
                            if (after != null) {
                                var compared = evidenceBuilder.comparePlayed(
                                        fen, r.score(), played, after, facts.size());
                                // The comparison facts state the before-value as
                                // part of a before/after sentence, so the
                                // standalone one is now a duplicate — and a
                                // duplicate the model tried to reconcile against
                                // its own pair rather than reading as one claim.
                                if (!compared.isEmpty()) {
                                    facts.removeIf(f -> f.kind() == ChessFact.Kind.EVAL_SWING
                                            || f.kind() == ChessFact.Kind.MATE);
                                }
                                facts.addAll(compared);
                            }
                        }
                    }
                    // Decisive facts first, and no `kind` — the model was quoting
                    // the enum straight into the answer ("The engine identifies
                    // this as a 'WALKS_INTO_MATE' fact"). Jargon that is never
                    // sent can never leak; the statements already carry the
                    // meaning the kind was labelling.
                    data.put("verifiedFacts", facts.stream()
                            .sorted(java.util.Comparator.comparingInt(ChessFact::priority))
                            .map(f -> Map.of("id", f.id(), "fact", f.statement()))
                            .toList());
                    yield ToolResult.engine(name, data);
                }
                default -> ToolResult.error(name, "Unknown tool: " + name);
            };
        } catch (Exception e) {
            // With the stack trace, because the model paraphrases a typed error
            // as "the tool returned no data" — indistinguishable from an empty
            // result unless the log says otherwise.
            log.warn("[prax] tool {} failed: {}", name, e.getMessage(), e);
            return ToolResult.error(name, e.getMessage() == null ? "failed" : e.getMessage());
        }
    }

    // ── schema helpers ───────────────────────────────────────────────────────

    private static Map<String, Object> tool(String name, String desc, Map<String, Object> props) {
        return Map.of(
                "type", "function",
                "function", Map.of(
                        "name", name,
                        "description", desc,
                        "parameters", Map.of(
                                "type", "object",
                                "properties", props,
                                "required", List.of())));
    }

    private static Map<String, Object> str(String desc) {
        return Map.of("type", "string", "description", desc);
    }

    private static Map<String, Object> intp(String desc) {
        return Map.of("type", "integer", "description", desc);
    }

    // ── arg helpers: models send loose types, so coerce rather than cast ──────

    private static String s(Map<String, Object> a, String k) {
        Object v = a.get(k);
        if (v == null) return null;
        String t = String.valueOf(v).trim();
        return t.isEmpty() || "null".equals(t) ? null : t;
    }

    private static int i(Map<String, Object> a, String k, int def) {
        Integer v = iOrNull(a, k);
        return v == null ? def : v;
    }

    private static Integer iOrNull(Map<String, Object> a, String k) {
        Object v = a.get(k);
        if (v == null) return null;
        try {
            return (int) Double.parseDouble(String.valueOf(v));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static int asInt(Object v, int def) {
        return v instanceof Number n ? n.intValue() : def;
    }
}
