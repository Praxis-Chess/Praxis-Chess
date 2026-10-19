package com.praxis.service.diagnosis;

import com.praxis.domain.MistakeEvidence;
import com.praxis.domain.MoveError;
import com.praxis.evidence.graph.EvidenceGraphBuilder;
import com.praxis.evidence.graph.GraphJson;
import com.praxis.repository.MistakeEvidenceRepository;
import com.praxis.repository.MoveErrorRepository;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The player's current mistakes, each with its verified diagnosis (Phase 12).
 *
 * <p>Every pattern, drill, insight and report reads mistakes through here instead
 * of {@code move_errors.tactical_motif}, which the old per-move model set on at
 * most a few mistakes per game and never checked. A diagnosis comes from the
 * evidence graph and the rules, and passed the verifier.
 *
 * <p>Matched on (game, ply), never on {@code mistake_evidence.move_error_id}: a
 * re-analysis writes new mistake rows, and a position is the same position
 * across any number of them.
 *
 * <p>A mistake without a current diagnosis is still returned, with
 * {@code diagnosis == null}: counts by phase or severity include it; counts by
 * motif or mechanism cannot, and say how many they cover.
 */
@Service
public class VerifiedMistakes {

    /**
     * What the rules found, verified.
     *
     * @param motif      FORK, PIN, SKEWER, BACK_RANK, DISCOVERED_ATTACK, HANGING_PIECE, POSITIONAL, OTHER
     * @param mechanism  IGNORED_THREAT, MISSED_OPPORTUNITY, CREATED_TACTIC, MOVED_INTO_ATTACK,
     *                   REMOVED_DEFENDER, LOSING_CAPTURE, UNCLEAR, NONE
     * @param explanation the rules' own explanation, every claim checked against the graph
     * @param commentary  the trained model's commentary, only when its checker passed it
     */
    public record Diagnosis(String motif, String mechanism, String consequence, String explanation,
                            String commentary) {
        /** What a player reads: the checked commentary when there is one, else the rules' explanation. */
        public String text() {
            return commentary != null ? commentary : explanation;
        }
    }

    /** One current mistake and, when there is one, its verified diagnosis. */
    public record Mistake(MoveError error, Diagnosis diagnosis) {
        public boolean diagnosed()   { return diagnosis != null; }
        public String motif()        { return diagnosis == null ? null : diagnosis.motif(); }
        public String mechanism()    { return diagnosis == null ? null : diagnosis.mechanism(); }
    }

    private final MoveErrorRepository moveErrors;
    private final MistakeEvidenceRepository evidence;

    public VerifiedMistakes(MoveErrorRepository moveErrors, MistakeEvidenceRepository evidence) {
        this.moveErrors = moveErrors;
        this.evidence = evidence;
    }

    /** The player's current Chess.com mistakes, game loaded, each with its diagnosis. */
    public List<Mistake> forUser(String username) {
        Map<String, Diagnosis> byPosition = index(evidence.findByUsername(username));
        return moveErrors.findAllByUsernameWithGame(username).stream()
                .map(e -> new Mistake(e, byPosition.get(key(e.getGame().getId(), e.getMoveNumber()))))
                .toList();
    }

    /** These mistakes (any population, e.g. practice games), each with its diagnosis. */
    public List<Mistake> of(Collection<MoveError> errors) {
        Map<UUID, Map<Integer, Diagnosis>> byGame = new HashMap<>();
        return errors.stream().map(e -> {
            UUID game = e.getGame().getId();
            Diagnosis d = byGame.computeIfAbsent(game, this::forGame).get(e.getMoveNumber());
            return new Mistake(e, d);
        }).toList();
    }

    /** One game's verified diagnoses, by ply. */
    public Map<Integer, Diagnosis> forGame(UUID gameId) {
        Map<Integer, Diagnosis> out = new HashMap<>();
        for (MistakeEvidence e : evidence.findByGameId(gameId)) {
            Diagnosis d = of(e);
            if (d != null) out.put(e.getMoveNumber(), d);
        }
        return out;
    }

    /** The diagnosis of one evidence row, or null when it is not current and verified. */
    public static Diagnosis of(MistakeEvidence e) {
        if (e == null) return null;
        if (e.getGraphVersion() < EvidenceGraphBuilder.GRAPH_VERSION) return null;   // older rules: rebuilt on the next look
        if (e.getRuleDiagnosisJson() == null || !e.isRuleDiagnosisVerified()) return null;
        String commentary = Boolean.TRUE.equals(e.getCommentaryVerified()) ? e.getCommentary() : null;
        String explanation;
        try {
            explanation = GraphJson.readDiagnosis(e.getRuleDiagnosisJson()).explanation();
        } catch (RuntimeException unreadable) {
            explanation = null;
        }
        return new Diagnosis(e.getRuleMotif(), e.getRuleMechanism(), e.getRuleConsequence(), explanation, commentary);
    }

    private static Map<String, Diagnosis> index(List<MistakeEvidence> rows) {
        Map<String, Diagnosis> out = new HashMap<>();
        for (MistakeEvidence e : rows) {
            Diagnosis d = of(e);
            if (d != null) out.put(key(e.getGameId(), e.getMoveNumber()), d);
        }
        return out;
    }

    private static String key(UUID game, int ply) {
        return game + ":" + ply;
    }
}
