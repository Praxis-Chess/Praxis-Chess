package com.praxis.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The Pattern Report and Training Plan.
 *
 * @param prose whether a model writes paragraphs around the computed figures.
 *              Off by default: the number checker verifies numbers, not claims,
 *              and in real reports the model joined figures from different rows
 *              and stated causes nothing measured. The figures are written in
 *              code either way.
 */
@ConfigurationProperties(prefix = "praxis-chess.reports")
public record ReportProperties(Boolean prose) {
    public boolean proseOn() {
        return Boolean.TRUE.equals(prose);
    }
}
