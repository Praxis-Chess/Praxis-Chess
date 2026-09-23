package com.praxis.evidence.graph;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.praxis.evidence.diagnosis.Diagnosis;

/**
 * The one JSON form of a graph and a diagnosis.
 *
 * <p>Not Spring's mapper. The app's mapper happens to use snake_case, but the
 * headless {@link GraphCli} has no Spring context, and a graph written by one and
 * read by the other must be byte-for-byte the same shape. Stored graphs, exported
 * training rows and the CLI's output all go through this.
 */
public final class GraphJson {

    private GraphJson() {}

    public static final ObjectMapper MAPPER = new ObjectMapper()
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .setSerializationInclusion(JsonInclude.Include.NON_NULL)
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    public static String write(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("could not serialise " + value.getClass().getSimpleName(), e);
        }
    }

    public static EvidenceGraph readGraph(String json) {
        try {
            return MAPPER.readValue(json, EvidenceGraph.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("not an evidence graph: " + e.getMessage(), e);
        }
    }

    public static Diagnosis readDiagnosis(String json) {
        try {
            return MAPPER.readValue(json, Diagnosis.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("not a diagnosis: " + e.getMessage(), e);
        }
    }
}
