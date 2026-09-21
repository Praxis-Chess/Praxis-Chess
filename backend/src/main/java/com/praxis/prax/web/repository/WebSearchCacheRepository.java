package com.praxis.prax.web.repository;

import com.praxis.prax.web.domain.WebSearchCache;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

public interface WebSearchCacheRepository extends JpaRepository<WebSearchCache, UUID> {

    Optional<WebSearchCache> findByQueryHash(String queryHash);

    @Modifying
    @Transactional
    @Query("DELETE FROM WebSearchCache s WHERE s.fetchedAt < :cutoff")
    int deleteOlderThan(@Param("cutoff") OffsetDateTime cutoff);
}
