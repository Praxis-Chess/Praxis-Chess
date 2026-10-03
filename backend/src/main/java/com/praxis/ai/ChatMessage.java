package com.praxis.ai;

import java.util.List;

/**
 * One message in a conversation, in no provider's format. Each {@link LlmProvider}
 * translates to its own wire shape.
 *
 * @param toolCalls  on an assistant message: the tools it asked for (else empty)
 * @param toolCallId on a tool message: the {@link ToolCall#id()} it answers
 */
public record ChatMessage(Role role, String content, List<ToolCall> toolCalls, String toolCallId) {

    public enum Role { SYSTEM, USER, ASSISTANT, TOOL }

    public ChatMessage {
        content = content == null ? "" : content;
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
    }

    public static ChatMessage system(String content) {
        return new ChatMessage(Role.SYSTEM, content, List.of(), null);
    }

    public static ChatMessage user(String content) {
        return new ChatMessage(Role.USER, content, List.of(), null);
    }

    public static ChatMessage assistant(String content) {
        return new ChatMessage(Role.ASSISTANT, content, List.of(), null);
    }

    /** An assistant turn that called tools rather than answering. */
    public static ChatMessage toolCalls(List<ToolCall> calls) {
        return new ChatMessage(Role.ASSISTANT, "", calls, null);
    }

    /** A tool's result, answering the call with this id. */
    public static ChatMessage toolResult(String toolCallId, String content) {
        return new ChatMessage(Role.TOOL, content, List.of(), toolCallId);
    }
}
