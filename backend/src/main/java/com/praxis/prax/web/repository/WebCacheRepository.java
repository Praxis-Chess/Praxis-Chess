package com.praxis.prax.web.repository;

import com.praxis.prax.web.domain.WebPageCache;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

public interface WebCacheRepository extends JpaRepository<WebPageCache, UUID> {

    Optional<WebPageCache> findByUrlHash(String urlHash);

    @Modifying
    @Transactional
    @Query("DELETE FROM WebPageCache p WHERE p.fetchedAt < :cutoff")
    int deleteOlderThan(@Param("cutoff") OffsetDateTime cutoff);
}
