package com.praxis.prax.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.praxis.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Search through a self-hosted SearXNG instance.
 *
 * The default provider because it fits how the rest of this application works:
 * no API key, no account, no third party learning what the player asks about,
 * and one more container next to the PostgreSQL one that is already there.
 *
 * SearXNG must be started with the JSON format enabled — see docker-compose.yml
 * and the note in application.example.yml. Without it every request returns 403
 * and this provider reports no results, which is handled but useless.
 */
@Component
public class SearxngProvider implements WebSearchProvider {

    private static final Logger log = LoggerFactory.getLogger(SearxngProvider.class);

    private final AppProperties props;
    private final ObjectMapper mapper;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    public SearxngProvider(AppProperties props, ObjectMapper mapper) {
        this.props = props;
        this.mapper = mapper;
    }

    @Override
    public String name() {
        return "searxng";
    }

    @Override
    public boolean isAvailable() {
        return props.webEnabled() && "searxng".equals(props.webProvider());
    }

    @Override
    public SearchOutcome search(String query, int limit) {
        String base = props.searxngUrl();
        String url = base
                + (base.endsWith("/") ? "" : "/")
                + "search?format=json&language=en&safesearch=1&q="
                + URLEncoder.encode(query, StandardCharsets.UTF_8);

        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Accept", "application/json")
                    .header("User-Agent", SafeFetcher.USER_AGENT)
                    // Search is the fast leg. If it is slow, the whole feature is
                    // not worth the wait, so fail early rather than blocking the
                    // agent's budget.
                    .timeout(Duration.ofSeconds(4))
                    .GET()
                    .build();

            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 200) {
                log.warn("[web] searxng {} — is `formats: [json]` enabled in settings.yml?",
                        res.statusCode());
                // A 403 here is a misconfiguration, not an empty result set.
                // Reporting it as "no results" is what made a down search engine
                // look like a web that had nothing to say.
                return SearchOutcome.unreachable();
            }

            JsonNode root = mapper.readTree(res.body());
            List<SearchHit> hits = new ArrayList<>();
            for (JsonNode r : root.path("results")) {
                String link = r.path("url").asText(null);
                if (link == null || link.isBlank()) continue;
                hits.add(new SearchHit(
                        r.path("title").asText(""),
                        link,
                        r.path("content").asText("")));
                if (hits.size() >= limit) break;
            }
            return hits.isEmpty() ? SearchOutcome.empty() : SearchOutcome.found(hits);

        } catch (Exception e) {
            // A search engine being unreachable is not an application failure —
            // but it must not be reported as "the web found nothing" either.
            log.warn("[web] searxng search failed: {}", e.toString());
            return SearchOutcome.unreachable();
        }
    }
}
