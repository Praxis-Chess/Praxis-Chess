package com.praxis.evidence.graph;

import com.praxis.config.AppProperties;
import com.praxis.evidence.diagnosis.DiagnosisRules;
import com.praxis.evidence.diagnosis.DiagnosisVerifier;
import com.praxis.service.analysis.StockfishService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The real builder on the §6.7 position, end to end: engine, primitives, graph,
 * rules, verifier, budget, serialisation.
 *
 * Skipped without Stockfish rather than passing silently. The rules and verifier
 * are also covered engine-free by the diagnosis tests.
 */
@DisplayName("Evidence graph builder")
@EnabledIf("com.praxis.evidence.graph.EvidenceGraphBuilderTest#stockfishAvailable")
class EvidenceGraphBuilderTest {

    private static final String P0 =
            "r1bqkbnr/pppp1ppp/2n5/4p2Q/2B1P3/8/PPPP1PPP/RNB1K1NR b KQkq - 3 3";

    private static StockfishService engine;
    private static EvidenceGraph graph;

    @BeforeAll
    static void build() {
        engine = new StockfishService(new AppProperties(null, null,
                new AppProperties.Stockfish(stockfishPath().orElseThrow()), null, null, null));
        engine.init();
        engine.setDeterministic(true);
        graph = new EvidenceGraphBuilder(engine, EvidenceGraphBuilder.DEFAULT_DEPTH)
                .build(P0, "Nf6", 6, "OPENING", "BLUNDER").orElseThrow().graph();
    }

    @AfterAll
    static void stop() {
        if (engine != null) engine.destroy();
    }

    @Nested
    @DisplayName("the §6.7 facts")
    class Facts {

        @Test
        void theReplyIsMateInOne() {
            assertThat(graph.reply().san()).isEqualTo("Qxf7#");
            assertThat(graph.reply().mate()).isTrue();
            assertThat(graph.reply().mateIn()).isEqualTo(1);
            assertThat(graph.playedLine().mateAgainstPlayer()).isTrue();
        }

        /** The threat pre-dated the move, and the reply IS that threat. */
        @Test
        void theThreatExistedBeforeTheMove() {
            assertThat(graph.t1().probed()).isTrue();
            assertThat(graph.t1().threatened()).isTrue();
            assertThat(graph.t1().replyIsThreat()).isTrue();
        }

        /**
         * After the engine's move — g6, Qe7 or Qf6, whichever it prefers, every one
         * guards f7 — the mate no longer works.
         */
        @Test
        void theEnginesMoveStopsIt() {
            assertThat(graph.cf1().bestIsPlayed()).isFalse();
            assertThat(graph.cf1().parries()).isTrue();
        }

        @Test
        void itWasEasyToSee() {
            assertThat(graph.d1().band()).isEqualTo("SHALLOW");
        }
    }

    @Nested
    @DisplayName("rules and verifier on the real graph")
    class RulesOnReal {

        @Test
        void labelledAnIgnoredThreat() {
            var labels = DiagnosisRules.label(graph);
            assertThat(labels.consequence()).isEqualTo("MATED");
            assertThat(labels.fired()).containsExactly("IGNORED_THREAT");
        }

        @Test
        void theRulesDiagnosisVerifiesInBothModes() {
            var d = DiagnosisRules.diagnose(graph);
            assertThat(DiagnosisVerifier.verify(graph, d, DiagnosisVerifier.Mode.CLAIM).violations()).isEmpty();
            assertThat(DiagnosisVerifier.verify(graph, d, DiagnosisVerifier.Mode.CITATION).violations()).isEmpty();
        }
    }

    @Nested
    @DisplayName("budget, levels and serialisation")
    class Output {

        @Test
        void theGraphIsWithinBudget() {
            var r3 = Representations.render(graph, Representations.Level.R3);
            assertThat(r3.items()).isLessThanOrEqualTo(GraphBudget.MAX_ITEMS);
            assertThat(r3.tokens()).isLessThanOrEqualTo(GraphBudget.MAX_TOKENS);
            assertThat(r3.dropped()).isEmpty();
        }

        /**
         * Each level contains the one below it. If R2 rephrased R1 instead of
         * adding to it, a difference between them could be wording, not evidence.
         */
        @Test
        void eachLevelContainsTheOneBelow() {
            String r0 = body(Representations.render(graph, Representations.Level.R0).text());
            String r1 = body(Representations.render(graph, Representations.Level.R1).text());
            String r2 = body(Representations.render(graph, Representations.Level.R2).text());
            assertThat(r1).startsWith(r0);
            assertThat(r2).startsWith(r1);
            assertThat(r2.length()).isGreaterThanOrEqualTo(r1.length());
        }

        /** Only R3 carries the threat, the counterfactual and IDs to cite. */
        @Test
        void onlyR3HasTheGraph() {
            String r2 = Representations.render(graph, Representations.Level.R2).text();
            String r3 = Representations.render(graph, Representations.Level.R3).text();
            assertThat(r3).contains("[T1]").contains("[CF1]").contains("[D1]").contains("[PC]");
            assertThat(r2).doesNotContain("[T1]").doesNotContain("null-move");
        }

        @Test
        void aStoredGraphReadsBackIdentically() {
            String json = GraphJson.write(graph);
            assertThat(GraphJson.readGraph(json)).isEqualTo(graph);
            assertThat(json).contains("\"best_line\"").doesNotContain("\"bestLine\"");
        }
    }

    /** The rendering minus the trailing instruction, which every level shares. */
    private static String body(String text) {
        return text.substring(0, text.lastIndexOf("\n\n"));
    }

    static Optional<String> stockfishPath() {
        String fromEnv = System.getenv("STOCKFISH_PATH");
        if (fromEnv != null && Files.isExecutable(Path.of(fromEnv))) return Optional.of(fromEnv);
        String local = "D:/Tanm/stockfish-windows-x86-64-avx2/stockfish/stockfish-windows-x86-64-avx2.exe";
        return Files.isExecutable(Path.of(local)) ? Optional.of(local) : Optional.empty();
    }

    static boolean stockfishAvailable() {
        return stockfishPath().isPresent();
    }
}
