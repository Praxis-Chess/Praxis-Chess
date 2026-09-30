package com.praxis.service.diagnosis;

import com.praxis.config.AppProperties;
import com.praxis.domain.MistakeEvidence;
import com.praxis.domain.MoveError;
import com.praxis.evidence.EvidenceEngine;
import com.praxis.evidence.diagnosis.Diagnosis;
import com.praxis.evidence.diagnosis.DiagnosisRules;
import com.praxis.evidence.diagnosis.DiagnosisVerifier;
import com.praxis.evidence.diagnosis.WhyView;
import com.praxis.evidence.graph.EvidenceGraph;
import com.praxis.evidence.graph.EvidenceGraphBuilder;
import com.praxis.evidence.graph.GraphBudget;
import com.praxis.evidence.graph.GraphJson;
import com.praxis.evidence.graph.Representations;
import com.praxis.repository.MistakeEvidenceRepository;
import com.praxis.repository.MoveErrorRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Builds evidence graphs for the player's own mistakes, collects hand labels for
 * them, and measures the rules against those labels — Phase 3's exit criterion.
 *
 * <p>"Labels a system is trained on are only as good as the evidence that they
 * are right" (§7.3). The rules produce the training labels, the baseline and the
 * fallback, so before any of that is used they are checked against a person's
 * judgement on a random sample of real mistakes, and their precision and recall
 * are reported per mechanism, with intervals.
 */
@Service
public class DiagnosisService {

    private static final Logger log = LoggerFactory.getLogger(DiagnosisService.class);

    /** The plan's labelling target (§7.3). */
    public static final int LABEL_TARGET = 100;

    private final MoveErrorRepository moveErrors;
    private final MistakeEvidenceRepository evidence;
    private final EvidenceEngine engine;
    private final AppProperties props;
    private final TrainedCommentary commentary;

    public DiagnosisService(MoveErrorRepository moveErrors, MistakeEvidenceRepository evidence,
                            EvidenceEngine engine, AppProperties props, TrainedCommentary commentary) {
        this.moveErrors = moveErrors;
        this.evidence = evidence;
        this.engine = engine;
        this.props = props;
        this.commentary = commentary;
    }

    // ── building ─────────────────────────────────────────────────────────────

    public record BuildResult(int built, int failed, long totalBuilt, long remaining, long millis) {}

    /**
     * Build graphs for the next {@code limit} mistakes that have none, in sample
     * order. Synchronous and bounded: the caller asks again for more, so progress
     * is visible and nothing runs unattended on the engine.
     */
    public synchronized BuildResult build(int limit) {
        String user = user();
        long started = System.currentTimeMillis();
        Set<String> existing = new HashSet<>();
        for (MistakeEvidence e : evidence.findByUsername(user)) existing.add(key(e.getGameId(), e.getMoveNumber()));

        List<MoveError> pending = new ArrayList<>();
        for (MoveError m : moveErrors.findAllByUsernameWithGame(user)) {
            if (m.getFenPosition() == null || m.getMovePlayed() == null) continue;
            if (!existing.contains(key(m.getGame().getId(), m.getMoveNumber()))) pending.add(m);
        }
        pending.sort(Comparator.comparingInt(m -> sampleKey(m.getGame().getId(), m.getMoveNumber())));

        var builder = new EvidenceGraphBuilder(engine.get(), EvidenceGraphBuilder.DEFAULT_DEPTH);
        int built = 0;
        int failed = 0;
        for (MoveError m : pending.subList(0, Math.min(Math.max(limit, 0), pending.size()))) {
            try {
                var graph = builder.build(m.getFenPosition(), m.getMovePlayed(), m.getMoveNumber(),
                        m.getGamePhase() == null ? "MIDDLEGAME" : m.getGamePhase().name(),
                        m.getSeverity() == null ? null : m.getSeverity().name());
                if (graph.isEmpty()) {
                    failed++;
                    continue;
                }
                evidence.save(toEntity(user, m, graph.get().graph()));
                built++;
            } catch (Exception e) {
                failed++;
                log.warn("[diagnosis] graph failed for {} ply {}: {}",
                        m.getGame().getId(), m.getMoveNumber(), e.getMessage());
            }
        }
        long total = evidence.countByUsername(user);
        return new BuildResult(built, failed, total, pending.size() - built - failed,
                System.currentTimeMillis() - started);
    }

    private MistakeEvidence toEntity(String user, MoveError m, EvidenceGraph g) {
        var e = MistakeEvidence.builder()
                .username(user)
                .gameId(m.getGame().getId())
                .moveNumber(m.getMoveNumber())
                .fenBefore(m.getFenPosition())
                .severity(m.getSeverity() == null ? null : m.getSeverity().name())
                .moveErrorId(m.getId())
                .analysisSettingsId(m.getGame().getAnalysisSettingsId())
                .sampleKey(sampleKey(m.getGame().getId(), m.getMoveNumber()))
                .build();
        applyGraph(e, g);
        return e;
    }

    /** The graph and everything derived from it: the rules' verdict and the budget. Never the hand labels. */
    private static void applyGraph(MistakeEvidence e, EvidenceGraph g) {
        var labels = DiagnosisRules.label(g);
        Diagnosis d = DiagnosisRules.diagnose(g);
        var report = DiagnosisVerifier.verify(g, d, DiagnosisVerifier.Mode.CITATION);
        if (!report.passed()) {
            // The rules are the fallback; a rules diagnosis that fails its own
            // verifier is a bug in one or the other, and is recorded, not hidden.
            log.warn("[diagnosis] rules' own diagnosis failed verification for {} ply {}: {}",
                    e.getGameId(), e.getMoveNumber(), report.violations());
        }
        var r3 = Representations.render(g, Representations.Level.R3);
        e.setPlayedSan(g.played().san());
        e.setGraphJson(GraphJson.write(g));
        e.setGraphVersion(g.version());
        e.setEngineDepth(g.header().depth());
        e.setRuleConsequence(labels.consequence());
        e.setRuleMechanisms(String.join(",", labels.fired()));
        e.setRuleMechanism(labels.mechanism());
        e.setRuleComposite(labels.composite());
        e.setRuleMotif(labels.motif());
        e.setRuleVisibility(labels.visibility());
        e.setRuleDiagnosisJson(GraphJson.write(d));
        e.setRuleDiagnosisVerified(report.passed());
        e.setItemCount(r3.items());
        e.setTokenEstimate(r3.tokens());
        e.setWithinBudget(r3.items() <= GraphBudget.MAX_ITEMS && r3.tokens() <= GraphBudget.MAX_TOKENS);
        e.setBuiltAt(OffsetDateTime.now());
    }

    /**
     * Rebuild up to {@code limit} graphs made by an older builder, in place. The
     * row keeps its ID, its place in the sample and its hand label. Only the
     * evidence and the rules' verdict are replaced. That is what lets a rule fix
     * be re-scored against the same labels, without anyone labelling again.
     *
     * <p>Labelled rows go first, so the scores are current as early as possible.
     * Each row is rebuilt from its own position and move, so re-analysing a game
     * in between does not matter.
     */
    public synchronized BuildResult rebuild(int limit) {
        String user = user();
        long started = System.currentTimeMillis();
        List<MistakeEvidence> stale = new ArrayList<>(stale(evidence.findByUsername(user)));
        stale.sort(Comparator.comparing((MistakeEvidence e) -> e.getLabelledAt() == null)
                .thenComparingInt(MistakeEvidence::getSampleKey));

        var builder = new EvidenceGraphBuilder(engine.get(), EvidenceGraphBuilder.DEFAULT_DEPTH);
        int built = 0;
        int failed = 0;
        for (MistakeEvidence e : stale.subList(0, Math.min(Math.max(limit, 0), stale.size()))) {
            try {
                var old = GraphJson.readGraph(e.getGraphJson());
                var graph = builder.build(e.getFenBefore(), e.getPlayedSan(), e.getMoveNumber(),
                        old.header().phase(), e.getSeverity());
                if (graph.isEmpty()) {
                    failed++;
                    continue;
                }
                applyGraph(e, graph.get().graph());
                evidence.save(e);
                built++;
            } catch (Exception ex) {
                failed++;
                log.warn("[diagnosis] rebuild failed for {} ply {}: {}", e.getGameId(), e.getMoveNumber(), ex.getMessage());
            }
        }
        return new BuildResult(built, failed, evidence.countByUsername(user), stale.size() - built,
                System.currentTimeMillis() - started);
    }

    // ── on demand ────────────────────────────────────────────────────────────

    /** A mistake's evidence row and its graph, current with the builder. */
    public record Diagnosed(MistakeEvidence row, EvidenceGraph graph) {}

    /**
     * The evidence and the rules' diagnosis for one mistake, for the product:
     * the "Why?" panel and Prax's explain_mistake.
     *
     * <p>Read from {@code mistake_evidence} when a current row exists. Otherwise
     * built now (about half a second of engine time) and stored, so the next view
     * of this mistake costs nothing. A row from an older builder is rebuilt in
     * place, keeping its hand label.
     *
     * <p>Empty when the game has no mistake at that ply, or the position cannot
     * be built.
     */
    public Optional<Diagnosed> diagnose(UUID gameId, int ply) {
        var existing = evidence.findByGameIdAndMoveNumber(gameId, ply);
        if (existing.isPresent() && existing.get().getGraphVersion() >= EvidenceGraphBuilder.GRAPH_VERSION) {
            var row = existing.get();
            return Optional.of(new Diagnosed(row, GraphJson.readGraph(row.getGraphJson())));
        }
        var mistakes = moveErrors.findByGameIdAndPlyWithGame(gameId, ply);
        if (mistakes.isEmpty()) return Optional.empty();
        return buildOnDemand(mistakes.get(0));
    }

    /**
     * Synchronized with the sample build and rebuild: one engine, one writer. Two
     * views of the same mistake at once would otherwise both build it, and the
     * second insert would hit the (game, ply) unique key.
     */
    private synchronized Optional<Diagnosed> buildOnDemand(MoveError m) {
        // Re-read inside the lock: another request may have built it meanwhile.
        var current = evidence.findByGameIdAndMoveNumber(m.getGame().getId(), m.getMoveNumber());
        if (current.isPresent() && current.get().getGraphVersion() >= EvidenceGraphBuilder.GRAPH_VERSION) {
            return Optional.of(new Diagnosed(current.get(), GraphJson.readGraph(current.get().getGraphJson())));
        }
        if (m.getFenPosition() == null || m.getMovePlayed() == null) return Optional.empty();

        var builder = new EvidenceGraphBuilder(engine.get(), EvidenceGraphBuilder.DEFAULT_DEPTH);
        var built = builder.build(m.getFenPosition(), m.getMovePlayed(), m.getMoveNumber(),
                m.getGamePhase() == null ? "MIDDLEGAME" : m.getGamePhase().name(),
                m.getSeverity() == null ? null : m.getSeverity().name());
        if (built.isEmpty()) return Optional.empty();
        EvidenceGraph g = built.get().graph();

        MistakeEvidence row;
        if (current.isPresent()) {
            row = current.get();   // stale: rebuilt in place, label kept
            applyGraph(row, g);
        } else {
            row = toEntity(m.getGame().getUsername(), m, g);
            row.setOnDemand(true);
        }
        return Optional.of(new Diagnosed(evidence.save(row), g));
    }

    /**
     * Diagnose every mistake of one game that has no current diagnosis yet.
     * Called by the analysis pipeline right after a game commits (Phase 9), so a
     * newly analysed game opens with a verified diagnosis on every mistake.
     * About half a second of engine time per mistake; a failure is logged and
     * skipped, never allowed to fail the game's analysis.
     *
     * <p>Built on demand (not in sample order), so the labelling queue's sample
     * is not disturbed.
     *
     * @return how many mistakes now have a current diagnosis
     */
    public int buildForGame(UUID gameId) {
        int current = 0;
        for (MoveError m : moveErrors.findByGameIdWithGame(gameId)) {
            try {
                if (buildOnDemand(m).isPresent()) current++;
            } catch (Exception e) {
                log.warn("[diagnosis] could not diagnose {} ply {}: {}", gameId, m.getMoveNumber(), e.getMessage());
            }
        }
        return current;
    }

    /**
     * The current diagnoses of one game's mistakes, by ply. A row from an older
     * builder is left out: it may say what the current rules no longer would, and
     * the "Why?" panel rebuilds it on the next look.
     */
    public Map<Integer, MistakeEvidence> currentForGame(UUID gameId) {
        Map<Integer, MistakeEvidence> out = new HashMap<>();
        for (MistakeEvidence e : evidence.findByGameId(gameId)) {
            if (e.getGraphVersion() >= EvidenceGraphBuilder.GRAPH_VERSION && e.getRuleDiagnosisJson() != null) {
                out.put(e.getMoveNumber(), e);
            }
        }
        return out;
    }

    // ── trained-model commentary (Phase 9b) ──────────────────────────────────

    /** Whether a current diagnosis still needs the configured model's commentary. */
    private boolean needsCommentary(MistakeEvidence e) {
        return commentary.enabled() && !commentary.model().equals(e.getCommentaryModel());
    }

    /**
     * Ask the trained model about one diagnosed mistake and store its answer with
     * the verifier's verdict. False if the model could not be reached or read.
     */
    private boolean writeCommentary(MistakeEvidence e) {
        var result = commentary.write(GraphJson.readGraph(e.getGraphJson()));
        if (result.isEmpty()) return false;
        var r = result.get();
        e.setCommentary(r.explanation());
        e.setCommentaryModel(r.model());
        e.setCommentaryVerified(r.verified());
        e.setCommentaryAt(OffsetDateTime.now());
        evidence.save(e);
        return true;
    }

    /** The commentary for one freshly diagnosed game, after buildForGame. */
    public int commentForGame(UUID gameId) {
        int written = 0;
        for (MistakeEvidence e : currentForGame(gameId).values()) {
            if (needsCommentary(e) && writeCommentary(e)) written++;
        }
        return written;
    }

    /** The next {@code limit} current diagnoses without the model's commentary, in sample order. */
    public BuildResult comment(int limit) {
        long started = System.currentTimeMillis();
        List<MistakeEvidence> pending = new ArrayList<>();
        for (MistakeEvidence e : evidence.findByUsername(user())) {
            if (e.getGraphVersion() >= EvidenceGraphBuilder.GRAPH_VERSION && needsCommentary(e)) pending.add(e);
        }
        pending.sort(Comparator.comparingInt(MistakeEvidence::getSampleKey));
        int written = 0;
        int failed = 0;
        for (MistakeEvidence e : pending.subList(0, Math.min(Math.max(limit, 0), pending.size()))) {
            if (writeCommentary(e)) written++; else failed++;
        }
        return new BuildResult(written, failed, evidence.countByUsername(user()), pending.size() - written,
                System.currentTimeMillis() - started);
    }

    public boolean commentaryEnabled() {
        return commentary.enabled();
    }

    /** Mistakes with no diagnosis at all, and diagnoses made by an older builder. */
    public record Coverage(long diagnosed, long missing, long stale) {}

    /** Current diagnoses still waiting for the trained model's commentary (0 when it is off). */
    public long uncommented() {
        if (!commentary.enabled()) return 0;
        return evidence.findByUsername(user()).stream()
                .filter(e -> e.getGraphVersion() >= EvidenceGraphBuilder.GRAPH_VERSION && needsCommentary(e))
                .count();
    }

    public Coverage coverage() {
        String user = user();
        Set<String> current = new HashSet<>();
        long stale = 0;
        for (MistakeEvidence e : evidence.findByUsername(user)) {
            current.add(key(e.getGameId(), e.getMoveNumber()));
            if (e.getGraphVersion() < EvidenceGraphBuilder.GRAPH_VERSION) stale++;
        }
        long missing = moveErrors.findAllByUsernameWithGame(user).stream()
                .filter(m -> m.getFenPosition() != null && m.getMovePlayed() != null)
                .filter(m -> !current.contains(key(m.getGame().getId(), m.getMoveNumber())))
                .count();
        return new Coverage(current.size() - stale, missing, stale);
    }

    /** The "Why?" for one mistake: its verified diagnosis, the steps and the boards. */
    public record Why(UUID gameId, int ply, String moveLabel, WhyView.View diagnosis, List<String> facts) {}

    public Optional<Why> why(UUID gameId, int ply) {
        return diagnose(gameId, ply).map(d -> {
            var view = WhyView.of(d.graph());
            String label = ((ply + 1) / 2) + (ply % 2 == 1 ? ". " : "... ") + d.graph().played().san();
            return new Why(gameId, ply, label, view, WhyView.facts(d.graph(), view));
        });
    }

    private static List<MistakeEvidence> stale(List<MistakeEvidence> all) {
        return all.stream().filter(e -> e.getGraphVersion() < EvidenceGraphBuilder.GRAPH_VERSION).toList();
    }

    // ── labelling ────────────────────────────────────────────────────────────

    /**
     * What the labeller sees: the position, both lines, and the evidence — and
     * <b>not</b> the rules' verdict. A labeller shown the answer first mostly
     * agrees with it, and the precision figure would then measure deference.
     */
    public record LabelCard(UUID id, String fen, String player, String moveLabel,
                            String playedSan, String playedUci, String bestSan, String bestUci,
                            String severity, String phase,
                            List<String> playedLine, List<String> bestLine,
                            String evidence, long labelled, long built, int target) {}

    /**
     * The next mistake to label: the first unlabelled one in sample order, up to
     * where the sample build has reached. Rows built on demand beyond that point
     * wait until the sample build gets there, so what the player happened to open
     * on Game Analysis never jumps the queue.
     */
    public Optional<LabelCard> next() {
        String user = user();
        Integer frontier = evidence.sampleFrontier(user);
        if (frontier == null) return Optional.empty();
        return evidence.findFirstByUsernameAndLabelledAtIsNullAndSampleKeyLessThanEqualOrderBySampleKeyAsc(user, frontier)
                .map(e -> card(e, evidence.countByUsernameAndLabelledAtIsNotNull(user),
                        evidence.countByUsername(user)));
    }

    private LabelCard card(MistakeEvidence e, long labelled, long built) {
        EvidenceGraph g = GraphJson.readGraph(e.getGraphJson());
        String text = Representations.render(g, Representations.Level.R3).text();
        // The instruction is for a model, not a person.
        String body = text.substring(0, text.lastIndexOf("\n\n"));
        int ply = e.getMoveNumber();
        String label = ((ply + 1) / 2) + (ply % 2 == 1 ? ". " : "... ") + e.getPlayedSan();
        return new LabelCard(e.getId(), e.getFenBefore(), g.header().player(), label,
                g.played().san(), g.played().uci(), g.best().san(), g.best().uci(),
                e.getSeverity(), g.header().phase(),
                g.playedLine().lineSan(), g.bestLine().lineSan(), body, labelled, built, LABEL_TARGET);
    }

    public void label(UUID id, String consequence, String mechanism, String note) {
        if (!Diagnosis.CONSEQUENCES.contains(consequence)) {
            throw new IllegalArgumentException("unknown consequence " + consequence);
        }
        if (!Diagnosis.MECHANISMS.contains(mechanism)) {
            throw new IllegalArgumentException("unknown mechanism " + mechanism);
        }
        MistakeEvidence e = evidence.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("no evidence " + id));
        e.setHumanConsequence(consequence);
        e.setHumanMechanism(mechanism);
        e.setHumanNote(note == null || note.isBlank() ? null : note.trim());
        if (e.getLabelledAt() == null) {
            e.setLabelledAt(OffsetDateTime.now());
        } else {
            e.setRelabelledAt(OffsetDateTime.now());   // a review: the first labelling time stands
        }
        evidence.save(e);
    }

    /**
     * A labelled mistake reopened for a second look, with the player's own label.
     * Unlike the queue, a review is not blind: it is reached from the list of
     * disagreements, which shows the rules' verdict. That is the point of it, and
     * why a relabel keeps the original labelled_at (see MistakeEvidence).
     */
    public record Review(LabelCard card, String consequence, String mechanism, String note) {}

    public Optional<Review> review(UUID id) {
        String user = user();
        return evidence.findById(id)
                .filter(e -> user.equals(e.getUsername()) && e.getLabelledAt() != null)
                .map(e -> new Review(
                        card(e, evidence.countByUsernameAndLabelledAtIsNotNull(user), evidence.countByUsername(user)),
                        e.getHumanConsequence(), e.getHumanMechanism(), e.getHumanNote()));
    }

    // ── measuring the rules ──────────────────────────────────────────────────

    /**
     * Precision and recall for one mechanism rule.
     *
     * <p>Per rule, not per label, because several rules can fire on one mistake
     * (§7.2): a rule "predicts" a mechanism whenever it fires. Intervals are
     * Wilson 95%, since at a hundred labels spread over six mechanisms most cells
     * are small and a bare percentage would overstate what is known.
     */
    public record MechanismScore(String mechanism, int truePositives, int falsePositives,
                                 int falseNegatives, Double precision, double[] precisionCi,
                                 Double recall, double[] recallCi) {}

    /** One case where the rules and the labeller disagreed — for reading, not just counting. */
    public record Disagreement(UUID id, String moveLabel, String humanConsequence, String ruleConsequence,
                               String humanMechanism, String ruleMechanisms, String note) {}

    public record BudgetStats(long graphs, long overBudget, int maxItems, int medianTokens,
                              int p95Tokens, int maxTokens) {}

    /** @param stale graphs from an older builder; their verdicts predate the current rules */
    public record Report(long built, long labelled, int target, long stale,
                         Double consequenceAgreement, double[] consequenceCi,
                         Double singleCauseAccuracy, int singleCauseLabelled,
                         List<MechanismScore> mechanisms,
                         double compositeRate, double notConcreteRate,
                         long ruleDiagnosesFailingVerification,
                         BudgetStats budget, List<Disagreement> disagreements, Fresh fresh) {}

    /**
     * The same two scores on the labels given AFTER the first {@link #LABEL_TARGET}:
     * the only labels the rules were not tuned on (the Phase 3 gap). Ordered by
     * labelled_at, which a review never moves.
     */
    public record Fresh(int labelled, Double consequenceAgreement, double[] consequenceCi,
                        Double singleCauseAccuracy, int singleCauseLabelled, double[] singleCauseCi) {}

    static Fresh fresh(List<MistakeEvidence> labelled) {
        List<MistakeEvidence> later = labelled.stream()
                .sorted(Comparator.comparing(MistakeEvidence::getLabelledAt))
                .skip(LABEL_TARGET)
                .toList();
        int cons = 0, singleN = 0, single = 0;
        for (MistakeEvidence e : later) {
            if (e.getRuleConsequence().equals(e.getHumanConsequence())) cons++;
            if (!e.isRuleComposite() && !"NONE".equals(e.getRuleMechanism())) {
                singleN++;
                if (e.getRuleMechanism().equals(e.getHumanMechanism())) single++;
            }
        }
        return new Fresh(later.size(), ratio(cons, later.size()), wilson(cons, later.size()),
                ratio(single, singleN), singleN, wilson(single, singleN));
    }

    public Report report() {
        String user = user();
        List<MistakeEvidence> all = evidence.findByUsername(user);
        List<MistakeEvidence> labelled = all.stream().filter(e -> e.getLabelledAt() != null).toList();

        int consequenceHits = 0;
        int singleN = 0;
        int singleHits = 0;
        for (MistakeEvidence e : labelled) {
            if (e.getRuleConsequence().equals(e.getHumanConsequence())) consequenceHits++;
            if (!e.isRuleComposite() && !"NONE".equals(e.getRuleMechanism())) {
                singleN++;
                if (e.getRuleMechanism().equals(e.getHumanMechanism())) singleHits++;
            }
        }

        List<MechanismScore> scores = new ArrayList<>();
        for (String mech : Diagnosis.MECHANISM_RULES) {
            int tp = 0, fp = 0, fn = 0;
            for (MistakeEvidence e : labelled) {
                boolean fired = fired(e).contains(mech);
                boolean human = mech.equals(e.getHumanMechanism());
                if (fired && human) tp++;
                else if (fired) fp++;
                else if (human) fn++;
            }
            scores.add(new MechanismScore(mech, tp, fp, fn,
                    ratio(tp, tp + fp), wilson(tp, tp + fp), ratio(tp, tp + fn), wilson(tp, tp + fn)));
        }

        List<Disagreement> disagreements = new ArrayList<>();
        for (MistakeEvidence e : labelled) {
            boolean consequenceOk = e.getRuleConsequence().equals(e.getHumanConsequence());
            boolean mechanismOk = fired(e).contains(e.getHumanMechanism())
                    || e.getRuleMechanism().equals(e.getHumanMechanism());
            if (consequenceOk && mechanismOk) continue;
            int ply = e.getMoveNumber();
            disagreements.add(new Disagreement(e.getId(),
                    ((ply + 1) / 2) + (ply % 2 == 1 ? ". " : "... ") + e.getPlayedSan(),
                    e.getHumanConsequence(), e.getRuleConsequence(),
                    e.getHumanMechanism(), e.getRuleMechanisms().isEmpty() ? e.getRuleMechanism() : e.getRuleMechanisms(),
                    e.getHumanNote()));
        }

        long composite = all.stream().filter(MistakeEvidence::isRuleComposite).count();
        long notConcrete = all.stream().filter(e -> "NOT_CONCRETE".equals(e.getRuleConsequence())).count();
        long unverified = all.stream().filter(e -> !e.isRuleDiagnosisVerified()).count();

        return new Report(all.size(), labelled.size(), LABEL_TARGET, stale(all).size(),
                ratio(consequenceHits, labelled.size()), wilson(consequenceHits, labelled.size()),
                ratio(singleHits, singleN), singleN, scores,
                all.isEmpty() ? 0 : (double) composite / all.size(),
                all.isEmpty() ? 0 : (double) notConcrete / all.size(),
                unverified, budget(all), disagreements, fresh(labelled));
    }

    private static BudgetStats budget(List<MistakeEvidence> all) {
        if (all.isEmpty()) return new BudgetStats(0, 0, 0, 0, 0, 0);
        List<Integer> tokens = all.stream().map(MistakeEvidence::getTokenEstimate).sorted().toList();
        return new BudgetStats(all.size(),
                all.stream().filter(e -> !e.isWithinBudget()).count(),
                all.stream().mapToInt(MistakeEvidence::getItemCount).max().orElse(0),
                tokens.get(tokens.size() / 2),
                tokens.get(Math.min(tokens.size() - 1, (int) Math.ceil(tokens.size() * 0.95) - 1)),
                tokens.get(tokens.size() - 1));
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static List<String> fired(MistakeEvidence e) {
        return e.getRuleMechanisms() == null || e.getRuleMechanisms().isBlank()
                ? List.of() : List.of(e.getRuleMechanisms().split(","));
    }

    private static Double ratio(int hits, int n) {
        return n == 0 ? null : (double) hits / n;
    }

    /** Wilson score interval, 95%. Null when there is nothing to estimate from. */
    static double[] wilson(int hits, int n) {
        if (n == 0) return null;
        double z = 1.96;
        double p = (double) hits / n;
        double denom = 1 + z * z / n;
        double centre = (p + z * z / (2.0 * n)) / denom;
        double margin = z * Math.sqrt(p * (1 - p) / n + z * z / (4.0 * n * n)) / denom;
        return new double[]{Math.max(0, centre - margin), Math.min(1, centre + margin)};
    }

    private static String key(UUID gameId, int ply) {
        return gameId + ":" + ply;
    }

    /**
     * A stable, well-spread position in the queue. Not {@code hashCode()}, whose
     * value for a UUID-and-int pair clusters by game, so a whole game's mistakes
     * would sit next to each other and the "random" sample would be a few games.
     */
    static int sampleKey(UUID gameId, int ply) {
        long h = gameId.getMostSignificantBits() ^ gameId.getLeastSignificantBits() ^ (ply * 0x9E3779B97F4A7C15L);
        h ^= (h >>> 33);
        h *= 0xff51afd7ed558ccdL;
        h ^= (h >>> 33);
        h *= 0xc4ceb9fe1a85ec53L;
        h ^= (h >>> 33);
        return (int) (h & 0x7fffffff);
    }

    private String user() {
        return props.chessCom().username();
    }
}
