package com.praxis.ai;

import java.util.Map;

/**
 * One tool invocation requested by a model.
 *
 * @param id        identifies the call so its result can be matched to it. Providers
 *                  that pair results by id (OpenAI-compatible) use it; Ollama ignores it.
 * @param arguments as the model sent them; numbers stay numbers, everything else is text
 */
public record ToolCall(String id, String name, Map<String, Object> arguments) {}
