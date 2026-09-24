package com.praxis.prax.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.praxis.config.AppProperties;
import com.praxis.evidence.diagnosis.TestGraphs;
import com.praxis.evidence.diagnosis.WhyView;
import com.praxis.prax.artifact.MoveComparisonArtifact;
import com.praxis.prax.evidence.Evidence;
import com.praxis.prax.evidence.PositionEvidenceBuilder;
import com.praxis.prax.intelligence.ChessIntelligence;
import com.praxis.prax.web.WebResearchService;
import com.praxis.repository.CardRepository;
import com.praxis.service.analysis.StockfishService;
import com.praxis.service.diagnosis.DiagnosisService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Prax's explain_mistake: the rules' verified diagnosis, handed to the model as
 * facts it may select but not reword.
 */
@DisplayName("explain_mistake tool")
class ExplainMistakeToolTest {

    private static final UUID GAME = UUID.fromString("7c3c9a2e-1111-4a22-8b33-445566778899");

    private DiagnosisService diagnosis;
    private StockfishService stockfish;
    private ToolRegistry tools;

    @BeforeEach
    void setUp() {
        diagnosis = mock(DiagnosisService.class);
        stockfish = mock(StockfishService.class);
        when(stockfish.isAvailable()).thenReturn(true);
        tools = new ToolRegistry(mock(ChessIntelligence.class), mock(AppProperties.class),
                mock(CardRepository.class), stockfish, mock(PositionEvidenceBuilder.class),
                mock(WebResearchService.class), diagnosis);
    }

    private static DiagnosisService.Why scholarsMate() {
        var g = TestGraphs.scholarsMate();
        var v = WhyView.of(g);
        return new DiagnosisService.Why(GAME, 6, "3... Nf6", v, WhyView.facts(g, v));
    }

    @Test
    void theVerifiedExplanationIsTheFirstFact() {
        when(diagnosis.why(GAME, 6)).thenReturn(Optional.of(scholarsMate()));

        var r = tools.execute("explain_mistake", Map.of("gameId", GAME.toString(), "ply", 6));

        assertThat(r.provenance()).isEqualTo(Evidence.Provenance.ENGINE);
        var data = (Map<?, ?>) r.data();
        assertThat(data.get("move")).isEqualTo("3... Nf6");
        assertThat(data.get("engineMove")).isEqualTo("g6");
        var facts = (List<?>) data.get("verifiedFacts");
        var first = (Map<?, ?>) facts.get(0);
        assertThat(first.get("id")).isEqualTo("w1");
        assertThat(first.get("fact")).isEqualTo(scholarsMate().diagnosis().explanation());
    }

    /** The model quotes whatever it is given, so it is never given an enum name. */
    @Test
    void theModelNeverSeesAnInternalLabel() throws Exception {
        when(diagnosis.why(GAME, 6)).thenReturn(Optional.of(scholarsMate()));

        var r = tools.execute("explain_mistake", Map.of("gameId", GAME.toString(), "ply", 6));
        String json = new ObjectMapper().writeValueAsString(r.data());

        assertThat(json).doesNotContain("IGNORED_THREAT", "MATED", "SHALLOW", "THREAT_EXISTS", "RULES");
        assertThat(((Map<?, ?>) r.data()).get("cause"))
                .isEqualTo("a threat that was already there, left unanswered");
    }

    @Test
    void theBoardComesWithIt() {
        when(diagnosis.why(GAME, 6)).thenReturn(Optional.of(scholarsMate()));

        var r = tools.execute("explain_mistake", Map.of("gameId", GAME.toString(), "ply", 6));

        assertThat(r.artifacts()).hasSize(1);
        var board = (MoveComparisonArtifact) r.artifacts().get(0);
        assertThat(board.playedMove()).isEqualTo("Nf6");
        assertThat(board.bestMove()).isEqualTo("g6");
    }

    /** Models send loose types: the ply often arrives as a string or a double. */
    @Test
    void aPlyAsTextStillWorks() {
        when(diagnosis.why(GAME, 6)).thenReturn(Optional.of(scholarsMate()));

        var r = tools.execute("explain_mistake", Map.of("gameId", GAME.toString(), "ply", "6.0"));

        assertThat(((Map<?, ?>) r.data()).containsKey("error")).isFalse();
    }

    @Test
    void aWrongGameIdSaysWhereToGetARightOne() {
        var r = tools.execute("explain_mistake", Map.of("gameId", "my last game", "ply", 6));

        assertThat(String.valueOf(((Map<?, ?>) r.data()).get("error")))
                .contains("is not a game id").contains("find_mistakes");
    }

    @Test
    void aPlyWithNoMistakeIsATypedError() {
        when(diagnosis.why(any(), anyInt())).thenReturn(Optional.empty());

        var r = tools.execute("explain_mistake", Map.of("gameId", GAME.toString(), "ply", 7));

        assertThat(String.valueOf(((Map<?, ?>) r.data()).get("error"))).contains("No flagged mistake at ply 7");
    }

    @Test
    void itIsOfferedToTheModel() {
        assertThat(tools.schemas()).anyMatch(s -> String.valueOf(s).contains("name=explain_mistake"));
    }

    @Test
    void findMistakesChainsTheDiagnosisOfItsWorstRow() {
        var found = ToolResult.singleObject("find_mistakes", Map.of("mistakes", List.of(
                Map.of("gameId", GAME.toString(), "ply", 31, "fen", TestGraphs.SCHOLAR_FEN, "movePlayed", "Nf6"))));

        var next = tools.followUp("find_mistakes", found);

        assertThat(next).contains(new ToolRegistry.FollowUp("explain_mistake",
                Map.of("gameId", GAME.toString(), "ply", 31)));
    }

    /** A row without a ply cannot name its mistake, so the engine is run on the position instead. */
    @Test
    void withoutAPlyItFallsBackToTheEngine() {
        var found = ToolResult.singleObject("find_mistakes", Map.of("mistakes", List.of(
                Map.of("fen", TestGraphs.SCHOLAR_FEN, "movePlayed", "Nf6"))));

        var next = tools.followUp("find_mistakes", found);

        assertThat(next).map(ToolRegistry.FollowUp::tool).contains("analyze_position");
    }
}
