package com.praxis.prax.web;

import java.util.List;

/**
 * Where search results come from.
 *
 * An interface because the right backend depends on what the operator is willing
 * to run and to share. SearXNG is the default — self-hosted, no API key, nobody
 * else sees the queries, which matches this app's local-first posture. Brave is
 * the drop-in for anyone who would rather have a key than a container.
 */
public interface WebSearchProvider {

    /**
     * @param title   result heading as the engine gave it
     * @param url     absolute, and still untrusted — SafeFetcher re-checks it
     * @param snippet the engine's own summary; useful when a page cannot be fetched
     */
    record SearchHit(String title, String url, String snippet) {}

    /** Short name for logs and config. */
    String name();

    /** True when this provider is configured enough to be used. */
    boolean isAvailable();

    /**
     * Why a search came back empty.
     *
     * These are NOT the same thing, and conflating them cost a debugging
     * session: SearXNG sits behind a Compose profile and quietly failed to
     * restart with PostgreSQL, so every general question was refused with
     * "I couldn't find a source for that" — which reads as "the web does not
     * know", not "your search container is down".
     *
     * @param reachable false when the provider could not be contacted at all
     */
    record SearchOutcome(List<SearchHit> hits, boolean reachable) {

        public static SearchOutcome found(List<SearchHit> hits) {
            return new SearchOutcome(hits, true);
        }

        /** Contacted successfully, but it had nothing. */
        public static SearchOutcome empty() {
            return new SearchOutcome(List.of(), true);
        }

        /** Could not be contacted. A setup problem, not an answer. */
        public static SearchOutcome unreachable() {
            return new SearchOutcome(List.of(), false);
        }

        public boolean isEmpty() {
            return hits == null || hits.isEmpty();
        }
    }

    /**
     * @return hits plus whether the provider answered at all. Never throws —
     *         a search engine being down is not an application failure.
     */
    SearchOutcome search(String query, int limit);
}
