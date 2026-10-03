package com.praxis.ai;

import java.util.Map;

/**
 * A tool offered to a model.
 *
 * @param parameters a JSON Schema object ({@code type}, {@code properties}, {@code required})
 */
public record ToolSpec(String name, String description, Map<String, Object> parameters) {}
