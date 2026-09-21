package com.praxis.prax.web;

import com.praxis.prax.web.WebResearchService.Source;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Deduplication of sources.
 *
 * From a real run: a search returned the same Wikipedia article under two URLs,
 * both were fetched, both cached, and both shown as separate sources. With
 * max-pages at 2 the entire web budget went on one article, and the model read
 * it twice.
 */
@DisplayName("Web Research Dedup Tests")
class WebResearchServiceTest {

    /** The two cached rows, as they actually appeared, trimmed to the shape that matters. */
    private static final String CANONICAL =
            "From Wikipedia, the free encyclopedia Chess opening Chess opening "
            + "Nimzowitsch-Larsen Attack a b c d e f g h 8 8 7 7 6 6 5 5 4 4 3 3 2 2 1 1 "
            + "Moves 1.b3 ECO A01 Named after Aron Nimzowitsch Bent Larsen Parent Queen's "
            + "Fianchetto Opening The Nimzowitsch-Larsen Attack is a chess opening that "
            + "typically begins with the move 1.b3, a flank opening.";

    /** Identical, except for the redirect notice Wikipedia prepends on the alias URL. */
    private static final String VIA_REDIRECT =
            "From Wikipedia, the free encyclopedia (Redirected from Nimzovich-Larsen Attack) "
            + "Chess opening Chess opening Nimzowitsch-Larsen Attack a b c d e f g h "
            + "8 8 7 7 6 6 5 5 4 4 3 3 2 2 1 1 Moves 1.b3 ECO A01 Named after Aron "
            + "Nimzowitsch Bent Larsen Parent Queen's Fianchetto Opening The "
            + "Nimzowitsch-Larsen Attack is a chess opening that typically begins with "
            + "the move 1.b3, a flank opening.";

    private static Source source(String url, String title, String text) {
        return new Source(title, WebResearchService.domainOf(url), url, "", text);
    }

    /** Two pages are the same when ANY signal matches — mirrors the dedup rule. */
    private static boolean shareASignal(Source a, Source b) {
        return WebResearchService.fingerprintsOf(a).stream()
                .anyMatch(WebResearchService.fingerprintsOf(b)::contains);
    }

    @Nested
    @DisplayName("Fingerprint Tests")
    class FingerprintTests {

        @Test
        @DisplayName("Should give the same fingerprint to one article served at two URLs")
        void shouldMatchAcrossAliasUrls() {
            // Wikipedia answers BOTH with 200 — there is no redirect to follow,
            // so comparing URLs cannot catch this.
            var a = source("https://en.wikipedia.org/wiki/Nimzowitsch%E2%80%93Larsen_Attack",
                    "Nimzowitsch–Larsen Attack - Wikipedia", CANONICAL);
            var b = source("https://en.wikipedia.org/wiki/Nimzovich-Larsen_Attack",
                    "Nimzowitsch–Larsen Attack - Wikipedia", VIA_REDIRECT);

            assertThat(shareASignal(a, b)).isTrue();
        }

        @Test
        @DisplayName("Should compare the tail, not the head, on a full-length page")
        void shouldCompareTailNotHead() {
            // The whole reason the head is unusable: the duplicate differs ONLY
            // in its opening words. Hashing the first characters would call
            // these two different pages.
            //
            // Padded past the tail window, as a real extract is (~1470 chars) —
            // that is what lets the differing prefix fall outside it.
            String pad = " Further reading and analysis of the variation follows.".repeat(12);
            var a = source("https://a.test/x", "Different Title A", CANONICAL + pad);
            var b = source("https://b.test/y", "Different Title B", VIA_REDIRECT + pad);

            assertThat(CANONICAL.substring(0, 60)).isNotEqualTo(VIA_REDIRECT.substring(0, 60));
            assertThat(shareASignal(a, b))
                    .as("identical tails must identify one page even with different titles")
                    .isTrue();
        }

        @Test
        @DisplayName("Should still catch a SHORT duplicate, via title and domain")
        void shouldCatchShortDuplicateByTitle() {
            // Under the tail window the prefix stays inside it, so the text
            // hashes differ. The title signal is what closes that gap.
            var a = source("https://en.wikipedia.org/wiki/Nimzowitsch%E2%80%93Larsen_Attack",
                    "Nimzowitsch–Larsen Attack - Wikipedia", CANONICAL);
            var b = source("https://en.wikipedia.org/wiki/Nimzovich-Larsen_Attack",
                    "Nimzowitsch–Larsen Attack - Wikipedia", VIA_REDIRECT);

            assertThat(shareASignal(a, b)).isTrue();
        }

        @Test
        @DisplayName("Should keep genuinely different pages apart")
        void shouldNotCollapseDifferentPages() {
            var a = source("https://en.wikipedia.org/wiki/Magnus_Carlsen",
                    "Magnus Carlsen - Wikipedia",
                    "Magnus Carlsen is a Norwegian chess grandmaster born in 1990 who has "
                    + "held the world number one ranking since 2011 and won five world titles.");
            var b = source("https://en.wikipedia.org/wiki/Aron_Nimzowitsch",
                    "Aron Nimzowitsch - Wikipedia",
                    "Aron Nimzowitsch was a Latvian-born Danish chess master and writer, one "
                    + "of the foremost figures of the hypermodern school of chess thought.");

            assertThat(shareASignal(a, b)).isFalse();
        }

        @Test
        @DisplayName("Should fall back to domain and title when there is no text")
        void shouldFallBackToDomainAndTitle() {
            var a = source("https://en.wikipedia.org/wiki/X", "Same Title", "");
            var b = source("https://en.wikipedia.org/wiki/Y", "same title", "");
            var c = source("https://other.test/Y", "Same Title", "");

            assertThat(shareASignal(a, b)).isTrue();
            assertThat(shareASignal(a, c))
                    .as("same title on a different site is a different page")
                    .isFalse();
        }
    }

    @Nested
    @DisplayName("Domain Tests")
    class DomainTests {

        @Test
        @DisplayName("Should strip www so mirrored hosts compare equal")
        void shouldStripWww() {
            assertThat(WebResearchService.domainOf("https://www.chess.com/openings"))
                    .isEqualTo("chess.com");
            assertThat(WebResearchService.domainOf("https://chess.com/openings"))
                    .isEqualTo("chess.com");
        }

        @Test
        @DisplayName("Should not throw on a malformed URL")
        void shouldNotThrowOnRubbish() {
            assertThat(WebResearchService.domainOf("not a url")).isEmpty();
        }
    }

    /**
     * From a real run: asked "Who is Magnus?", the search returned Magnus
     * Carlsen's Wikipedia page alongside "Magnus the Red | Warhammer 40k Wiki",
     * and the answer explained that Magnus is also a Daemon Prince.
     *
     * Nothing about that run violated the grounding invariant — both sources
     * were real and both were fetched through the trusted path. The wrong thing
     * had simply been looked up, which is a failure the grounding layer is not
     * built to catch and cannot be prompted away.
     */
    @Nested
    @DisplayName("Query domain")
    class QueryDomain {

        @Test
        @DisplayName("a query with no chess term is searched in the chess domain")
        void shouldAugmentBareQuery() {
            assertThat(WebResearchService.chessQuery("Who is Magnus?"))
                    .isEqualTo("Who is Magnus? chess");
            assertThat(WebResearchService.chessQuery("Who is the best player ever?"))
                    .isEqualTo("Who is the best player ever? chess");
        }

        @Test
        @DisplayName("a query that already says chess is searched as asked")
        void shouldLeaveChessQueryAlone() {
            for (String q : new String[] {
                    "What is the Nimzowitsch-Larsen Attack?",
                    "best chess opening for beginners",
                    "how does castling work",
                    "what is a zugzwang",
                    "explain the Sicilian",
                    "who has the highest Elo",
                    "what does PGN stand for",
            }) {
                assertThat(WebResearchService.chessQuery(q))
                        .as("%s already names its domain", q)
                        .isEqualTo(q);
            }
        }

        @Test
        @DisplayName("matching is on whole words, not substrings")
        void shouldNotMatchInsideAnotherWord() {
            // "Brooke" contains "rook", and a naive contains() check would call
            // that a chess query and search it as one.
            assertThat(WebResearchService.chessQuery("who is Brooke Shields"))
                    .isEqualTo("who is Brooke Shields chess");
            // "Openings" plural and capitalised still counts.
            assertThat(WebResearchService.chessQuery("Best Openings"))
                    .isEqualTo("Best Openings");
        }

        @Test
        @DisplayName("a hyphen splits, but a dot does not")
        void shouldSplitOnHyphenNotDot() {
            // Hyphens split, or "Nimzowitsch-Larsen" is one unrecognised token
            // and the query gets " chess" bolted onto it.
            assertThat(WebResearchService.chessQuery("Caro-Kann main line"))
                    .isEqualTo("Caro-Kann main line");
            assertThat(WebResearchService.chessQuery("what is en-passant?"))
                    .isEqualTo("what is en-passant?");
            // Dots do not, because "chess.com" is a single term.
            assertThat(WebResearchService.chessQuery("chess.com vs lichess"))
                    .isEqualTo("chess.com vs lichess");
        }

        @Test
        @DisplayName("a player question with no chess word still lands in chess")
        void shouldAugmentPlayerNames() {
            // The failing case, and its neighbours: a name alone is ambiguous to
            // a general search engine and unambiguous to this app.
            assertThat(WebResearchService.chessQuery("Who is Hikaru?"))
                    .isEqualTo("Who is Hikaru? chess");
        }
    }
}
