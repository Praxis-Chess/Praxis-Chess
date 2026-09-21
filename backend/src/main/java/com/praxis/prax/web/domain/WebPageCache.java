package com.praxis.prax.web.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * A page already fetched and reduced to text.
 *
 * Caching is not only a speed optimisation here. It also means asking the same
 * question twice does not hit the same site twice, and it makes the eval harness
 * close to reproducible — otherwise every run measures a different internet.
 *
 * The URL is hashed into the unique key rather than indexed directly: URLs run
 * past the 2704-byte limit PostgreSQL puts on a btree index entry, and a single
 * absurd URL would then fail the insert.
 */
@Entity
@Table(name = "web_page_cache", indexes = {
        @Index(name = "idx_web_page_url_hash", columnList = "url_hash", unique = true)
})
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class WebPageCache {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** SHA-256 of the final URL, hex. */
    @Column(name = "url_hash", nullable = false, length = 64)
    private String urlHash;

    @Column(nullable = false, length = 2048)
    private String url;

    @Column(length = 512)
    private String title;

    /** Extracted prose, already capped by ContentExtractor. */
    @Column(columnDefinition = "TEXT")
    private String text;

    @Column(name = "fetched_at", nullable = false)
    private OffsetDateTime fetchedAt;
}
