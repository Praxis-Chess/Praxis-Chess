package com.praxis.service.analysis;

import com.github.bhlangonijr.chesslib.Board;
import com.github.bhlangonijr.chesslib.move.Move;
import com.github.bhlangonijr.chesslib.move.MoveList;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class PgnParserService {

    private static final Pattern HEADER_PATTERN = Pattern.compile("\\[([\\w]+)\\s+\"([^\"]*)\"]");
    private static final Pattern CLOCK_PATTERN  = Pattern.compile("\\[%clk (\\d+):(\\d+):(\\d+)]");
    private static final Pattern EVAL_PATTERN   = Pattern.compile("\\[%eval (#?-?\\d+(?:\\.\\d+)?)\\]");
    private static final Pattern MOVE_NUMBER    = Pattern.compile("\\d+\\.+");
    private static final Pattern RESULT_TOKEN   = Pattern.compile("1-0|0-1|1/2-1/2|\\*");
    private static final Pattern COMMENT_BLOCK  = Pattern.compile("\\{[^}]*}");
    private static final Pattern VARIATION      = Pattern.compile("\\([^)]*\\)");

    public ParsedGame parse(String gameId, String rawPgn, String configuredUsername) {
        Map<String, String> headers = parseHeaders(rawPgn);

        String eco         = headers.getOrDefault("ECO", "");
        String openingName = headers.getOrDefault("Opening", "");
        String result      = headers.getOrDefault("Result", "*");
        String white       = headers.getOrDefault("White", "").toLowerCase();
        String black       = headers.getOrDefault("Black", "").toLowerCase();
        String playerColor = configuredUsername.equalsIgnoreCase(white) ? "white" : "black";

        List<String> sanMoves = extractSanMoves(rawPgn);
        List<Integer> clocks  = extractClocks(rawPgn);
        List<Double>  evals   = extractEvals(rawPgn);

        if (sanMoves.isEmpty()) {
            return new ParsedGame(gameId, eco, openingName, 0, result, playerColor, List.of());
        }

        List<ParsedMove> parsedMoves = replayMoves(sanMoves, clocks, evals);

        return new ParsedGame(gameId, eco, openingName, parsedMoves.size(), result, playerColor, parsedMoves);
    }

    private List<ParsedMove> replayMoves(List<String> sanMoves, List<Integer> clocks, List<Double> evals) {
        MoveList moveList = new MoveList();
        try {
            moveList.loadFromSan(String.join(" ", sanMoves));
        } catch (Exception e) {
            // If loadFromSan fails, return what we have
            return List.of();
        }

        Board board = new Board();
        List<ParsedMove> result = new ArrayList<>();

        for (int i = 0; i < moveList.size(); i++) {
            Move move = moveList.get(i);
            String fenBefore = board.getFen();
            board.doMove(move);
            String fenAfter = board.getFen();
            Integer clock = i < clocks.size() ? clocks.get(i) : null;
            Double eval   = i < evals.size()  ? evals.get(i)  : null;
            String san = i < sanMoves.size() ? sanMoves.get(i) : move.toString();

            result.add(new ParsedMove(i + 1, san, fenBefore, fenAfter, clock, eval));
        }

        return result;
    }

    private List<Double> extractEvals(String pgn) {
        List<Double> evals = new ArrayList<>();
        Matcher m = EVAL_PATTERN.matcher(pgn);
        while (m.find()) {
            String raw = m.group(1);
            if (raw.startsWith("#")) {
                // Mate sentinel: ±(10000 − N) centipawns converted to pawns.
                // Mate in 1 = ±99.99, mate in 5 = ±99.95. This preserves ordinality
                // across win% calculations without the ±100 sentinel blowing up ACPL.
                int mateIn = Integer.parseInt(raw.substring(1));
                int absN   = Math.abs(mateIn);
                double sentinel = (10000.0 - Math.min(absN, 9999)) / 100.0;
                evals.add(mateIn >= 0 ? sentinel : -sentinel);
            } else {
                evals.add(Double.parseDouble(raw));
            }
        }
        return evals;
    }

    private Map<String, String> parseHeaders(String pgn) {
        Map<String, String> headers = new LinkedHashMap<>();
        Matcher m = HEADER_PATTERN.matcher(pgn);
        while (m.find()) {
            headers.put(m.group(1), m.group(2));
        }
        return headers;
    }

    private List<String> extractSanMoves(String pgn) {
        // Strip headers
        String moveSection = pgn.replaceAll("(?m)^\\[.*]\\s*$", "").trim();
        // Strip comments (capture clocks first via extractClocks)
        moveSection = COMMENT_BLOCK.matcher(moveSection).replaceAll(" ");
        // Strip variations
        moveSection = VARIATION.matcher(moveSection).replaceAll(" ");
        // Strip move numbers
        moveSection = MOVE_NUMBER.matcher(moveSection).replaceAll(" ");
        // Strip result
        moveSection = RESULT_TOKEN.matcher(moveSection).replaceAll(" ");

        return Arrays.stream(moveSection.split("\\s+"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());
    }

    private List<Integer> extractClocks(String pgn) {
        List<Integer> clocks = new ArrayList<>();
        Matcher m = CLOCK_PATTERN.matcher(pgn);
        while (m.find()) {
            int h   = Integer.parseInt(m.group(1));
            int min = Integer.parseInt(m.group(2));
            int sec = Integer.parseInt(m.group(3));
            clocks.add(h * 3600 + min * 60 + sec);
        }
        return clocks;
    }
}
