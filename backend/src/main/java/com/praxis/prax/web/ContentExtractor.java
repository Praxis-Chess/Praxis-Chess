package com.praxis.prax.web;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Component;

/**
 * Page HTML in, readable prose out.
 *
 * Two jobs, and the second one is the one that matters. Stripping tags is easy;
 * what actually decides answer quality is throwing away navigation, cookie
 * banners, related-article rails and footers, because on a modern page those
 * outnumber the article and would fill the model's context with menu items.
 *
 * The budget is small on purpose. OllamaChatClient runs at num_ctx 4096, so
 * three pages at 1500 characters is already most of the window before the
 * question, the system prompt and the answer are counted.
 */
@Component
public class ContentExtractor {

    /** Per page. Roughly 350-400 tokens, so three pages fit inside the context. */
    public static final int MAX_CHARS = 1500;

    /** Below this there was no article, only furniture. */
    static final int MIN_USEFUL_CHARS = 120;

    /**
     * Elements that are never content. Removed before any text is read, so their
     * words cannot win the "biggest block" contest below.
     */
    private static final String NOISE = String.join(",",
            "script", "style", "noscript", "iframe", "svg", "form", "button",
            "nav", "header", "footer", "aside",
            "[role=navigation]", "[role=banner]", "[role=contentinfo]", "[role=complementary]",
            ".nav", ".navbar", ".menu", ".sidebar", ".footer", ".header",
            ".cookie", ".consent", ".newsletter", ".subscribe", ".advert", ".ads",
            ".related", ".recommended", ".comments", ".share", ".social",
            "#comments", "#sidebar", "#footer", "#header");

    /**
     * Containers that usually ARE the article, best first. Wikipedia's
     * `.mw-parser-output` is named explicitly because it is the single most
     * likely source for a chess-terminology question.
     */
    private static final String[] CONTENT_SELECTORS = {
            "article", "main", "[role=main]",
            ".mw-parser-output", "#mw-content-text",
            ".post-content", ".entry-content", ".article-body", ".content", "#content",
    };

    public record Extract(String title, String text) {}

    /**
     * @return the extract, or null when the page carried no usable prose. Null is
     *         a normal outcome — a JavaScript-only page is not an error.
     */
    public Extract extract(String html, String url) {
        if (html == null || html.isBlank()) return null;

        Document doc;
        try {
            doc = Jsoup.parse(html, url == null ? "" : url);
        } catch (Exception e) {
            return null;
        }

        String title = doc.title();
        doc.select(NOISE).remove();

        Element best = null;
        for (String sel : CONTENT_SELECTORS) {
            Element candidate = doc.selectFirst(sel);
            if (candidate != null && candidate.text().length() >= MIN_USEFUL_CHARS) {
                best = candidate;
                break;
            }
        }
        // Nothing matched — fall back to the whole body, which is worse but not
        // useless once the furniture above has been stripped.
        if (best == null) best = doc.body();
        if (best == null) return null;

        String text = normalise(best.text());
        if (text.length() < MIN_USEFUL_CHARS) return null;

        return new Extract(
                title == null || title.isBlank() ? hostOf(url) : title.trim(),
                truncate(text));
    }

    /** Collapses the whitespace jsoup leaves behind, including non-breaking spaces. */
    static String normalise(String s) {
        return s.replace(' ', ' ')
                .replaceAll("\\s+", " ")
                .trim();
    }

    /**
     * Cut at a sentence boundary where one is near the limit, so the model is
     * never handed half a claim to complete from imagination.
     */
    static String truncate(String text) {
        if (text.length() <= MAX_CHARS) return text;
        String cut = text.substring(0, MAX_CHARS);
        int stop = Math.max(cut.lastIndexOf(". "), cut.lastIndexOf("? "));
        return stop > MAX_CHARS / 2 ? cut.substring(0, stop + 1) : cut.trim() + "…";
    }

    static String hostOf(String url) {
        try {
            String h = java.net.URI.create(url).getHost();
            return h == null ? "Untitled" : h;
        } catch (Exception e) {
            return "Untitled";
        }
    }
}
