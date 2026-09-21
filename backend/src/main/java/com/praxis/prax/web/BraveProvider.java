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
 * Search through the Brave Search API.
 *
 * The alternative for anyone who would rather hold an API key than run another
 * container. The free tier allows a couple of thousand queries a month, which is
 * far more than a single-player coaching tool will use.
 *
 * The trade-off is explicit: Brave sees every query. SearXNG does not. That is
 * why it is not the default.
 */
@Component
public class BraveProvider implements WebSearchProvider {

    private static final Logger log = LoggerFactory.getLogger(BraveProvider.class);

    private static final String ENDPOINT = "https://api.search.brave.com/res/v1/web/search";

    private final AppProperties props;
    private final ObjectMapper mapper;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    public BraveProvider(AppProperties props, ObjectMapper mapper) {
        this.props = props;
        this.mapper = mapper;
    }

    @Override
    public String name() {
        return "brave";
    }

    @Override
    public boolean isAvailable() {
        return props.webEnabled()
                && "brave".equals(props.webProvider())
                && !props.braveApiKey().isBlank();
    }

    @Override
    public SearchOutcome search(String query, int limit) {
        String url = ENDPOINT
                + "?count=" + Math.max(1, Math.min(20, limit))
                + "&result_filter=web&safesearch=moderate&q="
                + URLEncoder.encode(query, StandardCharsets.UTF_8);

        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Accept", "application/json")
                    .header("X-Subscription-Token", props.braveApiKey())
                    .timeout(Duration.ofSeconds(4))
                    .GET()
                    .build();

            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 200) {
                // Never log the body at warn: an auth failure response can echo
                // request details, and the key is in the request.
                log.warn("[web] brave search returned {}", res.statusCode());
                return SearchOutcome.unreachable();
            }

            JsonNode root = mapper.readTree(res.body());
            List<SearchHit> hits = new ArrayList<>();
            for (JsonNode r : root.path("web").path("results")) {
                String link = r.path("url").asText(null);
                if (link == null || link.isBlank()) continue;
                hits.add(new SearchHit(
                        r.path("title").asText(""),
                        link,
                        r.path("description").asText("")));
                if (hits.size() >= limit) break;
            }
            return hits.isEmpty() ? SearchOutcome.empty() : SearchOutcome.found(hits);

        } catch (Exception e) {
            log.warn("[web] brave search failed: {}", e.toString());
            return SearchOutcome.unreachable();
        }
    }
}
