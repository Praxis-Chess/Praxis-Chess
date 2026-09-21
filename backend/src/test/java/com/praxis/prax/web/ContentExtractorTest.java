package com.praxis.prax.web;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What reaches the model decides what it can say.
 *
 * The context window is 4096 tokens, so every character of navigation, cookie
 * banner or related-articles rail that survives extraction is a character of
 * actual article that does not fit.
 */
@DisplayName("ContentExtractor Tests")
class ContentExtractorTest {

    private ContentExtractor extractor;

    @BeforeEach
    void setUp() {
        extractor = new ContentExtractor();
    }

    private static String page(String body) {
        return "<html><head><title>The Najdorf Variation</title></head><body>" + body + "</body></html>";
    }

    /** Long enough to clear MIN_USEFUL_CHARS. */
    private static String prose(String lead) {
        return lead + " " + "The Najdorf is a variation of the Sicilian Defence reached after "
                + "1.e4 c5 2.Nf3 d6 3.d4 cxd4 4.Nxd4 Nf6 5.Nc3 a6, and it is among the most "
                + "heavily analysed openings in chess.";
    }

    @Nested
    @DisplayName("Noise Removal Tests")
    class NoiseRemovalTests {

        @Test
        @DisplayName("Should drop scripts and styles entirely")
        void shouldDropScriptsAndStyles() {
            var e = extractor.extract(page(
                    "<script>var tracking = 'do not read me';</script>"
                    + "<style>.x{color:red}</style>"
                    + "<article><p>" + prose("Article text.") + "</p></article>"), "https://x.test/a");

            assertThat(e).isNotNull();
            assertThat(e.text()).doesNotContain("tracking").doesNotContain("color:red");
            assertThat(e.text()).contains("Najdorf");
        }

        @Test
        @DisplayName("Should drop navigation, header, footer and sidebars")
        void shouldDropFurniture() {
            var e = extractor.extract(page(
                    "<nav>Home Login Register</nav>"
                    + "<header>SiteName</header>"
                    + "<aside class=\"related\">Related: Ten best openings</aside>"
                    + "<article><p>" + prose("Body.") + "</p></article>"
                    + "<footer>Copyright 2026</footer>"), "https://x.test/a");

            assertThat(e).isNotNull();
            assertThat(e.text())
                    .doesNotContain("Login")
                    .doesNotContain("SiteName")
                    .doesNotContain("Ten best openings")
                    .doesNotContain("Copyright");
        }

        @Test
        @DisplayName("Should drop cookie and newsletter banners")
        void shouldDropBanners() {
            var e = extractor.extract(page(
                    "<div class=\"cookie\">We value your privacy. Accept all cookies?</div>"
                    + "<div class=\"newsletter\">Subscribe for more</div>"
                    + "<main><p>" + prose("Real content.") + "</p></main>"), "https://x.test/a");

            assertThat(e).isNotNull();
            assertThat(e.text()).doesNotContain("cookies").doesNotContain("Subscribe");
        }
    }

    @Nested
    @DisplayName("Content Selection Tests")
    class ContentSelectionTests {

        @Test
        @DisplayName("Should prefer the article element over the whole body")
        void shouldPreferArticle() {
            var e = extractor.extract(page(
                    "<div>Unrelated preamble that is not the article at all.</div>"
                    + "<article><p>" + prose("Chosen.") + "</p></article>"), "https://x.test/a");

            assertThat(e).isNotNull();
            assertThat(e.text()).startsWith("Chosen.");
        }

        @Test
        @DisplayName("Should read Wikipedia's parser output")
        void shouldReadWikipediaBody() {
            // Named explicitly in the selector list: Wikipedia is the single most
            // likely source for a chess-terminology question.
            var e = extractor.extract(page(
                    "<div class=\"mw-parser-output\"><p>" + prose("Wiki.") + "</p></div>"),
                    "https://en.wikipedia.org/wiki/Najdorf");

            assertThat(e).isNotNull();
            assertThat(e.text()).contains("Najdorf");
        }

        @Test
        @DisplayName("Should fall back to the body when nothing matches")
        void shouldFallBackToBody() {
            var e = extractor.extract(page("<div><p>" + prose("Plain.") + "</p></div>"),
                    "https://x.test/a");

            assertThat(e).isNotNull();
            assertThat(e.text()).contains("Najdorf");
        }
    }

    @Nested
    @DisplayName("Budget Tests")
    class BudgetTests {

        @Test
        @DisplayName("Should cap extracted text at the context budget")
        void shouldCapText() {
            String long_ = "Chess is a game of strategy and calculation. ".repeat(200);
            var e = extractor.extract(page("<article><p>" + long_ + "</p></article>"), "https://x.test/a");

            assertThat(e).isNotNull();
            // Three pages at this size must still leave room for the question,
            // the system prompt and the answer inside num_ctx 4096.
            assertThat(e.text().length()).isLessThanOrEqualTo(ContentExtractor.MAX_CHARS + 1);
        }

        @Test
        @DisplayName("Should cut at a sentence boundary rather than mid-claim")
        void shouldCutAtSentenceBoundary() {
            String long_ = "Chess is a game of strategy and calculation. ".repeat(200);
            var e = extractor.extract(page("<article><p>" + long_ + "</p></article>"), "https://x.test/a");

            assertThat(e).isNotNull();
            // Handing the model half a sentence invites it to finish the thought
            // from imagination, which is the whole failure this layer prevents.
            assertThat(e.text().trim()).endsWith(".");
        }

        @Test
        @DisplayName("Should collapse whitespace including non-breaking spaces")
        void shouldCollapseWhitespace() {
            var e = extractor.extract(page(
                    "<article><p>" + prose("A  B   C\n\n\nD.") + "</p></article>"),
                    "https://x.test/a");

            assertThat(e).isNotNull();
            assertThat(e.text()).doesNotContain(" ").doesNotContain("  ");
        }
    }

    @Nested
    @DisplayName("Empty Result Tests")
    class EmptyResultTests {

        @Test
        @DisplayName("Should return null for a page with no prose")
        void shouldReturnNullForNoProse() {
            // A JavaScript-only page is a normal outcome, not an error. The
            // caller drops the source and says so rather than inventing content.
            assertThat(extractor.extract(page("<nav>Home</nav><div id=\"app\"></div>"), "https://x.test/a"))
                    .isNull();
        }

        @Test
        @DisplayName("Should return null for null or blank html")
        void shouldReturnNullForBlank() {
            assertThat(extractor.extract(null, "https://x.test/a")).isNull();
            assertThat(extractor.extract("   ", "https://x.test/a")).isNull();
        }

        @Test
        @DisplayName("Should fall back to the host when a page has no title")
        void shouldFallBackToHost() {
            var e = extractor.extract(
                    "<html><body><article><p>" + prose("No title here.") + "</p></article></body></html>",
                    "https://en.wikipedia.org/wiki/Najdorf");

            assertThat(e).isNotNull();
            assertThat(e.title()).isEqualTo("en.wikipedia.org");
        }
    }
}
