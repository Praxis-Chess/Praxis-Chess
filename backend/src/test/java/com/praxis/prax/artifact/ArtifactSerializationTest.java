package com.praxis.prax.artifact;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.praxis.prax.artifact.ChessPositionArtifact.Arrow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the browser actually receives.
 *
 * THE GAP THIS CLOSES. The Playwright suite renders artifacts from JSON written
 * by hand in the test file, so it proved the renderer works — against JSON the
 * backend never produced. Meanwhile every backend test asserted on Java objects,
 * which were perfectly well formed.
 *
 * Between the two sat the serialiser, tested by neither. It dropped `type`
 * entirely, so every artifact reached the frontend switch as `undefined`, fell
 * to `default`, and rendered as nothing. No test failed.
 */
@DisplayName("Artifact Serialization Tests")
class ArtifactSerializationTest {

    private ObjectMapper mapper;

    @BeforeEach
    void setUp() {
        // The same naming strategy application.yml sets. Without it this test
        // would pass on camelCase keys the client never reads.
        mapper = new ObjectMapper()
                .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
    }

    private JsonNode toJson(PraxArtifact a) throws Exception {
        return mapper.readTree(mapper.writeValueAsString(a));
    }

    @Test
    @DisplayName("Should serialise the discriminator every renderer switches on")
    void shouldSerialiseType() throws Exception {
        // `type()` is an override method, not a record component. Jackson
        // serialises components, so without @JsonProperty this is simply absent.
        assertThat(toJson(position()).path("type").asText()).isEqualTo("CHESS_POSITION");
        assertThat(toJson(comparison()).path("type").asText()).isEqualTo("MOVE_COMPARISON");
        assertThat(toJson(chart()).path("type").asText()).isEqualTo("CHART");
        assertThat(toJson(table()).path("type").asText()).isEqualTo("TABLE");
    }

    @Test
    @DisplayName("Should never emit a null or missing type")
    void shouldNeverEmitNullType() throws Exception {
        for (PraxArtifact a : List.of(position(), comparison(), chart(), table())) {
            JsonNode type = toJson(a).path("type");
            assertThat(type.isMissingNode() || type.isNull())
                    .as("%s serialised without a usable type", a.getClass().getSimpleName())
                    .isFalse();
        }
    }

    @Test
    @DisplayName("Should use snake_case for the keys the client reads")
    void shouldUseSnakeCase() throws Exception {
        JsonNode cmp = toJson(comparison());
        assertThat(cmp.has("played_move")).isTrue();
        assertThat(cmp.has("best_move")).isTrue();
        assertThat(cmp.has("eval_before")).isTrue();
        assertThat(cmp.has("loss_pawns")).isTrue();
        // camelCase would be silently ignored by the renderer, exactly as
        // `sessionId` was by the practice-game client.
        assertThat(cmp.has("playedMove")).isFalse();

        assertThat(toJson(chart()).has("chart_id")).isTrue();
    }

    @Test
    @DisplayName("Should not nest a second copy of the board inside a comparison")
    void shouldNotSerialiseTheHelperGetter() throws Exception {
        // position() is a public no-arg method, which Jackson would otherwise
        // treat as a property and emit in full.
        assertThat(toJson(comparison()).has("position")).isFalse();
    }

    @Test
    @DisplayName("Should carry every field the board renderer needs")
    void shouldCarryBoardFields() throws Exception {
        JsonNode n = toJson(position());

        assertThat(n.path("fen").asText()).isNotBlank();
        assertThat(n.path("orientation").asText()).isEqualTo("white");
        assertThat(n.path("highlights").isArray()).isTrue();
        assertThat(n.path("arrows").isArray()).isTrue();
        // The arrow's MEANING, not a colour — the frontend owns the palette.
        assertThat(n.path("arrows").get(0).path("role").asText()).isEqualTo("BEST");
        assertThat(n.path("arrows").get(0).path("from").asText()).isEqualTo("e2");
    }

    @Test
    @DisplayName("Should carry chart bars as label and value")
    void shouldCarryChartFields() throws Exception {
        JsonNode n = toJson(chart());

        assertThat(n.path("chart_id").asText()).isEqualTo("MISTAKES_BY_MOTIF");
        assertThat(n.path("unit").asText()).isEqualTo("mistakes");
        assertThat(n.path("bars").get(0).path("label").asText()).isEqualTo("Positional");
        assertThat(n.path("bars").get(0).path("value").asDouble()).isEqualTo(154.0);
    }

    // --- fixtures ---

    private static final String FEN = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1";

    private static ChessPositionArtifact position() {
        return new ChessPositionArtifact("p1", "The position", FEN, "white",
                List.of("e2", "e4"), List.of(new Arrow("e2", "e4", Arrow.Role.BEST)), "Caption.");
    }

    private static MoveComparisonArtifact comparison() {
        return new MoveComparisonArtifact("c1", "The position", FEN, "white",
                List.of("e2"), List.of(new Arrow("e2", "e4", Arrow.Role.BEST)),
                "g6", "Nb4", 5.28, -3.2, 8.48, null);
    }

    private static ChartArtifact chart() {
        return new ChartArtifact("ch1", "Where your mistakes come from",
                ChartArtifact.ChartId.MISTAKES_BY_MOTIF, "mistakes",
                List.of(new ChartArtifact.Bar("Positional", 154)), null);
    }

    private static TableArtifact table() {
        return new TableArtifact("t1", "Your openings", List.of("Opening", "Games"),
                List.of(TableArtifact.Align.LEFT, TableArtifact.Align.RIGHT),
                List.of(List.of("Sicilian Defense", "21")), null);
    }
}
