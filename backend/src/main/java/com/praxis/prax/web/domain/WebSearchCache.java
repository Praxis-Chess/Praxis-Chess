package com.praxis.prax.web.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * The hit list for one query, so repeating a question does not repeat the
 * search call — which matters most on Brave, where queries are metered.
 *
 * Results are stored as JSON text rather than as rows. They are an opaque
 * snapshot that is only ever read back whole, and nothing queries inside them,
 * so a child table would be structure for its own sake.
 */
@Entity
@Table(name = "web_search_cache", indexes = {
        @Index(name = "idx_web_search_query_hash", columnList = "query_hash", unique = true)
})
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class WebSearchCache {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** SHA-256 of the normalised query plus the provider name. */
    @Column(name = "query_hash", nullable = false, length = 64)
    private String queryHash;

    @Column(nullable = false, length = 512)
    private String query;

    @Column(nullable = false, length = 32)
    private String provider;

    /** A JSON array of SearchHit. */
    @Column(name = "results_json", columnDefinition = "TEXT")
    private String resultsJson;

    @Column(name = "fetched_at", nullable = false)
    private OffsetDateTime fetchedAt;
}
