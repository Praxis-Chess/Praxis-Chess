package com.praxis.ai;

import java.util.List;

/**
 * One assistant turn: tool calls, or a final message.
 *
 * @param content   null when the provider could not be reached or returned nothing
 * @param truncated the provider stopped at the token limit, so the answer may be cut off
 */
public record ChatResult(String content, List<ToolCall> toolCalls, boolean truncated) {

    public ChatResult {
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
    }

    public ChatResult(String content, List<ToolCall> toolCalls) {
        this(content, toolCalls, false);
    }

    public static ChatResult empty() {
        return new ChatResult(null, List.of(), false);
    }

    public boolean wantsTools() {
        return !toolCalls.isEmpty();
    }
}
