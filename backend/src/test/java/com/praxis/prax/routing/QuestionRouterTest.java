package com.praxis.prax.routing;

import com.praxis.prax.routing.QuestionRouter.Lane;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The router decides which tools the model is shown, so a misroute is not a
 * cosmetic problem: sending a player question to the web lane means answering
 * "what is my accuracy" from a blog post.
 *
 * Table-driven, because the value here is breadth of phrasing rather than depth
 * of any one case.
 */
@DisplayName("QuestionRouter Tests")
class QuestionRouterTest {

    @Nested
    @DisplayName("Player Lane Tests")
    class PlayerLaneTests {

        @ParameterizedTest
        @ValueSource(strings = {
                "What is my weakness?",
                "what's my accuracy",
                "How am I doing?",
                "Where do I blunder most?",
                "Show me my worst games",
                "my Sicilian win rate",
                "Am I improving?",
                "do i play better as white",
                "What openings should I drill?",
                "How have we been doing this month?",
                "explain my last blunder",
                "What did I improve at this month?",
        })
        @DisplayName("Should route first-person questions to PLAYER")
        void shouldRouteFirstPersonToPlayer(String question) {
            assertThat(QuestionRouter.route(question)).isEqualTo(Lane.PLAYER);
        }

        @ParameterizedTest
        @ValueSource(strings = {
                "Why was 23...h6 bad?",
                "was 12. Nf3 a mistake",
                "look at that game again",
                "what happened in my last game",
                "Analyse this position",
                "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1 — what now?",
        })
        @DisplayName("Should route concrete game and move references to PLAYER")
        void shouldRouteGameReferencesToPlayer(String question) {
            assertThat(QuestionRouter.route(question)).isEqualTo(Lane.PLAYER);
        }

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {"   ", "hello", "hi prax", "thanks"})
        @DisplayName("Should default to PLAYER when nothing matches")
        void shouldDefaultToPlayer(String question) {
            // The safe direction: web access is opt-in on positive evidence, so a
            // router miss degrades to the existing, grounded behaviour.
            assertThat(QuestionRouter.route(question)).isEqualTo(Lane.PLAYER);
        }
    }

    @Nested
    @DisplayName("General Lane Tests")
    class GeneralLaneTests {

        @ParameterizedTest
        @ValueSource(strings = {
                "What is the Nimzovich-Larsen Attack?",
                "what's a Greek gift sacrifice",
                "Who is Ding Liren?",
                "What is the main line of the Najdorf?",
                "Explain the theory behind 5.Bg5",
                "define zugzwang",
                "meaning of prophylaxis",
                "history of the Sicilian Defence",
                "Who invented the Danish Gambit?",
                "How does castling work?",
                "What does en passant mean?",
        })
        @DisplayName("Should route definitional questions to GENERAL")
        void shouldRouteDefinitionalToGeneral(String question) {
            assertThat(QuestionRouter.route(question)).isEqualTo(Lane.GENERAL);
        }

        @Test
        @DisplayName("Should route the philosophy question to GENERAL")
        void shouldRouteThePhilosophyQuestionToGeneral() {
            // The question that motivated the feature. "tell me" must NOT read as
            // first person — it is a request for information, not a claim of
            // ownership. Treating bare "me" as first person sent this to PLAYER,
            // where no tool could answer it.
            assertThat(QuestionRouter.route(
                    "Tell me how strategic thinking in chess mirrors logical reasoning in philosophy"))
                    .isEqualTo(Lane.GENERAL);
        }
    }

    @Nested
    @DisplayName("Hybrid Lane Tests")
    class HybridLaneTests {

        @ParameterizedTest
        @ValueSource(strings = {
                "How do I score in the Najdorf, and what is its main line?",
                "I lose to the London System — what is the theory behind it?",
                "my worst opening is the Nimzovich-Larsen Attack, what is that?",
        })
        @DisplayName("Should route questions carrying both signals to HYBRID")
        void shouldRouteBothSignalsToHybrid(String question) {
            assertThat(QuestionRouter.route(question)).isEqualTo(Lane.HYBRID);
        }
    }

    @Nested
    @DisplayName("Time And Place Tests")
    class TimeAndPlaceTests {

        @ParameterizedTest
        @ValueSource(strings = {
                "When was Nimzovich-Larsen last match?",
                "When was the Sicilian Defence first played?",
                "When did Ding Liren become world champion?",
                "Where did Nimzowitsch live?",
        })
        @DisplayName("Should route questions of time and place to GENERAL")
        void shouldRouteTimeAndPlaceToGeneral(String question) {
            // REGRESSION: "When was Nimzovich-Larsen last match?" fell through to
            // PLAYER because no pattern covered `when` or `where`, so the player
            // got "that is outside the scope of your game history" for a plain
            // factual question.
            assertThat(QuestionRouter.route(question)).isEqualTo(Lane.GENERAL);
        }

        @ParameterizedTest
        @ValueSource(strings = {
                "When did I last play the Sicilian?",
                "When was my last game?",
                "Where do I blunder most?",
                "Where are my worst results?",
        })
        @DisplayName("Should keep the first-person forms of when and where in PLAYER")
        void shouldKeepFirstPersonTimeAndPlaceInPlayer(String question) {
            // The same lookahead that protects "what is my accuracy". Widening
            // the pattern set must not drag these across.
            assertThat(QuestionRouter.route(question)).isEqualTo(Lane.PLAYER);
        }
    }

    @Nested
    @DisplayName("Signal Strength Tests")
    class SignalStrengthTests {

        @Test
        @DisplayName("Should treat move notation as a weak player signal")
        void shouldTreatMoveNotationAsWeak() {
            // Alone, a move number means their own game.
            assertThat(QuestionRouter.route("Why was 23...h6 bad?")).isEqualTo(Lane.PLAYER);

            // Alongside a definitional phrase it names shared theory instead.
            // Routing this to HYBRID would hand a 4B model both toolsets and
            // invite the tool-selection confusion the router exists to prevent.
            assertThat(QuestionRouter.route("Explain the theory behind 5.Bg5")).isEqualTo(Lane.GENERAL);
        }

        @Test
        @DisplayName("Should treat a concrete game reference as a strong signal")
        void shouldTreatConcreteGameAsStrong() {
            // "that game" can only mean one in the database, so even with a
            // definitional phrase the player tools stay available.
            assertThat(QuestionRouter.route("What is the opening in that game?")).isEqualTo(Lane.HYBRID);
        }
    }

    @Nested
    @DisplayName("Lane Capability Tests")
    class LaneCapabilityTests {

        @Test
        @DisplayName("Should allow web only outside the player lane")
        void shouldAllowWebOnlyOutsidePlayerLane() {
            assertThat(Lane.PLAYER.allowsWeb()).isFalse();
            assertThat(Lane.GENERAL.allowsWeb()).isTrue();
            assertThat(Lane.HYBRID.allowsWeb()).isTrue();
        }

        @Test
        @DisplayName("Should keep player data out of the pure general lane")
        void shouldKeepPlayerDataOutOfGeneralLane() {
            assertThat(Lane.PLAYER.allowsPlayerData()).isTrue();
            assertThat(Lane.HYBRID.allowsPlayerData()).isTrue();
            assertThat(Lane.GENERAL.allowsPlayerData()).isFalse();
        }
    }

    @Nested
    @DisplayName("Possessive Precedence Tests")
    class PossessivePrecedenceTests {

        @Test
        @DisplayName("Should not treat 'what is my ...' as definitional")
        void shouldNotTreatPossessiveAsDefinitional() {
            // The negative lookahead earning its keep. Without it every
            // "what is my X" question would reach the web lane.
            assertThat(QuestionRouter.route("What is my best opening?")).isEqualTo(Lane.PLAYER);
            assertThat(QuestionRouter.route("what are my worst motifs")).isEqualTo(Lane.PLAYER);
            assertThat(QuestionRouter.route("How do I castle?")).isEqualTo(Lane.PLAYER);
        }

        @Test
        @DisplayName("Should still catch the definitional form of the same subject")
        void shouldCatchDefinitionalFormOfSameSubject() {
            assertThat(QuestionRouter.route("What is the best opening?")).isEqualTo(Lane.GENERAL);
            assertThat(QuestionRouter.route("How does one castle?")).isEqualTo(Lane.GENERAL);
        }
    }
}
