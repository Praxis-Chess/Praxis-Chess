package com.praxis.service;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

/**
 * Authoritative ECO → opening-name lookup backed by eco.properties on the classpath.
 * Covers A00-E99 (500 codes). Falls back gracefully to null when a code is unknown.
 *
 * Use this as the canonical name source. PGN "Opening" headers from Chess.com are
 * often absent, truncated, or just the code — this fills the gap consistently.
 */
@Service
public class EcoTable {

    private static final Logger log = LoggerFactory.getLogger(EcoTable.class);
    private Map<String, String> table = Collections.emptyMap();

    @PostConstruct
    void load() {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream("eco.properties")) {
            if (is == null) {
                log.warn("eco.properties not found on classpath — opening names will be absent");
                return;
            }
            Properties props = new Properties();
            props.load(is);
            Map<String, String> map = new HashMap<>((int) (props.size() * 1.5));
            props.forEach((k, v) -> {
                String key = k.toString().trim().toUpperCase();
                if (!key.startsWith("#") && key.length() == 3) {
                    map.put(key, v.toString().trim());
                }
            });
            table = Collections.unmodifiableMap(map);
            log.info("ECO table loaded: {} entries", table.size());
        } catch (Exception e) {
            log.error("Failed to load eco.properties: {}", e.getMessage());
        }
    }

    /**
     * Returns the canonical opening name for the given ECO code (case-insensitive),
     * or null when the code is unknown or the input is blank.
     */
    public String lookup(String eco) {
        if (eco == null || eco.isBlank() || eco.length() < 3) return null;
        return table.get(eco.substring(0, 3).toUpperCase());
    }

    /** True when the table was loaded from the properties file. */
    public boolean isLoaded() {
        return !table.isEmpty();
    }
}
